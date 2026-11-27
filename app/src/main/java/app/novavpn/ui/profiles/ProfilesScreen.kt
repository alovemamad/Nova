package app.novavpn.ui.profiles

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.novavpn.domain.model.AetherProtocol
import app.novavpn.domain.model.RouteChain
import app.novavpn.domain.model.VpnProfile
import app.novavpn.ui.glass.GlassBadge
import app.novavpn.ui.glass.GlassCapsuleButton
import app.novavpn.ui.glass.GlassCard
import app.novavpn.ui.glass.GlassCircleButton
import app.novavpn.ui.glass.GlassDivider
import app.novavpn.ui.glass.GlassIcons
import app.novavpn.ui.glass.GlassModalDialog
import app.novavpn.ui.glass.LocalGlassChrome
import app.novavpn.ui.glass.LocalGlassLook
import app.novavpn.ui.glass.LocalIsTv
import app.novavpn.ui.glass.ReportScrollShrink
import app.novavpn.ui.glass.glassHighlight
import app.novavpn.ui.glass.novaOutlinedTextFieldColors
import app.novavpn.ui.glass.tvFocusVisual
import app.novavpn.ui.theme.countryCodeToFlag
import app.novavpn.ui.theme.fmtDigits
import com.example.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilesScreen(
    profiles: List<VpnProfile>,
    activeProfileId: String,
    onSelectProfile: (String) -> Unit,
    onSaveProfile: (VpnProfile, Boolean) -> Unit,
    onDuplicateProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onImportText: (String) -> Unit,
    onImportFile: (Uri) -> Unit = {},
    onImportQrImage: (Uri) -> Unit,
    onGenerateQr: suspend (VpnProfile) -> Pair<String, android.graphics.Bitmap?>,
    onNotify: (String) -> Unit,
    onTabBarShrinkChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val look = LocalGlassLook.current
    val isTv = LocalIsTv.current
    val contentMaxWidth = if (isTv) 960.dp else 600.dp
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    ReportScrollShrink(listState = listState, onShrinkChanged = onTabBarShrinkChanged)

    val addFocus = remember { FocusRequester() }
    LaunchedEffect(isTv) {
        if (isTv) {
            runCatching { addFocus.requestFocus() }
        }
    }

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

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportFile(uri)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .then(if (isTv) Modifier else Modifier.statusBarsPadding()),
        contentPadding = if (isTv) {
            PaddingValues(start = 24.dp, end = 48.dp, top = 36.dp, bottom = 48.dp)
        } else {
            PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 112.dp)
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .widthIn(max = contentMaxWidth)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.profiles_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = look.textPrimary
                    )
                    Text(
                        text = stringResource(R.string.profiles_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = look.textSecondary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isTv) {
                        GlassCircleButton(
                            icon = GlassIcons.Qr,
                            contentDescription = stringResource(R.string.profiles_import),
                            onClick = {
                                qrImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            backdrop = LocalGlassChrome.current?.wall,
                            modifier = Modifier.testTag("import_qr_gallery_button")
                        )
                    }
                    GlassCircleButton(
                        icon = GlassIcons.Clipboard,
                        contentDescription = stringResource(R.string.profiles_import_title),
                        onClick = { showImportTextDialog = true },
                        backdrop = LocalGlassChrome.current?.wall,
                        modifier = Modifier.testTag("import_text_button")
                    )
                    GlassCircleButton(
                        icon = GlassIcons.Plus,
                        contentDescription = stringResource(R.string.profiles_new),
                        onClick = { showCreateDialog = true },
                        tint = look.accent,
                        backdrop = LocalGlassChrome.current?.wall,
                        focusRequester = addFocus,
                        modifier = Modifier.testTag("add_profile_button")
                    )
                }
            }
        }

        items(profiles, key = { it.id }) { profile ->
            val isActive = profile.id == activeProfileId
            val routeTint = when (profile.routeChain) {
                RouteChain.SMART_AUTO -> look.accent
                RouteChain.AETHER -> look.secondaryAccent
                RouteChain.AETHER_PSIPHON, RouteChain.PSIPHON_ONLY -> Color(0xFF0A84FF)
                RouteChain.AETHER_TOR, RouteChain.TOR_ONLY -> Color(0xFFBF5AF2)
            }
            val iconShape = RoundedCornerShape(13.dp)
            val iconBrush = remember(routeTint) {
                Brush.verticalGradient(
                    0f to lerp(routeTint, Color.White, 0.24f),
                    0.65f to routeTint,
                    1f to lerp(routeTint, Color.Black, 0.14f)
                )
            }

            GlassCard(
                modifier = Modifier
                    .animateItem()
                    .widthIn(max = contentMaxWidth)
                    .fillMaxWidth()
                    .testTag("profile_card_${profile.id}"),
                elevated = isActive,
                tintOverride = if (isActive) look.accent.copy(alpha = 0.16f) else null
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(iconShape)
                                .background(iconBrush)
                                .glassHighlight(iconShape, Color.White.copy(alpha = 0.45f), 0.6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (profile.routeChain == RouteChain.SMART_AUTO) GlassIcons.Shield else GlassIcons.Routing,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = look.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (isActive) {
                                    Icon(
                                        imageVector = GlassIcons.CheckCircle,
                                        contentDescription = stringResource(R.string.profiles_active),
                                        tint = look.accent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ProfileMetaChip(
                                    text = profile.routeChain.name.replace("_", " "),
                                    tint = routeTint
                                )
                                ProfileMetaChip(
                                    text = profile.aetherProtocol.name.replace("_", "-"),
                                    tint = look.textSecondary
                                )
                                val usesMasque = profile.aetherProtocol == AetherProtocol.MASQUE ||
                                    profile.aetherProtocol == AetherProtocol.WARP_IN_MASQUE ||
                                    profile.aetherProtocol == AetherProtocol.MASQUE_IN_MASQUE
                                if (usesMasque) {
                                    ProfileMetaChip(
                                        text = if (profile.masqueOverH2) "HTTP/2" else "HTTP/3",
                                        tint = look.accent
                                    )
                                }
                                if (profile.isCountryIndependent) {
                                    ProfileMetaChip(
                                        text = stringResource(R.string.profile_independent_badge),
                                        tint = look.success
                                    )
                                } else if (profile.routeChain == RouteChain.AETHER_PSIPHON || profile.routeChain == RouteChain.PSIPHON_ONLY) {
                                    ProfileMetaChip(
                                        text = "${countryCodeToFlag(profile.psiphonRegion)} ${profile.psiphonRegion}",
                                        tint = look.secondaryAccent
                                    )
                                }
                                ProfileMetaChip(
                                    text = fmtDigits("MTU ${profile.mtu}"),
                                    tint = look.textMuted
                                )
                            }
                        }
                    }

                    GlassDivider()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isActive) {
                            GlassCapsuleButton(
                                text = stringResource(R.string.profiles_select),
                                prominent = true,
                                onClick = { onSelectProfile(profile.id) }
                            )
                        } else {
                            GlassBadge(
                                text = stringResource(R.string.profiles_active),
                                color = look.accent
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassCircleButton(
                                icon = GlassIcons.QrFill,
                                contentDescription = stringResource(R.string.profiles_copy_link),
                                size = 40.dp,
                                tint = look.textSecondary,
                                onClick = {
                                    scope.launch {
                                        val (uri, bmp) = onGenerateQr(profile)
                                        qrPreview = Triple(profile.name, uri, bmp)
                                    }
                                }
                            )
                            GlassCircleButton(
                                icon = GlassIcons.Copy,
                                contentDescription = stringResource(R.string.common_copy),
                                size = 40.dp,
                                tint = look.textSecondary,
                                onClick = { onDuplicateProfile(profile.id) }
                            )
                            GlassCircleButton(
                                icon = GlassIcons.Pencil,
                                contentDescription = stringResource(R.string.profiles_edit),
                                size = 40.dp,
                                tint = look.accent,
                                onClick = { editingProfile = profile }
                            )
                            if (profiles.size > 1) {
                                GlassCircleButton(
                                    icon = GlassIcons.Trash,
                                    contentDescription = stringResource(R.string.common_clear),
                                    size = 40.dp,
                                    tint = look.danger,
                                    onClick = { onDeleteProfile(profile.id) }
                                )
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
        val inputFocus = remember { FocusRequester() }
        GlassModalDialog(
            onDismissRequest = { showImportTextDialog = false },
            title = stringResource(R.string.profiles_import_title),
            icon = GlassIcons.Clipboard,
            initialFocusRequester = inputFocus
        ) {
            OutlinedTextField(
                value = importInputText,
                onValueChange = { importInputText = it },
                label = { Text(stringResource(R.string.profiles_import_input_hint)) },
                minLines = 3,
                maxLines = 6,
                shape = RoundedCornerShape(16.dp),
                colors = novaOutlinedTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .tvFocusVisual(
                        shape = RoundedCornerShape(16.dp),
                        scaleOnFocus = 1.01f,
                        focusRequester = inputFocus
                    )
            )
            Spacer(modifier = Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassCapsuleButton(
                    text = stringResource(R.string.profiles_import_clipboard_now),
                    icon = GlassIcons.Clipboard,
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        if (clip.isNotBlank()) {
                            onImportText(clip)
                            importInputText = ""
                            showImportTextDialog = false
                        } else {
                            onNotify(context.getString(R.string.profiles_clipboard_empty))
                        }
                    }
                )
                GlassCapsuleButton(
                    text = stringResource(R.string.profiles_import_file),
                    icon = GlassIcons.Archive,
                    onClick = {
                        runCatching {
                            filePicker.launch(arrayOf("text/*", "application/json", "*/*"))
                        }.onSuccess {
                            showImportTextDialog = false
                        }.onFailure {
                            onNotify(context.getString(R.string.msg_file_picker_unavailable))
                        }
                    }
                )
                TextButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        if (clip.isNotBlank()) {
                            importInputText = clip
                        } else {
                            onNotify(context.getString(R.string.profiles_clipboard_empty))
                        }
                    },
                    modifier = Modifier.tvFocusVisual(shape = RoundedCornerShape(12.dp), scaleOnFocus = 1.04f)
                ) {
                    Text(stringResource(R.string.profiles_paste_clipboard))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                GlassCapsuleButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { showImportTextDialog = false }
                )
                Spacer(modifier = Modifier.width(10.dp))
                GlassCapsuleButton(
                    text = stringResource(R.string.profiles_import),
                    prominent = true,
                    onClick = {
                        onImportText(importInputText)
                        importInputText = ""
                        showImportTextDialog = false
                    }
                )
            }
        }
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

@Composable
private fun ProfileMetaChip(text: String, tint: Color) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            maxLines = 1
        )
    }
}
