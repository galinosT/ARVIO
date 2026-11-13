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
    ) {
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
        val target = targetStartsMs.sorted().toLongArray()
        val reference = referenceStartsMs.sorted().distinct().toLongArray()
        if (target.size < MIN_PAIRS_PER_THIRD * 3 || reference.size < MIN_PAIRS_PER_THIRD * 3) return null

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
            return Measurement(overall, thirds.map { if (it.isEmpty()) overall else median(it) }, UNPAIRED_SPREAD_MS, fine.size)
        }
        val medians = thirds.map(::median)
        return Measurement(
            shiftMs = overall,
            thirds = medians,
            spreadMs = medians.max() - medians.min(),
            pairs = fine.size,
        )
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
