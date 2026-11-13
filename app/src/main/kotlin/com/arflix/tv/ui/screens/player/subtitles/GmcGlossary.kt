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
 * to every Claude request (about 300 extra input tokens per batch). Every later episode adds its own
 * new names: one more small Claude request per episode (about $0.001), names already decided stay.
 */
object GmcGlossary {
    private const val TAG = "GmcGlossary"
    private const val FILE = "gmc_glossaries"

    /** English lines collected before the glossary is built or extended (about ten minutes). */
    const val LINES_TO_BUILD = 150
    private const val MAX_ENTRIES = 60
    private const val MAX_SOURCE_CHARS = 24_000
    private const val MAX_GLOSSARY_CHARS = 4_000

    /** [key] names the title (series or film); [episode] ("s1e3", "" for a film) the part being watched. */
    data class Title(val key: String, val name: String, val episode: String = "")

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

    private fun episodeKey(title: Title, lang: String) = "done|${localKey(title.key, lang)}|${title.episode}"

    /** Whether this episode's lines already went into the glossary on this box. */
    fun episodeDone(context: Context, title: Title, lang: String): Boolean =
        prefs(context).getBoolean(episodeKey(title, lang), false)

    private fun markEpisodeDone(context: Context, title: Title, lang: String) {
        prefs(context).edit().putBoolean(episodeKey(title, lang), true).apply()
    }

    /**
     * Asks Claude for the glossary of [title] from its English [lines] — a new one, or, when
     * [existing] is set, the same glossary with the new names of this episode added and nothing
     * already there changed. Saves it (server and device) and returns it; null when Claude failed.
     */
    suspend fun build(
        context: Context,
        service: SubtitleTranslationService,
        title: Title,
        lang: String,
        lines: List<String>,
        existing: String = "",
    ): String? {
        val source = buildString {
            for (line in lines) {
                if (length + line.length > MAX_SOURCE_CHARS) break
                appendLine(line.replace('\n', ' '))
            }
        }
        if (source.isBlank()) return null
        val rules = "List the recurring character names, nicknames, places, organisations and invented " +
            "terms, each with how $lang subtitles should write it (names are transliterated the way $lang " +
            "subtitles normally do). For the main characters, say who addresses whom formally or informally " +
            "in $lang. Only include what appears in the lines. Plain text, one entry per line, at most " +
            "$MAX_ENTRIES lines, no commentary."
        val system: String
        val user: String
        if (existing.isBlank()) {
            system = "You prepare a glossary for translating the subtitles of a film or TV series into $lang. " +
                "From the English subtitle lines given: $rules"
            user = "Title: ${title.name}\n\n$source"
        } else {
            system = "You keep the glossary used to translate the subtitles of a TV series into $lang. You get " +
                "the current glossary and English lines from another episode. Return the whole glossary: every " +
                "existing entry exactly as it is (never re-spell a name already decided), plus entries for new " +
                "recurring names and terms in the lines. If nothing new matters, return it unchanged. $rules"
            user = "Title: ${title.name}\n\nCurrent glossary:\n$existing\n\nNew English lines:\n$source"
        }
        var glossary = service.claudeComplete(system, user)
            ?.trim()?.take(MAX_GLOSSARY_CHARS)?.takeIf { it.isNotBlank() }
            ?: return null
        // An update must not lose what was decided: if the reply dropped existing entries (it sent
        // only the new ones, or rewrote the list), keep the old list and add whatever is new.
        if (existing.isNotBlank()) {
            val old = existing.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val new = glossary.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (!new.containsAll(old)) glossary = (old + new.filter { it !in old }).joinToString("\n").take(MAX_GLOSSARY_CHARS)
        }
        prefs(context).edit().putString(localKey(title.key, lang), glossary).apply()
        markEpisodeDone(context, title, lang)
        GmcSubtitleMemory.config(context)?.let { GmcSubtitleMemory.putGlossary(it, title.key, lang, glossary) }
        Log.i(TAG, "built for ${title.key} (${glossary.lines().size} entries)")
        return glossary
    }
}
