package com.arflix.tv.ui.screens.player.subtitles

import kotlin.math.abs

/*
 * GMC personal build: the last half-second of subtitle sync.
 *
 * The scan accepts a subtitle by how much its lines OVERLAP the reference track's, with every line
 * widened by 400ms, and never applies a shift under 700ms (MATCH_OFFSET_MIN_MS). So a subtitle half
 * a second early or late is accepted and shown as it is. Overlap cannot measure that finely (a short
 * line centred in a long reference line scores best off-sync), which is why the floor exists.
 *
 * This measures it differently: where each line STARTS. Every subtitle line start is paired with the
 * nearest reference line start (mutual nearest, so a line that merges or splits sentences does not
 * pair with the wrong one), and the median difference is taken separately over the first, middle
 * and last third of the episode. When the three agree, the subtitle is off by one constant amount,
 * known to within the agreement; when they disagree (a different cut, a frame-rate mismatch, the
 * wrong subtitle), it is not one constant amount and nothing is changed.
 *
 * Two more cases (Nov 2026):
 * - Only where the reference HAS lines. A reference built from what the player buffered covers the
 *   first minutes; measured over the whole film, the rest found nothing to pair with and read as
 *   "not in sync" — every subtitle went to AI on such files.
 * - A steady drift (0.2s → 0.6s → 1.0s across the film: a slightly different speed) is not one
 *   constant amount, but it is one straight line. When the three thirds lie on a line and every
 *   third pairs well around it, [Measurement.stretch] carries that line, and the subtitle is served
 *   stretched onto it instead of giving way to AI. A jump (a cut) is not a line and still is not.
 */
object GmcFineSync {

    /** Line starts this far apart, after the scan's own shift, may be the same line. */
    const val PAIR_WINDOW_MS = 1_200L

    /** Second pass: pairs this far from the first estimate are mis-pairings. */
    const val REFINE_WINDOW_MS = 400L

    /** Pairs each third needs before its median means anything. */
    const val MIN_PAIRS_PER_THIRD = 8

    /** The thirds agree when their medians lie within this of each other. */
    const val MAX_SPREAD_MS = 200L

    /** Shifts smaller than this are left alone: below what anyone can see. */
    const val MIN_SHIFT_MS = 100L

    /** Each part of the episode must pair at least this share of the best part's pairing rate. */
    const val MIN_RATE_RATIO = 0.7

    /** Reported as the spread when one part of the episode does not pair at all. */
    const val UNPAIRED_SPREAD_MS = 99_999L

    /** Stretch: the middle third may lie this far off the line through the outer two. */
    const val STRETCH_COLLINEAR_MS = 120L

    /** Stretch: at most this much drift per millisecond (0.3% — 10.8s an hour). */
    const val STRETCH_MAX_RATE = 0.003

    /**
     * A steady drift: media = subtitle + [interceptMs] + [rate] × subtitle, in the subtitle's
     * timing as measured (scan shift included).
     */
    data class Stretch(val interceptMs: Double, val rate: Double, val pairs: Int = 0) {
        fun shiftAt(atMs: Long): Long = (interceptMs + rate * atMs).toLong()
    }

    /**
     * What one subtitle measured against the reference.
     * [shiftMs]: add this to the subtitle's timing (media = subtitle + shift) to put it on the
     * reference; [thirds]: the same per third of the episode; [spreadMs]: how far they disagree.
     */
    data class Measurement(
        val shiftMs: Long,
        val thirds: List<Long>,
        val spreadMs: Long,
        val pairs: Int,
        /** Set when the thirds disagree but lie on one straight line (see [Stretch]). */
        val stretch: Stretch? = null,
        /** Subtitle lines inside the measured range (the share of them that paired: [pairShare]). */
        val lines: Int = 0,
    ) {
        val pairShare: Double get() = if (lines == 0) 0.0 else maxOf(pairs, stretch?.pairs ?: 0).toDouble() / lines

        /** One constant amount across the whole episode. */
        val consistent: Boolean get() = spreadMs <= MAX_SPREAD_MS

        /** The shift worth applying on top of what the scan applied (0 when none is). */
        val correctionMs: Long get() = if (consistent && abs(shiftMs) >= MIN_SHIFT_MS) shiftMs else 0L
    }

    /**
     * Measures [targetStartsMs] (the subtitle's line starts, with the scan's shift already added)
     * against [referenceStartsMs] (the built-in track's line starts). Null when there are too few
     * pairs in any third to say anything — the caller then keeps the scan's own verdict.
     */
    fun measure(targetStartsMs: List<Long>, referenceStartsMs: List<Long>): Measurement? {
        val reference = referenceStartsMs.sorted().distinct().toLongArray()
        if (reference.size < MIN_PAIRS_PER_THIRD * 3) return null
        // Only the stretch of the film the reference covers: beyond it there is nothing to pair with.
        val coveredFrom = reference.first() - PAIR_WINDOW_MS
        val coveredTo = reference.last() + PAIR_WINDOW_MS
        val target = targetStartsMs.filter { it in coveredFrom..coveredTo }.sorted().toLongArray()
        if (target.size < MIN_PAIRS_PER_THIRD * 3) return null

        // First pass: wide window, whole episode.
        val rough = pairs(target, reference, 0L, PAIR_WINDOW_MS)
        if (rough.size < MIN_PAIRS_PER_THIRD * 3) return null
        val roughShift = median(rough.map { it.second })

        // Second pass: tight window around the first estimate, so neighbouring lines in dense
        // dialogue cannot pull the medians.
        val fine = pairs(target, reference, roughShift, REFINE_WINDOW_MS)
        if (fine.size < MIN_PAIRS_PER_THIRD * 3) return null

        val from = target.first()
        val span = (target.last() - from).coerceAtLeast(1L)
        fun thirdOf(at: Long) = ((at - from) * 3 / span).coerceAtMost(2L).toInt()
        val linesPerThird = IntArray(3).also { counts -> target.forEach { counts[thirdOf(it)]++ } }
        // Too little dialogue in a part of the episode to judge it either way.
        if (linesPerThird.any { it < MIN_PAIRS_PER_THIRD * 2 }) return null
        val thirds = (0 until 3).map { part ->
            fine.filter { (at, _) -> thirdOf(at) == part }.map { it.second }
        }
        val overall = median(fine.map { it.second })
        // A part whose lines mostly do not land where the rest of the episode puts them sits
        // elsewhere (a cut, a re-edit): not one constant amount. Its median says nothing then —
        // the few lines that do pair there pair by chance with a neighbour, and chance pairs
        // centre on the window, so they look perfectly in sync. The pairing RATE gives it away.
        val rates = (0 until 3).map { thirds[it].size.toDouble() / linesPerThird[it] }
        if (thirds.any { it.size < MIN_PAIRS_PER_THIRD } || rates.min() < rates.max() * MIN_RATE_RATIO) {
            // A drift loses pairs at the ends of a window centred on the overall shift: try a line.
            return Measurement(
                overall, thirds.map { if (it.isEmpty()) overall else median(it) }, UNPAIRED_SPREAD_MS, fine.size,
                stretch = stretch(target, reference, roughShift, linesPerThird, ::thirdOf),
                lines = target.size,
            )
        }
        val medians = thirds.map(::median)
        val spread = medians.max() - medians.min()
        return Measurement(
            shiftMs = overall,
            thirds = medians,
            spreadMs = spread,
            pairs = fine.size,
            stretch = if (spread <= MAX_SPREAD_MS) null else stretch(target, reference, roughShift, linesPerThird, ::thirdOf),
            lines = target.size,
        )
    }

    /**
     * The line through the thirds, when the subtitle drifts steadily: each third's own median (wide
     * pairing), the middle one on the line through the outer two, then a tight pairing around the
     * line where every third pairs as well as the others and the leftovers agree across thirds —
     * the same test as one constant amount, around a line instead. Null when it is not a line.
     */
    private fun stretch(
        target: LongArray,
        reference: LongArray,
        roughShift: Long,
        linesPerThird: IntArray,
        thirdOf: (Long) -> Int,
    ): Stretch? {
        val wide = pairs(target, reference, roughShift, PAIR_WINDOW_MS)
        val parts = (0 until 3).map { part -> wide.filter { (at, _) -> thirdOf(at) == part } }
        if (parts.any { it.size < MIN_PAIRS_PER_THIRD }) return null
        // Each third's own centre, by its own pairs: a wide window centred on the overall shift
        // still holds the ends of a drift of up to ±1.2s.
        val centres = parts.map { p -> median(p.map { it.first }).toDouble() }
        val medians = parts.map { p -> median(p.map { it.second }).toDouble() }
        if (centres[2] - centres[0] < 1.0) return null
        val rate = (medians[2] - medians[0]) / (centres[2] - centres[0])
        if (kotlin.math.abs(rate) > STRETCH_MAX_RATE) return null
        val intercept0 = medians[0] - rate * centres[0]
        if (kotlin.math.abs(medians[1] - (intercept0 + rate * centres[1])) > STRETCH_COLLINEAR_MS) return null

        // Tight pairing around the line, then the line fitted to those pairs (least squares).
        val tight = pairsAlong(target, reference, Stretch(intercept0, rate), REFINE_WINDOW_MS)
        val tightParts = (0 until 3).map { part -> tight.filter { (at, _) -> thirdOf(at) == part } }
        if (tightParts.any { it.size < MIN_PAIRS_PER_THIRD }) return null
        val rates = (0 until 3).map { tightParts[it].size.toDouble() / linesPerThird[it] }
        if (rates.min() < rates.max() * MIN_RATE_RATIO) return null
        val n = tight.size.toDouble()
        val mx = tight.sumOf { it.first.toDouble() } / n
        val my = tight.sumOf { it.second.toDouble() } / n
        val sxx = tight.sumOf { (it.first - mx) * (it.first - mx) }
        if (sxx <= 0.0) return null
        val fitRate = tight.sumOf { (it.first - mx) * (it.second - my) } / sxx
        if (kotlin.math.abs(fitRate) > STRETCH_MAX_RATE) return null
        val fit = Stretch(my - fitRate * mx, fitRate)
        // What the line leaves over, per third: the three must agree like one constant amount does.
        val leftovers = tightParts.map { p -> median(p.map { it.second - fit.shiftAt(it.first) }) }
        if (leftovers.max() - leftovers.min() > MAX_SPREAD_MS) return null
        return fit.copy(pairs = tight.size)
    }

    /** As [pairs], with the expected difference following [line] instead of a constant. */
    private fun pairsAlong(target: LongArray, reference: LongArray, line: Stretch, windowMs: Long): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        for (t in target) {
            val expected = line.shiftAt(t)
            val wanted = t + expected
            val r = reference[nearest(reference, wanted)]
            if (abs(r - wanted) > windowMs) continue
            val back = target[nearest(target, r - expected)]
            if (back != t) continue
            out += t to (r - t)
        }
        return out
    }

    /**
     * (subtitle line start, reference start − subtitle start) for every subtitle line whose nearest
     * reference start, once [expectedMs] is added, lies within [windowMs] — and whose reference start
     * has that same subtitle line as ITS nearest one.
     */
    private fun pairs(target: LongArray, reference: LongArray, expectedMs: Long, windowMs: Long): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        for (t in target) {
            val wanted = t + expectedMs
            val r = reference[nearest(reference, wanted)]
            if (abs(r - wanted) > windowMs) continue
            val back = target[nearest(target, r - expectedMs)]
            if (back != t) continue
            out += t to (r - t)
        }
        return out
    }

    /** Index of the value in sorted [values] closest to [x]. */
    private fun nearest(values: LongArray, x: Long): Int {
        var lo = 0
        var hi = values.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (values[mid] < x) lo = mid + 1 else hi = mid
        }
        return if (lo > 0 && abs(values[lo - 1] - x) <= abs(values[lo] - x)) lo - 1 else lo
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }
}
