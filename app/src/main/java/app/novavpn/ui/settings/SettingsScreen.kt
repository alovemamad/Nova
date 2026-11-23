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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import app.novavpn.ui.theme.tr
import com.example.BuildConfig

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
                text = tr("Settings", "تنظیمات"),
                style = MaterialTheme.typography.headlineMedium,
                color = look.textPrimary,
                modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()
            )
        }

        // TUNNEL & ROUTING
        item {
            GlassGroup(
                title = tr("Tunnel & Network", "تونل و شبکه"),
                modifier = Modifier.widthIn(max = 600.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(tr("Operation Mode", "حالت اجرا"), style = MaterialTheme.typography.labelLarge, color = look.textSecondary)
                    Spacer(modifier = Modifier.height(6.dp))
                    GlassSegmented(
                        options = listOf(
                            VpnMode.VPN to tr("VPN Tunnel", "تونل کامل"),
                            VpnMode.PROXY_ONLY to tr("Proxy Only", "فقط پروکسی")
                        ),
                        selected = settings.vpnMode,
                        onSelect = { mode -> onUpdateSettings { it.copy(vpnMode = mode) } },
                        modifier = Modifier.testTag("settings_mode_segmented")
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(tr("TUN Engine", "موتور TUN"), style = MaterialTheme.typography.labelLarge, color = look.textSecondary)
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
                    title = tr("Split Tunneling", "تونل‌زنی انتخابی برنامه‌ها"),
                    subtitle = "${settings.splitTunnelMode.name} · ${settings.splitTunnelPackages.size} ${tr("apps", "برنامه")}",
                    icon = Icons.Rounded.Apps,
                    onClick = { showSplitPicker = true },
                    modifier = Modifier.testTag("settings_split_tunnel_row")
                )
                GlassDivider()
                GlassRow(
                    title = tr("Local Proxy Ports", "پورت‌های محلی"),
                    subtitle = "SOCKS5 :${settings.localSocksPort} · HTTP :${settings.localHttpPort}",
                    icon = Icons.Rounded.Dns,
                    onClick = { showPortsDialog = true }
                )
                GlassDivider()
                GlassSwitchRow(
                    title = tr("Share Proxy to LAN", "اشتراک پروکسی در شبکه محلی"),
                    subtitle = tr("Warning: binds 0.0.0.0 without auth; use only on trusted Wi-Fi", "هشدار: فقط در شبکه وای‌فای مطمئن فعال کنید"),
                    icon = Icons.Rounded.Lan,
                    checked = settings.shareProxyOnLan,
                    onCheckedChange = { checked -> onUpdateSettings { it.copy(shareProxyOnLan = checked) } }
                )
                GlassDivider()
                GlassSwitchRow(
                    title = tr("Prefer Last Good Gateway", "اولویت با آخرین سرور سالم"),
                    subtitle = tr("Faster connect; disable to run a fresh endpoint scan each time", "اتصال سریع‌تر؛ با غیرفعال کردن هر بار اسکن تازه انجام می‌شود"),
                    icon = Icons.Rounded.Speed,
                    checked = settings.preferLastGoodGateway,
                    onCheckedChange = { checked -> onUpdateSettings { it.copy(preferLastGoodGateway = checked) } }
                )
            }
        }

        // RELIABILITY & AUTOMATION
        item {
            GlassGroup(
                title = tr("Reliability & Automation", "پایداری و اتوماسیون"),
                modifier = Modifier.widthIn(max = 600.dp)
            ) {
                GlassSwitchRow(
                    title = tr("Blocking Kill Switch", "کیل‌سوئیچ مسدودکننده"),
                    subtitle = tr("Blocks non-tunnel traffic inside VpnService", "جلوگیری از نشت ترافیک در زمان قطع تونل"),
                    icon = Icons.Rounded.GppGood,
                    checked = settings.killSwitchBlocking,
                    onCheckedChange = { checked -> onUpdateSettings { it.copy(killSwitchBlocking = checked) } }
                )
                GlassDivider()
                GlassRow(
                    title = tr("Always-on VPN System Settings", "تنظیمات اتصال همیشگی اندروید"),
                    subtitle = tr("Enforce strict system-level Kill Switch", "فعال‌سازی کیل‌سوئیچ سیستمی اندروید"),
                    icon = Icons.Rounded.VpnLock,
                    onClick = onOpenVpnSettings
                )
                GlassDivider()
                GlassRow(
                    title = tr("Auto-Connect Rules", "قوانین اتصال خودکار"),
                    subtitle = buildString {
                        if (settings.autoConnect.startOnBoot) append("Boot · ")
                        if (settings.autoConnect.onUntrustedWifi) append("Untrusted Wi-Fi · ")
                        if (settings.autoConnect.onAppLaunchEnabled) append("${settings.autoConnect.triggerPackages.size} apps")
                        if (isEmpty()) append(tr("Disabled", "غیرفعال"))
                    },
                    icon = Icons.Rounded.AutoMode,
                    onClick = { showAutoConnect = true }
                )
                GlassDivider()
                GlassRow(
                    title = tr("Battery Optimization Exemption", "استثنای بهینه‌سازی باتری"),
                    subtitle = tr("Prevent OS from killing background VPN service", "جلوگیری از بسته شدن تونل در پس‌زمینه"),
                    icon = Icons.Rounded.BatterySaver,
                    onClick = onOpenBatterySettings
                )
            }
        }

        // SECURITY & PRIVACY
        item {
            GlassGroup(
                title = tr("Security & Privacy", "امنیت و حریم خصوصی"),
                modifier = Modifier.widthIn(max = 600.dp)
            ) {
                GlassSwitchRow(
                    title = tr("App Lock (Biometric / PIN)", "قفل برنامه (اثرانگشت / رمز)"),
                    subtitle = if (settings.appLockEnabled) tr("Enabled", "فعال") else tr("Protect access to Nova VPN", "محافظت از دسترسی به برنامه"),
                    icon = Icons.Rounded.Fingerprint,
                    checked = settings.appLockEnabled,
                    onCheckedChange = { checked ->
                        if (checked) showPinSetup = true else onDisableAppLock()
                    },
                    modifier = Modifier.testTag("settings_applock_switch")
                )
                GlassDivider()
                GlassSwitchRow(
                    title = tr("Hide from Recents", "مخفی کردن از برنامه‌های اخیر"),
                    subtitle = tr("Applies FLAG_SECURE screenshot & recents blur protection", "محافظت از پیش‌نمایش صفحه در بخش برنامه‌های اخیر"),
                    icon = Icons.Rounded.VisibilityOff,
                    checked = settings.hideFromRecents,
                    onCheckedChange = { checked -> onUpdateSettings { it.copy(hideFromRecents = checked) } }
                )
            }
        }

        // LIQUID GLASS & APPEARANCE
        item {
            GlassGroup(
                title = tr("Liquid Glass & Language", "ظاهر شیشه‌ای و زبان"),
                modifier = Modifier.widthIn(max = 600.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr("Theme", "تم"), style = MaterialTheme.typography.labelLarge, color = look.textSecondary)
                    GlassSegmented(
                        options = listOf(
                            AppThemeMode.DARK to tr("Dark", "تیره"),
                            AppThemeMode.LIGHT to tr("Light", "روشن"),
                            AppThemeMode.SYSTEM to tr("System", "سیستم")
                        ),
                        selected = settings.themeMode,
                        onSelect = { m -> onUpdateSettings { it.copy(themeMode = m) } }
                    )

                    Text(tr("Language", "زبان"), style = MaterialTheme.typography.labelLarge, color = look.textSecondary)
                    GlassSegmented(
                        options = listOf(
                            AppLanguage.SYSTEM to tr("System", "سیستم"),
                            AppLanguage.EN to "English",
                            AppLanguage.FA to "فارسی"
                        ),
                        selected = settings.language,
                        onSelect = { l -> onUpdateSettings { it.copy(language = l) } },
                        modifier = Modifier.testTag("settings_language_segmented")
                    )

                    Text(tr("Digits", "ارقام"), style = MaterialTheme.typography.labelLarge, color = look.textSecondary)
                    GlassSegmented(
                        options = listOf(
                            DigitMode.LOCALE to tr("Auto", "خودکار"),
                            DigitMode.LATIN to "123",
                            DigitMode.PERSIAN to "۱۲۳"
                        ),
                        selected = settings.digitMode,
                        onSelect = { d -> onUpdateSettings { it.copy(digitMode = d) } }
                    )

                    Text(tr("Glass Material", "جنس شیشه"), style = MaterialTheme.typography.labelLarge, color = look.textSecondary)
                    GlassSegmented(
                        options = listOf(
                            GlassMaterial.CLEAR to tr("Clear", "شفاف"),
                            GlassMaterial.TINTED to tr("Tinted", "رنگی")
                        ),
                        selected = settings.glass.material,
                        onSelect = { mat -> onUpdateSettings { it.copy(glass = it.glass.copy(material = mat)) } }
                    )

                    Text(tr("Wallpaper Aura", "پس‌زمینه"), style = MaterialTheme.typography.labelLarge, color = look.textSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GlassWallpaper.entries.forEach { wp ->
                            FilterChip(
                                selected = settings.glass.wallpaper == wp,
                                onClick = { onUpdateSettings { it.copy(glass = it.glass.copy(wallpaper = wp)) } },
                                label = { Text(wp.name.lowercase().replaceFirstChar(Char::uppercase)) }
                            )
                        }
                    }

                    Text(tr("Connect Control", "دکمه اتصال"), style = MaterialTheme.typography.labelLarge, color = look.textSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GlassConnectControl.entries.forEach { ctrl ->
                            FilterChip(
                                selected = settings.glass.connectControl == ctrl,
                                onClick = { onUpdateSettings { it.copy(glass = it.glass.copy(connectControl = ctrl)) } },
                                label = { Text(ctrl.name.replace("_", " ").lowercase().replaceFirstChar(Char::uppercase)) }
                            )
                        }
                    }

                    Text(tr("Accent Tint", "رنگ تاکیدی"), style = MaterialTheme.typography.labelLarge, color = look.textSecondary)
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
                        title = tr("Dynamic System Colors", "رنگ پویا از تصویر زمینه"),
                        icon = Icons.Rounded.Palette,
                        checked = settings.dynamicColor,
                        onCheckedChange = { checked -> onUpdateSettings { it.copy(dynamicColor = checked) } }
                    )
                }
                GlassDivider()
                GlassSwitchRow(
                    title = tr("Motion Tilt Highlights", "بازتاب نور با حرکت گوشی"),
                    checked = settings.glass.motionHighlights,
                    onCheckedChange = { checked -> onUpdateSettings { it.copy(glass = it.glass.copy(motionHighlights = checked)) } }
                )
                GlassDivider()
                GlassSwitchRow(
                    title = tr("Reduce Transparency", "کاهش شفافیت (عملکرد بالاتر)"),
                    checked = settings.glass.reduceTransparency,
                    onCheckedChange = { checked -> onUpdateSettings { it.copy(glass = it.glass.copy(reduceTransparency = checked)) } }
                )
            }
        }

        // LOGS, BACKUP & UPDATES
        item {
            GlassGroup(
                title = tr("Maintenance", "نگهداری و ابزارها"),
                modifier = Modifier.widthIn(max = 600.dp)
            ) {
                GlassRow(
                    title = tr("View Diagnostic Logs", "مشاهده لاگ‌های سیستم"),
                    subtitle = "${logs.size} ${tr("entries recorded", "خط ثبت شده")}",
                    icon = Icons.Rounded.BugReport,
                    onClick = { showLogs = true },
                    modifier = Modifier.testTag("settings_logs_row")
                )
                GlassDivider()
                GlassRow(
                    title = tr("Backup & Restore (JSON)", "پشتیبان‌گیری و بازیابی"),
                    subtitle = tr("Export or import all profiles and preferences", "خروجی یا بازیابی تمام پروفایل‌ها و تنظیمات"),
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
                    title = tr("Check for Updates", "بررسی نسخه جدید"),
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
                onUpdateSettings { it.copy(localSocksPort = socks, localHttpPort = http) }
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
