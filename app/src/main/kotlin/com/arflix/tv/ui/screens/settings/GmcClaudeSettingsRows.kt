package com.arflix.tv.ui.screens.settings

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arflix.tv.ui.components.MobileSettingsRow
import com.arflix.tv.ui.components.SettingsRow
import com.arflix.tv.ui.components.SettingsToggleRow
import com.arflix.tv.ui.screens.player.subtitles.GmcClaudePrefs
import com.arflix.tv.ui.theme.ArflixTypography
import com.arflix.tv.ui.theme.BackgroundElevated
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextSecondary

/*
 * GMC personal build: "Claude Haiku" switch and key, in the AI subtitles section. Both live only
 * on this device (GmcClaudePrefs), never in the ARVIO cloud sync.
 */

/** TV settings row indices, after the existing ones (0–54). */
internal val GMC_CLAUDE_ROWS = listOf(60, 61)

private const val TITLE_SWITCH = "Claude Haiku (GMC)"
private const val DESC_SWITCH =
    "Μετάφραση AI υποτίτλων με Claude Haiku αντί για το παραπάνω μοντέλο. Μόνο σε αυτή τη συσκευή."
private const val TITLE_KEY = "Κλειδί Claude (GMC)"
private const val DESC_KEY = "Anthropic API key (sk-ant-…). Δεν συγχρονίζεται με τον λογαριασμό ARVIO."

// Bumped on every write so the rows re-read the device-local preferences.
private var revision by mutableStateOf(0)
private var showKeyDialog by mutableStateOf(false)

private fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

/** Handles OK/click on one of [GMC_CLAUDE_ROWS]. */
internal fun onGmcClaudeRowClick(context: Context, index: Int) {
    when (index) {
        60 -> {
            val enable = !GmcClaudePrefs.isEnabled(context)
            if (enable && !GmcClaudePrefs.looksLikeKey(GmcClaudePrefs.apiKey(context))) {
                toast(context, "Βάλε πρώτα κλειδί Claude")
                showKeyDialog = true
                return
            }
            GmcClaudePrefs.setEnabled(context, enable)
            revision++
        }
        61 -> showKeyDialog = true
    }
}

private fun maskKey(key: String): String =
    if (key.isBlank()) "Δεν έχει οριστεί" else "Claude · …" + key.takeLast(4)

@Composable
private fun GmcClaudeKeyDialogHost() {
    if (!showKeyDialog) return
    val context = LocalContext.current
    GmcClaudeKeyDialog(
        currentKey = remember(revision) { GmcClaudePrefs.apiKey(context) },
        onSave = { key ->
            val trimmed = key.trim()
            if (trimmed.isNotEmpty() && !GmcClaudePrefs.looksLikeKey(trimmed)) {
                toast(context, "Το κλειδί Claude ξεκινά με sk-ant-")
                return@GmcClaudeKeyDialog
            }
            GmcClaudePrefs.setApiKey(context, trimmed)
            if (trimmed.isEmpty()) GmcClaudePrefs.setEnabled(context, false)
            revision++
            showKeyDialog = false
            toast(context, if (trimmed.isEmpty()) "Το κλειδί Claude διαγράφηκε" else "Το κλειδί Claude αποθηκεύτηκε")
        },
        onDismiss = { showKeyDialog = false },
    )
}

/** One TV row for [index] in [GMC_CLAUDE_ROWS]. */
@Composable
internal fun GmcClaudeTvRow(index: Int, isFocused: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val enabled = remember(revision) { GmcClaudePrefs.isEnabled(context) }
    val key = remember(revision) { GmcClaudePrefs.apiKey(context) }
    when (index) {
        60 -> SettingsToggleRow(
            title = TITLE_SWITCH,
            subtitle = DESC_SWITCH,
            isEnabled = enabled,
            isFocused = isFocused,
            onToggle = { onGmcClaudeRowClick(context, 60) },
            modifier = modifier,
        )
        61 -> {
            SettingsRow(
                icon = Icons.Default.VpnKey,
                title = TITLE_KEY,
                subtitle = DESC_KEY,
                value = maskKey(key),
                isFocused = isFocused,
                onClick = { onGmcClaudeRowClick(context, 61) },
                modifier = if (enabled || key.isBlank()) modifier else modifier.alpha(0.6f),
            )
            GmcClaudeKeyDialogHost()
        }
    }
}

/** The same settings as touch rows, for the mobile settings page. */
@Composable
internal fun GmcClaudeMobileRows() {
    val context = LocalContext.current
    val enabled = remember(revision) { GmcClaudePrefs.isEnabled(context) }
    val key = remember(revision) { GmcClaudePrefs.apiKey(context) }
    MobileSettingsRow(
        title = TITLE_SWITCH,
        subtitle = DESC_SWITCH,
        value = if (enabled) "On" else "Off",
        toggleChecked = enabled,
        onClick = { onGmcClaudeRowClick(context, 60) },
    )
    MobileSettingsRow(
        icon = Icons.Default.VpnKey,
        title = TITLE_KEY,
        subtitle = DESC_KEY,
        value = maskKey(key),
        showDivider = false,
        onClick = { onGmcClaudeRowClick(context, 61) },
    )
    GmcClaudeKeyDialogHost()
}

@Composable
private fun GmcClaudeKeyDialog(currentKey: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember(currentKey) { mutableStateOf(currentKey) }
    val inputFocus = remember { FocusRequester() }
    BackHandler { onDismiss() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        inputFocus.requestFocus()
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(BackgroundElevated)
                .padding(24.dp)
        ) {
            Text(text = TITLE_KEY, style = ArflixTypography.sectionTitle, color = TextPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Από το console.anthropic.com → API keys. Άφησέ το κενό για διαγραφή.",
                style = ArflixTypography.caption,
                color = TextSecondary,
            )
            Spacer(modifier = Modifier.height(16.dp))
            androidx.compose.material3.OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = { androidx.compose.material3.Text("sk-ant-...", color = TextSecondary.copy(alpha = 0.4f)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(inputFocus),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = Pink,
                    unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f),
                ),
            )
            Spacer(modifier = Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DialogButton("Άκυρο", TextSecondary, Modifier.weight(1f), onDismiss)
                DialogButton("Αποθήκευση", Pink, Modifier.weight(1f)) { onSave(value) }
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = color.copy(alpha = 0.12f),
            focusedContainerColor = color.copy(alpha = 0.3f),
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = color,
        )
    }
}
