package com.arflix.tv.data.gmc

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/*
 * GMC personal build: the files in the user's own Premiumize cloud, browsable and playable in GMC.
 *
 * Listing uses Premiumize's folder API rather than WebDAV: the same folders and files, but every
 * file comes with a direct link the player can open as it is (WebDAV would need a Basic-auth
 * header on every video request) and with its upload date, so the newest can come first. Only the
 * API key is needed (Account → API key on premiumize.me), kept on this device only.
 *
 * Playback goes through the normal provided-URL path with a negative media id in a range of its
 * own ([isCloudId]): no metadata lookup, no source search, no Trakt or cloud progress — the
 * resume position is kept here, per file.
 */
object GmcCloud {

    data class Item(
        val id: String,
        val name: String,
        val isFolder: Boolean,
        val sizeBytes: Long,
        /** Seconds since the epoch, 0 when unknown. */
        val createdAt: Long,
        /** Direct link for a file, null for a folder. */
        val link: String?,
    )

    data class Listing(val folderName: String, val items: List<Item>)

    private const val PREFS = "gmc_cloud"
    private const val KEY_API = "premiumize_api_key"
    private const val RESUME_PREFIX = "resume_"
    private const val API = "https://www.premiumize.me/api/folder/list"

    private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "webm", "wmv", "mpg", "mpeg")

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun apiKey(context: Context): String = prefs(context).getString(KEY_API, "").orEmpty().trim()

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API, key.trim()).apply()
    }

    /** One folder (the root when [folderId] is null): folders first, then videos, newest first. */
    suspend fun list(context: Context, folderId: String?): Result<Listing> = withContext(Dispatchers.IO) {
        runCatching {
            val key = apiKey(context)
            require(key.isNotBlank()) { "Δεν έχει οριστεί κλειδί Premiumize" }
            val url = API.toHttpUrl().newBuilder().apply {
                addQueryParameter("apikey", key)
                if (!folderId.isNullOrBlank()) addQueryParameter("id", folderId)
            }.build()
            val body = client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                check(response.isSuccessful) { "Premiumize: HTTP ${response.code}" }
                response.body?.string().orEmpty()
            }
            parseListing(body)
        }
    }

    internal fun parseListing(body: String): Listing {
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
        val sorted = items.sortedWith(compareByDescending<Item> { it.isFolder }.thenByDescending { it.createdAt })
        return Listing(folderName = json.optString("name"), items = sorted)
    }

    private fun isVideo(name: String, mime: String): Boolean =
        mime.startsWith("video/") || name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

    // ── Playback identity and resume ────────────────────────────────────────────────────────

    private const val ID_LOW = -1_900_000_000
    private const val ID_HIGH = -1_500_000_000

    /** A stable negative media id for a cloud file, in a range nothing else uses. */
    fun mediaIdFor(fileId: String): Int = ID_HIGH - (abs(fileId.hashCode() % 400_000_000))

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
