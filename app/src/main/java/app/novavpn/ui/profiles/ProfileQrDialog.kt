package app.novavpn.ui.profiles

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.novavpn.ui.glass.GlassCapsuleButton
import app.novavpn.ui.glass.GlassCard
import app.novavpn.ui.glass.GlassIcons
import app.novavpn.ui.glass.GlassModalDialog
import app.novavpn.ui.glass.LocalGlassLook
import app.novavpn.ui.theme.JetBrainsMonoFamily
import com.example.R

@Composable
fun ProfileQrDialog(
    profileName: String,
    shareUri: String,
    qrBitmap: Bitmap?,
    onCopyUri: () -> Unit,
    onDismiss: () -> Unit
) {
    val look = LocalGlassLook.current
    val copyFocus = remember { FocusRequester() }
    GlassModalDialog(
        onDismissRequest = onDismiss,
        title = profileName,
        icon = GlassIcons.QrFill,
        initialFocusRequester = copyFocus
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (qrBitmap != null) {
                Box(
                    modifier = Modifier
                        .size(228.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.White)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = profileName,
                        modifier = Modifier.size(204.dp)
                    )
                }
            }
            GlassCard(
                cornerRadius = 14.dp,
                tintOverride = look.fill2,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = shareUri.take(78) + if (shareUri.length > 78) "…" else "",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = look.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassCapsuleButton(
                    text = stringResource(R.string.common_close),
                    onClick = onDismiss,
                    prominent = false,
                    modifier = Modifier.weight(1f)
                )
                GlassCapsuleButton(
                    text = stringResource(R.string.profiles_copy_link),
                    icon = GlassIcons.Copy,
                    onClick = onCopyUri,
                    prominent = true,
                    focusRequester = copyFocus,
                    modifier = Modifier.weight(1.3f)
                )
            }
        }
    }
}
