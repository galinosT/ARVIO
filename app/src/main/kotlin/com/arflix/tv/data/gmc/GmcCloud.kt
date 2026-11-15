package com.arflix.tv.data.gmc

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/*
 * GMC personal build: the files in the user's own debrid cloud (Premiumize, TorBox), browsable and
 * playable in GMC.
 *
 * Listing uses each service's API rather than WebDAV: the same folders and files, with upload dates
 * (newest first) and links the player can open as they are, with no auth header on every video
 * request. Only the service's API key is needed, kept on this device only.
 *
 *  - Premiumize: real folders (folder/list); every file carries a direct link.
 *  - TorBox: each download (torrent, usenet or web download) is shown as a folder of its videos;
 *    a file's link is requested when it is played (requestdl), since those links expire.
 *
 * Playback goes through the normal provided-URL path with a negative media id in a range of its
 * own ([isCloudId]): no metadata lookup, no source search, no Trakt or cloud progress — the resume
 * position is kept here, per file.
 */
object GmcCloud {

    enum class Provider(val label: String, val keyHint: String) {
        PREMIUMIZE("Premiumize", "premiumize.me → Account → API key"),
        TORBOX("TorBox", "torbox.app → Settings → API key"),
    }

    data class Item(
        val id: String,
        val name: String,
        val isFolder: Boolean,
        val sizeBytes: Long,
        /** Seconds since the epoch, 0 when unknown. */
        val createdAt: Long,
        /** Direct link for a Premiumize file; null for folders and for TorBox (see [resolveLink]). */
        val link: String?,
    )

    data class Listing(val folderName: String, val items: List<Item>)

    private const val PREFS = "gmc_cloud"
    private const val RESUME_PREFIX = "resume_"
    private const val PREMIUMIZE_API = "https://www.premiumize.me/api/folder/list"
    private const val TORBOX_API = "https://api.torbox.app/v1/api"

    /** TorBox download kinds: (list path, id parameter for requestdl). */
    private val TORBOX_KINDS = listOf(
        "torrents" to "torrent_id",
        "usenet" to "usenet_id",
        "webdl" to "web_id",
    )

    private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "webm", "wmv", "mpg", "mpeg")

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun keyName(provider: Provider) = when (provider) {
        Provider.PREMIUMIZE -> "premiumize_api_key"
        Provider.TORBOX -> "torbox_api_key"
    }

    fun apiKey(context: Context, provider: Provider): String =
        prefs(context).getString(keyName(provider), "").orEmpty().trim()

    fun setApiKey(context: Context, provider: Provider, key: String) {
        prefs(context).edit().putString(keyName(provider), key.trim()).apply()
    }

    /** One folder (the top level when [folderId] is null): folders first, then videos, newest first. */
    suspend fun list(context: Context, provider: Provider, folderId: String?): Result<Listing> = withContext(Dispatchers.IO) {
        runCatching {
            val key = apiKey(context, provider)
            require(key.isNotBlank()) { "Δεν έχει οριστεί κλειδί ${provider.label}" }
            when (provider) {
                Provider.PREMIUMIZE -> listPremiumize(key, folderId)
                Provider.TORBOX -> listTorBox(key, folderId)
            }
        }
    }

    /** The playable link for a file: Premiumize's own, or a fresh one from TorBox. */
    suspend fun resolveLink(context: Context, provider: Provider, item: Item): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            item.link?.let { return@runCatching it }
            check(provider == Provider.TORBOX) { "Δεν βρέθηκε link" }
            // id = "<kind>:<downloadId>:<fileId>"
            val (kind, downloadId, fileId) = item.id.split(':').also { require(it.size == 3) { "Άγνωστο αρχείο" } }
            val idParam = TORBOX_KINDS.firstOrNull { it.first == kind }?.second ?: error("Άγνωστο είδος: $kind")
            val url = "$TORBOX_API/$kind/requestdl".toHttpUrl().newBuilder()
                .addQueryParameter("token", apiKey(context, provider))
                .addQueryParameter(idParam, downloadId)
                .addQueryParameter("file_id", fileId)
                .addQueryParameter("redirect", "false")
                .build()
            val json = JSONObject(get(url.toString(), bearer = null))
            check(json.optBoolean("success", false)) { torBoxError(json) }
            json.optString("data").takeIf { it.startsWith("http") } ?: error("Το TorBox δεν έδωσε link")
        }
    }

    // ── Premiumize ──────────────────────────────────────────────────────────────────────────

    private fun listPremiumize(key: String, folderId: String?): Listing {
        val url = PREMIUMIZE_API.toHttpUrl().newBuilder().apply {
            addQueryParameter("apikey", key)
            if (!folderId.isNullOrBlank()) addQueryParameter("id", folderId)
        }.build()
        return parsePremiumize(get(url.toString(), bearer = null))
    }

    internal fun parsePremiumize(body: String): Listing {
        val json = JSONObject(body)
        check(json.optString("status") == "success") {
            json.optString("message").ifBlank { "Premiumize: άγνωστο σφάλμα" }
        }
        val content = json.optJSONArray("content")
        val items = ArrayList<Item>()
        for (i in 0 until (content?.length() ?: 0)) {
            val o = content!!.optJSONObject(i) ?: continue
            val isFolder = o.optString("type") == "folder"
            val name = o.optString("name")
            val link = o.optString("link").takeIf { it.isNotBlank() }
            if (!isFolder && (link == null || !isVideo(name, o.optString("mime_type")))) continue
            items += Item(
                id = o.optString("id"),
                name = name,
                isFolder = isFolder,
                sizeBytes = o.optLong("size", 0L),
                createdAt = o.optLong("created_at", 0L),
                link = if (isFolder) null else link,
            )
        }
        return Listing(folderName = json.optString("name"), items = sortItems(items))
    }

    // ── TorBox ──────────────────────────────────────────────────────────────────────────────

    /**
     * Top level: every finished download (torrents, usenet, web downloads) with at least one video,
     * as a folder. Inside a download ([folderId] = "<kind>:<id>"): its videos.
     */
    private suspend fun listTorBox(key: String, folderId: String?): Listing = coroutineScope {
        val downloads = TORBOX_KINDS.map { (kind, _) ->
            async {
                // A kind the account does not use may fail on its own; the others still list.
                runCatching { kind to torBoxDownloads(key, kind) }.getOrElse { if (kind == "torrents") throw it else kind to emptyList() }
            }
        }.awaitAll()
        if (folderId == null) {
            val folders = downloads.flatMap { (kind, list) ->
                list.mapNotNull { d ->
                    val videos = torBoxVideos(kind, d)
                    if (videos.isEmpty()) null else Item(
                        id = "$kind:${d.optString("id")}",
                        name = d.optString("name"),
                        isFolder = true,
                        sizeBytes = d.optLong("size", 0L),
                        createdAt = isoSeconds(d.optString("created_at")),
                        link = null,
                    )
                }
            }
            Listing("TorBox", sortItems(folders))
        } else {
            val (kind, id) = folderId.split(':', limit = 2)
            val download = downloads.firstOrNull { it.first == kind }?.second
                ?.firstOrNull { it.optString("id") == id }
                ?: error("Η λήψη δεν υπάρχει πια στο TorBox")
            Listing(download.optString("name"), torBoxVideos(kind, download).sortedBy { it.name.lowercase() })
        }
    }

    private fun torBoxDownloads(key: String, kind: String): List<JSONObject> {
        val url = "$TORBOX_API/$kind/mylist".toHttpUrl().newBuilder()
            .addQueryParameter("bypass_cache", "true")
            .addQueryParameter("limit", "1000")
            .build()
        val json = JSONObject(get(url.toString(), bearer = key))
        check(json.optBoolean("success", false)) { torBoxError(json) }
        val data = json.opt("data")
        val array = when (data) {
            is JSONArray -> data
            is JSONObject -> JSONArray().put(data)
            else -> JSONArray()
        }
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            // Only what is ready to play.
            .filter { it.optBoolean("download_finished", true) && it.optBoolean("download_present", true) }
    }

    private fun torBoxVideos(kind: String, download: JSONObject): List<Item> {
        val files = download.optJSONArray("files") ?: return emptyList()
        val created = isoSeconds(download.optString("created_at"))
        return (0 until files.length()).mapNotNull { i ->
            val f = files.optJSONObject(i) ?: return@mapNotNull null
            val name = f.optString("short_name").ifBlank { f.optString("name").substringAfterLast('/') }
            if (!isVideo(name, f.optString("mimetype"))) return@mapNotNull null
            // Samples and extras are not what anyone opens a download for.
            if (Regex("""(?i)(^|[ ._-])sample([ ._-]|$)""").containsMatchIn(name)) return@mapNotNull null
            Item(
                id = "$kind:${download.optString("id")}:${f.optString("id")}",
                name = name,
                isFolder = false,
                sizeBytes = f.optLong("size", 0L),
                createdAt = created,
                link = null,
            )
        }
    }

    private fun torBoxError(json: JSONObject): String =
        "TorBox: " + json.optString("detail").ifBlank { json.optString("error").ifBlank { "άγνωστο σφάλμα" } }

    // ── Shared ──────────────────────────────────────────────────────────────────────────────

    private fun get(url: String, bearer: String?): String {
        val request = Request.Builder().url(url).get().apply {
            bearer?.let { header("Authorization", "Bearer $it") }
        }.build()
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(text).let { it.optString("detail").ifBlank { it.optString("message") } } }.getOrNull()
                error("HTTP ${response.code}" + (detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
            }
            text
        }
    }

    private fun sortItems(items: List<Item>): List<Item> =
        items.sortedWith(compareByDescending<Item> { it.isFolder }.thenByDescending { it.createdAt })

    private fun isoSeconds(value: String): Long =
        runCatching { java.time.OffsetDateTime.parse(value).toEpochSecond() }.getOrNull()
            ?: runCatching { java.time.Instant.parse(value).epochSecond }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(value.substringBefore('.')).toEpochSecond(java.time.ZoneOffset.UTC) }.getOrNull()
            ?: 0L

    private fun isVideo(name: String, mime: String): Boolean =
        mime.startsWith("video/") || name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

    // ── Playback identity and resume ────────────────────────────────────────────────────────

    private const val ID_LOW = -1_900_000_000
    private const val ID_HIGH = -1_500_000_000

    /** A stable negative media id for a cloud file, in a range nothing else uses. */
    fun mediaIdFor(provider: Provider, fileId: String): Int {
        // Premiumize ids keep their original form so resume points saved before TorBox stay valid.
        val key = if (provider == Provider.PREMIUMIZE) fileId else "torbox:$fileId"
        return ID_HIGH - (abs(key.hashCode() % 400_000_000))
    }

    fun isCloudId(mediaId: Int): Boolean = mediaId in ID_LOW..ID_HIGH

    fun resumePositionMs(context: Context, mediaId: Int): Long =
        prefs(context).getLong(RESUME_PREFIX + mediaId, 0L)

    /** Kept until the last 5% (or 3 minutes) — then the file counts as finished and starts over. */
    fun saveResume(context: Context, mediaId: Int, positionMs: Long, durationMs: Long) {
        val finished = durationMs > 0 && (positionMs >= durationMs * 95 / 100 || durationMs - positionMs < 180_000L)
        prefs(context).edit().apply {
            if (finished || positionMs < 30_000L) remove(RESUME_PREFIX + mediaId) else putLong(RESUME_PREFIX + mediaId, positionMs)
        }.apply()
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
        bytes > 0 -> "${bytes / 1024} KB"
        else -> ""
    }
}
