package app.novavpn.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.novavpn.domain.model.AppSettings
import app.novavpn.domain.model.DropReason
import app.novavpn.domain.model.SuggestedFixAction
import app.novavpn.domain.model.VpnMode
import app.novavpn.domain.model.VpnProfile
import app.novavpn.domain.model.VpnRuntimeStatus
import app.novavpn.domain.model.VpnState
import app.novavpn.ui.glass.GlassBadge
import app.novavpn.ui.glass.GlassCard
import app.novavpn.ui.glass.GlassCircleButton
import app.novavpn.ui.glass.GlassDivider
import app.novavpn.ui.glass.GlassRow
import app.novavpn.ui.glass.LocalGlassLook
import app.novavpn.ui.glass.NovaConnectControl
import app.novavpn.ui.glass.SpeedSparkline
import app.novavpn.ui.theme.JetBrainsMonoFamily
import app.novavpn.ui.theme.countryCodeToFlag
import app.novavpn.ui.theme.fmtDigits
import app.novavpn.ui.theme.formatBytes
import app.novavpn.ui.theme.formatDurationHms
import app.novavpn.ui.theme.formatSpeedRate
import app.novavpn.ui.theme.tr

@Composable
fun HomeScreen(
    status: VpnRuntimeStatus,
    speedHistory: List<Pair<Long, Long>>,
    settings: AppSettings,
    activeProfile: VpnProfile?,
    onToggleConnect: () -> Unit,
    onFreshScanReconnect: () -> Unit,
    onPauseResume: () -> Unit,
    onSelectProfileClick: () -> Unit,
    onSuggestedFixClick: (SuggestedFixAction) -> Unit,
    onDismissError: () -> Unit
) {
    val look = LocalGlassLook.current

    val stateColor = when (status.state) {
        VpnState.CONNECTED -> look.accent
        VpnState.CONNECTING, VpnState.RECONNECTING -> look.secondaryAccent
        VpnState.PAUSED -> look.warning
        VpnState.ERROR -> look.danger
        VpnState.DISCONNECTED -> look.textSecondary
    }

    val stateLabel = when (status.state) {
        VpnState.CONNECTED -> tr("Connected", "متصل")
        VpnState.CONNECTING -> tr("Connecting…", "در حال اتصال…")
        VpnState.RECONNECTING -> tr("Reconnecting #${status.reconnectAttempt}", "اتصال مجدد #${status.reconnectAttempt}")
        VpnState.PAUSED -> tr("Paused", "متوقف")
        VpnState.ERROR -> tr("Connection Issue", "خطای اتصال")
        VpnState.DISCONNECTED -> tr("Disconnected", "قطع‌شده")
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 112.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header row
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
                        text = tr("Nova VPN", "نوا وی‌پی‌ان"),
                        style = MaterialTheme.typography.headlineMedium,
                        color = look.textPrimary
                    )
                    Text(
                        text = if (settings.vpnMode == VpnMode.VPN) {
                            tr("Full Device Tunnel", "تونل کامل دستگاه")
                        } else {
                            tr("Local Proxy Only (${status.socksPort})", "پروکسی محلی (${status.socksPort})")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = look.textSecondary
                    )
                }
                GlassBadge(
                    text = stateLabel,
                    color = stateColor,
                    modifier = Modifier.testTag("home_status_badge")
                )
            }
        }

        // Smart probe pill if probing
        if (!status.smartProbeStatus.isNullOrBlank()) {
            item {
                GlassBadge(
                    text = status.smartProbeStatus,
                    color = look.secondaryAccent
                )
            }
        }

        // Main Connect Orb / Selected Control
        item {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                NovaConnectControl(
                    controlType = settings.glass.connectControl,
                    vpnState = status.state,
                    onToggle = onToggleConnect
                )

                if (settings.glass.homeTimer && (status.state == VpnState.CONNECTED || status.state == VpnState.PAUSED)) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Timer,
                            contentDescription = null,
                            tint = look.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = fmtDigits(formatDurationHms(status.uptimeSeconds)),
                            style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMonoFamily),
                            color = look.textPrimary
                        )
                    }
                }

                // Pause / Fresh scan action pills when active
                if (status.state == VpnState.CONNECTED || status.state == VpnState.PAUSED) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (settings.vpnMode == VpnMode.VPN) {
                            OutlinedButton(
                                onClick = onPauseResume,
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.testTag("pause_resume_button")
                            ) {
                                Icon(
                                    imageVector = if (status.state == VpnState.PAUSED) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    if (status.state == VpnState.PAUSED) tr("Resume", "ادامه") else tr("Pause", "توقف موقت")
                                )
                            }
                        }
                        GlassCircleButton(
                            icon = Icons.Rounded.Refresh,
                            contentDescription = tr("Fresh Gateway Scan", "اسکن دروازه جدید"),
                            onClick = onFreshScanReconnect,
                            tint = look.accent,
                            modifier = Modifier.testTag("fresh_scan_button")
                        )
                    }
                }
            }
        }

        // Error & actionable suggestion banner
        if (!status.errorMessage.isNullOrBlank()) {
            item {
                GlassCard(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth(),
                    tintOverride = look.danger.copy(alpha = 0.14f)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = look.danger)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = tr("Connection Failure", "خطای اتصال"),
                                style = MaterialTheme.typography.titleMedium,
                                color = look.danger
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = status.errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = look.textPrimary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onDismissError) {
                                Text(tr("Dismiss", "بستن"))
                            }
                            if (status.suggestedFix != SuggestedFixAction.NONE) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = { onSuggestedFixClick(status.suggestedFix) },
                                    colors = ButtonDefaults.buttonColors(containerColor = look.accent, contentColor = Color(0xFF041412))
                                ) {
                                    Icon(Icons.Rounded.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(suggestedFixLabel(status.suggestedFix))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Live Speed & Telemetry Card
        if (settings.glass.homeSpeed) {
            item {
                GlassCard(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.ArrowDownward, contentDescription = null, tint = look.accent, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(tr("Download", "دریافت"), style = MaterialTheme.typography.labelMedium, color = look.textSecondary)
                                }
                                Text(
                                    text = fmtDigits(formatSpeedRate(status.downSpeedBps)),
                                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMonoFamily),
                                    color = look.textPrimary
                                )
                                Text(
                                    text = fmtDigits(formatBytes(status.sessionDownBytes)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = look.textMuted
                                )
                            }

                            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Speed, contentDescription = null, tint = look.textSecondary, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(tr("Ping", "پینگ"), style = MaterialTheme.typography.labelMedium, color = look.textSecondary)
                                }
                                Text(
                                    text = status.pingMs?.let { fmtDigits("${it} ms") } ?: "—",
                                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMonoFamily),
                                    color = look.accent
                                )
                                Text(
                                    text = status.activeTunEngine.name.replace("_", " "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = look.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = look.secondaryAccent, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(tr("Upload", "ارسال"), style = MaterialTheme.typography.labelMedium, color = look.textSecondary)
                                }
                                Text(
                                    text = fmtDigits(formatSpeedRate(status.upSpeedBps)),
                                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMonoFamily),
                                    color = look.textPrimary
                                )
                                Text(
                                    text = fmtDigits(formatBytes(status.sessionUpBytes)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = look.textMuted
                                )
                            }
                        }

                        if (speedHistory.size >= 2) {
                            Spacer(modifier = Modifier.height(10.dp))
                            SpeedSparkline(samples = speedHistory)
                        }
                    }
                }
            }
        }

        // Route & Exit IP Card
        if (settings.glass.homeCard) {
            item {
                GlassCard(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                ) {
                    Column {
                        GlassRow(
                            title = activeProfile?.name ?: status.activeProfileName,
                            subtitle = "${status.activeRoute.name} · ${status.activeProtocol.name}",
                            icon = Icons.Rounded.Route,
                            onClick = onSelectProfileClick,
                            modifier = Modifier.testTag("home_profile_row")
                        ) {
                            Text(
                                text = tr("Switch", "تغییر"),
                                style = MaterialTheme.typography.labelLarge,
                                color = look.accent
                            )
                        }
                        GlassDivider()
                        val exitTitle = if (!status.exitIp.isNullOrBlank()) {
                            "${countryCodeToFlag(status.exitCountryCode)} ${status.exitIp}"
                        } else {
                            tr("Exit IP: Not probed yet", "آی‌پی خروجی: نامشخص")
                        }
                        val exitSub = buildString {
                            status.exitCity?.let { append("$it · ") }
                            if (status.lastDropReason != DropReason.NONE && status.lastDropReason != DropReason.USER_DISCONNECTED) {
                                append(tr("Last drop: ", "آخرین قطع: ") + status.lastDropReason.name)
                            } else {
                                append(
                                    if (status.lanShareActive) "LAN Proxy: 0.0.0.0:${status.socksPort}"
                                    else "SOCKS5 127.0.0.1:${status.socksPort}"
                                )
                            }
                        }
                        GlassRow(
                            title = exitTitle,
                            subtitle = exitSub,
                            icon = Icons.Rounded.Public,
                            iconTint = look.secondaryAccent
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun suggestedFixLabel(action: SuggestedFixAction): String = when (action) {
    SuggestedFixAction.GRANT_VPN_PERMISSION -> tr("Grant Permission", "تایید مجوز")
    SuggestedFixAction.GRANT_NOTIFICATION_PERMISSION -> tr("Allow Alerts", "اجازه اعلان")
    SuggestedFixAction.OPEN_BATTERY_SETTINGS -> tr("Battery Settings", "تنظیمات باتری")
    SuggestedFixAction.OPEN_VPN_SETTINGS -> tr("VPN Settings", "تنظیمات وی‌پی‌ان")
    SuggestedFixAction.RUN_DIAGNOSTICS -> tr("Run Diagnostics", "اجرای عیب‌یابی")
    SuggestedFixAction.APPLY_SAFE_FIX -> tr("Apply Safe Fix", "اعمال تنظیمات امن")
    SuggestedFixAction.SWITCH_TO_HEV_TUN -> tr("Use Fallback TUN", "تغییر موتور TUN")
    SuggestedFixAction.RESET_PORTS -> tr("Reset Ports", "تغییر پورت‌ها")
    SuggestedFixAction.FRESH_SCAN -> tr("Fresh Scan", "اسکن مجدد")
    SuggestedFixAction.NONE -> ""
}
