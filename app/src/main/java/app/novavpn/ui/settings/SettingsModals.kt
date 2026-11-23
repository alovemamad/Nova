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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.novavpn.domain.model.AppSettings
import app.novavpn.domain.model.AutoConnectSettings
import app.novavpn.domain.model.InstalledAppInfo
import app.novavpn.domain.model.LogEntry
import app.novavpn.domain.model.SplitTunnelMode
import app.novavpn.domain.model.UpdateInfo
import app.novavpn.ui.glass.GlassSegmented
import app.novavpn.ui.theme.JetBrainsMonoFamily
import app.novavpn.ui.theme.tr

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
    var localMode by remember { mutableStateOf(mode) }
    var localSelected by remember { mutableStateOf(selectedPackages) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { onLoadApps() }

    val filtered = remember(installedApps, query, showSystem) {
        installedApps.filter { app ->
            (showSystem || !app.isSystem) &&
                (query.isBlank() || app.label.contains(query, ignoreCase = true) || app.packageName.contains(query, ignoreCase = true))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Split Tunneling", "تونل‌زنی انتخابی برنامه‌ها")) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                GlassSegmented(
                    options = listOf(
                        SplitTunnelMode.DISABLED to tr("All", "همه"),
                        SplitTunnelMode.EXCLUDE to tr("Bypass", "مستثنی"),
                        SplitTunnelMode.INCLUDE to tr("Only", "فقط اینها")
                    ),
                    selected = localMode,
                    onSelect = { localMode = it }
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(tr("Search apps", "جستجوی برنامه")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(tr("Show system apps", "نمایش برنامه‌های سیستمی"), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = showSystem, onCheckedChange = { showSystem = it })
                }
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
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
                                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { c ->
                                    localSelected = if (c) localSelected + app.packageName else localSelected - app.packageName
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(localMode, localSelected) }) {
                Text(tr("Apply", "اعمال"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel", "لغو")) }
        }
    )
}

@Composable
fun AutoConnectDialog(
    current: AutoConnectSettings,
    onSave: (AutoConnectSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var startOnBoot by remember { mutableStateOf(current.startOnBoot) }
    var onUntrustedWifi by remember { mutableStateOf(current.onUntrustedWifi) }
    var trustedSsidsText by remember { mutableStateOf(current.trustedWifiSsids.joinToString("\n")) }
    var onAppLaunch by remember { mutableStateOf(current.onAppLaunchEnabled) }
    var triggerPkgsText by remember { mutableStateOf(current.triggerPackages.joinToString("\n")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Auto-Connect Rules", "قوانین اتصال خودکار")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Start on device boot", "اجرا هنگام روشن شدن دستگاه"))
                    Switch(checked = startOnBoot, onCheckedChange = { startOnBoot = it })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Connect on untrusted Wi-Fi", "اتصال در وای‌فای نامطمئن"))
                    Switch(checked = onUntrustedWifi, onCheckedChange = { onUntrustedWifi = it })
                }
                if (onUntrustedWifi) {
                    OutlinedTextField(
                        value = trustedSsidsText,
                        onValueChange = { trustedSsidsText = it },
                        label = { Text(tr("Trusted Wi-Fi SSIDs (1 per line)", "نام وای‌فای‌های امن")) },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Trigger on selected apps", "اتصال هنگام باز شدن برنامه‌ها"))
                    Switch(checked = onAppLaunch, onCheckedChange = { onAppLaunch = it })
                }
                if (onAppLaunch) {
                    OutlinedTextField(
                        value = triggerPkgsText,
                        onValueChange = { triggerPkgsText = it },
                        label = { Text(tr("Package names (1 per line)", "نام پکیج برنامه‌ها")) },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    AutoConnectSettings(
                        startOnBoot = startOnBoot,
                        onUntrustedWifi = onUntrustedWifi,
                        trustedWifiSsids = trustedSsidsText.lines().map { it.trim() }.filter { it.isNotEmpty() },
                        onAppLaunchEnabled = onAppLaunch,
                        triggerPackages = triggerPkgsText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    )
                )
            }) { Text(tr("Save", "ذخیره")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel", "لغو")) }
        }
    )
}

@Composable
fun LogsViewerDialog(
    logs: List<LogEntry>,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val formatted = remember(logs) { app.novavpn.data.log.LogRepository.exportText() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Redacted Logs", "لاگ‌های سیستم (سانسورشده)")) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    if (logs.isEmpty()) {
                        item { Text(tr("No log entries yet.", "هنوز لاگی ثبت نشده است.")) }
                    } else {
                        items(logs.takeLast(120)) { entry ->
                            Text(
                                text = "[${entry.level.name.first()}] ${entry.tag}: ${entry.message}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("Nova VPN Logs", formatted))
                    }) { Text(tr("Copy", "کپی")) }
                    OutlinedButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, formatted)
                        }
                        context.startActivity(Intent.createChooser(share, "Share Nova VPN Logs"))
                    }) { Text(tr("Share", "اشتراک")) }
                    TextButton(onClick = onClear) { Text(tr("Clear", "پاک")) }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(tr("Close", "بستن")) }
        }
    )
}

@Composable
fun PinSetupDialog(
    onConfirmPin: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Set App Lock PIN (4–6 digits)", "تنظیم رمز ۴ تا ۶ رقمی")) },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                    label = { Text("PIN") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (pin.length < 4) err = "Enter at least 4 digits" else onConfirmPin(pin)
            }) { Text(tr("Enable Lock", "فعال‌سازی قفل")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel", "لغو")) }
        }
    )
}

@Composable
fun BackupRestoreDialog(
    exportedJson: String?,
    onImportJson: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var inputText by remember(exportedJson) { mutableStateOf(exportedJson.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Backup & Restore (JSON)", "پشتیبان‌گیری و بازیابی")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = { Text("Nova VPN JSON") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("Nova VPN Backup", inputText))
                    }) { Text(tr("Copy", "کپی")) }
                    OutlinedButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        if (clip.isNotBlank()) inputText = clip
                    }) { Text(tr("Paste", "چسباندن")) }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onImportJson(inputText) }) {
                Text(tr("Restore JSON", "بازیابی"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Close", "بستن")) }
        }
    )
}

@Composable
fun PortsEditorDialog(
    settings: AppSettings,
    onSave: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var socks by remember { mutableStateOf(settings.localSocksPort.toString()) }
    var http by remember { mutableStateOf(settings.localHttpPort.toString()) }
    var err by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Local Proxy Ports", "پورت‌های محلی")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = socks,
                    onValueChange = { socks = it.filter(Char::isDigit) },
                    label = { Text("SOCKS5 Port (1024-65535)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = http,
                    onValueChange = { http = it.filter(Char::isDigit) },
                    label = { Text("HTTP Port (1024-65535)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val s = socks.toIntOrNull() ?: 0
                val h = http.toIntOrNull() ?: 0
                if (s !in 1024..65535 || h !in 1024..65535 || s == h) {
                    err = "Enter distinct valid ports between 1024 and 65535"
                } else {
                    onSave(s, h)
                }
            }) { Text(tr("Save", "ذخیره")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel", "لغو")) }
        }
    )
}

@Composable
fun UpdateCheckerDialog(
    info: UpdateInfo,
    currentVersion: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("App Updates", "به‌روزرسانی برنامه")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${tr("Installed version", "نسخه فعلی")}: $currentVersion")
                when {
                    info.checking -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(tr("Checking GitHub Releases…", "در حال بررسی گیت‌هاب…"))
                        }
                    }
                    info.error != null -> {
                        Text(info.error, color = MaterialTheme.colorScheme.error)
                    }
                    info.updateAvailable -> {
                        Text(
                            text = "${tr("New version available", "نسخه جدید موجود است")}: ${info.latestTag}",
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (!info.releaseNotes.isNullOrBlank()) {
                            Text(info.releaseNotes, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    else -> {
                        Text(tr("You are on the latest release.", "شما آخرین نسخه را دارید."))
                    }
                }
            }
        },
        confirmButton = {
            if (info.updateAvailable && !info.releaseUrl.isNullOrBlank()) {
                Button(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(info.releaseUrl))
                    context.startActivity(intent)
                }) { Text(tr("Open Release", "باز کردن صفحه")) }
            } else {
                Button(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = {
            if (info.updateAvailable) {
                TextButton(onClick = onDismiss) { Text(tr("Close", "بستن")) }
            }
        }
    )
}
