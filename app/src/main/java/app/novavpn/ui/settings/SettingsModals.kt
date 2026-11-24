package app.novavpn.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.novavpn.domain.model.AppSettings
import app.novavpn.domain.model.AutoConnectSettings
import app.novavpn.domain.model.InstalledAppInfo
import app.novavpn.domain.model.LogEntry
import app.novavpn.domain.model.SplitTunnelMode
import app.novavpn.ui.glass.GlassCapsuleButton
import app.novavpn.ui.glass.GlassModalDialog
import app.novavpn.ui.glass.GlassSegmented
import app.novavpn.ui.glass.GlassSwitch
import app.novavpn.ui.glass.LocalGlassLook
import app.novavpn.ui.theme.JetBrainsMonoFamily
import app.novavpn.ui.theme.tr
import com.example.R

@Composable
fun SplitTunnelPickerDialog(
    mode: SplitTunnelMode,
    selectedPackages: Set<String>,
    installedApps: List<InstalledAppInfo>,
    onLoadApps: () -> Unit,
    onSave: (SplitTunnelMode, Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val look = LocalGlassLook.current
    var localMode by remember { mutableStateOf(mode) }
    var localSelected by remember { mutableStateOf(selectedPackages) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { onLoadApps() }

    val filtered = remember(installedApps, query, showSystem) {
        installedApps.filter { app ->
            (showSystem || !app.isSystem) &&
                (query.isBlank() || app.label.contains(query, ignoreCase = true) || app.packageName.contains(query, ignoreCase = true))
        }
    }

    val includeEmptyErr = stringResource(R.string.split_include_empty_error)

    GlassModalDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_split_tunnel),
        icon = Icons.Rounded.Apps
    ) {
        GlassSegmented(
            options = listOf(
                SplitTunnelMode.DISABLED to stringResource(R.string.split_all),
                SplitTunnelMode.EXCLUDE to stringResource(R.string.split_exclude),
                SplitTunnelMode.INCLUDE to stringResource(R.string.split_include)
            ),
            selected = localMode,
            onSelect = {
                localMode = it
                errorMsg = null
            }
        )
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.split_search)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                stringResource(R.string.split_show_system),
                style = MaterialTheme.typography.bodySmall,
                color = look.textSecondary
            )
            GlassSwitch(checked = showSystem, onCheckedChange = { showSystem = it })
        }
        errorMsg?.let { err ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(err, color = look.danger, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(6.dp))
        LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
            items(filtered, key = { it.packageName }) { app ->
                val checked = app.packageName in localSelected
                val iconBmp: Bitmap? = remember(app.packageName) {
                    runCatching {
                        context.packageManager.getApplicationIcon(app.packageName).toBitmap(64, 64)
                    }.getOrNull()
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            errorMsg = null
                            localSelected = if (checked) localSelected - app.packageName else localSelected + app.packageName
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (iconBmp != null) {
                        Image(
                            bitmap = iconBmp.asImageBitmap(),
                            contentDescription = app.label,
                            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyMedium, color = look.textPrimary)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = look.textSecondary)
                    }
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { c ->
                            errorMsg = null
                            localSelected = if (c) localSelected + app.packageName else localSelected - app.packageName
                        }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            GlassCapsuleButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss
            )
            Spacer(modifier = Modifier.width(10.dp))
            GlassCapsuleButton(
                text = stringResource(R.string.common_apply),
                prominent = true,
                onClick = {
                    if (localMode == SplitTunnelMode.INCLUDE && localSelected.isEmpty()) {
                        errorMsg = includeEmptyErr
                    } else {
                        onSave(localMode, localSelected)
                    }
                }
            )
        }
    }
}

@Composable
fun AutoConnectDialog(
    current: AutoConnectSettings,
    onSave: (AutoConnectSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val look = LocalGlassLook.current
    var startOnBoot by remember { mutableStateOf(current.startOnBoot) }
    var onUntrustedWifi by remember { mutableStateOf(current.onUntrustedWifi) }
    var trustedSsidsText by remember { mutableStateOf(current.trustedWifiSsids.joinToString("\n")) }
    var onAppLaunch by remember { mutableStateOf(current.onAppLaunchEnabled) }
    var triggerPkgsText by remember { mutableStateOf(current.triggerPackages.joinToString("\n")) }

    GlassModalDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_auto_connect),
        icon = Icons.Rounded.AutoMode
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Start on device boot", "اجرا هنگام روشن شدن دستگاه"), color = look.textPrimary)
                GlassSwitch(checked = startOnBoot, onCheckedChange = { startOnBoot = it })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Connect on untrusted Wi-Fi", "اتصال در وای‌فای نامطمئن"), color = look.textPrimary)
                GlassSwitch(checked = onUntrustedWifi, onCheckedChange = { onUntrustedWifi = it })
            }
            if (onUntrustedWifi) {
                OutlinedTextField(
                    value = trustedSsidsText,
                    onValueChange = { trustedSsidsText = it },
                    label = { Text(tr("Trusted Wi-Fi SSIDs (1 per line)", "نام وای‌فای‌های امن")) },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Trigger on selected apps", "اتصال هنگام باز شدن برنامه‌ها"), color = look.textPrimary)
                GlassSwitch(checked = onAppLaunch, onCheckedChange = { onAppLaunch = it })
            }
            if (onAppLaunch) {
                OutlinedTextField(
                    value = triggerPkgsText,
                    onValueChange = { triggerPkgsText = it },
                    label = { Text(tr("Package names (1 per line)", "نام پکیج برنامه‌ها")) },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                GlassCapsuleButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onDismiss
                )
                Spacer(modifier = Modifier.width(10.dp))
                GlassCapsuleButton(
                    text = stringResource(R.string.common_save),
                    prominent = true,
                    onClick = {
                        onSave(
                            AutoConnectSettings(
                                startOnBoot = startOnBoot,
                                onUntrustedWifi = onUntrustedWifi,
                                trustedWifiSsids = trustedSsidsText.lines().map { it.trim() }.filter { it.isNotEmpty() },
                                onAppLaunchEnabled = onAppLaunch,
                                triggerPackages = triggerPkgsText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
fun LogsViewerDialog(
    logs: List<LogEntry>,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val look = LocalGlassLook.current
    val formatted = remember(logs) { app.novavpn.data.log.LogRepository.exportText() }

    GlassModalDialog(
        onDismissRequest = onDismiss,
        title = tr("Redacted Logs", "لاگ‌های سیستم (سانسورشده)"),
        icon = Icons.Rounded.BugReport
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
            if (logs.isEmpty()) {
                item {
                    Text(
                        tr("No log entries yet.", "هنوز لاگی ثبت نشده است."),
                        color = look.textSecondary
                    )
                }
            } else {
                items(logs.takeLast(120)) { entry ->
                    Text(
                        text = "[${entry.level.name.first()}] ${entry.tag}: ${entry.message}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = look.textPrimary,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("Nova VPN Logs", formatted))
                }) { Text(stringResource(R.string.common_copy)) }
                TextButton(onClick = {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, formatted)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(share, "Share Nova VPN Logs"))
                    }
                }) { Text(stringResource(R.string.common_share)) }
                TextButton(onClick = onClear) { Text(stringResource(R.string.common_clear)) }
            }
            GlassCapsuleButton(
                text = stringResource(R.string.common_close),
                prominent = true,
                onClick = onDismiss
            )
        }
    }
}

@Composable
fun BackupRestoreDialog(
    exportedJson: String?,
    onImportJson: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var inputText by remember(exportedJson) { mutableStateOf(exportedJson.orEmpty()) }

    GlassModalDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_backup_restore),
        icon = Icons.Rounded.Restore
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                label = { Text("Nova VPN JSON") },
                minLines = 4,
                maxLines = 8,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("Nova VPN Backup", inputText))
                }) { Text(stringResource(R.string.common_copy)) }
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    if (clip.isNotBlank()) inputText = clip
                }) { Text(stringResource(R.string.common_paste)) }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                GlassCapsuleButton(
                    text = stringResource(R.string.common_close),
                    onClick = onDismiss
                )
                Spacer(modifier = Modifier.width(10.dp))
                GlassCapsuleButton(
                    text = tr("Restore JSON", "بازیابی"),
                    prominent = true,
                    onClick = { onImportJson(inputText) }
                )
            }
        }
    }
}

@Composable
fun PortsEditorDialog(
    settings: AppSettings,
    onSave: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val look = LocalGlassLook.current
    var socks by remember { mutableStateOf(settings.localSocksPort.toString()) }
    var http by remember { mutableStateOf(settings.localHttpPort.toString()) }
    var err by remember { mutableStateOf<String?>(null) }

    GlassModalDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_local_ports),
        icon = Icons.Rounded.Dns
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = socks,
                onValueChange = { socks = it.filter(Char::isDigit) },
                label = { Text("SOCKS5 Port (1024-65535)") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = http,
                onValueChange = { http = it.filter(Char::isDigit) },
                label = { Text("HTTP Port (1024-65535)") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            err?.let { Text(it, color = look.danger, style = MaterialTheme.typography.bodySmall) }
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                GlassCapsuleButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onDismiss
                )
                Spacer(modifier = Modifier.width(10.dp))
                GlassCapsuleButton(
                    text = stringResource(R.string.common_save),
                    prominent = true,
                    onClick = {
                        val s = socks.toIntOrNull() ?: 0
                        val h = http.toIntOrNull() ?: 0
                        if (s !in 1024..65535 || h !in 1024..65535 || s == h) {
                            err = "Enter distinct valid ports between 1024 and 65535"
                        } else {
                            onSave(s, h)
                        }
                    }
                )
            }
        }
    }
}
