package com.arflix.tv.ui.screens.player.subtitles

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/*
 * GMC personal build: "Debug συγχρονισμού" — a per-box switch (Settings → AI subtitles), off by
 * default. While on, the box sends to the gmc-server (POST /v1/debug):
 *
 * - the whole subtitle scan of each title (the player's own SubMatch log lines, read back from
 *   this app's logcat), once five minutes have been watched;
 * - every manual correction — switching to AI, picking another subtitle, changing the offset —
 *   with what was on screen at that moment and the scan that put it there.
 *
 * Read with GET /v1/debug?format=text on the server. Nothing is sent while the switch is off.
 */
object GmcSyncDebug {
    private const val TAG = "GmcSyncDebug"
    private const val FILE = "gmc_local"
    private const val KEY = "sync_debug"
    private const val MAX_TRACE_CHARS = 16_000
    private val LOG_TAGS = listOf("SubMatch", "GmcSkip", "SubtitleTranslation", "GmcEmbeddedSubs", "AudioSync")

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    fun isOn(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setOn(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    /**
     * This app's own log lines for [LOG_TAGS] since [sinceEpochMs] (an app may read its own
     * logcat). The most recent part when it is long.
     */
    suspend fun traceSince(sinceEpochMs: Long): String = withContext(Dispatchers.IO) {
        runCatching {
            val args = arrayListOf("logcat", "-d", "-v", "epoch", "-s") + LOG_TAGS.map { "$it:V" }
            val process = ProcessBuilder(args).redirectErrorStream(true).start()
            val pid = android.os.Process.myPid().toString()
            val lines = process.inputStream.bufferedReader().useLines { seq ->
                seq.filter { line -> isOurs(line, pid, sinceEpochMs) }.map(::clean).toList()
            }
            // `logcat -d` exits once it has dumped the buffer, which the read above waited for.
            process.destroy()
            val text = lines.joinToString("\n")
            if (text.length <= MAX_TRACE_CHARS) text else "…\n" + text.takeLast(MAX_TRACE_CHARS)
        }.onFailure { Log.w(TAG, "logcat: ${it.message}") }.getOrDefault("")
    }

    /** "  1763659200.123  4321  4400 I SubMatch: …" — this process, at or after [since]. */
    internal fun isOurs(line: String, pid: String, since: Long): Boolean {
        val parts = line.trim().split(Regex("\\s+"), limit = 4)
        if (parts.size < 4 || parts[1] != pid) return false
        val at = parts[0].toDoubleOrNull() ?: return false
        return (at * 1000).toLong() >= since
    }

    /** Keeps the time of day, the level and the message. */
    internal fun clean(line: String): String {
        val parts = line.trim().split(Regex("\\s+"), limit = 4)
        val at = parts.getOrNull(0)?.toDoubleOrNull() ?: return line
        val clock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date((at * 1000).toLong()))
        return "$clock ${parts.getOrNull(3).orEmpty()}"
    }

    suspend fun send(context: Context, event: JSONObject) {
        if (!isOn(context)) return
        val config = GmcSubtitleMemory.config(context) ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(config.baseUrl.trimEnd('/') + "/v1/debug")
                    .header("Authorization", "Bearer ${config.token}")
                    .post(event.toString().toRequestBody(json))
                    .build()
                client.newCall(request).execute().use { if (!it.isSuccessful) error("HTTP ${it.code}") }
            }.onFailure { Log.w(TAG, "send failed: ${it.message}") }
        }
    }

    /**
     * After a crash, an ANR or a kill by the system: what ended the previous run, sent once on
     * the next start while the switch is on. Java crashes carry their stack (kept by the app's
     * crash handler); ANRs and native crashes carry the system's own trace (Android 11+).
     */
    suspend fun sendLastExits(context: Context) {
        if (!isOn(context)) return
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val device = GmcPlayLog.deviceLabel(context)
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
        prefs.getString("gmc_crash_stack", null)?.let { stack ->
            send(context, JSONObject().apply {
                put("device", device); put("title", "GMC $version"); put("event", "crash")
                put("detail", stack.lineSequence().drop(1).firstOrNull().orEmpty().take(300))
                put("trace", stack)
            })
            prefs.edit().remove("gmc_crash_stack").apply()
        }
        if (android.os.Build.VERSION.SDK_INT < 30) return
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val lastSeen = prefs.getLong("gmc_exit_seen", 0L)
        val exits = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 5) }.getOrNull().orEmpty()
            .filter { it.timestamp > maxOf(lastSeen, System.currentTimeMillis() - 12 * 3_600_000L) }
        if (exits.isEmpty()) return
        prefs.edit().putLong("gmc_exit_seen", exits.maxOf { it.timestamp }).apply()
        val interesting = setOf(
            android.app.ApplicationExitInfo.REASON_ANR,
            android.app.ApplicationExitInfo.REASON_CRASH,
            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,
            android.app.ApplicationExitInfo.REASON_LOW_MEMORY,
            android.app.ApplicationExitInfo.REASON_SIGNALED,
        )
        exits.filter { it.reason in interesting }.forEach { exit ->
            val trace = runCatching {
                exit.traceInputStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty()
            val clock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(exit.timestamp))
            send(context, JSONObject().apply {
                put("device", device); put("title", "GMC $version"); put("event", "exit-" + exitName(exit.reason))
                put("detail", "at $clock · ${exit.description.orEmpty()} · pss=${exit.pss / 1024}MB rss=${exit.rss / 1024}MB")
                put("trace", if (trace.length <= MAX_TRACE_CHARS) trace else trace.take(MAX_TRACE_CHARS))
            })
        }
    }

    private fun exitName(reason: Int): String = when (reason) {
        android.app.ApplicationExitInfo.REASON_ANR -> "anr"
        android.app.ApplicationExitInfo.REASON_CRASH -> "crash"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "lowmem"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        else -> reason.toString()
    }
}
