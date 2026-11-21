package com.arflix.tv.ui.screens.player

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.arflix.tv.data.model.StreamSource
import com.arflix.tv.network.OkHttpProvider
import com.arflix.tv.util.normalizeLanguageCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
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

    /** What each probe of the last [pick] answered, in order (for the source log). */
    @Volatile var lastResults: String = ""
        private set

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
            val answers = mutableListOf<String>()
            for ((index, pair) in probes.withIndex()) {
                val answer = pair.second.await()
                answers += "#${index + 1} " + when (answer) { true -> "yes"; false -> "no"; null -> "?" }
                if (answer == true) {
                    chosen = pair.first
                    break
                }
            }
            lastResults = answers.joinToString(", ")
            probes.forEach { it.second.cancel() }
            Log.i(TAG, "target=$target candidates=${candidates.size} chosen=${chosen?.let { candidates.indexOf(it) }}")
            chosen
        }
    }

    /** true / false: the file has / lacks the track; null: it could not be read in time. */
    private suspend fun probe(context: Context, stream: StreamSource, target: String): Boolean? {
        val headers = stream.behaviorHints?.proxyHeaders?.request.orEmpty()
        val dataSource = OkHttpDataSource.Factory(OkHttpProvider.playbackClient).setDefaultRequestProperties(headers)
        // media3's own track-list reader: it opens the file, reads the container's track list and
        // stops — no player, no renderers, so no hardware decoder is ever taken from the real
        // player (2.4.53 used a renderer-less ExoPlayer, which never reported a Greek track: Small
        // Prophets S01E02, Nov 2026, BiOMA had one).
        @Suppress("DEPRECATION")
        val retriever = androidx.media3.exoplayer.MetadataRetriever.Builder(
            context.applicationContext,
            MediaItem.fromUri(stream.url.orEmpty())
        ).setMediaSourceFactory(
            DefaultMediaSourceFactory(context.applicationContext).setDataSourceFactory(dataSource)
        ).build()
        val label = (stream.behaviorHints?.filename ?: stream.source).take(70)
        return try {
            val groups = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                suspendCancellableCoroutine<androidx.media3.exoplayer.source.TrackGroupArray?> { cont ->
                    val future = retriever.retrieveTrackGroups()
                    future.addListener({
                        val result = runCatching { future.get() }
                        if (cont.isActive) cont.resume(result.getOrNull())
                        result.exceptionOrNull()?.let { Log.i(TAG, "probe \"$label\": ${it.cause?.message ?: it.message}") }
                    }, Runnable::run)
                    cont.invokeOnCancellation { future.cancel(true) }
                }
            }
            val found = groups?.let { hasTrack(it, target) }
            Log.i(
                TAG,
                "probe \"$label\": " + when (found) {
                    null -> "no answer in ${PROBE_TIMEOUT_MS / 1000}s"
                    true -> "has $target"
                    false -> "no $target (text: ${groups.textLanguages()})"
                }
            )
            found
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.i(TAG, "probe \"$label\": ${e.message}")
            null
        } finally {
            runCatching { retriever.close() }
        }
    }

    private fun androidx.media3.exoplayer.source.TrackGroupArray.textLanguages(): String =
        (0 until length).map { get(it) }.filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { g -> (0 until g.length).map { g.getFormat(it).language ?: "?" } }
            .joinToString(",").ifEmpty { "none" }

    private fun hasTrack(groups: androidx.media3.exoplayer.source.TrackGroupArray, target: String): Boolean =
        (0 until groups.length).map { groups.get(it) }.any { group ->
            group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any { i ->
                val format = group.getFormat(i)
                val forced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0 ||
                    format.label.orEmpty().contains("forced", ignoreCase = true)
                val byCode = format.language?.let { normalizeLanguageCode(it) } == target
                val byLabel = format.label?.let { normalizeLanguageCode(it) } == target
                !forced && (byCode || byLabel)
            }
        }
}
