package com.arflix.tv.ui.screens.player.subtitles

import android.content.Context

/*
 * GMC personal build: what the subtitle scan decided, counted on this device, so the extra AI
 * translation the GMC sync rules cause (and so their cost) can be read off in Settings.
 *
 * Every scan that ends in a decision is counted once. "AI possible" means AI translation could have
 * taken over (switch on, key set, a built-in English track to translate) — only then can the GMC
 * rule change anything. For each of those scans both answers are recorded: what the original ARVIO
 * rules chose, and what GMC chose.
 */
object GmcSyncStats {
    private const val FILE = "gmc_local"
    private const val SCANS_AI_POSSIBLE = "sync_scans_ai_possible"
    private const val ORIGINAL_AI = "sync_original_ai"
    private const val GMC_KEPT_AI = "sync_gmc_kept_ai"
    private const val SCANS_NO_AI = "sync_scans_no_ai"
    private const val FINE_TUNED = "sync_fine_tuned"
    private const val FINE_TUNED_TOTAL_MS = "sync_fine_tuned_total_ms"
    private const val UNMEASURED = "sync_unmeasured"

    data class Snapshot(
        val scansAiPossible: Int,
        val originalAi: Int,
        val gmcKeptAi: Int,
        val scansNoAi: Int,
        val fineTuned: Int,
        val fineTunedTotalMs: Long,
        val unmeasured: Int,
    ) {
        val gmcAi: Int get() = originalAi + gmcKeptAi
        val averageFineTuneMs: Long get() = if (fineTuned == 0) 0L else fineTunedTotalMs / fineTuned
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun bump(context: Context, key: String, by: Long = 1L) {
        val p = prefs(context)
        p.edit().putLong(key, p.getLong(key, 0L) + by).apply()
    }

    /** A scan ended: [aiPossible], what the original rules chose ([originalAi]), and GMC's ([gmcAi]). */
    fun recordScan(context: Context, aiPossible: Boolean, originalAi: Boolean, gmcAi: Boolean) {
        if (!aiPossible) {
            bump(context, SCANS_NO_AI)
            return
        }
        bump(context, SCANS_AI_POSSIBLE)
        if (originalAi) bump(context, ORIGINAL_AI)
        if (gmcAi && !originalAi) bump(context, GMC_KEPT_AI)
    }

    fun recordFineTune(context: Context, correctionMs: Long) {
        bump(context, FINE_TUNED)
        bump(context, FINE_TUNED_TOTAL_MS, kotlin.math.abs(correctionMs))
    }

    fun recordUnmeasured(context: Context) = bump(context, UNMEASURED)

    fun snapshot(context: Context): Snapshot {
        val p = prefs(context)
        fun n(key: String) = p.getLong(key, 0L)
        return Snapshot(
            scansAiPossible = n(SCANS_AI_POSSIBLE).toInt(),
            originalAi = n(ORIGINAL_AI).toInt(),
            gmcKeptAi = n(GMC_KEPT_AI).toInt(),
            scansNoAi = n(SCANS_NO_AI).toInt(),
            fineTuned = n(FINE_TUNED).toInt(),
            fineTunedTotalMs = n(FINE_TUNED_TOTAL_MS),
            unmeasured = n(UNMEASURED).toInt(),
        )
    }

    fun reset(context: Context) {
        prefs(context).edit().apply {
            listOf(SCANS_AI_POSSIBLE, ORIGINAL_AI, GMC_KEPT_AI, SCANS_NO_AI, FINE_TUNED, FINE_TUNED_TOTAL_MS, UNMEASURED)
                .forEach { remove(it) }
        }.apply()
    }
}
