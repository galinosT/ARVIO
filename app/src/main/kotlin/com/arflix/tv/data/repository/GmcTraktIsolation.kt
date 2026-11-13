package com.arflix.tv.data.repository

import android.content.Context

/*
 * GMC personal build: this app's Trakt connection stays on this device.
 *
 * GMC is built with its own Trakt application (client id), the official ARVIO with another. A Trakt
 * token works only with the application that issued it, but the cloud sync carries the tokens to
 * every device of the account — so connecting Trakt in one app broke it in the other, and
 * disconnecting in one disconnected the other.
 *
 * So GMC neither imports the Trakt tokens it finds in the cloud payload nor uploads its own: it
 * keeps the cloud's tokens as it found them (here) and sends those back untouched. The official app
 * keeps its connection, GMC keeps its own, and both write to the same Trakt account.
 */
internal object GmcTraktIsolation {
    private const val FILE = "gmc_local"
    private const val REMOTE_TOKENS = "cloud_trakt_tokens_json"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The cloud payload's `traktTokens` object, kept as received. */
    fun rememberRemoteTokens(context: Context, json: String) {
        prefs(context).edit().putString(REMOTE_TOKENS, json).apply()
    }

    /** What to upload as `traktTokens`: the cloud's own, never this device's. */
    fun tokensToUpload(context: Context): String =
        prefs(context).getString(REMOTE_TOKENS, null)?.takeIf { it.isNotBlank() } ?: "{}"
}
