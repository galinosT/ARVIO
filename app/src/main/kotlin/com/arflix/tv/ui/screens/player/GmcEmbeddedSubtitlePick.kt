package com.arflix.tv.ui.screens.player

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.arflix.tv.data.model.StreamSource
import com.arflix.tv.network.OkHttpProvider
import com.arflix.tv.util.normalizeLanguageCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * GMC: when a title starts on its own (autoplay), prefer a source whose file carries an embedded
 * subtitle in the viewer's language.
 *
 * Addons only know subtitles from the file name ("ITA.ENG" says nothing about a Greek track
 * inside), so the top-ranked source and the next ones of the same resolution are opened briefly,
 * in parallel, without playing, and their track lists read. The first one, in the addon's order,
 * with a matching (non-forced) text track plays; when none has one, or the check runs out of
 * time, the top-ranked source plays as before (addon subtitle or AI, as usual).
 *
 * Only sources of the same resolution AND the same HDR class (Nov 2026): a Greek track is not
 * worth losing HDR on an OLED — the AI translation covers the subtitle.
 */
internal object GmcEmbeddedSubtitlePick {
    private const val TAG = "GmcEmbeddedSubs"
    private const val PREFS = "gmc_local"
    private const val KEY = "prefer_embedded_target_subs"
    private const val MAX_CANDIDATES = 5
    private const val PROBE_TIMEOUT_MS = 7_000L

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    /** [selected] first, then the next sources of the same resolution, all playable over HTTP. */
    fun candidates(
        selected: StreamSource,
        ranked: List<StreamSource>,
        resolution: (StreamSource) -> Int
    ): List<StreamSource> {
        if (!isHttp(selected)) return emptyList()
        val level = resolution(selected)
        val hdr = isHdr(selected)
        val rest = ranked.asSequence()
            .filter { it !== selected && it.url != selected.url && isHttp(it) && resolution(it) == level && isHdr(it) == hdr }
            .take(MAX_CANDIDATES - 1)
            .toList()
        return if (rest.isEmpty()) emptyList() else listOf(selected) + rest
    }

    private val HDR_REGEX = Regex("""(?i)\b(hdr(10)?(\+|plus)?|dv|dovi|dolby[ ._-]?vision)\b""")

    /** HDR10/HDR10+/HDR/Dolby Vision, as the addon's text names it. */
    internal fun isHdr(stream: StreamSource): Boolean {
        val text = buildString {
            append(stream.quality).append(' ').append(stream.source)
            stream.behaviorHints?.filename?.let { append(' ').append(it) }
            stream.description?.let { append(' ').append(it) }
        }
        return HDR_REGEX.containsMatchIn(text)
    }

    private fun isHttp(stream: StreamSource): Boolean {
        val url = stream.url.orEmpty()
        return url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
    }

    /** The first of [candidates] with an embedded subtitle in [languageCode], or null. */
    suspend fun pick(context: Context, candidates: List<StreamSource>, languageCode: String): StreamSource? {
        val target = normalizeLanguageCode(languageCode)
        if (target.isBlank() || candidates.size < 2) return null
        return coroutineScope {
            val probes = candidates.map { stream ->
                stream to async { probe(context, stream, target) }
            }
            var chosen: StreamSource? = null
            for ((stream, result) in probes) {
                if (result.await() == true) {
                    chosen = stream
                    break
                }
            }
            probes.forEach { it.second.cancel() }
            Log.i(TAG, "target=$target candidates=${candidates.size} chosen=${chosen?.let { candidates.indexOf(it) }}")
            chosen
        }
    }

    /** true / false: the file has / lacks the track; null: it could not be read in time. */
    private suspend fun probe(context: Context, stream: StreamSource, target: String): Boolean? =
        withContext(Dispatchers.Main) {
            val headers = stream.behaviorHints?.proxyHeaders?.request.orEmpty()
            val dataSource = OkHttpDataSource.Factory(OkHttpProvider.playbackClient).setDefaultRequestProperties(headers)
            val player = ExoPlayer.Builder(context.applicationContext)
                .setMediaSourceFactory(DefaultMediaSourceFactory(context.applicationContext).setDataSourceFactory(dataSource))
                .build()
            try {
                withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Boolean?> { cont ->
                        player.addListener(object : Player.Listener {
                            override fun onTracksChanged(tracks: Tracks) {
                                if (tracks.groups.isEmpty() || !cont.isActive) return
                                cont.resume(hasTrack(tracks, target))
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                if (cont.isActive) cont.resume(null)
                            }
                        })
                        player.playWhenReady = false
                        player.setMediaItem(MediaItem.fromUri(stream.url.orEmpty()))
                        player.prepare()
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            } finally {
                player.release()
            }
        }

    private fun hasTrack(tracks: Tracks, target: String): Boolean =
        tracks.groups.any { group ->
            group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any { i ->
                val format = group.getTrackFormat(i)
                val forced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0 ||
                    format.label.orEmpty().contains("forced", ignoreCase = true)
                val byCode = format.language?.let { normalizeLanguageCode(it) } == target
                val byLabel = format.label?.let { normalizeLanguageCode(it) } == target
                !forced && (byCode || byLabel)
            }
        }
}
