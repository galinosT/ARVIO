package com.arflix.tv.ui.components

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.arflix.tv.server.GmcPhoneServer
import com.arflix.tv.ui.theme.ArflixTypography
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextSecondary
import com.arflix.tv.util.DeviceIpAddress
import com.arflix.tv.util.LocalDeviceType

/*
 * GMC personal build: "write it on your phone" for a TV dialog. Drop it into any dialog with the
 * dialog's fields: while the dialog is open, a QR code opens the same fields on the phone, and what
 * the phone sends arrives in [onValues] (main thread) to fill them. Nothing is saved until the
 * user confirms on the TV. Not shown on phones and tablets, which have a keyboard already.
 */
@Composable
fun GmcPhoneQr(
    title: String,
    fields: List<GmcPhoneServer.Field>,
    onValues: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    download: GmcPhoneServer.Download? = null,
    /** A file the phone sent back (its text). Main thread. */
    onUpload: ((String) -> Unit)? = null,
    hint: String = "Σκάναρε με το κινητό για να γράψεις ή να κάνεις επικόλληση εκεί.",
    qrSize: Dp = 112.dp,
) {
    if (LocalDeviceType.current.isTouchDevice()) return
    val context = LocalContext.current
    val latestOnValues by rememberUpdatedState(onValues)
    val latestOnUpload by rememberUpdatedState(onUpload)
    var url by remember { mutableStateOf<String?>(null) }

    DisposableEffect(title) {
        val main = Handler(Looper.getMainLooper())
        val server = GmcPhoneServer.start(
            title = title,
            fields = fields,
            onValues = { values -> main.post { latestOnValues(values) } },
            download = download,
            onUpload = if (onUpload == null) null else { content ->
                main.post { latestOnUpload?.invoke(content) }
                "Παραλήφθηκε ✓ — δες την τηλεόραση."
            },
        )
        val ip = DeviceIpAddress.get(context)
        url = if (server != null && ip != null) "http://$ip:${server.listeningPort}/?t=${server.token}" else null
        onDispose {
            runCatching { server?.stop() }
            url = null
        }
    }

    val address = url ?: return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(qrSize)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .padding(6.dp)
        ) {
            QrCodeImage(data = address, sizePx = 360, modifier = Modifier.size(qrSize))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "📱 Από το κινητό", style = ArflixTypography.cardTitle, color = TextPrimary)
            Text(text = hint, style = ArflixTypography.caption, color = TextSecondary)
            Text(text = address.substringBefore("/?"), style = ArflixTypography.caption, color = TextSecondary.copy(alpha = 0.6f))
        }
    }
}
