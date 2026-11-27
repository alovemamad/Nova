package app.novavpn.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.novavpn.data.store.GeoResourceRepository
import app.novavpn.domain.model.GeoResourceFormat
import app.novavpn.domain.model.GeoResourceItem
import app.novavpn.domain.model.GeoResourceStatus
import app.novavpn.domain.model.GeoSourceType
import app.novavpn.ui.glass.GlassBadge
import app.novavpn.ui.glass.GlassCapsuleButton
import app.novavpn.ui.glass.GlassCircleButton
import app.novavpn.ui.glass.GlassDivider
import app.novavpn.ui.glass.GlassIcons
import app.novavpn.ui.glass.GlassModalDialog
import app.novavpn.ui.glass.GlassSheet
import app.novavpn.ui.glass.LocalGlassLook
import app.novavpn.ui.glass.novaOutlinedTextFieldColors
import app.novavpn.ui.theme.JetBrainsMonoFamily
import app.novavpn.ui.theme.fmtDigits
import app.novavpn.ui.theme.formatBytes
import com.example.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GeoResourcesDialog(
    resources: List<GeoResourceItem>,
    isUpdatingAll: Boolean,
    unresolvedTags: List<String> = emptyList(),
    errorMessage: String?,
    onClearError: () -> Unit,
    onAddFromFile: (Uri) -> Unit,
    onAddFromUrl: (String, String?) -> Unit,
    onImportFromQr: (Uri) -> Unit,
    onUpdateAll: () -> Unit,
    onRefreshSingle: (String) -> Unit,
    onDeleteResource: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val look = LocalGlassLook.current
    var showUrlInputDialog by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var customNameInput by remember { mutableStateOf("") }
    var urlValidationError by remember { mutableStateOf<String?>(null) }

    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }

    // Zero-permission SAF Document Picker for importing .dat files from device storage
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onAddFromFile(uri)
        }
    }

    // Zero-permission Android Photo Picker for decoding geo-resource URL from QR code image
    val qrPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onImportFromQr(uri)
        }
    }

    GlassSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.geo_title),
        icon = GlassIcons.Globe
    ) {
        Text(
            text = stringResource(R.string.geo_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = look.textSecondary
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Action Buttons (Add File, Add URL, Scan QR)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GlassCapsuleButton(
                text = stringResource(R.string.geo_add_file),
                icon = GlassIcons.Archive,
                onClick = {
                    onClearError()
                    filePicker.launch(arrayOf("application/octet-stream", "*/*"))
                },
                modifier = Modifier.testTag("geo_add_file_button")
            )
            GlassCapsuleButton(
                text = stringResource(R.string.geo_add_url),
                icon = GlassIcons.Link,
                onClick = {
                    onClearError()
                    urlValidationError = null
                    showUrlInputDialog = true
                },
                modifier = Modifier.testTag("geo_add_url_button")
            )
            GlassCapsuleButton(
                text = stringResource(R.string.geo_scan_qr),
                icon = GlassIcons.Qr,
                onClick = {
                    onClearError()
                    qrPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                modifier = Modifier.testTag("geo_scan_qr_button")
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // "Update Resources" ("به‌روزرسانی منابع") primary batch update button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassCapsuleButton(
                text = if (isUpdatingAll) stringResource(R.string.geo_updating_all) else stringResource(R.string.geo_update_all),
                icon = GlassIcons.Refresh,
                prominent = true,
                onClick = {
                    if (!isUpdatingAll) onUpdateAll()
                },
                modifier = Modifier.testTag("geo_update_all_button")
            )
            if (isUpdatingAll) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = look.accent
                )
            }
        }

        AnimatedVisibility(visible = unresolvedTags.isNotEmpty()) {
            val bannerShape = RoundedCornerShape(16.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(bannerShape)
                    .background(look.warning.copy(alpha = 0.14f), bannerShape)
                    .border(0.8.dp, look.warning.copy(alpha = 0.36f), bannerShape)
                    .testTag("geo_unresolved_warning_banner")
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = GlassIcons.Warning,
                        contentDescription = null,
                        tint = look.warning,
                        modifier = Modifier.padding(top = 2.dp).size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.geo_unresolved_warning_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = look.warning
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(
                                R.string.geo_unresolved_warning_body,
                                unresolvedTags.joinToString(", ")
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = look.textPrimary
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = !errorMessage.isNullOrBlank()) {
            val errorShape = RoundedCornerShape(16.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(errorShape)
                    .background(look.danger.copy(alpha = 0.14f), errorShape)
                    .border(0.8.dp, look.danger.copy(alpha = 0.36f), errorShape)
                    .testTag("geo_error_banner")
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = GlassIcons.Warning,
                        contentDescription = null,
                        tint = look.danger,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = look.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        GlassDivider()
        Spacer(modifier = Modifier.height(10.dp))

        if (resources.isEmpty()) {
            val emptyShape = RoundedCornerShape(18.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(emptyShape)
                    .background(look.fill2, emptyShape)
                    .border(0.8.dp, look.separator, emptyShape)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.geo_empty),
                        style = MaterialTheme.typography.titleMedium,
                        color = look.textPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.geo_empty_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = look.textSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(resources, key = { it.id }) { item ->
                    val statusColor = when (item.status) {
                        GeoResourceStatus.READY -> look.success
                        GeoResourceStatus.UPDATING -> look.secondaryAccent
                        GeoResourceStatus.ERROR -> look.danger
                    }
                    val statusLabel = when (item.status) {
                        GeoResourceStatus.READY -> stringResource(R.string.geo_status_ready)
                        GeoResourceStatus.UPDATING -> stringResource(R.string.geo_status_updating)
                        GeoResourceStatus.ERROR -> stringResource(R.string.geo_status_error)
                    }
                    val sourceLabel = if (item.sourceType == GeoSourceType.URL) {
                        stringResource(R.string.geo_source_url)
                    } else {
                        stringResource(R.string.geo_source_local)
                    }
                    val formattedTime = if (item.lastUpdatedMs > 0L) {
                        fmtDigits(dateFormatter.format(Date(item.lastUpdatedMs)))
                    } else "—"

                    val itemShape = RoundedCornerShape(18.dp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(itemShape)
                            .background(look.fill2, itemShape)
                            .border(0.8.dp, look.separator, itemShape)
                            .testTag("geo_item_${item.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMonoFamily),
                                        fontWeight = FontWeight.SemiBold,
                                        color = look.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    GlassBadge(text = statusLabel, color = statusColor)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "$sourceLabel · ${fmtDigits(formatBytes(item.sizeBytes))}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = look.textSecondary
                                )
                                val formatLabel = when (item.format) {
                                    GeoResourceFormat.V2RAY_GEOIP -> "V2Ray GeoIP (.dat)"
                                    GeoResourceFormat.V2RAY_GEOSITE -> "V2Ray GeoSite (.dat)"
                                    GeoResourceFormat.TEXT_RULES -> "Rule List"
                                    GeoResourceFormat.UNKNOWN -> "Raw"
                                }
                                val sampleSuffix = if (item.sampleTags.isNotEmpty()) {
                                    " (${item.sampleTags.take(3).joinToString(", ")})"
                                } else ""
                                Text(
                                    text = fmtDigits(
                                        stringResource(
                                            R.string.geo_format_info,
                                            formatLabel,
                                            item.entryCount,
                                            sampleSuffix
                                        )
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = look.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = stringResource(R.string.geo_core_compat),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = look.accent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = stringResource(R.string.geo_last_updated, formattedTime),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = look.textMuted
                                )
                                if (!item.sourceUrl.isNullOrBlank()) {
                                    Text(
                                        text = item.sourceUrl,
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                        color = look.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (!item.lastError.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = item.lastError,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = look.danger
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (item.sourceType == GeoSourceType.URL) {
                                    GlassCircleButton(
                                        icon = GlassIcons.Refresh,
                                        contentDescription = stringResource(R.string.geo_refresh_single),
                                        size = 40.dp,
                                        tint = look.accent,
                                        onClick = { onRefreshSingle(item.id) }
                                    )
                                }
                                GlassCircleButton(
                                    icon = GlassIcons.Trash,
                                    contentDescription = stringResource(R.string.geo_delete),
                                    size = 40.dp,
                                    tint = look.danger,
                                    onClick = { onDeleteResource(item.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showUrlInputDialog) {
        GlassModalDialog(
            onDismissRequest = { showUrlInputDialog = false },
            title = stringResource(R.string.geo_url_dialog_title),
            icon = GlassIcons.Link
        ) {
            OutlinedTextField(
                value = urlInput,
                onValueChange = {
                    urlInput = it
                    urlValidationError = null
                },
                label = { Text(stringResource(R.string.geo_url_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = novaOutlinedTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("geo_url_input")
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = customNameInput,
                onValueChange = { customNameInput = it },
                label = { Text(stringResource(R.string.geo_name_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = novaOutlinedTextFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            if (!urlValidationError.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = urlValidationError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = look.danger
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                GlassCapsuleButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { showUrlInputDialog = false }
                )
                Spacer(modifier = Modifier.width(10.dp))
                GlassCapsuleButton(
                    text = stringResource(R.string.common_save),
                    prominent = true,
                    onClick = {
                        val valid = GeoResourceRepository.validateUrl(urlInput)
                        valid.onSuccess { cleanUrl ->
                            showUrlInputDialog = false
                            onAddFromUrl(cleanUrl, customNameInput.trim().ifBlank { null })
                            urlInput = ""
                            customNameInput = ""
                        }.onFailure { err ->
                            urlValidationError = err.message ?: "Invalid URL"
                        }
                    },
                    modifier = Modifier.testTag("geo_url_confirm_button")
                )
            }
        }
    }
}
