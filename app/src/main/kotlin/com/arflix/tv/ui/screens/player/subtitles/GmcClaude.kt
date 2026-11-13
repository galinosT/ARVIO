package com.arflix.tv.ui.screens.player.subtitles

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/*
 * GMC personal build: AI subtitle translation with Claude Haiku.
 *
 * The Claude key and switch are kept in a separate local file on this device and are NOT part of
 * the ARVIO cloud sync, so the official app on other devices keeps its own Gemini/Groq setup.
 * When the switch is on and a key is set, SubtitleTranslationService sends the same prompt and
 * batches it sends Gemini to Claude instead. Everything else (line matching, live TV) stays as is.
 */

private const val TAG = "GmcClaude"

internal const val GMC_CLAUDE_MODEL_ID = "claude-haiku-5-5"
private const val CLAUDE_URL = "https://api.anthropic.com/v1/messages"
private const val CLAUDE_API_VERSION = "2023-06-01"
private const val CLAUDE_MAX_TOKENS = 8000
private const val CLAUDE_MAX_TRANSIENT_RETRIES = 2
private val CLAUDE_RETRYABLE_HTTP = setOf(500, 502, 503, 529)

/** Device-local settings. Never synced. */
object GmcClaudePrefs {
    private const val FILE = "gmc_local"
    private const val KEY_ENABLED = "claude_enabled"
    private const val KEY_API = "claude_api_key"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun apiKey(context: Context): String = prefs(context).getString(KEY_API, "").orEmpty().trim()

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API, key.trim()).apply()
    }

    fun looksLikeKey(key: String): Boolean = key.trim().startsWith("sk-ant-")

    /** The key to translate with, or "" when Claude is off or has no usable key. */
    fun activeKey(context: Context): String {
        if (!isEnabled(context)) return ""
        return apiKey(context).takeIf(::looksLikeKey).orEmpty()
    }
}

/** What one Messages API call produced. */
internal sealed class ClaudeReply {
    data class Text(val text: String) : ClaudeReply()
    object Refused : ClaudeReply()
    data class Failed(val message: String) : ClaudeReply()
}

/**
 * One Claude Messages call with ARVIO's subtitle prompt. No temperature (Claude 5.5 models reject a
 * non-default one) and low effort: subtitle batches need speed, not reasoning.
 */
internal suspend fun claudeTranslateCall(
    client: OkHttpClient,
    apiKey: String,
    systemPrompt: String,
    userText: String,
    transientAttempt: Int = 0
): ClaudeReply {
    val body = JSONObject().apply {
        put("model", GMC_CLAUDE_MODEL_ID)
        put("max_tokens", CLAUDE_MAX_TOKENS)
        put("system", systemPrompt)
        put("messages", JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", userText)
        }))
        put("output_config", JSONObject().put("effort", "low"))
    }
    val request = Request.Builder()
        .url(CLAUDE_URL)
        .header("x-api-key", apiKey)
        .header("anthropic-version", CLAUDE_API_VERSION)
        .header("content-type", "application/json")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()

    val retry: suspend () -> ClaudeReply = {
        val jitter = 0.7 + Math.random() * 0.6
        delay((400L * (1L shl transientAttempt) * jitter).toLong())
        claudeTranslateCall(client, apiKey, systemPrompt, userText, transientAttempt + 1)
    }

    val outcome: Any = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> parseClaudeReply(text)
                    response.code in CLAUDE_RETRYABLE_HTTP && transientAttempt < CLAUDE_MAX_TRANSIENT_RETRIES -> RETRY
                    response.code == 429 -> ClaudeReply.Failed("RATE_LIMITED")
                    else -> ClaudeReply.Failed("HTTP ${response.code}: ${text.take(300)}")
                }
            }
        } catch (e: java.io.IOException) {
            if (transientAttempt < CLAUDE_MAX_TRANSIENT_RETRIES) RETRY
            else ClaudeReply.Failed(e.message ?: "Network error")
        }
    }
    return if (outcome === RETRY) {
        Log.w(TAG, "Claude transient failure — retry ${transientAttempt + 1}/$CLAUDE_MAX_TRANSIENT_RETRIES")
        retry()
    } else {
        outcome as ClaudeReply
    }
}

private val RETRY = Any()

private fun parseClaudeReply(body: String): ClaudeReply {
    val json = runCatching { JSONObject(body) }.getOrNull()
        ?: return ClaudeReply.Failed("Unreadable Claude response")
    if (json.optString("stop_reason") == "refusal") return ClaudeReply.Refused
    val content = json.optJSONArray("content") ?: return ClaudeReply.Failed("No content in Claude response")
    val text = buildString {
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") append(block.optString("text"))
        }
    }.trim()
    if (json.optString("stop_reason") == "max_tokens") Log.w(TAG, "Claude reply hit max_tokens")
    return if (text.isBlank()) ClaudeReply.Failed("Empty Claude response") else ClaudeReply.Text(text)
}
