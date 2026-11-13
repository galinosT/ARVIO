package com.arflix.tv.ui.screens.player.subtitles

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/*
 * GMC personal build: the subtitle memory on Galinos's own server (gmc-server/ in this repo), shared
 * by every GMC box. Lines already translated anywhere come back from it instead of from the AI;
 * new translations go up to it for the other boxes. Each series' glossary lives there too.
 *
 * Address and key are kept on this device only (gmc_local), never in the ARVIO cloud sync. Every
 * call is best effort: a server that is down, slow or unset costs nothing but the lookup's timeout,
 * and translation carries on as if it did not exist.
 */
object GmcSubtitleMemory {
    private const val TAG = "GmcMemory"
    private const val FILE = "gmc_local"
    private const val KEY_URL = "memory_url"
    private const val KEY_TOKEN = "memory_token"
    private const val BATCH = 400

    data class Config(val baseUrl: String, val token: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    /** After a failed lookup the server is skipped for a minute, so a dead server cannot slow every batch. */
    @Volatile private var skipUntilMs = 0L
    private const val SKIP_AFTER_FAILURE_MS = 60_000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun url(context: Context): String = prefs(context).getString(KEY_URL, "").orEmpty()
    fun token(context: Context): String = prefs(context).getString(KEY_TOKEN, "").orEmpty()

    fun setUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_URL, normalizeUrl(url)).apply()
    }

    fun setToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    /** "subs.example.gr" -> "https://subs.example.gr"; trailing slashes dropped. */
    fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

    /** Null until both an address and a key are set. */
    fun config(context: Context): Config? {
        val url = url(context)
        val token = token(context)
        if (url.toHttpUrlOrNull() == null || token.length < 24) return null
        return Config(url, token)
    }

    private fun request(config: Config, path: String): Request.Builder =
        Request.Builder().url(config.baseUrl + path).header("Authorization", "Bearer ${config.token}")

    /** The translations the server already has for [texts] into [lang]; empty on any failure. */
    suspend fun lookup(config: Config, lang: String, texts: List<String>): Map<String, String> =
        withContext(Dispatchers.IO) {
            val out = HashMap<String, String>()
            if (System.currentTimeMillis() < skipUntilMs) return@withContext out
            for (chunk in texts.distinct().filter { it.isNotBlank() && it.length <= 2000 }.chunked(BATCH)) {
                val body = JSONObject().put("lang", lang).put("texts", JSONArray(chunk)).toString()
                val ok = runCatching {
                    client.newCall(request(config, "/v1/translations/lookup").post(body.toRequestBody(json)).build())
                        .execute().use { response ->
                            if (!response.isSuccessful) error("HTTP ${response.code}")
                            val found = JSONObject(response.body?.string().orEmpty()).optJSONObject("found")
                            found?.keys()?.forEach { key -> found.optString(key).takeIf { it.isNotBlank() }?.let { out[key] = it } }
                        }
                }.onFailure {
                    Log.w(TAG, "lookup failed: ${it.message}")
                    skipUntilMs = System.currentTimeMillis() + SKIP_AFTER_FAILURE_MS
                }.isSuccess
                if (!ok) break
            }
            out
        }

    /** Uploads [items] (source line, translation). Failures are only logged. */
    suspend fun store(config: Config, lang: String, model: String, items: List<Pair<String, String>>) =
        withContext(Dispatchers.IO) {
            val usable = items.filter { (src, dst) ->
                src.isNotBlank() && dst.isNotBlank() && src.length <= 2000 && dst.length <= 2000 && src != dst
            }
            for (chunk in usable.chunked(BATCH)) {
                val array = JSONArray().apply { chunk.forEach { (src, dst) -> put(JSONArray().put(src).put(dst)) } }
                val body = JSONObject().put("lang", lang).put("model", model).put("items", array).toString()
                val ok = runCatching {
                    client.newCall(request(config, "/v1/translations/store").post(body.toRequestBody(json)).build())
                        .execute().use { if (!it.isSuccessful) error("HTTP ${it.code}") }
                }.onFailure { Log.w(TAG, "store failed: ${it.message}") }.isSuccess
                if (!ok) break
            }
        }

    /** The stored glossary for series [key] into [lang], "" when none, null when the server failed. */
    suspend fun glossary(config: Config, key: String, lang: String): String? = withContext(Dispatchers.IO) {
        val url = (config.baseUrl + "/v1/glossary").toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("key", key)?.addQueryParameter("lang", lang)?.build() ?: return@withContext null
        runCatching {
            client.newCall(Request.Builder().url(url).header("Authorization", "Bearer ${config.token}").get().build())
                .execute().use { response ->
                    when {
                        response.code == 404 -> ""
                        !response.isSuccessful -> error("HTTP ${response.code}")
                        else -> JSONObject(response.body?.string().orEmpty()).optString("glossary")
                    }
                }
        }.onFailure { Log.w(TAG, "glossary failed: ${it.message}") }.getOrNull()
    }

    suspend fun putGlossary(config: Config, key: String, lang: String, glossary: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("key", key).put("lang", lang).put("glossary", glossary).toString()
        runCatching {
            client.newCall(request(config, "/v1/glossary").put(body.toRequestBody(json)).build())
                .execute().use { if (!it.isSuccessful) error("HTTP ${it.code}") }
        }.onFailure { Log.w(TAG, "glossary upload failed: ${it.message}") }
    }

    /** For the settings screen: "ok (1234 lines)" or why not. */
    suspend fun check(config: Config): String = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(request(config, "/v1/glossary?key=_check&lang=_check").get().build()).execute().use { r ->
                when (r.code) {
                    200, 404 -> null
                    401 -> "λάθος κλειδί"
                    else -> "HTTP ${r.code}"
                }
            } ?: client.newCall(Request.Builder().url(config.baseUrl + "/health").get().build()).execute().use { r ->
                "OK · ${JSONObject(r.body?.string().orEmpty()).optLong("lines")} γραμμές"
            }
        }.getOrElse { "δεν απαντά (${it.message})" }
    }
}
