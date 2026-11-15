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
}
