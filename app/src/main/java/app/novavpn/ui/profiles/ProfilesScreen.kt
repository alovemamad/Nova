package app.novavpn.ui.profiles

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.novavpn.domain.model.VpnProfile
import app.novavpn.ui.components.ProfileQrDialog
import app.novavpn.ui.glass.GlassBadge
import app.novavpn.ui.glass.GlassCard
import app.novavpn.ui.glass.GlassCircleButton
import app.novavpn.ui.glass.LocalGlassLook
import app.novavpn.ui.theme.tr

@Composable
fun ProfilesScreen(
    profiles: List<VpnProfile>,
    activeProfileId: String,
    onSelectProfile: (String) -> Unit,
    onSaveProfile: (VpnProfile, Boolean) -> Unit,
    onDuplicateProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onImportText: (String) -> Unit,
    onImportQrImage: (Uri) -> Unit,
    onGenerateQr: (VpnProfile) -> Pair<String, android.graphics.Bitmap?>,
    onNotify: (String) -> Unit
) {
    val context = LocalContext.current
    val look = LocalGlassLook.current

    var editingProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showImportTextDialog by remember { mutableStateOf(false) }
    var importInputText by remember { mutableStateOf("") }
    var qrPreview by remember { mutableStateOf<Triple<String, String, android.graphics.Bitmap?>?>(null) }

    // Zero-permission Photo Picker for decoding profile QR from gallery
    val qrImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onImportQrImage(uri)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 112.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = tr("Profiles", "پروفایل‌ها"),
                        style = MaterialTheme.typography.headlineMedium,
                        color = look.textPrimary
                    )
                    Text(
                        text = tr("Import, share QR, or customize routing", "وارد کردن، اشتراک QR یا شخصی‌سازی مسیر"),
                        style = MaterialTheme.typography.bodySmall,
                        color = look.textSecondary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassCircleButton(
                        icon = Icons.Rounded.PhotoLibrary,
                        contentDescription = tr("Import QR from Gallery", "خواندن QR از گالری"),
                        onClick = {
                            qrImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier.testTag("import_qr_gallery_button")
                    )
                    GlassCircleButton(
                        icon = Icons.Rounded.ContentPaste,
                        contentDescription = tr("Import Link / JSON", "وارد کردن لینک یا جیسون"),
                        onClick = { showImportTextDialog = true },
                        modifier = Modifier.testTag("import_text_button")
                    )
                    GlassCircleButton(
                        icon = Icons.Rounded.Add,
                        contentDescription = tr("Create Profile", "ساخت پروفایل"),
                        onClick = { showCreateDialog = true },
                        tint = look.accent,
                        modifier = Modifier.testTag("add_profile_button")
                    )
                }
            }
        }

        items(profiles, key = { it.id }) { profile ->
            val isActive = profile.id == activeProfileId
            GlassCard(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .testTag("profile_card_${profile.id}"),
                tintOverride = if (isActive) look.accent.copy(alpha = 0.14f) else null
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = look.textPrimary
                                )
                                if (isActive) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = tr("Active", "فعال"),
                                        tint = look.accent
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${profile.routeChain.name} · ${profile.aetherProtocol.name} · MTU ${profile.mtu}",
                                style = MaterialTheme.typography.bodySmall,
                                color = look.textSecondary
                            )
                        }
                        if (!isActive) {
                            OutlinedButton(onClick = { onSelectProfile(profile.id) }) {
                                Text(tr("Select", "انتخاب"))
                            }
                        } else {
                            GlassBadge(text = tr("Active", "فعال"), color = look.accent)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(onClick = {
                            val (uri, bmp) = onGenerateQr(profile)
                            qrPreview = Triple(profile.name, uri, bmp)
                        }) {
                            Icon(Icons.Rounded.QrCode2, contentDescription = tr("Share QR", "کد QR"), tint = look.textSecondary)
                        }
                        IconButton(onClick = { onDuplicateProfile(profile.id) }) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = tr("Duplicate", "کپی"), tint = look.textSecondary)
                        }
                        IconButton(onClick = { editingProfile = profile }) {
                            Icon(Icons.Rounded.Edit, contentDescription = tr("Edit", "ویرایش"), tint = look.accent)
                        }
                        if (profiles.size > 1) {
                            IconButton(onClick = { onDeleteProfile(profile.id) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = tr("Delete", "حذف"), tint = look.danger)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog || editingProfile != null) {
        ProfileEditorDialog(
            initial = editingProfile,
            onSave = { saved ->
                onSaveProfile(saved, editingProfile == null || saved.id == activeProfileId)
                editingProfile = null
                showCreateDialog = false
            },
            onDismiss = {
                editingProfile = null
                showCreateDialog = false
            }
        )
    }

    if (showImportTextDialog) {
        AlertDialog(
            onDismissRequest = { showImportTextDialog = false },
            title = { Text(tr("Import Profile URI or JSON", "وارد کردن لینک یا JSON پروفایل")) },
            text = {
                Column {
                    OutlinedTextField(
                        value = importInputText,
                        onValueChange = { importInputText = it },
                        label = { Text("novavpn://profile?data=… or JSON") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        if (clip.isNotBlank()) importInputText = clip
                    }) {
                        Text(tr("Paste from Clipboard", "چسباندن از کلیپ‌بورد"))
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onImportText(importInputText)
                    importInputText = ""
                    showImportTextDialog = false
                }) {
                    Text(tr("Import", "وارد کردن"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportTextDialog = false }) {
                    Text(tr("Cancel", "لغو"))
                }
            }
        )
    }

    qrPreview?.let { (name, uri, bmp) ->
        ProfileQrDialog(
            profileName = name,
            shareUri = uri,
            qrBitmap = bmp,
            onCopyUri = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("Nova VPN Profile", uri))
                onNotify("Copied share URI to clipboard")
                qrPreview = null
            },
            onDismiss = { qrPreview = null }
        )
    }
}
