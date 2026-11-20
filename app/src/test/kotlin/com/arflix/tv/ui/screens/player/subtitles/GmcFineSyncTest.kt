package com.arflix.tv.ui.screens.player.subtitles

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Synthetic episodes: an English CC track (the reference) and a translation made from it the way
 * real ones are — some lines merged, some split, sound-effect cues left out, every start placed
 * by hand a little differently — then moved by a known error.
 */
class GmcFineSyncTest {

    @Test
    fun aSubtitleInSyncIsLeftAlone() {
        val m = measured(errorMs = 0)!!
        assertThat(m.consistent).isTrue()
        assertThat(m.correctionMs).isEqualTo(0L)
    }

    @Test
    fun halfASecondLateIsCorrected() {
        val m = measured(errorMs = 450)!!
        assertThat(m.consistent).isTrue()
        assertThat(abs(m.correctionMs + 450) <= 60).isTrue()
    }

    @Test
    fun earlyIsCorrectedTheOtherWay() {
        val m = measured(errorMs = -300)!!
        assertThat(m.consistent).isTrue()
        assertThat(abs(m.correctionMs - 300) <= 60).isTrue()
    }

    @Test
    fun theLargestErrorTheScanLetsThroughIsCorrected() {
        val m = measured(errorMs = 680)!!
        assertThat(m.consistent).isTrue()
        assertThat(abs(m.correctionMs + 680) <= 60).isTrue()
    }

    @Test
    fun anInvisibleErrorIsNotWorthAShift() {
        assertThat(measured(errorMs = 50)!!.correctionMs).isEqualTo(0L)
    }

    @Test
    fun aDifferentCutIsNotOneConstantAmount() {
        val m = measured(errorMs = 0, cutAfterFraction = 0.6, cutMs = 2_500)!!
        assertThat(m.consistent).isFalse()
        assertThat(m.correctionMs).isEqualTo(0L)
    }

    @Test
    fun aSpeedMismatchIsNotOneConstantAmount() {
        // 0.1% fast: about 2.7s apart by the end of a 45-minute episode.
        val m = measured(errorMs = 0, speed = 1.001)!!
        assertThat(m.consistent).isFalse()
        assertThat(m.correctionMs).isEqualTo(0L)
    }

    @Test
    fun aShortSubtitleCannotBeJudged() {
        val episode = Episode(Random(7), minutes = 3)
        assertThat(GmcFineSync.measure(episode.translation(), episode.reference)).isNull()
    }

    @Test
    fun carelessTimingStillMeasuresTheConstantPart() {
        val m = measured(errorMs = 400, authoringNoiseMs = 150.0)!!
        assertThat(m.consistent).isTrue()
        assertThat(abs(m.correctionMs + 400) <= 80).isTrue()
    }

    @Test
    fun manyEpisodesNeverGetAWrongCorrection() {
        // An in-sync subtitle must never be moved, whatever the episode looks like.
        repeat(50) { seed ->
            val m = measured(errorMs = 0, seed = 100 + seed)
            if (m != null) assertThat(abs(m.correctionMs) <= 120).isTrue()
        }
    }

    // ── Nov 2026: reference coverage and steady drift ──────────────────────────────────────

    private fun evenStarts(minutes: Int, seed: Int = 1): List<Long> {
        val r = Random(seed)
        val out = ArrayList<Long>()
        var t = 20_000L
        while (t < minutes * 60_000L) { out += t; t += 3_100L + r.nextLong(-900, 900) }
        return out
    }

    /** media = subtitle + shift(at): a subtitle that needs [shift] to sit on [film]. */
    private fun shifted(film: List<Long>, seed: Int = 7, shift: (Long) -> Long): List<Long> {
        val r = Random(seed)
        return film.filterIndexed { i, _ -> i % 9 != 4 }.map { it - shift(it) + r.nextLong(-60, 60) }
    }

    @Test
    fun aReferenceCoveringOnlyTheFirstMinutesMeasuresThoseMinutes() {
        // A reference built from what the player buffered: the first 7 minutes of a 110-minute film.
        val film = evenStarts(110)
        val m = GmcFineSync.measure(shifted(film) { 300 }, film.filter { it < 7 * 60_000L })
        assertThat(m != null && m.consistent).isTrue()
        assertThat(abs(m!!.correctionMs - 300) < 60).isTrue()
    }

    @Test
    fun aSteadyDriftIsAStretch() {
        val film = evenStarts(110)
        val m = GmcFineSync.measure(shifted(film) { at -> 200 + at * 800 / (110 * 60_000L) }, film)!!
        assertThat(m.consistent).isFalse()
        val s = m.stretch!!
        assertThat(abs(s.shiftAt(60_000L) - 207) < 80).isTrue()
        assertThat(abs(s.shiftAt(105 * 60_000L) - 964) < 80).isTrue()
    }

    @Test
    fun aJumpIsNotAStretch() {
        val film = evenStarts(110)
        val m = GmcFineSync.measure(shifted(film) { at -> if (at < 55 * 60_000L) 0 else 2_000 }, film)
        assertThat(m == null || (!m.consistent && m.stretch == null)).isTrue()
    }

    @Test
    fun aConstantShiftIsNoStretch() {
        val film = evenStarts(110)
        val m = GmcFineSync.measure(shifted(film) { 400 }, film)!!
        assertThat(m.consistent).isTrue()
        assertThat(m.stretch).isNull()
    }

    private fun measured(
        errorMs: Long,
        seed: Int = 42,
        authoringNoiseMs: Double = 80.0,
        speed: Double = 1.0,
        cutAfterFraction: Double? = null,
        cutMs: Long = 0,
    ): GmcFineSync.Measurement? {
        val episode = Episode(Random(seed))
        val translation = episode.translation(authoringNoiseMs).map { start ->
            var t = (start * speed).toLong() + errorMs
            if (cutAfterFraction != null && start > episode.lengthMs * cutAfterFraction) t += cutMs
            t
        }
        return GmcFineSync.measure(translation, episode.reference)
    }

    /** One episode's English CC track: line starts, plus which of them are dialogue. */
    private class Episode(private val random: Random, minutes: Int = 45) {
        val lengthMs = minutes * 60_000L
        private val lines = ArrayList<Line>()
        val reference: List<Long>

        init {
            var at = 20_000L + random.nextLong(20_000)
            while (at < lengthMs) {
                val duration = 900L + random.nextLong(3_500)
                lines += Line(at, duration, dialogue = random.nextDouble() > 0.08)
                // Dialogue gaps: mostly short, sometimes a scene with nobody talking.
                val gap = if (random.nextDouble() < 0.04) 15_000L + random.nextLong(45_000) else 150L + (random.nextDouble() * random.nextDouble() * 4_000).toLong()
                at += duration + gap
            }
            reference = lines.map { it.start }
        }

        /** The translation's line starts as authored: merged, split and placed by hand. */
        fun translation(authoringNoiseMs: Double = 80.0): List<Long> {
            val out = ArrayList<Long>()
            val dialogue = lines.filter { it.dialogue }
            var i = 0
            while (i < dialogue.size) {
                val line = dialogue[i]
                val noise = (random.nextGaussian() * authoringNoiseMs).toLong()
                out += line.start + noise
                when {
                    // Two quick lines become one: only the first start survives.
                    i + 1 < dialogue.size && dialogue[i + 1].start - (line.start + line.duration) < 1_000 && random.nextDouble() < 0.25 -> i += 2
                    // One long line becomes two.
                    line.duration > 3_000 && random.nextDouble() < 0.3 -> { out += line.start + line.duration / 2 + noise; i++ }
                    else -> i++
                }
            }
            return out
        }

        private fun Random.nextGaussian(): Double {
            val u = nextDouble().coerceAtLeast(1e-12)
            return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2.0 * Math.PI * nextDouble())
        }

        private class Line(val start: Long, val duration: Long, val dialogue: Boolean)
    }
}
