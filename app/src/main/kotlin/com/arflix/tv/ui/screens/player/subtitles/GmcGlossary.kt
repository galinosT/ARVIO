package com.arflix.tv.ui.screens.player.subtitles

import android.content.Context
import android.util.Log

/*
 * GMC personal build: one glossary per title (series or film) for Claude translation.
 *
 * Each batch of 40 lines is translated on its own, so nothing tells the model that "Jesse" was
 * written «Τζέσι» three batches ago, or that two characters say «εσείς» to each other. The glossary
 * says it: built once per title by Claude from the English lines of the first episode watched,
 * kept on the GMC memory server (shared by every box) or, without one, on this device, and added
 * to every Claude request. About 300 extra input tokens per batch.
 */
object GmcGlossary {
    private const val TAG = "GmcGlossary"
    private const val FILE = "gmc_glossaries"

    /** English lines collected before the glossary is built (about the first ten minutes). */
    const val LINES_TO_BUILD = 150
    private const val MAX_SOURCE_CHARS = 24_000
    private const val MAX_GLOSSARY_CHARS = 4_000

    data class Title(val key: String, val name: String)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun localKey(key: String, lang: String) = "$key|${lang.trim().lowercase()}"

    /** The glossary for [title] into [lang], "" when there is none yet. */
    suspend fun load(context: Context, title: Title, lang: String): String {
        val config = GmcSubtitleMemory.config(context)
        if (config != null) {
            val remote = GmcSubtitleMemory.glossary(config, title.key, lang)
            if (!remote.isNullOrBlank()) {
                prefs(context).edit().putString(localKey(title.key, lang), remote).apply()
                return remote
            }
        }
        return prefs(context).getString(localKey(title.key, lang), "").orEmpty()
    }

    /** Asks Claude for the glossary of [title] from its English [lines]; saves and returns it. */
    suspend fun build(
        context: Context,
        service: SubtitleTranslationService,
        title: Title,
        lang: String,
        lines: List<String>,
    ): String? {
        val source = buildString {
            for (line in lines) {
                if (length + line.length > MAX_SOURCE_CHARS) break
                appendLine(line.replace('\n', ' '))
            }
        }
        if (source.isBlank()) return null
        val system = "You prepare a glossary for translating the subtitles of a film or TV series into $lang. " +
            "From the English subtitle lines given, list the recurring character names, nicknames, places, " +
            "organisations and invented terms, each with how $lang subtitles should write it (names are " +
            "transliterated the way $lang subtitles normally do). Then, for the main characters, say who " +
            "addresses whom formally or informally in $lang. Only include what appears in the lines. " +
            "Plain text, one entry per line, at most 40 lines, no commentary."
        val glossary = service.claudeComplete(system, "Title: ${title.name}\n\n$source")
            ?.trim()?.take(MAX_GLOSSARY_CHARS)?.takeIf { it.isNotBlank() }
            ?: return null
        prefs(context).edit().putString(localKey(title.key, lang), glossary).apply()
        GmcSubtitleMemory.config(context)?.let { GmcSubtitleMemory.putGlossary(it, title.key, lang, glossary) }
        Log.i(TAG, "built for ${title.key} (${glossary.lines().size} entries)")
        return glossary
    }
}
