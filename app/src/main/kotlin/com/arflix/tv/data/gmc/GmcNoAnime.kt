package com.arflix.tv.data.gmc

import android.content.Context
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.util.AnimeMapper

/**
 * GMC: "Χωρίς anime" — a per-device switch (Settings → Interface) that keeps anime out of Home rows,
 * folders, "view all" pages, search results and "more like this".
 *
 * Continue Watching and the Library are left alone: what is there, the user put there.
 *
 * Anime is what ARVIO itself treats as anime: a title in its anime map, or Animation (TMDB genre 16)
 * in Japanese. Items that arrive without genre or language (some add-on rows) pass unless they are
 * in the map.
 */
object GmcNoAnime {
    private const val PREFS = "gmc_local"
    private const val KEY = "no_anime"

    @Volatile
    var enabled: Boolean = false
        private set
    @Volatile
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        enabled = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
        loaded = true
    }

    fun isEnabled(context: Context): Boolean {
        load(context)
        return enabled
    }

    fun setEnabled(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
        enabled = value
        loaded = true
    }

    fun isAnime(item: MediaItem): Boolean =
        AnimeMapper.isAnimeContentStatic(item.id, item.genreIds, item.originalLanguage)

    /** [items] without anime when the switch is on; unchanged otherwise. */
    fun filter(items: List<MediaItem>): List<MediaItem> =
        if (!enabled) items else items.filterNot(::isAnime)
}
