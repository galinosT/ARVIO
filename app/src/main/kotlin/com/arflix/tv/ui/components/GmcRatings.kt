package com.arflix.tv.ui.components

import android.content.Context
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.ui.screens.player.subtitles.GmcSubtitleMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/*
 * GMC personal build: Rotten Tomatoes and Metacritic next to the IMDb rating on the home screen
 * (hero and the focused card's line). They come from the gmc-server (/v1/ratings), which keeps one
 * MDBList lookup per title for every box, so the boxes never spend MDBList's daily allowance.
 * Nothing shows until the server answers, and nothing at all without the server set up.
 */
object GmcRatings {
    private const val TAG = "GmcRatings"
    private const val TTL_MS = 6 * 60 * 60_000L

    data class Ratings(val tomatoes: String?, val metacritic: String?) {
        val isEmpty get() = tomatoes == null && metacritic == null
    }

    private data class Entry(val ratings: Ratings, val at: Long)

    private val cache = LruCache<String, Entry>(600)
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    fun cached(mediaType: MediaType, tmdbId: Int): Ratings? =
        cache.get(key(mediaType, tmdbId))?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }?.ratings

    private fun key(mediaType: MediaType, tmdbId: Int) = "${if (mediaType == MediaType.TV) "show" else "movie"}:$tmdbId"

    suspend fun load(context: Context, mediaType: MediaType, tmdbId: Int): Ratings? {
        if (tmdbId <= 0 || (mediaType != MediaType.MOVIE && mediaType != MediaType.TV)) return null
        cached(mediaType, tmdbId)?.let { return it }
        val config = GmcSubtitleMemory.config(context) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val url = (config.baseUrl.trimEnd('/') + "/v1/ratings").toHttpUrlOrNull()!!.newBuilder()
                    .addQueryParameter("type", if (mediaType == MediaType.TV) "show" else "movie")
                    .addQueryParameter("tmdb", tmdbId.toString())
                    .build()
                val request = Request.Builder().url(url).header("Authorization", "Bearer ${config.token}").get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val ratings = JSONObject(response.body?.string().orEmpty()).optJSONObject("ratings") ?: JSONObject()
                    Ratings(
                        tomatoes = ratings.optString("tomatoes").takeIf { it.isNotBlank() },
                        metacritic = ratings.optString("metacritic").takeIf { it.isNotBlank() },
                    ).also { cache.put(key(mediaType, tmdbId), Entry(it, System.currentTimeMillis())) }
                }
            }.onFailure { Log.w(TAG, "ratings $tmdbId: ${it.message}") }.getOrNull()
        }
    }

    /** Metacritic's own colours: green 61+, yellow 40-60, red below. */
    fun metacriticColor(score: String): Color {
        val n = score.toIntOrNull() ?: return Color(0xFF888888)
        return when {
            n >= 61 -> Color(0xFF66CC33)
            n >= 40 -> Color(0xFFFFCC33)
            else -> Color(0xFFFF0000)
        }
    }

    /** Fresh (60%+) or rotten, as Rotten Tomatoes marks it. */
    fun isFresh(tomatoes: String): Boolean = (tomatoes.trimEnd('%').toIntOrNull() ?: 0) >= 60
}

/**
 * "  •  🍅 93%  •  [92]" — appended after the IMDb badge. Waits a moment while the focus is still
 * moving, so scrolling a row does not ask for every card it passes.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GmcExtraRatings(
    mediaType: MediaType,
    tmdbId: Int,
    fontSize: Int,
    textShadow: Shadow = Shadow.None,
    separator: String = "  •  ",
    separatorColor: Color = Color.White.copy(alpha = 0.92f),
) {
    val context = LocalContext.current
    var ratings by remember(mediaType, tmdbId) { mutableStateOf(GmcRatings.cached(mediaType, tmdbId)) }
    LaunchedEffect(mediaType, tmdbId) {
        if (ratings != null) return@LaunchedEffect
        delay(350)
        ratings = GmcRatings.load(context, mediaType, tmdbId)
    }
    val r = ratings ?: return
    if (r.isEmpty) return
    val textStyle = TextStyle(fontSize = fontSize.sp, fontWeight = FontWeight.Bold, shadow = textShadow)
    Row(verticalAlignment = Alignment.CenterVertically) {
        r.tomatoes?.let { tomatoes ->
            Text(text = separator, style = textStyle, color = separatorColor)
            Text(
                text = (if (GmcRatings.isFresh(tomatoes)) "🍅 " else "🤢 ") + tomatoes,
                style = textStyle,
                color = Color.White,
            )
        }
        r.metacritic?.let { score ->
            Text(text = separator, style = textStyle, color = separatorColor)
            Box(
                modifier = Modifier
                    .background(GmcRatings.metacriticColor(score), RoundedCornerShape(3.dp))
                    .padding(horizontal = (fontSize * 0.3f).dp, vertical = 0.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = score,
                    style = textStyle.copy(shadow = Shadow.None, fontSize = (fontSize - 1).sp),
                    color = if (GmcRatings.metacriticColor(score) == Color(0xFFFFCC33)) Color.Black else Color.White,
                )
            }
        }
    }
}
