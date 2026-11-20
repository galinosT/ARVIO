package com.arflix.tv.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** GMC: the TheIntroDB / SkipMe.db payloads and the per-kind merge. */
class GmcSkipProvidersTest {

    @Test
    fun theIntroDbReadsMillisSecondsAndOpenEndedCredits() {
        val raw = """
            {"intro":[{"start_ms":30000,"end_ms":75000}],
             "recap":[{"start":0,"end":28.5}],
             "credits":[{"start_ms":1250000,"end_ms":null}],
             "preview":[{"start_ms":1300000,"end_ms":1320000}]}
        """.trimIndent()
        val out = GmcSkipProviders.parseTheIntroDb(raw, durationMs = 1_320_000L)
        assertThat(out.map { Triple(it.type, it.startMs, it.endMs) }).containsExactly(
            Triple("recap", 0L, 28_500L),
            Triple("intro", 30_000L, 75_000L),
            Triple("outro", 1_250_000L, 1_320_000L),
        ).inOrder()
        assertThat(out.map { it.provider }.toSet()).containsExactly(GmcSkipProviders.THEINTRODB)
    }

    @Test
    fun skipMeKeepsOnlyThisEpisodesSegments() {
        val raw = """
            [{"segments":[
              {"season":2,"episode":20,"segment":"intro","start_ms":5000,"end_ms":40000,"submissions":3},
              {"season":2,"episode":21,"segment":"intro","start_ms":6000,"end_ms":41000},
              {"season":2,"episode":20,"segment":"credits","start_ms":1200000,"end_ms":1260000},
              {"season":2,"episode":20,"segment":"preview","start_ms":1260000,"end_ms":1270000}
            ]}]
        """.trimIndent()
        val out = GmcSkipProviders.parseSkipMe(raw, season = 2, episode = 20)
        assertThat(out.map { Triple(it.type, it.startMs, it.endMs) }).containsExactly(
            Triple("intro", 5_000L, 40_000L),
            Triple("outro", 1_200_000L, 1_260_000L),
        ).inOrder()
    }

    @Test
    fun skipMeItemWithoutSegments() {
        val raw = """[{"intro":[{"start_ms":1000,"end_ms":2000}],"credits":[]}]"""
        val out = GmcSkipProviders.parseSkipMe(raw, season = 1, episode = 1)
        assertThat(out.map { it.type }).containsExactly("intro")
    }

    @Test
    fun oddPayloadsAreNoSegments() {
        assertThat(GmcSkipProviders.parseTheIntroDb("not json", null)).isEmpty()
        assertThat(GmcSkipProviders.parseTheIntroDb("""{"intro":[{"start_ms":50,"end_ms":10}]}""", null)).isEmpty()
        assertThat(GmcSkipProviders.parseSkipMe("[]", 1, 1)).isEmpty()
        assertThat(GmcSkipProviders.parseSkipMe("{}", 1, 1)).isEmpty()
    }

    @Test
    fun mergeTakesEachKindFromTheFirstProviderThatHasIt() {
        val introDb = listOf(SkipInterval(30_000, 70_000, "intro", "introdb"))
        val theIntroDb = listOf(
            SkipInterval(31_000, 71_000, "intro", GmcSkipProviders.THEINTRODB),
            SkipInterval(0, 25_000, "recap", GmcSkipProviders.THEINTRODB),
        )
        val skipMe = listOf(
            SkipInterval(0, 20_000, "recap", GmcSkipProviders.SKIPME),
            SkipInterval(1_200_000, 1_260_000, "outro", GmcSkipProviders.SKIPME),
        )
        val merged = GmcSkipProviders.merge(listOf(introDb, theIntroDb, skipMe))
        assertThat(merged.map { it.type to it.provider }).containsExactly(
            "recap" to GmcSkipProviders.THEINTRODB,
            "intro" to "introdb",
            "outro" to GmcSkipProviders.SKIPME,
        ).inOrder()
        assertThat(GmcSkipProviders.isComplete(merged)).isTrue()
        assertThat(GmcSkipProviders.isComplete(introDb)).isFalse()
    }
}
