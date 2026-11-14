package com.arflix.tv.data.repository

import com.arflix.tv.data.api.TraktIds
import com.arflix.tv.data.api.TraktNextEpisode
import com.arflix.tv.data.api.TraktProgressEpisode
import com.arflix.tv.data.api.TraktProgressSeason
import com.arflix.tv.data.api.TraktShowInfo
import com.arflix.tv.data.api.TraktShowProgress
import com.arflix.tv.data.api.TraktWatchedEpisode
import com.arflix.tv.data.api.TraktWatchedSeason
import com.arflix.tv.data.api.TraktWatchedShow
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GmcRewatchTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000L * day

    /** Two seasons of four; [plays] maps "s:e" to the day of its last play. */
    private fun show(plays: Map<String, Long>, traktNext: Pair<Int, Int>? = null): Pair<TraktShowProgress, TraktWatchedShow> {
        val seasons = (1..2).map { s ->
            TraktProgressSeason(s, 4, plays.keys.count { it.startsWith("$s:") },
                (1..4).map { e -> TraktProgressEpisode(e, "$s:$e" in plays, null) })
        }
        val progress = TraktShowProgress(
            aired = 8, completed = plays.size, lastWatchedAt = null, resetAt = null,
            nextEpisode = traktNext?.let { TraktNextEpisode(it.first, it.second, null, TraktIds()) },
            seasons = seasons
        )
        val watched = TraktWatchedShow(
            plays = plays.size, lastWatchedAt = null, lastUpdatedAt = null,
            show = TraktShowInfo("Show", 2003, TraktIds(trakt = 1, tmdb = 2)),
            seasons = (1..2).map { s ->
                TraktWatchedSeason(s, plays.filterKeys { it.startsWith("$s:") }.map { (k, d) ->
                    TraktWatchedEpisode(k.substringAfter(':').toInt(), 1, d.toString())
                })
            }
        )
        return progress to watched
    }

    private fun target(p: Pair<TraktShowProgress, TraktWatchedShow>) =
        GmcRewatch.target(p.first, p.second, includeSpecials = false, nowMs = now, parse = { it.toLong() * day })

    private val allSeenLongAgo = (1..2).flatMap { s -> (1..4).map { "$s:$it" } }.associateWith { 10L }

    @Test
    fun rewatchContinuesAfterTheEpisodePlayedLast() {
        val plays = allSeenLongAgo + mapOf("1:1" to 990L, "1:2" to 995L)
        val t = target(show(plays))!!
        assertThat(t.episode).isEqualTo(GmcRewatch.Episode(1, 3))
    }

    @Test
    fun rewatchCrossesIntoTheNextSeason() {
        val plays = allSeenLongAgo + mapOf("1:4" to 995L)
        assertThat(target(show(plays))!!.episode).isEqualTo(GmcRewatch.Episode(2, 1))
    }

    @Test
    fun finishedRewatchLeavesTheRow() {
        val plays = allSeenLongAgo + mapOf("2:4" to 995L)
        assertThat(target(show(plays))).isNull()
    }

    @Test
    fun oldRewatchIsDropped() {
        val plays = allSeenLongAgo + mapOf("1:2" to 700L)
        assertThat(target(show(plays))).isNull()
    }

    @Test
    fun firstWatchIsLeftToTrakt() {
        val plays = mapOf("1:1" to 990L, "1:2" to 995L)
        assertThat(target(show(plays, traktNext = 1 to 3))).isNull()
    }

    @Test
    fun aWholeSeasonMarkedAtOnceCountsAsItsLastEpisode() {
        val plays = allSeenLongAgo + (1..4).associate { "1:$it" to 995L }
        assertThat(target(show(plays))!!.episode).isEqualTo(GmcRewatch.Episode(2, 1))
    }

    @Test
    fun sameAnswerAsTraktIsNotDuplicated() {
        val plays = allSeenLongAgo.minus("1:3") + mapOf("1:2" to 995L)
        // 1:3 never watched: a first watch of that episode, Trakt's pointer
        assertThat(target(show(plays, traktNext = 1 to 3))).isNull()
    }

    @Test
    fun pausedRewatchIsKeptOnlyWhenNewerThanItsLastPlay() {
        assertThat(GmcRewatch.pauseIsNewer(lastWatchedAtMs = 100L, pausedAtMs = 200L)).isTrue()
        assertThat(GmcRewatch.pauseIsNewer(lastWatchedAtMs = 200L, pausedAtMs = 100L)).isFalse()
        assertThat(GmcRewatch.pauseIsNewer(lastWatchedAtMs = 0L, pausedAtMs = 100L)).isFalse()
        assertThat(GmcRewatch.pauseIsNewer(lastWatchedAtMs = null, pausedAtMs = 100L)).isTrue()
    }
}
