package com.arflix.tv.data.gmc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * GMC: the device-local half of a settings file, for mirroring one box onto another.
 *
 * The ARVIO snapshot (CloudSyncRepository) already carries addons, catalogs, profiles and the
 * synced settings. What it leaves on the device on purpose — the Claude key, the subtitle memory
 * server, the Premiumize/TorBox keys, the Library switches, glossaries, the audio sync settings —
 * rides along here when the user asks for a full mirror.
 *
 * Never copied: sign-ins whose refresh token rotates (two boxes sharing one would sign each other
 * out — the Simkl lists grant, the isolated Trakt tokens), and this box's own counters.
 */
object GmcDeviceMirror {
    const val JSON_KEY = "gmcDevice"

    private val FILES = listOf("gmc_local", "gmc_cloud", "gmc_glossaries", "arvio_audio_sync_settings")
    private val SKIPPED_KEYS = setOf("cloud_trakt_tokens_json")
    private const val SKIPPED_PREFIX = "sync_" // GmcSyncStats: this box's own counters

    private fun copied(key: String) = key !in SKIPPED_KEYS && !key.startsWith(SKIPPED_PREFIX)

    fun export(context: Context): JSONObject {
        val root = JSONObject()
        for (file in FILES) {
            val values = JSONObject()
            context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                if (!copied(key) || value == null) return@forEach
                val entry = when (value) {
                    is String -> JSONObject().put("t", "s").put("v", value)
                    is Boolean -> JSONObject().put("t", "b").put("v", value)
                    is Int -> JSONObject().put("t", "i").put("v", value)
                    is Long -> JSONObject().put("t", "l").put("v", value)
                    is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                    is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.filterIsInstance<String>()))
                    else -> null
                }
                if (entry != null) values.put(key, entry)
            }
            root.put(file, values)
        }
        return root
    }

    // ── Account sync (GMC Cloud) ──────────────────────────────────────────────────────────────
    // The same values ride the account snapshot as "gmcDeviceSync": a flat map "<file>/<key>" ->
    // {t, v}, one merge field per value, so every box of the account gets them. The Claude switch
    // and key have their own fields (gmcClaude*), so they are left out here.

    const val CLOUD_KEY = "gmcDeviceSync"
    private val CLOUD_SKIPPED = setOf("gmc_local/claude_enabled", "gmc_local/claude_api_key")

    private fun entryOf(value: Any?): JSONObject? = when (value) {
        is String -> JSONObject().put("t", "s").put("v", value)
        is Boolean -> JSONObject().put("t", "b").put("v", value)
        is Int -> JSONObject().put("t", "i").put("v", value)
        is Long -> JSONObject().put("t", "l").put("v", value)
        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
        is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.filterIsInstance<String>().sorted()))
        else -> null
    }

    fun cloudEntries(context: Context): JSONObject {
        val out = JSONObject()
        for (file in FILES) {
            context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE).all.toSortedMap().forEach { (key, value) ->
                val id = "$file/$key"
                if (!copied(key) || id in CLOUD_SKIPPED) return@forEach
                entryOf(value)?.let { out.put(id, it) }
            }
        }
        return out
    }

    /** Writes the account's values onto this box (values the account does not carry stay). */
    fun applyCloudEntries(context: Context, entries: JSONObject): Int {
        val editors = HashMap<String, android.content.SharedPreferences.Editor>()
        var written = 0
        entries.keys().forEach { id ->
            val file = id.substringBefore('/')
            val key = id.substringAfter('/', "")
            if (file !in FILES || key.isEmpty() || !copied(key) || id in CLOUD_SKIPPED) return@forEach
            val entry = entries.optJSONObject(id) ?: return@forEach
            val editor = editors.getOrPut(file) {
                context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
            }
            when (entry.optString("t")) {
                "s" -> editor.putString(key, entry.optString("v"))
                "b" -> editor.putBoolean(key, entry.optBoolean("v"))
                "i" -> editor.putInt(key, entry.optInt("v"))
                "l" -> editor.putLong(key, entry.optLong("v"))
                "f" -> editor.putFloat(key, entry.optDouble("v").toFloat())
                "ss" -> {
                    val array = entry.optJSONArray("v") ?: JSONArray()
                    editor.putStringSet(key, (0 until array.length()).map { array.optString(it) }.toSet())
                }
                else -> return@forEach
            }
            written++
        }
        editors.values.forEach { it.commit() }
        if (written > 0) {
            // Settings read once into memory pick up the new values.
            GmcNoAnime.reload(context)
            com.arflix.tv.ui.screens.watchlist.GmcLibraryPrefs.reload(context)
            com.arflix.tv.ui.screens.home.GmcHomeMotion.reload(context)
        }
        return written
    }

    /** Replaces this box's device-local settings with the file's (its own counters stay). Returns the values written. */
    fun import(context: Context, root: JSONObject): Int {
        var written = 0
        for (file in FILES) {
            val values = root.optJSONObject(file) ?: continue
            val prefs = context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            prefs.all.keys.filter(::copied).forEach { editor.remove(it) }
            values.keys().forEach { key ->
                if (!copied(key)) return@forEach
                val entry = values.optJSONObject(key) ?: return@forEach
                when (entry.optString("t")) {
                    "s" -> editor.putString(key, entry.optString("v"))
                    "b" -> editor.putBoolean(key, entry.optBoolean("v"))
                    "i" -> editor.putInt(key, entry.optInt("v"))
                    "l" -> editor.putLong(key, entry.optLong("v"))
                    "f" -> editor.putFloat(key, entry.optDouble("v").toFloat())
                    "ss" -> {
                        val array = entry.optJSONArray("v") ?: JSONArray()
                        editor.putStringSet(key, (0 until array.length()).map { array.optString(it) }.toSet())
                    }
                    else -> return@forEach
                }
                written++
            }
            editor.commit()
        }
        return written
    }
}
