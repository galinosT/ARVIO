package com.arflix.tv.data.repository

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/*
 * GMC personal build: two more skip providers beside IntroDB and AniSkip, for intro, recap and
 * credits on episodes IntroDB does not cover. Neither needs a key.
 *
 * - TheIntroDB (api.theintrodb.org): intro, recap, credits, preview per episode.
 * - SkipMe.db (db.skipme.workers.dev): the same, but it only answers with the file's runtime, so
 *   it is asked once the player knows the duration.
 *
 * Every provider is best effort: an error, a timeout or an odd payload is simply no segments.
 * Per kind (intro, recap, ending) the first provider that has it wins, in the order
 * IntroDB, TheIntroDB, SkipMe.db, so nothing IntroDB already gives changes.
 */
internal object GmcSkipProviders {
    private const val TAG = "GmcSkip"
    private const val THEINTRODB_URL = "https://api.theintrodb.org/v3/media"
    private const val SKIPME_URL = "https://db.skipme.workers.dev/v1/movies"
    const val THEINTRODB = "theintrodb"
    const val SKIPME = "skipme"

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    /** The kinds the player's skip button knows: intro, recap and outro (its word for credits). */
    fun normalizeType(raw: String?): String? = when (raw?.trim()?.lowercase()) {
        "intro", "opening" -> "intro"
        "recap" -> "recap"
        "credits", "outro", "ending", "ed" -> "outro"
        else -> null // preview, jump scares and anything new: no button for them
    }

    private fun kind(type: String): String = when (type.lowercase()) {
        "op", "mixed-op", "intro" -> "intro"
        "ed", "mixed-ed", "outro" -> "outro"
        else -> type.lowercase()
    }

    /** True when [intervals] already hold an intro and an ending, so asking further is pointless. */
    fun isComplete(intervals: List<SkipInterval>): Boolean {
        val kinds = intervals.map { kind(it.type) }.toSet()
        return "intro" in kinds && "outro" in kinds
    }

    /** Per kind, the segments of the first list (in priority order) that has that kind. */
    fun merge(byPriority: List<List<SkipInterval>>): List<SkipInterval> {
        val taken = HashSet<String>()
        val out = ArrayList<SkipInterval>()
        for (list in byPriority) {
            val kinds = list.map { kind(it.type) }.toSet()
            for (k in kinds) {
                if (k in taken) continue
                taken += k
                out += list.filter { kind(it.type) == k }
            }
        }
        return out.sortedBy { it.startMs }
    }

    /**
     * IntroDB's [first] plus TheIntroDB and, when [durationMs] is known, SkipMe.db — asked side by
     * side. Returns [first] unchanged when it is already complete.
     */
    suspend fun withMore(
        first: List<SkipInterval>,
        imdbId: String,
        season: Int,
        episode: Int,
        durationMs: Long?,
    ): List<SkipInterval> {
        if (isComplete(first)) return first
        val duration = durationMs?.takeIf { it > 60_000L }
        val (theIntroDb, skipMe) = coroutineScope {
            val a = async { theIntroDb(imdbId, season, episode, duration) }
            val b = async { if (duration != null) skipMe(imdbId, season, episode, duration) else emptyList() }
            a.await() to b.await()
        }
        val merged = merge(listOf(first, theIntroDb, skipMe))
        if (merged.size != first.size) {
            Log.i(TAG, "$imdbId s${season}e$episode: introdb=${first.size} theintrodb=${theIntroDb.size} skipme=${skipMe.size} -> ${merged.size}")
        }
        return merged
    }

    private suspend fun theIntroDb(imdbId: String, season: Int, episode: Int, durationMs: Long?): List<SkipInterval> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = THEINTRODB_URL.toHttpUrl().newBuilder()
                    .addQueryParameter("imdb_id", imdbId)
                    .addQueryParameter("season", season.toString())
                    .addQueryParameter("episode", episode.toString())
                    .apply { durationMs?.let { addQueryParameter("duration_ms", it.toString()) } }
                    .build()
                client.newCall(Request.Builder().url(url).header("Accept", "application/json").get().build())
                    .execute().use { r ->
                        if (!r.isSuccessful) emptyList() else parseTheIntroDb(r.body?.string().orEmpty(), durationMs)
                    }
            }.onFailure { Log.w(TAG, "theintrodb: ${it.message}") }.getOrDefault(emptyList())
        }

    private suspend fun skipMe(imdbId: String, season: Int, episode: Int, durationMs: Long): List<SkipInterval> =
        withContext(Dispatchers.IO) {
            runCatching {
                val lookup = JsonObject().apply {
                    addProperty("imdb_id", imdbId)
                    addProperty("season", season)
                    addProperty("episode", episode)
                    addProperty("duration_ms", durationMs)
                }
                val body = JsonArray().apply { add(lookup) }.toString().toRequestBody(json)
                client.newCall(Request.Builder().url(SKIPME_URL).header("Accept", "application/json").post(body).build())
                    .execute().use { r ->
                        if (!r.isSuccessful) emptyList() else parseSkipMe(r.body?.string().orEmpty(), season, episode)
                    }
            }.onFailure { Log.w(TAG, "skipme: ${it.message}") }.getOrDefault(emptyList())
        }

    /** `{"intro":[{"start_ms":..,"end_ms":..}], "credits":[{"start":..}], ...}`; seconds or ms. */
    fun parseTheIntroDb(raw: String, durationMs: Long?): List<SkipInterval> = runCatching {
        val root = JsonParser.parseString(raw).asJsonObject
        buildList {
            for ((rawType, value) in root.entrySet()) {
                val type = normalizeType(rawType) ?: continue
                if (!value.isJsonArray) continue
                for (element in value.asJsonArray) {
                    val item = element.objOrNull() ?: continue
                    val start = item.millis("start_ms", "start") ?: 0L
                    // Credits often run to the end of the file and come without an end.
                    val end = item.millis("end_ms", "end") ?: durationMs?.takeIf { type == "outro" } ?: continue
                    if (end > start && start >= 0) add(SkipInterval(start, end, type, THEINTRODB))
                }
            }
        }.sortedBy { it.startMs }
    }.getOrDefault(emptyList())

    /**
     * A one-element array. Episode answers carry `segments` with season/episode on each; otherwise
     * the item has `intro`, `recap`, `credits` arrays like TheIntroDB.
     */
    fun parseSkipMe(raw: String, season: Int, episode: Int): List<SkipInterval> = runCatching {
        val root = JsonParser.parseString(raw)
        val item = (if (root.isJsonArray) root.asJsonArray.firstOrNull() else root)?.objOrNull()
            ?: return@runCatching emptyList()
        val segments = item.get("segments")?.takeIf { it.isJsonArray }?.asJsonArray
        buildList {
            if (segments != null) {
                for (element in segments) {
                    val s = element.objOrNull() ?: continue
                    if (s.int("season") != season || s.int("episode") != episode) continue
                    val type = normalizeType(s.string("segment")) ?: continue
                    val start = s.millis("start_ms", null) ?: continue
                    val end = s.millis("end_ms", null) ?: continue
                    if (end > start && start >= 0) add(SkipInterval(start, end, type, SKIPME))
                }
            } else {
                for (rawType in listOf("intro", "recap", "credits")) {
                    val type = normalizeType(rawType) ?: continue
                    val list = item.get(rawType)?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                    for (element in list) {
                        val s = element.objOrNull() ?: continue
                        val start = s.millis("start_ms", null) ?: continue
                        val end = s.millis("end_ms", null) ?: continue
                        if (end > start && start >= 0) add(SkipInterval(start, end, type, SKIPME))
                    }
                }
            }
        }.sortedBy { it.startMs }
    }.getOrDefault(emptyList())

    private fun JsonElement.objOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

    private fun JsonObject.number(key: String): Double? {
        val e = get(key) ?: return null
        if (e.isJsonNull || !e.isJsonPrimitive) return null
        return e.asJsonPrimitive.let { p -> if (p.isNumber) p.asDouble else p.asString.trim().toDoubleOrNull() }
    }

    private fun JsonObject.int(key: String): Int? = number(key)?.toInt()

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    /** [msKey] in milliseconds, else [secondsKey] in seconds. */
    private fun JsonObject.millis(msKey: String, secondsKey: String?): Long? =
        number(msKey)?.toLong() ?: secondsKey?.let { number(it) }?.let { (it * 1000.0).toLong() }
}
