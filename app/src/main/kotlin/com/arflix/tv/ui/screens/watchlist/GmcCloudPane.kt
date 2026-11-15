package com.arflix.tv.ui.screens.watchlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.arflix.tv.data.gmc.GmcCloud
import com.arflix.tv.ui.screens.settings.GmcDialogButton
import com.arflix.tv.ui.screens.settings.gmcToast
import kotlinx.coroutines.launch
import com.arflix.tv.ui.skin.resolveAccentColor
import com.arflix.tv.ui.theme.BackgroundElevated
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * GMC personal build: the "Premiumize" and "TorBox" tabs in the Library — folders and videos in the
 * user's debrid cloud (GmcCloud), newest first; OK on a video plays it in GMC's player.
 */

private data class CloudFolder(val id: String?, val name: String)

@Composable
internal fun GmcCloudPane(
    provider: GmcCloud.Provider,
    onPlay: (url: String, name: String, mediaId: Int, startPositionMs: Long?) -> Unit,
    onExitUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var apiKey by remember(provider) { mutableStateOf(GmcCloud.apiKey(context, provider)) }
    var showKeyDialog by remember { mutableStateOf(false) }
    val path = remember(provider) { mutableStateListOf(CloudFolder(null, provider.label)) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var opening by remember { mutableStateOf<String?>(null) }
    var listing by remember { mutableStateOf<GmcCloud.Listing?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val firstRow = remember { FocusRequester() }

    val current = path.last()
    LaunchedEffect(apiKey, current.id, reload) {
        if (apiKey.isBlank()) {
            listing = null; error = null; return@LaunchedEffect
        }
        loading = true; error = null
        GmcCloud.list(context, provider, current.id)
            .onSuccess { listing = it; listState.scrollToItem(0) }
            .onFailure { listing = null; error = it.message ?: "Σφάλμα σύνδεσης" }
        loading = false
    }
    // Back walks up the folders before it leaves the Library.
    BackHandler(enabled = path.size > 1) { path.removeAt(path.lastIndex) }

    Column(modifier.fillMaxSize().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                path.joinToString("  ›  ") { it.name },
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (apiKey.isNotBlank()) {
                OledControl("Ανανέωση", onClick = { reload++ })
            }
            OledControl(if (apiKey.isBlank()) "Σύνδεση ${provider.label}" else "Κλειδί", onClick = { showKeyDialog = true })
        }
        Spacer(Modifier.height(12.dp))
        when {
            apiKey.isBlank() -> CloudMessage(
                "Σύνδεσε τον λογαριασμό σου στο ${provider.label} για να βλέπεις εδώ τα αρχεία σου.\n" +
                    "Θα χρειαστείς το API key από το ${provider.keyHint}."
            )
            loading && listing == null -> CloudMessage("Φόρτωση…")
            error != null -> CloudMessage(error!!)
            listing?.items?.isEmpty() == true -> CloudMessage("Δεν υπάρχουν βίντεο σε αυτόν τον φάκελο.")
            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(listing?.items.orEmpty(), key = { _, it -> it.id }) { index, item ->
                    CloudRow(
                        item = item,
                        resumeMs = if (item.isFolder) 0L else GmcCloud.resumePositionMs(context, GmcCloud.mediaIdFor(provider, item.id)),
                        busy = opening == item.id,
                        modifier = (if (index == 0) Modifier.focusRequester(firstRow) else Modifier)
                            .onPreviewKeyEvent { e ->
                                if (index == 0 && e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) {
                                    onExitUp(); true
                                } else false
                            },
                        onClick = {
                            if (item.isFolder) {
                                path.add(CloudFolder(item.id, item.name))
                            } else if (opening == null) {
                                // TorBox links are requested on play (they expire); Premiumize has one already.
                                opening = item.id
                                scope.launch {
                                    GmcCloud.resolveLink(context, provider, item)
                                        .onSuccess { link ->
                                            val mediaId = GmcCloud.mediaIdFor(provider, item.id)
                                            onPlay(link, item.name, mediaId, GmcCloud.resumePositionMs(context, mediaId).takeIf { it > 0 })
                                        }
                                        .onFailure { gmcToast(context, it.message ?: "Δεν άνοιξε το αρχείο") }
                                    opening = null
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    if (showKeyDialog) {
        CloudKeyDialog(
            provider = provider,
            current = apiKey,
            onSave = { key ->
                GmcCloud.setApiKey(context, provider, key)
                apiKey = GmcCloud.apiKey(context, provider)
                path.removeRange(1, path.size)
                showKeyDialog = false
            },
            onDismiss = { showKeyDialog = false },
        )
    }
}

@Composable
private fun CloudMessage(text: String) {
    Text(text, color = Color.LightGray, fontSize = 15.sp, modifier = Modifier.padding(top = 24.dp))
}

@Composable
private fun CloudRow(item: GmcCloud.Item, resumeMs: Long, busy: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val accent = resolveAccentColor(Color.White)
    val foreground = if (focused) { if (accent.luminance() > .4f) Color.Black else Color.White } else Color.White
    val date = remember(item.createdAt) {
        if (item.createdAt > 0) SimpleDateFormat("d MMM yyyy", Locale("el")).format(Date(item.createdAt * 1000)) else ""
    }
    val details = listOfNotNull(
        "Άνοιγμα…".takeIf { busy },
        GmcCloud.formatSize(item.sizeBytes).takeIf { it.isNotBlank() },
        date.takeIf { it.isNotBlank() },
        resumeMs.takeIf { it > 0 }?.let { "συνέχεια από ${formatClock(it)}" },
    ).joinToString("  ·  ")
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) accent else BackgroundElevated.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            if (item.isFolder) Icons.Outlined.Folder else Icons.Outlined.Movie,
            contentDescription = null, tint = foreground, modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(item.name, color = foreground, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (details.isNotBlank()) {
                Text(details, color = foreground.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

private fun formatClock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun CloudKeyDialog(provider: GmcCloud.Provider, current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember(current) { mutableStateOf(current) }
    val inputFocus = remember { FocusRequester() }
    BackHandler { onDismiss() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        inputFocus.requestFocus()
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(BackgroundElevated)
                .padding(24.dp)
        ) {
            Text("Κλειδί ${provider.label} (GMC)", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Από το ${provider.keyHint}. Μένει μόνο σε αυτή τη συσκευή. Άφησέ το κενό για αποσύνδεση.",
                color = TextSecondary, fontSize = 12.sp,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(inputFocus),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = Pink,
                    unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f),
                ),
            )
            Spacer(Modifier.height(12.dp))
            com.arflix.tv.ui.components.GmcPhoneQr(
                title = "Κλειδί ${provider.label}",
                fields = listOf(com.arflix.tv.server.GmcPhoneServer.Field("${provider.label} API key", secret = true)),
                onValues = { values -> values.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { value = it } },
                qrSize = 96.dp,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GmcDialogButton("Άκυρο", TextSecondary, Modifier.weight(1f), onDismiss)
                GmcDialogButton("Αποθήκευση", Pink, Modifier.weight(1f)) { onSave(value) }
            }
        }
    }
}
