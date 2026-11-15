package com.arflix.tv.ui.screens.watchlist

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * GMC: which optional tabs the Library shows, a per-device switch in Settings → Interface.
 * Calendar is on by default, as in ARVIO.
 */
internal object GmcLibraryPrefs {
    private const val PREFS = "gmc_local"
    private const val KEY_CALENDAR = "library_calendar"

    var showCalendar by mutableStateOf(true)
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        showCalendar = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CALENDAR, true)
    }

    fun setShowCalendar(context: Context, show: Boolean) {
        loaded = true
        showCalendar = show
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CALENDAR, show).apply()
    }
}
