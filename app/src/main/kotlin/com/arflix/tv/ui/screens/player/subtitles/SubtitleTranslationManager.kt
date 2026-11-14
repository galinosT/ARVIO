package com.arflix.tv.ui.screens.player.subtitles

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException

class SubtitleTranslationManager(
    private var service: SubtitleTranslationService,
    internal var targetLanguage: String,
    private val scope: CoroutineScope,
    /** GMC: the subtitle memory server and glossaries need it; null turns both off. */
    private val gmcContext: android.content.Context? = null,
    /** GMC: the title being watched, for its glossary; null when unknown. */
    private val gmcTitle: () -> GmcGlossary.Title? = { null },
) {
    companion object {
        const val MOCK_MODE = false
        /** GMC: marks an episode whose lines are already in the glossary. */
        private const val DONE = "\u0000gmc-glossary-done"
        // Groq free tier: 30 RPM. Subtitles change every 2-4s naturally (~15-20 RPM).
        // No artificial rate limit needed — just don't fire concurrent requests.
        // If we get a 429, we back off 5s.
        private const val BATCH_WINDOW_MS = 150L  // wait up to 150ms for more items to batch
        private const val GMC_BLANK_CUES_FOR_IMAGE_TRACK = 3
        private const val GMC_REPORT_AFTER_LINES = 60
    }

    var isEnabled: Boolean = false
    var removeHearingImpaired: Boolean = true
    @Volatile var removeSubtitleHearingImpaired: Boolean = false

    var onTranslatingChanged: ((Boolean) -> Unit)? = null
    var onBatchResult: ((success: Boolean, error: String?) -> Unit)? = null
    /**
     * Fired when translation is active but the cues carry no extractable text — i.e. the chosen
     * AI source is an image track (PGS/VOBSUB). Metadata alone can't be trusted to catch this
     * (containers report null/generic MIME), so this is the runtime backstop that stops AI from
     * silently rendering the untranslated source language.
     */
    var onUntranslatableSource: (() -> Unit)? = null

    // GMC: one blank cue on a real text track (an empty or styling-only cue) used to be enough to
    // call the English source an image track, and AI moved to the next embedded track — Polish on
    // a Married… with Children episode (14 Nov 2026), shown on screen in the middle of the episode.
    // A source counts as untranslatable only when it has shown no text at all and several blank cues.
    @Volatile private var gmcTextCues = 0
    @Volatile private var gmcBlankCues = 0

    /** A cue with text arrived from the current source. */
    fun gmcNoteTextCue() { gmcTextCues++ }

    /** A cue without text arrived; true when the source should now be treated as image-only. */
    fun gmcNoteBlankCue(): Boolean {
        gmcBlankCues++
        return gmcTextCues == 0 && gmcBlankCues >= GMC_BLANK_CUES_FOR_IMAGE_TRACK
    }

    /** A new AI source was chosen: start counting again. */
    fun gmcNewSource() {
        gmcTextCues = 0
        gmcBlankCues = 0
        gmcMemoryLines = 0
        gmcModelLines = 0
        gmcReported = false
    }

    // GMC: once per episode, after the first [GMC_REPORT_AFTER_LINES] lines, say how many came
    // from the memory server (already translated on some box) instead of the model.
    var onGmcMemoryReport: ((fromMemory: Int, total: Int) -> Unit)? = null
    @Volatile private var gmcMemoryLines = 0
    @Volatile private var gmcModelLines = 0
    @Volatile private var gmcReported = false

    private fun gmcCount(fromMemory: Int, fromModel: Int) {
        gmcMemoryLines += fromMemory
        gmcModelLines += fromModel
        val total = gmcMemoryLines + gmcModelLines
        if (!gmcReported && total >= GMC_REPORT_AFTER_LINES) {
            gmcReported = true
            if (gmcMemoryLines > 0) onGmcMemoryReport?.invoke(gmcMemoryLines, total)
        }
    }

    val translatedCount: Int get() = cache.size

    @Volatile var isTranslating: Boolean = false
        private set

    private val cache = ConcurrentHashMap<String, String>()
    // Tracks texts currently queued but not yet translated, to avoid double-queuing the same text
    // from both the real-time path and preTranslateWindow racing on the same cue.
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val pendingCount = AtomicInteger(0)
    private var hideTranslatingJob: Job? = null

    private data class PendingItem(val text: String, val deferred: CompletableDeferred<String>)
    private val queue = Channel<PendingItem>(Channel.UNLIMITED)

    init {
        if (!MOCK_MODE) {
            scope.launch { processBatches() }
        }
    }

    fun updateService(apiKey: String, model: SubtitleAiModel) {
        // GMC: keep the Claude key provider. Rebuilding the service without it (this runs every
        // time the player loads its settings) silently sent every translation back to Gemini.
        service = SubtitleTranslationService(
            apiKeyProvider = { apiKey },
            modelProvider = { model },
            claudeKeyProvider = service.claudeKeyProvider
        )
    }

    private suspend fun processBatches() {
        val batch = mutableListOf<PendingItem>()
        while (true) {
            // Block until the first item is available
            val first = queue.receive()
            batch.add(first)

            // Collect any additional items that arrive within BATCH_WINDOW_MS.
            // This handles burst cache misses (e.g., right after a seek) efficiently.
            val deadline = System.currentTimeMillis() + BATCH_WINDOW_MS
            while (batch.size < 40) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) break
                val next = withTimeoutOrNull(remaining) { queue.receive() } ?: break
                batch.add(next)
            }

            // GMC: lines any box has translated before come from the memory server, free.
            val remembered = fromMemory(batch.map { it.text })
            gmcCount(fromMemory = batch.count { it.text in remembered }, fromModel = batch.count { it.text !in remembered })
            if (remembered.isNotEmpty()) {
                batch.removeAll { item ->
                    val known = remembered[item.text] ?: return@removeAll false
                    cache[item.text] = known
                    inFlight.remove(item.text)
                    item.deferred.complete(known)
                    true
                }
                if (batch.isEmpty()) {
                    onBatchResult?.invoke(true, null)
                    continue
                }
            }
            val texts = batch.map { it.text }
            prepareGlossary(texts)
            val result = try {
                service.translateBatch(texts, targetLanguage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TranslationResult(
                    lines = emptyList(),
                    success = false,
                    errorMessage = e.message ?: "Translation error"
                )
            }
            if (!result.success) {
                onBatchResult?.invoke(false, result.errorMessage)
                // On error: complete deferreds with original text so the caller doesn't hang,
                // but do NOT cache — the next render will retry rather than permanently show English.
                batch.forEachIndexed { i, item ->
                    inFlight.remove(item.text)
                    item.deferred.complete(item.text)
                }
                batch.clear()
                delay(5_000L)
                continue
            }
            onBatchResult?.invoke(true, null)
            batch.forEachIndexed { i, item ->
                val translated = result.lines.getOrElse(i) { item.text }
                cache[item.text] = translated
                inFlight.remove(item.text)
                item.deferred.complete(translated)
            }
            remember(texts, result)
            batch.clear()
        }
    }

    fun getCached(text: String): String? = cache[text]

    fun isInFlight(text: String): Boolean = inFlight.containsKey(text)

    suspend fun translate(text: String): String {
        cache[text]?.let { return it }
        // If the same text is already queued (e.g. preTranslateWindow raced us), join it.
        inFlight[text]?.let { return it.await() }

        val deferred = CompletableDeferred<String>()
        inFlight[text] = deferred
        val depth = pendingCount.getAndIncrement()
        if (depth == 0) {
            isTranslating = true
            onTranslatingChanged?.invoke(true)
        }
        queue.send(PendingItem(text, deferred))
        return try {
            deferred.await()
        } finally {
            if (pendingCount.decrementAndGet() == 0) {
                hideTranslatingJob?.cancel()
                hideTranslatingJob = scope.launch {
                    delay(1500)
                    if (pendingCount.get() == 0) {
                        isTranslating = false
                        onTranslatingChanged?.invoke(false)
                    }
                }
            }
        }
    }

    fun reset() {
        cache.clear()
        inFlight.clear()
        if (pendingCount.get() == 0) {
            isTranslating = false
            onTranslatingChanged?.invoke(false)
        }
    }

    suspend fun preTranslateWindow(texts: List<String>) {
        // The lookahead triggers fire whenever a text track renders cues — including tracks
        // selected under the hood by "find best match" with AI translation off. Never spend
        // API requests unless translation is actually active.
        if (!isEnabled) return
        val waiting = texts.filter { !cache.containsKey(it) && !inFlight.containsKey(it) }
        if (waiting.isEmpty()) return
        // GMC: what the memory server already has needs no request at all.
        val remembered = fromMemory(waiting)
        remembered.forEach { (text, translated) -> cache[text] = translated }
        val uncached = waiting.filter { it !in remembered }
        gmcCount(fromMemory = waiting.size - uncached.size, fromModel = uncached.size)
        if (uncached.isEmpty()) return
        uncached.chunked(40).forEach { chunk ->
            prepareGlossary(chunk)
            val result = service.translateBatch(chunk, targetLanguage)
            if (result.success) {
                onBatchResult?.invoke(true, null)
                chunk.forEachIndexed { i, text ->
                    cache[text] = result.lines.getOrElse(i) { text }
                }
                remember(chunk, result)
            } else {
                onBatchResult?.invoke(false, result.errorMessage)
                delay(5_000L)
                return
            }
        }
    }

    // ── GMC: subtitle memory and glossary ────────────────────────────────────────────────────

    /**
     * The memory is kept per title: the same English line can need another Greek line in another
     * show (gender, register — "Are you ready?" is «έτοιμος» or «έτοιμη»). The server only hashes
     * this value, so the title rides along in it. Null when the title is unknown: nothing is shared.
     */
    private fun memoryScope(): String? {
        val lang = targetLanguage.takeIf { it.isNotBlank() } ?: return null
        val title = gmcTitle() ?: return null
        return "$lang|${title.key}"
    }

    private suspend fun fromMemory(texts: List<String>): Map<String, String> {
        val context = gmcContext ?: return emptyMap()
        val config = GmcSubtitleMemory.config(context) ?: return emptyMap()
        val lang = memoryScope() ?: return emptyMap()
        return try {
            GmcSubtitleMemory.lookup(config, lang, texts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Uploads a successful batch, leaving out the lines the model never really translated. */
    private fun remember(texts: List<String>, result: TranslationResult) {
        val context = gmcContext ?: return
        val config = GmcSubtitleMemory.config(context) ?: return
        val lang = memoryScope() ?: return
        val model = service.modelLabel()
        val items = texts.mapIndexedNotNull { i, text ->
            if (i in result.untranslated) return@mapIndexedNotNull null
            result.lines.getOrNull(i)?.takeIf { it.isNotBlank() && it != text }?.let { text to it }
        }
        if (items.isEmpty()) return
        scope.launch {
            try {
                GmcSubtitleMemory.store(config, lang, model, items)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    @Volatile private var glossaryKey: String? = null
    @Volatile private var glossaryText: String = ""
    @Volatile private var glossaryEpisode: String? = null
    private val glossarySource = java.util.Collections.synchronizedList(mutableListOf<String>())

    /**
     * Puts the current title's glossary on the service before a Claude batch: loaded once per title,
     * then, once per episode, built (first episode) or extended with that episode's new names from the
     * first [GmcGlossary.LINES_TO_BUILD] English lines seen — in the background, so no batch waits.
     * Gemini/Groq batches are left exactly as they were.
     */
    private suspend fun prepareGlossary(texts: List<String>) {
        val context = gmcContext ?: return
        if (!service.modelLabel().startsWith("claude")) {
            service.glossary = ""
            return
        }
        val title = gmcTitle() ?: run { service.glossary = ""; return }
        val lang = targetLanguage.takeIf { it.isNotBlank() } ?: return
        val key = "${title.key}|${lang.lowercase()}"
        if (key != glossaryKey) {
            glossaryKey = key
            glossaryText = try {
                GmcGlossary.load(context, title, lang)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ""
            }
        }
        val episode = "$key|${title.episode}"
        if (episode != glossaryEpisode) {
            glossaryEpisode = episode
            glossarySource.clear()
            if (GmcGlossary.episodeDone(context, title, lang)) glossarySource.add(DONE)
        }
        if (glossarySource.firstOrNull() != DONE) {
            if (glossarySource.size < GmcGlossary.LINES_TO_BUILD) glossarySource.addAll(texts)
            if (glossarySource.size >= GmcGlossary.LINES_TO_BUILD) {
                val lines = glossarySource.toList()
                glossarySource.clear()
                glossarySource.add(DONE)
                val builder = service
                val existing = glossaryText
                scope.launch {
                    val built = try {
                        GmcGlossary.build(context, builder, title, lang, lines, existing)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (built != null && glossaryKey == key) glossaryText = built
                }
            }
        }
        service.glossary = glossaryText
    }
}
