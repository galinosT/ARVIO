package com.arflix.tv.data.repository

import com.arflix.tv.data.api.TraktShowProgress
import com.arflix.tv.data.api.TraktWatchedShow

/**
 * GMC: Continue Watching for shows that are being watched again.
 *
 * Trakt's own "next episode" is the first episode that was never watched, so a show seen in full
 * years ago and now being rewatched either has no next episode at all or points somewhere
 * unrelated. Every play still updates the episode's last_watched_at, though, so the episode
 * watched most recently tells where a rewatch stands: the next one is offered instead.
 */
internal object GmcRewatch {

    /** A rewatch older than this is treated as abandoned and leaves the row. */
    const val RECENT_MS = 120L * 24 * 60 * 60 * 1000

    data class Episode(val season: Int, val number: Int)

    data class Target(val episode: Episode, val position: Int, val total: Int, val lastWatchedAtMs: Long)

    /**
     * The episode to offer for a show being rewatched, or null when Trakt's own next episode is
     * the right answer (a first watch in progress, a finished rewatch, nothing recent).
     */
    fun target(
        progress: TraktShowProgress,
        watched: TraktWatchedShow,
        includeSpecials: Boolean,
        nowMs: Long,
        parse: (String) -> Long = ::parseIsoMs
    ): Target? {
        val latest = latestWatched(watched, includeSpecials, parse) ?: return null
        if (nowMs - latest.second > RECENT_MS) return null

        val aired = progress.seasons.orEmpty()
            .filter { includeSpecials || it.number > 0 }
            .sortedBy { it.number }
            .flatMap { season ->
                season.episodes.orEmpty().sortedBy { it.number }.map { season.number to it }
            }
        val index = aired.indexOfFirst { (s, e) -> s == latest.first.season && e.number == latest.first.number }
        if (index < 0 || index + 1 >= aired.size) return null
        val (nextSeason, next) = aired[index + 1]
        // Never watched before: this is a first watch, and Trakt's own pointer handles it.
        if (!next.completed) return null
        val episode = Episode(nextSeason, next.number)
        val trakt = progress.nextEpisode
        if (trakt != null && trakt.season == episode.season && trakt.number == episode.number) return null
        return Target(episode, index + 1, aired.size, latest.second)
    }

    /** The most recently watched episode; a whole season marked at once resolves to its last one. */
    fun latestWatched(
        watched: TraktWatchedShow,
        includeSpecials: Boolean,
        parse: (String) -> Long = ::parseIsoMs
    ): Pair<Episode, Long>? {
        var best: Pair<Episode, Long>? = null
        for (season in watched.seasons.orEmpty()) {
            if (!includeSpecials && season.number == 0) continue
            for (episode in season.episodes) {
                if (episode.plays <= 0) continue
                val at = episode.lastWatchedAt?.let(parse)?.takeIf { it > 0L } ?: continue
                val candidate = Episode(season.number, episode.number) to at
                val current = best
                if (current == null || at > current.second ||
                    (at == current.second && isAfter(candidate.first, current.first))
                ) {
                    best = candidate
                }
            }
        }
        return best
    }

    /** Last play of every watched episode, keyed like traktWatchedEpisodeKeys. */
    fun lastWatchedByEpisode(
        shows: List<TraktWatchedShow>,
        parse: (String) -> Long = ::parseIsoMs
    ): Map<String, Long> = buildMap {
        for (watched in shows) {
            val id = watched.show.ids.tmdb ?: continue
            for (season in watched.seasons.orEmpty()) {
                for (episode in season.episodes) {
                    if (episode.plays <= 0) continue
                    put("show_tmdb:$id:${season.number}:${episode.number}",
                        episode.lastWatchedAt?.let(parse) ?: 0L)
                }
            }
        }
    }

    /**
     * A paused episode that was watched before belongs in the row when the pause is newer than
     * the last full play: it is a rewatch stopped half way, not a stale leftover.
     */
    fun pauseIsNewer(lastWatchedAtMs: Long?, pausedAtMs: Long): Boolean =
        lastWatchedAtMs == null || (pausedAtMs > 0L && lastWatchedAtMs > 0L && pausedAtMs > lastWatchedAtMs)

    private fun isAfter(a: Episode, b: Episode): Boolean =
        a.season > b.season || (a.season == b.season && a.number > b.number)

    fun parseIsoMs(value: String): Long =
        runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
}
