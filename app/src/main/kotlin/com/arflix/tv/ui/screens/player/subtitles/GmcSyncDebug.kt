package com.arflix.tv.ui.screens.player.subtitles

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/*
 * GMC personal build: "Debug συγχρονισμού" — a per-box switch (Settings → AI subtitles), off by
 * default. While on, the box sends to the gmc-server (POST /v1/debug):
 *
 * - the whole subtitle scan of each title (the player's own SubMatch log lines, read back from
 *   this app's logcat), once five minutes have been watched;
 * - every manual correction — switching to AI, picking another subtitle, changing the offset —
 *   with what was on screen at that moment and the scan that put it there.
 *
 * Read with GET /v1/debug?format=text on the server. Nothing is sent while the switch is off.
 */
object GmcSyncDebug {
    private const val TAG = "GmcSyncDebug"
    private const val FILE = "gmc_local"
    private const val KEY = "sync_debug"
    private const val MAX_TRACE_CHARS = 16_000
    private val LOG_TAGS = listOf("SubMatch", "GmcSkip", "SubtitleTranslation")

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    fun isOn(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setOn(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    /**
     * This app's own log lines for [LOG_TAGS] since [sinceEpochMs] (an app may read its own
     * logcat). The most recent part when it is long.
     */
    suspend fun traceSince(sinceEpochMs: Long): String = withContext(Dispatchers.IO) {
        runCatching {
            val args = arrayListOf("logcat", "-d", "-v", "epoch", "-s") + LOG_TAGS.map { "$it:V" }
            val process = ProcessBuilder(args).redirectErrorStream(true).start()
            val pid = android.os.Process.myPid().toString()
            val lines = process.inputStream.bufferedReader().useLines { seq ->
                seq.filter { line -> isOurs(line, pid, sinceEpochMs) }.map(::clean).toList()
            }
            // `logcat -d` exits once it has dumped the buffer, which the read above waited for.
            process.destroy()
            val text = lines.joinToString("\n")
            if (text.length <= MAX_TRACE_CHARS) text else "…\n" + text.takeLast(MAX_TRACE_CHARS)
        }.onFailure { Log.w(TAG, "logcat: ${it.message}") }.getOrDefault("")
    }

    /** "  1763659200.123  4321  4400 I SubMatch: …" — this process, at or after [since]. */
    internal fun isOurs(line: String, pid: String, since: Long): Boolean {
        val parts = line.trim().split(Regex("\\s+"), limit = 4)
        if (parts.size < 4 || parts[1] != pid) return false
        val at = parts[0].toDoubleOrNull() ?: return false
        return (at * 1000).toLong() >= since
    }

    /** Keeps the time of day, the level and the message. */
    internal fun clean(line: String): String {
        val parts = line.trim().split(Regex("\\s+"), limit = 4)
        val at = parts.getOrNull(0)?.toDoubleOrNull() ?: return line
        val clock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date((at * 1000).toLong()))
        return "$clock ${parts.getOrNull(3).orEmpty()}"
    }

    suspend fun send(context: Context, event: JSONObject) {
        if (!isOn(context)) return
        val config = GmcSubtitleMemory.config(context) ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(config.baseUrl.trimEnd('/') + "/v1/debug")
                    .header("Authorization", "Bearer ${config.token}")
                    .post(event.toString().toRequestBody(json))
                    .build()
                client.newCall(request).execute().use { if (!it.isSuccessful) error("HTTP ${it.code}") }
            }.onFailure { Log.w(TAG, "send failed: ${it.message}") }
        }
    }
}
