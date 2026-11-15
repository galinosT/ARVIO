package com.arflix.tv.ui.screens.watchlist

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * GMC: which optional tabs the Library shows, a per-device switch in Settings → Interface.
 * Calendar and Homeserver are on by default, as in ARVIO.
 */
internal object GmcLibraryPrefs {
    private const val PREFS = "gmc_local"
    private const val KEY_CALENDAR = "library_calendar"
    private const val KEY_SERVERS = "library_servers"
    private const val KEY_LIBRARY_ONLY = "library_only_catalogs"
    private const val KEY_HOME_ONLY = "home_only_catalogs"

    /** Ids of catalogs kept on Home only — not listed in the Library. */
    val homeOnlyIds = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    /** Ids of catalogs kept in the Library only — not shown as Home rows. Bumped on every change. */
    val libraryOnlyIds = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    var showCalendar by mutableStateOf(true)
        private set
    var showServers by mutableStateOf(true)
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        showCalendar = prefs.getBoolean(KEY_CALENDAR, true)
        showServers = prefs.getBoolean(KEY_SERVERS, true)
        libraryOnlyIds.value = prefs.getStringSet(KEY_LIBRARY_ONLY, emptySet()).orEmpty().toSet()
        homeOnlyIds.value = prefs.getStringSet(KEY_HOME_ONLY, emptySet()).orEmpty().toSet()
    }

    fun setShowCalendar(context: Context, show: Boolean) {
        loaded = true
        showCalendar = show
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CALENDAR, show).apply()
    }

    fun setShowServers(context: Context, show: Boolean) {
        loaded = true
        showServers = show
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SERVERS, show).apply()
    }

    fun isLibraryOnly(context: Context, catalogId: String): Boolean {
        load(context)
        return catalogId in libraryOnlyIds.value
    }

    fun setLibraryOnly(context: Context, catalogId: String, libraryOnly: Boolean) {
        load(context)
        val next = if (libraryOnly) libraryOnlyIds.value + catalogId else libraryOnlyIds.value - catalogId
        if (next == libraryOnlyIds.value) return
        libraryOnlyIds.value = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_LIBRARY_ONLY, next).apply()
    }

    fun isHomeOnly(context: Context, catalogId: String): Boolean {
        load(context)
        return catalogId in homeOnlyIds.value
    }

    fun setHomeOnly(context: Context, catalogId: String, homeOnly: Boolean) {
        load(context)
        val next = if (homeOnly) homeOnlyIds.value + catalogId else homeOnlyIds.value - catalogId
        if (next == homeOnlyIds.value) return
        homeOnlyIds.value = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_HOME_ONLY, next).apply()
    }
}
