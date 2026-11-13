package com.arflix.tv.ui.screens.settings

import android.content.Context
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.arflix.tv.data.repository.CloudSyncRepository
import com.arflix.tv.server.GmcPhoneServer
import com.arflix.tv.ui.components.GmcPhoneQr
import com.arflix.tv.ui.components.MobileSettingsRow
import com.arflix.tv.ui.components.SettingsRow
import com.arflix.tv.ui.screens.player.subtitles.GmcSubtitleMemory
import com.arflix.tv.ui.theme.ArflixTypography
import com.arflix.tv.ui.theme.BackgroundElevated
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextSecondary
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * GMC personal build: two more rows in Settings → AI subtitles.
 *  63  Subtitle memory server: address and key of gmc-server on the OVH VPS (shared translations
 *      and glossaries for every GMC box). Kept on this device only.
 *  64  Settings export / import: the phone downloads this box's settings as a file (the same JSON
 *      as the ARVIO cloud snapshot) or sends one back to restore it.
 */

private const val TITLE_MEMORY = "Μνήμη υποτίτλων στο server (GMC)"
private const val DESC_MEMORY = "Κοινή μνήμη μεταφράσεων και λεξικά σειρών για όλα τα κουτιά GMC."
private const val TITLE_EXPORT = "Export / import ρυθμίσεων (GMC)"
private const val DESC_EXPORT = "Λήψη των ρυθμίσεων στο κινητό ως αρχείο, ή επαναφορά από αρχείο, μέσω QR."

private var showMemoryDialog by mutableStateOf(false)
private var showExportDialog by mutableStateOf(false)
private var extraRevision by mutableStateOf(0)

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface GmcSettingsEntryPoint {
    fun cloudSyncRepository(): CloudSyncRepository
}

private fun cloudSync(context: Context): CloudSyncRepository =
    EntryPointAccessors.fromApplication(context.applicationContext, GmcSettingsEntryPoint::class.java).cloudSyncRepository()

internal fun onGmcExtraRowClick(index: Int) {
    when (index) {
        63 -> showMemoryDialog = true
        64 -> showExportDialog = true
    }
}

private fun memorySummary(context: Context): String =
    GmcSubtitleMemory.url(context).removePrefix("https://").ifBlank { "Δεν έχει οριστεί" }

@Composable
internal fun GmcExtraTvRow(index: Int, isFocused: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    when (index) {
        63 -> {
            SettingsRow(
                icon = Icons.Default.Cloud,
                title = TITLE_MEMORY,
                subtitle = DESC_MEMORY,
                value = remember(extraRevision) { memorySummary(context) },
                isFocused = isFocused,
                onClick = { onGmcExtraRowClick(63) },
                modifier = modifier,
            )
            GmcMemoryDialogHost()
        }
        64 -> {
            SettingsRow(
                icon = Icons.Default.ImportExport,
                title = TITLE_EXPORT,
                subtitle = DESC_EXPORT,
                value = null,
                isFocused = isFocused,
                onClick = { onGmcExtraRowClick(64) },
                modifier = modifier,
            )
            GmcExportDialogHost()
        }
    }
}

@Composable
internal fun GmcExtraMobileRows() {
    val context = LocalContext.current
    MobileSettingsRow(
        icon = Icons.Default.Cloud,
        title = TITLE_MEMORY,
        subtitle = DESC_MEMORY,
        value = remember(extraRevision) { memorySummary(context) },
        onClick = { onGmcExtraRowClick(63) },
    )
    MobileSettingsRow(
        icon = Icons.Default.ImportExport,
        title = TITLE_EXPORT,
        subtitle = DESC_EXPORT,
        value = null,
        showDivider = false,
        onClick = { onGmcExtraRowClick(64) },
    )
    GmcMemoryDialogHost()
    GmcExportDialogHost()
}

@Composable
private fun GmcDialogFrame(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BackHandler { onDismiss() }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(600.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(BackgroundElevated)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Text(text = title, style = ArflixTypography.sectionTitle, color = TextPrimary)
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun GmcField(label: String, value: String, onValueChange: (String) -> Unit, secret: Boolean = false) {
    Text(text = label, style = ArflixTypography.caption, color = TextSecondary)
    Spacer(modifier = Modifier.height(4.dp))
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedBorderColor = Pink,
            unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f),
        ),
    )
    Spacer(modifier = Modifier.height(12.dp))
}

@Composable
private fun GmcMemoryDialogHost() {
    if (!showMemoryDialog) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(GmcSubtitleMemory.url(context)) }
    var token by remember { mutableStateOf(GmcSubtitleMemory.token(context)) }
    var status by remember { mutableStateOf("") }
    val dismiss = { showMemoryDialog = false }
    GmcDialogFrame(TITLE_MEMORY, dismiss) {
        Text(
            text = "Η διεύθυνση του gmc-server στο VPS (π.χ. subs.projectneverland.gr) και το κλειδί του. " +
                "Αποθηκεύονται μόνο σε αυτή τη συσκευή. Άδεια πεδία = χωρίς server.",
            style = ArflixTypography.caption, color = TextSecondary,
        )
        Spacer(modifier = Modifier.height(12.dp))
        GmcField("Διεύθυνση", url, { url = it })
        GmcField("Κλειδί", token, { token = it }, secret = true)
        GmcPhoneQr(
            title = TITLE_MEMORY,
            fields = listOf(
                GmcPhoneServer.Field("Διεύθυνση", url),
                GmcPhoneServer.Field("Κλειδί", secret = true),
            ),
            onValues = { values ->
                values.getOrNull(0)?.takeIf { it.isNotEmpty() }?.let { url = it }
                values.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { token = it.trim() }
            },
            qrSize = 96.dp,
        )
        if (status.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = status, style = ArflixTypography.caption, color = TextPrimary)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GmcDialogButton("Άκυρο", TextSecondary, Modifier.weight(1f)) { dismiss() }
            GmcDialogButton("Έλεγχος", TextPrimary, Modifier.weight(1f)) {
                val config = GmcSubtitleMemory.normalizeUrl(url).takeIf { it.isNotEmpty() }
                    ?.let { GmcSubtitleMemory.Config(it, token.trim()) }
                status = "Έλεγχος…"
                scope.launch { status = config?.let { GmcSubtitleMemory.check(it) } ?: "Βάλε διεύθυνση" }
            }
            GmcDialogButton("Αποθήκευση", Pink, Modifier.weight(1f)) {
                GmcSubtitleMemory.setUrl(context, url)
                GmcSubtitleMemory.setToken(context, token)
                extraRevision++
                dismiss()
                gmcToast(
                    context,
                    if (GmcSubtitleMemory.config(context) != null) "Ο server μνήμης αποθηκεύτηκε"
                    else "Χωρίς server μνήμης (λείπει διεύθυνση ή κλειδί)"
                )
            }
        }
    }
}

@Composable
private fun GmcExportDialogHost() {
    if (!showExportDialog) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }
    val dismiss = { showExportDialog = false }
    val fileName = remember {
        "gmc-settings-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"
    }
    GmcDialogFrame(TITLE_EXPORT, dismiss) {
        Text(
            text = "Σκάναρε το QR με το κινητό: «Λήψη αρχείου» σώζει τις ρυθμίσεις αυτού του κουτιού " +
                "(addons, catalogs, προφίλ, ρυθμίσεις). Σε άλλο κουτί, άνοιξε το ίδιο παράθυρο και στείλε " +
                "το αρχείο από το κινητό για επαναφορά.\n\n" +
                "Το αρχείο περιέχει κλειδιά και κωδικούς (API keys, IPTV) — κράτα το ιδιωτικό. " +
                "Το κλειδί Claude και ο server μνήμης δεν περιλαμβάνονται.",
            style = ArflixTypography.caption, color = TextSecondary,
        )
        Spacer(modifier = Modifier.height(12.dp))
        GmcPhoneQr(
            title = TITLE_EXPORT,
            fields = emptyList(),
            onValues = {},
            download = GmcPhoneServer.Download(fileName) {
                // Served on the phone server's own thread: building the snapshot there is fine.
                runBlocking { cloudSync(context).buildCloudSnapshotJson() }
            },
            onUpload = { content -> pending = content },
            hint = "Σκάναρε για λήψη ή επαναφορά ρυθμίσεων από το κινητό.",
        )
        val incoming = pending
        if (incoming != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Ήρθε αρχείο ρυθμίσεων (${incoming.length / 1024} KB). Η επαναφορά αντικαθιστά τις " +
                    "ρυθμίσεις αυτής της συσκευής.",
                style = ArflixTypography.caption, color = TextPrimary,
            )
        }
        if (status.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = status, style = ArflixTypography.caption, color = TextPrimary)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GmcDialogButton("Κλείσιμο", TextSecondary, Modifier.weight(1f)) { dismiss() }
            if (incoming != null) {
                GmcDialogButton("Επαναφορά", Pink, Modifier.weight(1f)) {
                    status = "Επαναφορά…"
                    scope.launch {
                        val result = cloudSync(context).gmcImportSnapshot(incoming)
                        status = result.fold(
                            onSuccess = { "Οι ρυθμίσεις επανήλθαν ✓ — κάνε επανεκκίνηση της εφαρμογής." },
                            onFailure = { "Η επαναφορά απέτυχε: ${it.message}" },
                        )
                        if (result.isSuccess) pending = null
                    }
                }
            }
        }
    }
}
