package com.arflix.tv.ui.screens.home

import android.content.Context
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type

/**
 * GMC: "snappy" motion for the Netflix-style Home, a per-device switch in Settings → Interface.
 *
 * Off, everything stays as in ARVIO (220 ms, ease in and out). On, rows and cards move in 150 ms
 * with an easing that starts at full speed, so the move answers the key press at once; and while
 * a direction key is held down the rows jump without animating, instead of queueing slides.
 */
internal object GmcHomeMotion {
    private const val PREFS = "gmc_local"
    private const val KEY = "snappy_home"

    private const val ARVIO_MS = 220
    private const val SNAPPY_MS = 150

    var snappy by mutableStateOf(false)
        private set
    private var loaded = false

    /** True while a key is auto-repeating (held down). */
    @Volatile
    var holdingKey = false
        private set

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        snappy = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    fun isEnabled(context: Context): Boolean {
        load(context)
        return snappy
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
        loaded = true
        snappy = enabled
    }

    fun noteKey(event: KeyEvent) {
        holdingKey = event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount > 0
    }

    val morphMs: Int get() = if (snappy) SNAPPY_MS else ARVIO_MS

    val easing: Easing get() = if (snappy) LinearOutSlowInEasing else FastOutSlowInEasing

    /** Jump instead of animating: only with the switch on and a key held down. */
    val skipAnimation: Boolean get() = snappy && holdingKey
}
