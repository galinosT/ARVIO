package com.arflix.tv.ui.screens.player.subtitles

import android.content.Context
import okhttp3.OkHttpClient

/*
 * GMC personal build: AI subtitle translation with Claude Haiku, switched on per device.
 *
 * The Claude key and switch are kept in a separate local file on this device and are NOT part of
 * the cloud sync. When the switch is on and a key is set, SubtitleTranslationService sends the
 * same prompt and batches it sends Gemini to Claude instead, with GMC's glossary added.
 * The transport is ARVIO's own (ClaudeMessages.kt, from the Claude provider merged upstream).
 */

internal const val GMC_CLAUDE_MODEL_ID = CLAUDE_MODEL_ID

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

/** One Claude Messages call; ARVIO's transport, kept under GMC's name for GMC's callers. */
internal suspend fun claudeTranslateCall(
    client: OkHttpClient,
    apiKey: String,
    systemPrompt: String,
    userText: String,
): ClaudeReply = claudeMessagesCall(client, apiKey, systemPrompt, userText)
