package com.arflix.tv.ui.screens.player.audiosync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** GMC: the one small correction after a lock (SteadyMapping.gmcFine). */
class GmcSteadyFineTest {

    private var now = 0L
    private val steady = SteadyMapping { now }

    private fun shift(ms: Long) = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.0, ms.toDouble())))

    private fun feed(model: SubtitleSyncModel?, forMs: Long = 250L): List<SteadyMapping.Decision> =
        (0 until (forMs / 250L).coerceAtLeast(1L)).mapNotNull {
            steady.decide(model, 60_000L, true).also { now += 250L }
                .takeIf { it != SteadyMapping.Decision.Keep }
        }

    private fun shown() = steady.displayed?.let { SteadyMapping.delayMs(it, 0L) }

    /** The engine re-reading roughly the same small offset every few seconds. */
    private fun readings(vararg ms: Long, everyMs: Long = 5_000L) =
        ms.flatMap { feed(shift(it), forMs = everyMs) }

    @Test
    fun aSmallOffsetTheEngineKeepsFindingIsCorrectedOnce() {
        feed(shift(6_500L))
        val decisions = readings(6_800L, 6_820L, 6_790L, 6_810L, 6_800L)
        assertThat(decisions).hasSize(1)
        assertThat(shown()).isIn(listOf(6_790L, 6_800L, 6_810L, 6_820L))
    }

    @Test
    fun onlyOnceForTheSameLock() {
        feed(shift(6_500L))
        readings(6_800L, 6_820L, 6_790L, 6_810L, 6_800L)
        val again = readings(6_500L, 6_520L, 6_490L, 6_510L, 6_500L)
        assertThat(again).isEmpty()
    }

    @Test
    fun aWobbleAroundTheTimingIsNotCorrected() {
        feed(shift(6_500L))
        val decisions = readings(6_800L, 6_250L, 6_780L, 6_220L, 6_790L, 6_240L, 6_800L)
        assertThat(decisions).isEmpty()
        assertThat(shown()).isEqualTo(6_500L)
    }

    @Test
    fun underAHundredAndFiftyIsLeftAlone() {
        feed(shift(6_500L))
        assertThat(readings(6_600L, 6_610L, 6_590L, 6_600L, 6_620L, 6_600L)).isEmpty()
    }

    @Test
    fun tooFewReadingsAreNotEnough() {
        feed(shift(6_500L))
        // one reading repeated for a minute is one measurement, not three
        assertThat(feed(shift(6_800L), forMs = 60_000L)).isEmpty()
    }

    @Test
    fun aBigChangeEarnsItsOwnFineCorrection() {
        feed(shift(6_500L))
        readings(6_800L, 6_820L, 6_790L, 6_810L, 6_800L)
        now += 60_000L
        feed(shift(9_000L), forMs = 6_000L)
        assertThat(shown()).isEqualTo(9_000L)
        now += 60_000L
        assertThat(readings(9_300L, 9_320L, 9_290L, 9_310L, 9_300L)).hasSize(1)
    }
}
