package app.novavpn.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.novavpn.domain.model.AppLanguage
import app.novavpn.domain.model.AppSettings
import app.novavpn.domain.model.AppThemeMode
import app.novavpn.domain.model.DigitMode
import app.novavpn.domain.model.GlassConnectControl
import app.novavpn.domain.model.GlassMaterial
import app.novavpn.domain.model.GlassTint
import app.novavpn.domain.model.GlassWallpaper
import app.novavpn.domain.model.InstalledAppInfo
import app.novavpn.domain.model.LogEntry
import app.novavpn.domain.model.TunEnginePreference
import app.novavpn.domain.model.UpdateInfo
import app.novavpn.domain.model.VpnMode
import app.novavpn.ui.glass.GlassDivider
import app.novavpn.ui.glass.GlassGroup
import app.novavpn.ui.glass.GlassRow
import app.novavpn.ui.glass.GlassSegmented
import app.novavpn.ui.glass.GlassSwitchRow
import app.novavpn.ui.glass.LocalGlassLook
import app.novavpn.ui.glass.resolveGlassTintColor
import com.example.BuildConfig
import com.example.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    installedApps: List<InstalledAppInfo>,
    logs: List<LogEntry>,
    updateInfo: UpdateInfo,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onLoadApps: () -> Unit,
    onEnableAppLock: (String) -> Unit,
    onDisableAppLock: () -> Unit,
    onExportBackup: ((String) -> Unit) -> Unit,
    onImportBackup: (String) -> Unit,
    onClearLogs: () -> Unit,
    onCheckUpdates: (String) -> Unit,
    onOpenVpnSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit
) {
    val look = LocalGlassLook.current

    var showSplitPicker by remember { mutableStateOf(false) }
    var showAutoConnect by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var showPinSetup by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var exportedJson by remember { mutableStateOf<String?>(null) }
    var showPortsDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showSystemKillSwitchDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 112.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                color = look.textPrimary,
                modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()
            )
        }

        // TUNNEL & ROUTING
        item {
            GlassGroup(
                title = stringResource(R.string.settings_section_tunnel),
                modifier = Modifier.widthIn(max = 600.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(stringResource(R.string.settings_operation_mode), style = MaterialTheme.typography.labelLarge, color = look.textSecondary)
                    Spacer(modifier = Modifier.height(6.dp))
                    GlassSegmented(
                        options = listOf(
                            VpnMode.VPN to stringResource(R.string.settings_mode_vpn),
                            VpnMode.PROXY_ONLY to stringResource(R.string.settings_mode_proxy)
                        ),
                        selected = settings.vpnMode,
                        onSelect = { mode -> onUpdateSettings { it.copy(vpnMode = mode) } },
                        modifier = Modifier.testTag("settings_mode_segmented")
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.settings_tun_engine), style = MaterialTheme.typography.labelLarge, color = look.textSecondary)
                    Spacer(modifier = Modifier.height(6.dp))
                    GlassSegmented(
                        options = listOf(
                            TunEnginePreference.AUTO_SINGBOX_FALLBACK_HEV to "Auto (gVisor)",
                            TunEnginePreference.SINGBOX_ONLY to "sing-box",
                            TunEnginePreference.HEV_SOCKS5_ONLY to "hev-socks5"
                        ),
                        selected = settings.tunEnginePreference,
                        onSelect = { pref -> onUpdateSettings { it.copy(tunEnginePreference = pref) } }
                    )
                }
                GlassDivider()
                GlassRow(
                    title = stringResource(R.string.settings_split_tunnel),
                    subtitle = stringResource(R.string.settings_apps_count, settings.splitTunnelMode.name, settings.splitTunnelPackages.size),
                    icon = Icons.Rounded.Apps,
                    onClick = { showSplitPicker = true },
                    modifier = Modifier.testTag("settings_split_tunnel_row")
                )
                GlassDivider()
                GlassRow(
                    title = stringResource(R.string.settings_local_ports),
                    subtitle = "SOCKS5 :${settings.localSocksPort} · HTTP :${settings.localHttpPort}",
                    icon = Icons.Rounded.Dns,
                    onClick = { showPortsDialog = true }
                )
                GlassDivider()
                GlassSwitchRow(
                    title = stringResource(R.string.settings_lan_share),
                    subtitle = stringResource(R.string.settings_lan_share_desc),
                    icon = Icons.Rounded.Lan,
                    checked = settings.shareProxyOnLan,
                    onCheckedChange = { checked -> onUpdateSettings { it.copy(shareProxyOnLan = checked) } }
                )
                GlassDivider()
                GlassSwitchRow(
                    title = stringResource(R.string.settings_prefer_last),
                    subtitle = stringResource(R.string.settings_prefer_last_desc),
                    icon = Icons.Rounded.Speed,
                    checked = settings.preferLastGoodGateway,
                    onCheckedChange = { checked -> onUpdateSettings { it.copy(preferLastGoodGateway = checked) } }
                )
            }
        }

        // RELIABILITY & AUTOMATION
        item {
            GlassGroup(
                title = stringResource(R.string.settings_section_reliability),
                modifier = Modifier.widthIn(max = 600.dp)
            ) {
                GlassSwitchRow(
                    title = stringResource(R.string.settings_keep_tun_reconnect),
                    subtitle = stringResource(R.string.settings_keep_tun_reconnect_desc),
                    icon = Icons.Rounded.GppGood,
                    checked = settings.keepTunOnReconnect,
                    onCheckedChange = { checked -> onUpdateSettings { it.copy(keepTunOnReconnect = checked) } },
                    modifier = Modifier.testTag("settings_keep_tun_switch")
                )
                GlassDivider()
                GlassRow(
                    title = stringResource(R.string.settings_always_on),
                    subtitle = stringResource(R.string.settings_always_on_desc),
                    icon = Icons.Rounded.VpnLock,
                    onClick = { showSystemKillSwitchDialog = true },
                    modifier = Modifier.testTag("settings_system_killswitch_row")
                )
                GlassDivider()
                val disabledLabel = stringResource(R.string.settings_disabled)
                GlassRow(
                    title = stringResource(R.string.settings_auto_connect),
                    subtitle = buildString {
                        if (settings.autoConnect.startOnBoot) append("Boot · ")
                        if (settings.autoConnect.onUntrustedWifi) append("Untrusted Wi-Fi · ")
                        if (settings.autoConnect.onAppLaunchEnabled) append("${settings.autoConnect.triggerPackages.size} apps")
                        if (isEmpty()) append(disabledLabel)
                    },
                    icon = Icons.Rounded.AutoMode,
                    onClick = { showAutoConnect = true }
                )
                GlassDivider()
                GlassRow(
                    title = stringResource(R.string.settings_battery_opt),
                    subtitle = stringResource(R.string.settings_battery_opt_desc),
                    icon = Icons.Rounded.BatterySaver,
                    onClick = onOpenBatterySettings
                )
            }
        }

        // SECURITY & PRIVACY
        item {
            GlassGroup(
                title = stringResource(R.string.settings_section_security),
                modifier = Modifier.widthIn(max = 600.dp)
            ) {
                GlassSwitchRow(
                    title = stringResource(R.string.settings_app_lock),
                    subtitle = if (settings.appLockEnabled) stringResource(R.string.settings_enabled) else stringResource(R.string.settings_app_lock_desc),
                    icon = Icons.Rounded.Fingerprint,
                    checked = settings.appLockEnabled,
                    onCheckedChange = { checked ->
                        if (checked) showPinSetup = true else onDisableAppLock()
                    },
                    modifier = Modifier.testTag("settings_applock_switch")
                )
                GlassDivider()
                GlassSwitchRow(
                    title = stringResource(R.string.settings_hide_recents),
                    subtitle = stringResource(R.string.settings_hide_recents_desc),
                    icon = Icons.Rounded.VisibilityOff,
                    checked = settings.hideFromRecents,
                    onCheckedChange = { checked -> onUpdateSettings { it.copy(hideFromRecents = checked) } }
                )
            }
        }

        // LIQUID GLASS & APPEARANCE
        item {
            GlassGroup(
                title = stringResource(R.string.settings_section_glass),
                modifier = Modifier.widthIn(max = 600.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.labelLarge, color = look.textSecondary)
                    GlassSegmented(
                        options = listOf(
                            AppThemeMode.DARK to stringResource(R.string.settings_dark),
                            AppThemeMode.LIGHT to stringResource(R.string.settings_light),
                            AppThemeMode.SYSTEM to stringResource(R.string.settings_system)
                        ),
                        selected = settings.themeMode,
                        onSelect = { m -> onUpdateSettings { it.copy(themeMode = m) } }
                    )

                    Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.labelLarge, color = look.textSecondary)
                    GlassSegmented(
                        options = listOf(
                            AppLanguage.SYSTEM to stringResource(R.string.settings_system),
                            AppLanguage.EN to "English",
                            AppLanguage.FA to "فارسی"
                        ),
                        selected = settings.language,
                        onSelect = { l -> onUpdateSettings { it.copy(language = l) } },
                        modifier = Modifier.testTag("settings_language_segmented")
                    )

                    Text(stringResource(R.string.settings_digits), style = MaterialTheme.typography.labelLarge, color = look.textSecondary)
                    GlassSegmented(
                        options = listOf(
                            DigitMode.LOCALE to stringResource(R.string.settings_auto),
                            DigitMode.LATIN to "123",
                            DigitMode.PERSIAN to "۱۲۳"
                        ),
                        selected = settings.digitMode,
                        onSelect = { d -> onUpdateSettings { it.copy(digitMode = d) } }
                    )

                    Text(stringResource(R.string.settings_glass_material), style = MaterialTheme.typography.labelLarge, color = look.textSecondary)
                    GlassSegmented(
                        options = listOf(
                            GlassMaterial.CLEAR to stringResource(R.string.settings_clear),
                            GlassMaterial.TINTED to stringResource(R.string.settings_tinted)
                        ),
                        selected = settings.glass.material,
                        onSelect = { mat -> onUpdateSettings { it.copy(glass = it.glass.copy(material = mat)) } }
                    )

                    Text(stringResource(R.string.settings_wallpaper), style = MaterialTheme.typography.labelLarge, color = look.textSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GlassWallpaper.entries.forEach { wp ->
                            FilterChip(
                                selected = settings.glass.wallpaper == wp,
                                onClick = { onUpdateSettings { it.copy(glass = it.glass.copy(wallpaper = wp)) } },
                                label = { Text(wp.name.lowercase().replaceFirstChar(Char::uppercase)) }
                            )
                        }
                    }

                    Text(stringResource(R.string.settings_connect_control), style = MaterialTheme.typography.labelLarge, color = look.textSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GlassConnectControl.entries.forEach { ctrl ->
                            FilterChip(
                                selected = settings.glass.connectControl == ctrl,
                                onClick = { onUpdateSettings { it.copy(glass = it.glass.copy(connectControl = ctrl)) } },
                                label = { Text(ctrl.name.replace("_", " ").lowercase().replaceFirstChar(Char::uppercase)) }
                            )
                        }
                    }

                    Text(stringResource(R.string.settings_accent_tint), style = MaterialTheme.typography.labelLarge, color = look.textSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassTint.entries.forEach { tint ->
                            val (primaryTint, _) = resolveGlassTintColor(tint)
                            val selected = settings.glass.tint == tint
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(primaryTint)
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) look.textPrimary else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable { onUpdateSettings { it.copy(glass = it.glass.copy(tint = tint)) } }
                            )
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    GlassDivider()
                    GlassSwitchRow(
                        title = stringResource(R.string.settings_dynamic_color),
                        icon = Icons.Rounded.Palette,
                        checked = settings.dynamicColor,
                        onCheckedChange = { checked -> onUpdateSettings { it.copy(dynamicColor = checked) } }
                    )
                }
                GlassDivider()
                GlassSwitchRow(
                    title = stringResource(R.string.settings_motion_tilt),
                    checked = settings.glass.motionHighlights,
                    onCheckedChange = { checked -> onUpdateSettings { it.copy(glass = it.glass.copy(motionHighlights = checked)) } }
                )
                GlassDivider()
                GlassSwitchRow(
                    title = stringResource(R.string.settings_reduce_transparency),
                    checked = settings.glass.reduceTransparency,
                    onCheckedChange = { checked -> onUpdateSettings { it.copy(glass = it.glass.copy(reduceTransparency = checked)) } }
                )
            }
        }

        // LOGS, BACKUP & UPDATES
        item {
            GlassGroup(
                title = stringResource(R.string.settings_section_maintenance),
                modifier = Modifier.widthIn(max = 600.dp)
            ) {
                GlassRow(
                    title = stringResource(R.string.settings_view_logs),
                    subtitle = stringResource(R.string.settings_logs_count, logs.size),
                    icon = Icons.Rounded.BugReport,
                    onClick = { showLogs = true },
                    modifier = Modifier.testTag("settings_logs_row")
                )
                GlassDivider()
                GlassRow(
                    title = stringResource(R.string.settings_backup_restore),
                    subtitle = stringResource(R.string.settings_backup_desc),
                    icon = Icons.Rounded.Restore,
                    onClick = {
                        onExportBackup { raw ->
                            exportedJson = raw
                            showBackupDialog = true
                        }
                    }
                )
                GlassDivider()
                GlassRow(
                    title = stringResource(R.string.settings_check_updates),
                    subtitle = "v${BuildConfig.VERSION_NAME}",
                    icon = Icons.Rounded.CloudDownload,
                    onClick = {
                        onCheckUpdates(BuildConfig.VERSION_NAME)
                        showUpdateDialog = true
                    }
                )
            }
        }
    }

    if (showSystemKillSwitchDialog) {
        AlertDialog(
            onDismissRequest = { showSystemKillSwitchDialog = false },
            title = { Text(stringResource(R.string.settings_always_on_dialog_title)) },
            text = { Text(stringResource(R.string.settings_always_on_dialog_body)) },
            confirmButton = {
                Button(onClick = {
                    showSystemKillSwitchDialog = false
                    onOpenVpnSettings()
                }) {
                    Text(stringResource(R.string.settings_open_vpn_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSystemKillSwitchDialog = false }) {
                    Text(stringResource(R.string.common_close))
                }
            }
        )
    }

    if (showSplitPicker) {
        SplitTunnelPickerDialog(
            mode = settings.splitTunnelMode,
            selectedPackages = settings.splitTunnelPackages,
            installedApps = installedApps,
            onLoadApps = onLoadApps,
            onSave = { mode, pkgs ->
                onUpdateSettings { it.copy(splitTunnelMode = mode, splitTunnelPackages = pkgs) }
                showSplitPicker = false
            },
            onDismiss = { showSplitPicker = false }
        )
    }

    if (showAutoConnect) {
        AutoConnectDialog(
            current = settings.autoConnect,
            onSave = { updated ->
                onUpdateSettings { it.copy(autoConnect = updated) }
                showAutoConnect = false
            },
            onDismiss = { showAutoConnect = false }
        )
    }

    if (showLogs) {
        LogsViewerDialog(
            logs = logs,
            onClear = onClearLogs,
            onDismiss = { showLogs = false }
        )
    }

    if (showPinSetup) {
        PinSetupDialog(
            onConfirmPin = { pin ->
                onEnableAppLock(pin)
                showPinSetup = false
            },
            onDismiss = { showPinSetup = false }
        )
    }

    if (showBackupDialog) {
        BackupRestoreDialog(
            exportedJson = exportedJson,
            onImportJson = { raw ->
                onImportBackup(raw)
                showBackupDialog = false
            },
            onDismiss = { showBackupDialog = false }
        )
    }

    if (showPortsDialog) {
        PortsEditorDialog(
            settings = settings,
            onSave = { socks, http ->
                onUpdateSettings { it.copy(localSocksPort = socks, localHttpPort = http, portsInitialized = true) }
                showPortsDialog = false
            },
            onDismiss = { showPortsDialog = false }
        )
    }

    if (showUpdateDialog) {
        UpdateCheckerDialog(
            info = updateInfo,
            currentVersion = BuildConfig.VERSION_NAME,
            onDismiss = { showUpdateDialog = false }
        )
    }
}
