package app.novavpn.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.novavpn.core.net.GitHubUpdater
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
import app.novavpn.ui.glass.GlassBadge
import app.novavpn.ui.glass.GlassCapsuleButton
import app.novavpn.ui.glass.GlassDivider
import app.novavpn.ui.glass.GlassGroup
import app.novavpn.ui.glass.GlassModalDialog
import app.novavpn.ui.glass.GlassRow
import app.novavpn.ui.glass.GlassSegmented
import app.novavpn.ui.glass.GlassSwitchRow
import app.novavpn.ui.glass.LocalGlassLook
import app.novavpn.ui.glass.resolveGlassTintColor
import app.novavpn.ui.theme.fmtDigits
import app.novavpn.ui.theme.formatBytes
import com.example.BuildConfig
import com.example.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    installedApps: List<InstalledAppInfo>,
    logs: List<LogEntry>,
    updateInfo: UpdateInfo,
    isBatteryOptimizationIgnored: () -> Boolean,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onLoadApps: () -> Unit,
    onExportBackup: ((String) -> Unit) -> Unit,
    onImportBackup: (String) -> Unit,
    onClearLogs: () -> Unit,
    onCheckUpdates: (String) -> Unit,
    onDownloadUpdate: () -> Unit,
    onRetryInstallUpdate: () -> Unit,
    onOpenVpnSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit
) {
    val context = LocalContext.current
    val look = LocalGlassLook.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var batteryExempted by remember { mutableStateOf(isBatteryOptimizationIgnored()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryExempted = isBatteryOptimizationIgnored()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showSplitPicker by remember { mutableStateOf(false) }
    var showAutoConnect by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var exportedJson by remember { mutableStateOf<String?>(null) }
    var showPortsDialog by remember { mutableStateOf(false) }
    var showSystemKillSwitchDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 112.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                color = look.textPrimary,
                modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()
            )
        }

        // 1. CONNECTION
        item {
            GlassGroup(
                title = stringResource(R.string.settings_cat_connection),
                modifier = Modifier.widthIn(max = 600.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        stringResource(R.string.settings_operation_mode),
                        style = MaterialTheme.typography.labelLarge,
                        color = look.textSecondary
                    )
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
                    Text(
                        stringResource(R.string.settings_tun_engine),
                        style = MaterialTheme.typography.labelLarge,
                        color = look.textSecondary
                    )
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
                GlassSwitchRow(
                    title = stringResource(R.string.settings_prefer_last),
                    subtitle = stringResource(R.string.settings_prefer_last_desc),
                    icon = Icons.Rounded.Speed,
                    checked = settings.preferLastGoodGateway,
                    onCheckedChange = { checked -> onUpdateSettings { it.copy(preferLastGoodGateway = checked) } }
                )
                GlassDivider()
                GlassRow(
                    title = stringResource(R.string.settings_local_ports),
                    subtitle = fmtDigits("SOCKS5 :${settings.localSocksPort} · HTTP :${settings.localHttpPort}"),
                    icon = Icons.Rounded.Dns,
                    onClick = { showPortsDialog = true }
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
            }
        }

        // 2. NETWORK
        item {
            GlassGroup(
                title = stringResource(R.string.settings_cat_network),
                modifier = Modifier.widthIn(max = 600.dp)
            ) {
                GlassRow(
                    title = stringResource(R.string.settings_split_tunnel),
                    subtitle = fmtDigits(stringResource(R.string.settings_apps_count, settings.splitTunnelMode.name, settings.splitTunnelPackages.size)),
                    icon = Icons.Rounded.Apps,
                    onClick = { showSplitPicker = true },
                    modifier = Modifier.testTag("settings_split_tunnel_row")
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
                GlassRow(
                    title = stringResource(R.string.settings_battery_opt),
                    subtitle = stringResource(
                        if (batteryExempted) R.string.settings_battery_exempted
                        else R.string.settings_battery_not_exempted
                    ),
                    icon = Icons.Rounded.BatterySaver,
                    iconTint = if (batteryExempted) look.success else look.warning,
                    onClick = onOpenBatterySettings,
                    modifier = Modifier.testTag("settings_battery_opt_row")
                ) {
                    Icon(
                        imageVector = if (batteryExempted) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = if (batteryExempted) look.success else look.warning
                    )
                }
            }
        }

        // 3. APPEARANCE
        item {
            GlassGroup(
                title = stringResource(R.string.settings_cat_appearance),
                modifier = Modifier.widthIn(max = 600.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
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
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GlassWallpaper.entries.forEach { wp ->
                            SettingsOptionPill(
                                label = wp.name.lowercase().replaceFirstChar(Char::uppercase),
                                selected = settings.glass.wallpaper == wp,
                                onClick = { onUpdateSettings { it.copy(glass = it.glass.copy(wallpaper = wp)) } }
                            )
                        }
                    }

                    Text(stringResource(R.string.settings_connect_control), style = MaterialTheme.typography.labelLarge, color = look.textSecondary)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GlassConnectControl.entries.forEach { ctrl ->
                            SettingsOptionPill(
                                label = ctrl.name.replace("_", " ").lowercase().replaceFirstChar(Char::uppercase),
                                selected = settings.glass.connectControl == ctrl,
                                onClick = { onUpdateSettings { it.copy(glass = it.glass.copy(connectControl = ctrl)) } }
                            )
                        }
                    }

                    Text(stringResource(R.string.settings_accent_tint), style = MaterialTheme.typography.labelLarge, color = look.textSecondary)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
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
                    icon = Icons.Rounded.Tune,
                    checked = settings.glass.motionHighlights,
                    onCheckedChange = { checked -> onUpdateSettings { it.copy(glass = it.glass.copy(motionHighlights = checked)) } }
                )
                GlassDivider()
                GlassSwitchRow(
                    title = stringResource(R.string.settings_reduce_transparency),
                    checked = settings.glass.reduceTransparency,
                    onCheckedChange = { checked -> onUpdateSettings { it.copy(glass = it.glass.copy(reduceTransparency = checked)) } }
                )
                GlassDivider()
                GlassSwitchRow(
                    title = stringResource(R.string.settings_home_speed),
                    icon = Icons.Rounded.Speed,
                    checked = settings.glass.homeSpeed,
                    onCheckedChange = { checked -> onUpdateSettings { it.copy(glass = it.glass.copy(homeSpeed = checked)) } }
                )
                GlassDivider()
                GlassSwitchRow(
                    title = stringResource(R.string.settings_home_timer),
                    icon = Icons.Rounded.Timer,
                    checked = settings.glass.homeTimer,
                    onCheckedChange = { checked -> onUpdateSettings { it.copy(glass = it.glass.copy(homeTimer = checked)) } }
                )
                GlassDivider()
                GlassSwitchRow(
                    title = stringResource(R.string.settings_home_card),
                    checked = settings.glass.homeCard,
                    onCheckedChange = { checked -> onUpdateSettings { it.copy(glass = it.glass.copy(homeCard = checked)) } }
                )
            }
        }

        // 4. PRIVACY (App Lock & Hide from Recents intentionally removed)
        item {
            GlassGroup(
                title = stringResource(R.string.settings_cat_privacy),
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
                    title = stringResource(R.string.settings_view_logs),
                    subtitle = fmtDigits(stringResource(R.string.settings_logs_count, logs.size)),
                    icon = Icons.Rounded.BugReport,
                    onClick = { showLogs = true },
                    modifier = Modifier.testTag("settings_logs_row")
                )
            }
        }

        // 5. UPDATES (GitHub alovemamad/Nova)
        item {
            GlassGroup(
                title = stringResource(R.string.settings_cat_updates),
                modifier = Modifier.widthIn(max = 600.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudDownload,
                                contentDescription = null,
                                tint = look.accent,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.updates_repo_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = look.textPrimary
                                )
                                Text(
                                    text = "${stringResource(R.string.updates_installed_version, fmtDigits(BuildConfig.VERSION_NAME))} · ${stringResource(R.string.updates_repo_sub)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = look.textSecondary
                                )
                            }
                        }
                        if (updateInfo.checking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp,
                                color = look.accent
                            )
                        } else {
                            GlassCapsuleButton(
                                text = stringResource(R.string.updates_check_now),
                                prominent = !updateInfo.updateAvailable,
                                onClick = { onCheckUpdates(BuildConfig.VERSION_NAME) },
                                modifier = Modifier.testTag("settings_check_updates_button")
                            )
                        }
                    }

                    AnimatedVisibility(visible = updateInfo.checking || updateInfo.latestTag != null || updateInfo.error != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassDivider()
                            when {
                                updateInfo.checking -> {
                                    Text(
                                        text = stringResource(R.string.updates_checking),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = look.textSecondary
                                    )
                                }
                                updateInfo.error != null -> {
                                    Text(
                                        text = updateInfo.error,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = look.danger
                                    )
                                }
                                updateInfo.updateAvailable && updateInfo.latestTag != null -> {
                                    GlassBadge(
                                        text = fmtDigits(stringResource(R.string.updates_available, updateInfo.latestTag)),
                                        color = look.accent
                                    )
                                    if (!updateInfo.releaseNotes.isNullOrBlank()) {
                                        Text(
                                            text = updateInfo.releaseNotes,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = look.textSecondary
                                        )
                                    }
                                    if (updateInfo.downloading) {
                                        val pct = (updateInfo.downloadProgress * 100f).toInt().coerceIn(0, 100)
                                        Text(
                                            text = fmtDigits(stringResource(R.string.updates_downloading, pct)),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = look.accent
                                        )
                                        LinearProgressIndicator(
                                            progress = { updateInfo.downloadProgress.coerceIn(0f, 1f) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = look.accent,
                                            trackColor = look.fill2
                                        )
                                    } else {
                                        if (updateInfo.downloadError != null) {
                                            Text(
                                                text = updateInfo.downloadError,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = look.danger
                                            )
                                        }
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            when {
                                                updateInfo.needsInstallPermission && updateInfo.downloadedApkPath != null -> {
                                                    GlassCapsuleButton(
                                                        text = stringResource(R.string.updates_grant_install_perm),
                                                        icon = Icons.Rounded.Security,
                                                        prominent = true,
                                                        tint = look.warning,
                                                        onClick = onRetryInstallUpdate
                                                    )
                                                }
                                                updateInfo.downloadedApkPath != null -> {
                                                    GlassCapsuleButton(
                                                        text = stringResource(R.string.updates_install_now),
                                                        icon = Icons.Rounded.InstallMobile,
                                                        prominent = true,
                                                        onClick = onRetryInstallUpdate
                                                    )
                                                }
                                                !updateInfo.apkDownloadUrl.isNullOrBlank() -> {
                                                    val sizeLabel = updateInfo.apkSizeBytes
                                                        ?.takeIf { it > 0L }
                                                        ?.let { fmtDigits(formatBytes(it)) }
                                                        ?: "APK"
                                                    GlassCapsuleButton(
                                                        text = stringResource(R.string.updates_download_install, sizeLabel),
                                                        icon = Icons.Rounded.Download,
                                                        prominent = true,
                                                        onClick = onDownloadUpdate,
                                                        modifier = Modifier.testTag("settings_download_update_button")
                                                    )
                                                }
                                            }
                                            if (!updateInfo.releaseUrl.isNullOrBlank()) {
                                                GlassCapsuleButton(
                                                    text = stringResource(R.string.updates_open_github),
                                                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                                                    onClick = {
                                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.releaseUrl))
                                                        runCatching { context.startActivity(intent) }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                else -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Rounded.Verified,
                                            contentDescription = null,
                                            tint = look.success,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.updates_up_to_date),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = look.success
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 6. ABOUT
        item {
            GlassGroup(
                title = stringResource(R.string.settings_cat_about),
                modifier = Modifier.widthIn(max = 600.dp)
            ) {
                GlassRow(
                    title = stringResource(R.string.about_version),
                    subtitle = fmtDigits("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"),
                    icon = Icons.Rounded.Info
                )
                GlassDivider()
                GlassRow(
                    title = stringResource(R.string.about_engines),
                    subtitle = stringResource(R.string.about_engines_list),
                    icon = Icons.Rounded.Code
                )
                GlassDivider()
                GlassRow(
                    title = stringResource(R.string.about_github),
                    subtitle = stringResource(R.string.about_github_url),
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GitHubUpdater.REPO_HTML_URL))
                        runCatching { context.startActivity(intent) }
                    }
                )
            }
        }
    }

    if (showSystemKillSwitchDialog) {
        GlassModalDialog(
            onDismissRequest = { showSystemKillSwitchDialog = false },
            title = stringResource(R.string.settings_always_on_dialog_title),
            icon = Icons.Rounded.VpnLock
        ) {
            Text(
                text = stringResource(R.string.settings_always_on_dialog_body),
                style = MaterialTheme.typography.bodyMedium,
                color = look.textSecondary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                GlassCapsuleButton(
                    text = stringResource(R.string.common_close),
                    onClick = { showSystemKillSwitchDialog = false }
                )
                Spacer(modifier = Modifier.width(10.dp))
                GlassCapsuleButton(
                    text = stringResource(R.string.settings_open_vpn_settings),
                    prominent = true,
                    onClick = {
                        showSystemKillSwitchDialog = false
                        onOpenVpnSettings()
                    }
                )
            }
        }
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
}

@Composable
private fun SettingsOptionPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val look = LocalGlassLook.current
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) look.accent.copy(alpha = 0.24f) else look.fill2)
            .border(
                width = 1.dp,
                color = if (selected) look.accent.copy(alpha = 0.65f) else look.separator,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) look.textPrimary else look.textSecondary,
            maxLines = 1
        )
    }
}
