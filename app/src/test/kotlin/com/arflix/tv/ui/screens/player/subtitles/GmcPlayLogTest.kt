package com.arflix.tv.ui.screens.player.subtitles

import com.arflix.tv.data.model.Subtitle
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** GMC: how a playback is described in the gmc-server log. */
class GmcPlayLogTest {

    private val addon = Subtitle(id = "1", url = "u", lang = "ell", label = "Two.and.a.Half.Men.S02E20", provider = "OpenSubtitles")
    private val embedded = Subtitle(id = "2", url = "", lang = "eng", label = "English", isEmbedded = true)

    @Test
    fun kindFollowsWhatIsOnScreen() {
        assertThat(GmcPlayLog.kind(isAiTranslating = true, selected = embedded)).isEqualTo("ai")
        assertThat(GmcPlayLog.kind(false, null)).isEqualTo("none")
        assertThat(GmcPlayLog.kind(false, embedded)).isEqualTo("embedded")
        assertThat(GmcPlayLog.kind(false, addon)).isEqualTo("addon")
    }

    @Test
    fun detailForAnAddonCarriesProviderLabelLanguageAndShift() {
        val d = GmcPlayLog.detail("addon", addon, null, offsetMs = 1_200L, lastStep = "4· switched")
        assertThat(d).isEqualTo("OpenSubtitles · Two.and.a.Half.Men.S02E20 · ell | +1.2s | scan: 4· switched")
    }

    @Test
    fun detailForAiNamesItsSource() {
        val d = GmcPlayLog.detail("ai", embedded, embedded, offsetMs = 0L, lastStep = null)
        assertThat(d).isEqualTo("from English")
    }

    @Test
    fun detailForEmbeddedLeavesOutProvider() {
        assertThat(GmcPlayLog.detail("embedded", embedded, null, 0L, "")).isEqualTo("English · eng")
    }
}
