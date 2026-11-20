package com.arflix.tv.ui.screens.player.subtitles

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.arflix.tv.data.model.Subtitle
import org.json.JSONObject

/*
 * GMC personal build: one line per playback on the gmc-server (POST /v1/plays), so it is known
 * which titles ended on AI translation, which on an addon or built-in subtitle, on which box.
 * Read it with GET /v1/plays?format=text on the server.
 *
 * Sent once per title, five minutes into playback (by then the subtitle scan has decided), and
 * only when the subtitle memory server is set up. Nothing else about the box is sent.
 */
object GmcPlayLog {
    /** How far into a title the report goes out. */
    const val REPORT_AFTER_MS = 5 * 60_000L
    private const val FILE = "gmc_local"
    private const val KEY_DEVICE_ID = "play_device_id"

    data class Play(
        val device: String,
        val title: String,
        val imdb: String?,
        val season: Int?,
        val episode: Int?,
        val subtitle: String,       // ai | addon | embedded | none
        val detail: String,
        val memoryPercent: Int?,
        val model: String?,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("device", device)
            put("title", title)
            imdb?.let { put("imdb", it) }
            season?.let { put("season", it) }
            episode?.let { put("episode", it) }
            put("subtitle", subtitle)
            if (detail.isNotBlank()) put("detail", detail.take(400))
            memoryPercent?.let { put("memory_pct", it) }
            model?.let { put("model", it) }
        }
    }

    /** What is on screen: AI translation, an addon subtitle, a built-in track, or nothing. */
    fun kind(isAiTranslating: Boolean, selected: Subtitle?): String = when {
        isAiTranslating -> "ai"
        selected == null -> "none"
        selected.isEmbedded -> "embedded"
        else -> "addon"
    }

    /** A short line about the subtitle and the last scan step, for reading the log by eye. */
    fun detail(kind: String, selected: Subtitle?, aiSource: Subtitle?, offsetMs: Long, lastStep: String?): String {
        val parts = ArrayList<String>()
        when (kind) {
            "ai" -> aiSource?.let { parts += "from ${it.label}" }
            "addon", "embedded" -> selected?.let { sub ->
                parts += listOf(sub.provider.takeIf { it.isNotBlank() && kind == "addon" }, sub.label, sub.lang.takeIf { it.isNotBlank() })
                    .filterNotNull().joinToString(" · ")
                if (offsetMs != 0L) parts += "%+.1fs".format(java.util.Locale.US, offsetMs / 1000.0)
            }
        }
        lastStep?.takeIf { it.isNotBlank() }?.let { parts += "scan: $it" }
        return parts.joinToString(" | ")
    }

    /** The box's name from Android settings (Device name), plus a short id that stays the same. */
    fun deviceLabel(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_DEVICE_ID, null) ?: java.util.UUID.randomUUID().toString().take(4).also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
        val name = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL.orEmpty().ifBlank { "box" }
        return "$name · $id"
    }

    suspend fun send(context: Context, play: Play) {
        val config = GmcSubtitleMemory.config(context) ?: return
        GmcSubtitleMemory.reportPlay(config, play.toJson())
    }
}
