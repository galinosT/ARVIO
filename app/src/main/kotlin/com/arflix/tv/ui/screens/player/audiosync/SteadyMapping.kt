package com.arflix.tv.ui.screens.player.audiosync

import kotlin.math.abs
import kotlin.math.sign

/**
 * ARVIO: decides when a new audio-sync mapping reaches the screen, so a poor match cannot move the
 * subtitle every couple of seconds.
 *
 * After its first confirmed lock the engine re-evaluates whenever it recognises a few more words
 * (every 1–3s on a TV box) and reports any mapping 300ms or more from the last. Where the audio and
 * the reference fit badly (a video whose own English subtitles were poor, Sept 2026) those reports
 * wobble, and each one used to be a visible jump. Here, once the audio has confirmed a lock:
 * - a change under [MIN_CHANGE_MS] at the playhead is not shown;
 * - a change must still be the answer [HOLD_MS] later;
 * - at most one change per [MIN_INTERVAL_MS], unless it is [BIG_CHANGE_MS] or more (a cut) or it
 *   follows a seek;
 * - [MAX_REVERSALS] changes of direction within [REVERSAL_WINDOW_MS] mean the audio cannot be
 *   trusted here: the current timing is kept for the rest of the session.
 * Before the first confirmed lock every mapping passes straight through, as the engine reports it.
 */
internal class SteadyMapping(private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L }) {

    sealed interface Decision {
        /** Leave the subtitle as it is. */
        data object Keep : Decision

        /** Show [model]. [changeMs] is the move at the playhead when it is an adjustment, else null. */
        data class Show(val model: SubtitleSyncModel?, val changeMs: Long?) : Decision

        /** The mapping keeps reversing: the current timing stays from now on. */
        data object Froze : Decision
    }

    var displayed: SubtitleSyncModel? = null
        private set

    private var settledSeen = false
    private var frozen = false
    private var pendingValueMs: Long? = null
    private var pendingSinceMs = 0L
    private var lastChangeAtMs = Long.MIN_VALUE / 2
    private var seekUntilMs = Long.MIN_VALUE / 2
    private var lastDirection = 0
    private val reversals = ArrayDeque<Long>()

    // GMC fine correction: see [gmcFine].
    private var fineDone = false
    private var finePendingMs: Long? = null
    private var fineSinceMs = 0L
    private var fineReports = 0
    private var fineLastModel: SubtitleSyncModel? = null

    fun reset() {
        displayed = null
        settledSeen = false
        frozen = false
        pendingValueMs = null
        lastChangeAtMs = Long.MIN_VALUE / 2
        seekUntilMs = Long.MIN_VALUE / 2
        lastDirection = 0
        reversals.clear()
        fineDone = false
        clearFine()
    }

    private fun clearFine() {
        finePendingMs = null
        fineReports = 0
        fineLastModel = null
    }

    /**
     * GMC: changes under [MIN_CHANGE_MS] stay hidden, so a lock that landed 0.2–0.4s off stayed
     * that way for the whole episode. Once per lock, a small change is shown after all when the
     * engine keeps finding it: [FINE_MIN_REPORTS] separate readings within [FINE_AGREE_MS] of each
     * other, for [FINE_HOLD_MS]. A wobble around the current timing resets the wait.
     */
    private fun gmcFine(latest: SubtitleSyncModel, latestMs: Long, changeMs: Long): Decision {
        if (fineDone || abs(changeMs) < FINE_MIN_MS) {
            clearFine()
            return Decision.Keep
        }
        val now = clockMs()
        val pending = finePendingMs
        if (pending == null || abs(latestMs - pending) > FINE_AGREE_MS) {
            finePendingMs = latestMs
            fineSinceMs = now
            fineReports = 1
            fineLastModel = latest
            return Decision.Keep
        }
        if (latest !== fineLastModel) {
            fineReports++
            fineLastModel = latest
        }
        if (fineReports < FINE_MIN_REPORTS || now - fineSinceMs < FINE_HOLD_MS) return Decision.Keep
        fineDone = true
        clearFine()
        lastChangeAtMs = now
        return show(latest, changeMs)
    }

    /** The user jumped: the timing at the new place may be shown at once. */
    fun onSeek() {
        seekUntilMs = clockMs() + SEEK_GRACE_MS
    }

    /** [latest] is what the engine (and the anchor) would show at [positionMs]; [settled]: confirmed. */
    fun decide(latest: SubtitleSyncModel?, positionMs: Long, settled: Boolean): Decision {
        val current = displayed
        if (latest === current) {
            pendingValueMs = null
            return Decision.Keep
        }
        if (!settled || latest == null || current == null || (settled && !settledSeen)) {
            if (settled) settledSeen = true
            return show(latest, null)
        }
        if (frozen) return Decision.Keep
        val latestMs = delayMs(latest, positionMs)
        val changeMs = latestMs - delayMs(current, positionMs)
        if (abs(changeMs) < MIN_CHANGE_MS) {
            pendingValueMs = null
            return gmcFine(latest, latestMs, changeMs)
        }
        clearFine()
        val now = clockMs()
        val afterSeek = now < seekUntilMs
        val pending = pendingValueMs
        if (pending == null || abs(latestMs - pending) >= MIN_CHANGE_MS) {
            pendingValueMs = latestMs
            pendingSinceMs = now
            if (!afterSeek) return Decision.Keep
        }
        val held = afterSeek || now - pendingSinceMs >= HOLD_MS
        val allowed = afterSeek || abs(changeMs) >= BIG_CHANGE_MS || now - lastChangeAtMs >= MIN_INTERVAL_MS
        if (!held || !allowed) return Decision.Keep

        val direction = changeMs.sign
        if (lastDirection != 0 && direction != lastDirection) reversals.addLast(now)
        lastDirection = direction
        while (reversals.isNotEmpty() && now - reversals.first() > REVERSAL_WINDOW_MS) reversals.removeFirst()
        if (reversals.size >= MAX_REVERSALS) {
            frozen = true
            pendingValueMs = null
            return Decision.Froze
        }
        lastChangeAtMs = now
        // A new timing may itself land a little off: it gets its own fine correction.
        fineDone = false
        return show(latest, changeMs)
    }

    private fun show(model: SubtitleSyncModel?, changeMs: Long?): Decision {
        displayed = model
        pendingValueMs = null
        return Decision.Show(model, changeMs)
    }

    companion object {
        const val MIN_CHANGE_MS = 500L
        const val HOLD_MS = 5_000L
        const val MIN_INTERVAL_MS = 30_000L
        const val BIG_CHANGE_MS = 2_000L
        const val SEEK_GRACE_MS = 20_000L
        const val MAX_REVERSALS = 3
        const val REVERSAL_WINDOW_MS = 4 * 60_000L
        const val FINE_MIN_MS = 150L
        const val FINE_AGREE_MS = 120L
        const val FINE_HOLD_MS = 20_000L
        const val FINE_MIN_REPORTS = 3

        fun delayMs(model: SubtitleSyncModel, positionMs: Long): Long = model.delayUsAt(positionMs * 1_000L) / 1_000L
    }
}
