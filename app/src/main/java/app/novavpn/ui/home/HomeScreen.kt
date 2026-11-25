package app.novavpn.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.novavpn.core.net.LanShareHelper
import app.novavpn.domain.model.AppSettings
import app.novavpn.domain.model.DropReason
import app.novavpn.domain.model.PsiphonRegionInfo
import app.novavpn.domain.model.PsiphonRegions
import app.novavpn.domain.model.RouteChain
import app.novavpn.domain.model.SuggestedFixAction
import app.novavpn.domain.model.VpnMode
import app.novavpn.domain.model.VpnProfile
import app.novavpn.domain.model.VpnRuntimeStatus
import app.novavpn.domain.model.VpnState
import app.novavpn.ui.glass.GlassBadge
import app.novavpn.ui.glass.GlassCapsuleButton
import app.novavpn.ui.glass.GlassCard
import app.novavpn.ui.glass.GlassCircleButton
import app.novavpn.ui.glass.GlassDivider
import app.novavpn.ui.glass.GlassIcons
import app.novavpn.ui.glass.GlassRow
import app.novavpn.ui.glass.GlassSheet
import app.novavpn.ui.glass.LocalGlassLook
import app.novavpn.ui.glass.NovaConnectControl
import app.novavpn.ui.glass.ReportScrollShrink
import app.novavpn.ui.glass.SpeedSparkline
import app.novavpn.ui.glass.glassHighlight
import app.novavpn.ui.glass.glassPress
import app.novavpn.ui.glass.liquidGlass
import app.novavpn.ui.theme.JetBrainsMonoFamily
import app.novavpn.ui.theme.countryCodeToFlag
import app.novavpn.ui.theme.fmtDigits
import app.novavpn.ui.theme.formatBytes
import app.novavpn.ui.theme.formatDurationHms
import app.novavpn.ui.theme.formatSpeedRate
import app.novavpn.ui.theme.isPersianUi
import com.example.R
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    status: VpnRuntimeStatus,
    speedHistory: List<Pair<Long, Long>>,
    settings: AppSettings,
    activeProfile: VpnProfile?,
    lanAddresses: List<LanShareHelper.Addr> = emptyList(),
    testingTelemetry: Boolean = false,
    telemetryUpdatedAtMs: Long = 0L,
    onToggleConnect: () -> Unit,
    onFreshScanReconnect: () -> Unit,
    onRefreshTelemetry: () -> Unit = {},
    onRefreshLanAddresses: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onSwitchVpnMode: (VpnMode) -> Unit = {},
    onPauseResume: () -> Unit,
    onSelectProfileClick: () -> Unit,
    onSelectCountryAndConnect: (String) -> Unit,
    onSuggestedFixClick: (SuggestedFixAction) -> Unit,
    onDismissError: () -> Unit,
    onPostMessage: (String) -> Unit,
    onTabBarShrinkChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val look = LocalGlassLook.current
    val faUi = isPersianUi()
    var showCountrySheet by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    ReportScrollShrink(listState = listState, onShrinkChanged = onTabBarShrinkChanged)

    LaunchedEffect(status.state, status.lanShareActive, status.socksPort) {
        if (status.lanShareActive && (status.state == VpnState.CONNECTED || status.state == VpnState.RECONNECTING)) {
            onRefreshLanAddresses()
        }
    }

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(telemetryUpdatedAtMs) {
        if (telemetryUpdatedAtMs > 0L) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(5_000L)
            }
        }
    }

    val stateColor = when (status.state) {
        VpnState.CONNECTED -> look.accent
        VpnState.CONNECTING, VpnState.RECONNECTING -> look.secondaryAccent
        VpnState.PAUSED -> look.warning
        VpnState.ERROR -> look.danger
        VpnState.DISCONNECTED -> look.textSecondary
    }

    val stateLabel = when (status.state) {
        VpnState.CONNECTED -> stringResource(R.string.status_connected)
        VpnState.CONNECTING -> stringResource(R.string.status_connecting)
        VpnState.RECONNECTING -> stringResource(R.string.status_reconnecting_attempt, status.reconnectAttempt)
        VpnState.PAUSED -> stringResource(R.string.status_paused)
        VpnState.ERROR -> stringResource(R.string.status_error)
        VpnState.DISCONNECTED -> stringResource(R.string.status_disconnected)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 112.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Zone 1: ZedSecure Header (App name + Mode pill + Logs & Refresh circular controls)
        item {
            Row(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = look.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (settings.vpnMode == VpnMode.VPN) {
                            stringResource(R.string.home_full_device_tunnel)
                        } else {
                            fmtDigits(stringResource(R.string.home_local_proxy_only, status.socksPort))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = look.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HeaderModeTogglePill(
                        mode = settings.vpnMode,
                        onSelect = onSwitchVpnMode
                    )
                    GlassCircleButton(
                        icon = GlassIcons.Logs,
                        contentDescription = stringResource(R.string.home_logs),
                        onClick = onOpenLogs,
                        size = 42.dp,
                        tint = look.textPrimary,
                        modifier = Modifier.testTag("home_logs_button")
                    )
                    GlassCircleButton(
                        icon = GlassIcons.Refresh,
                        contentDescription = stringResource(R.string.home_refresh_telemetry),
                        onClick = onRefreshTelemetry,
                        size = 42.dp,
                        tint = if (testingTelemetry) look.accent else look.textPrimary,
                        modifier = Modifier.testTag("home_refresh_telemetry_button")
                    )
                }
            }
        }

        // Zone 2: Hero Connect Control + Status Badge + Uptime + Quick Action Pills
        item {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                NovaConnectControl(
                    controlType = settings.glass.connectControl,
                    vpnState = status.state,
                    onToggle = onToggleConnect
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AnimatedContent(
                        targetState = stateLabel to stateColor,
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                        label = "home_status_badge_anim"
                    ) { (label, color) ->
                        GlassBadge(
                            text = label,
                            color = color,
                            modifier = Modifier.testTag("home_status_badge")
                        )
                    }

                    if (!status.smartProbeStatus.isNullOrBlank()) {
                        GlassBadge(
                            text = status.smartProbeStatus,
                            color = look.secondaryAccent
                        )
                    }
                }

                AnimatedVisibility(
                    visible = status.state == VpnState.CONNECTED,
                    enter = fadeIn(tween(240)) + expandVertically(),
                    exit = fadeOut(tween(180)) + shrinkVertically()
                ) {
                    Text(
                        text = stringResource(R.string.home_connected_tagline),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = look.accent,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(top = 8.dp, start = 16.dp, end = 16.dp)
                            .testTag("home_connected_tagline")
                    )
                }

                AnimatedVisibility(
                    visible = settings.glass.homeTimer && (status.state == VpnState.CONNECTED || status.state == VpnState.PAUSED),
                    enter = fadeIn(tween(240)) + expandVertically(),
                    exit = fadeOut(tween(180)) + shrinkVertically()
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = GlassIcons.Timer,
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
                }

                AnimatedVisibility(
                    visible = status.state == VpnState.CONNECTED || status.state == VpnState.PAUSED,
                    enter = fadeIn(tween(240)) + expandVertically(),
                    exit = fadeOut(tween(180)) + shrinkVertically()
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (settings.vpnMode == VpnMode.VPN) {
                                GlassCapsuleButton(
                                    text = stringResource(
                                        if (status.state == VpnState.PAUSED) R.string.notif_action_resume
                                        else R.string.notif_action_pause
                                    ),
                                    icon = if (status.state == VpnState.PAUSED) GlassIcons.Play else GlassIcons.Pause,
                                    prominent = status.state == VpnState.PAUSED,
                                    onClick = onPauseResume,
                                    modifier = Modifier.testTag("pause_resume_button")
                                )
                            }
                            GlassCircleButton(
                                icon = GlassIcons.Bolt,
                                contentDescription = stringResource(R.string.home_fresh_scan),
                                onClick = onFreshScanReconnect,
                                tint = look.accent,
                                modifier = Modifier.testTag("fresh_scan_button")
                            )
                        }
                    }
                }
            }
        }

        // Pause bypass warning banner
        if (status.state == VpnState.PAUSED) {
            item {
                GlassCard(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                        .testTag("pause_warning_banner"),
                    tintOverride = look.warning.copy(alpha = 0.16f)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(GlassIcons.Warning, contentDescription = null, tint = look.warning, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.home_paused_banner_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = look.warning
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.home_paused_banner_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = look.textPrimary
                            )
                        }
                    }
                }
            }
        }

        // Ephemeral Session Proxy Credentials / Active Endpoints banner
        val user = status.sessionProxyUser
        val pass = status.sessionProxyPass
        val hasCreds = !user.isNullOrBlank() && !pass.isNullOrBlank()
        if ((settings.vpnMode == VpnMode.PROXY_ONLY || status.lanShareActive) &&
            (status.state == VpnState.CONNECTED || status.state == VpnState.RECONNECTING)
        ) {
            item {
                val bindHost = if (status.lanShareActive) "0.0.0.0" else "127.0.0.1"
                val httpPart = status.httpPort?.let { " · HTTP :$it" } ?: ""
                val primaryLanIp = lanAddresses.firstOrNull()?.ip ?: bindHost
                val cardTitle = if (hasCreds) {
                    stringResource(R.string.home_proxy_creds_title)
                } else {
                    stringResource(R.string.home_proxy_endpoints_title)
                }
                val cardSummary = if (hasCreds) {
                    stringResource(
                        R.string.home_proxy_creds_body,
                        status.socksPort,
                        httpPart,
                        user,
                        pass
                    )
                } else {
                    fmtDigits(
                        stringResource(
                            R.string.home_proxy_endpoints_body,
                            bindHost,
                            status.socksPort,
                            httpPart
                        )
                    )
                }
                val copyLabel = if (hasCreds) {
                    stringResource(R.string.home_proxy_creds_copy)
                } else {
                    stringResource(R.string.home_proxy_endpoints_copy)
                }
                val copiedMsg = if (hasCreds) {
                    stringResource(R.string.home_proxy_creds_copied)
                } else {
                    stringResource(R.string.home_proxy_endpoints_copied)
                }
                val clipValue = if (hasCreds) {
                    "$user:$pass"
                } else {
                    "$primaryLanIp:${status.socksPort}" + (status.httpPort?.let { " / $primaryLanIp:$it" } ?: "")
                }

                GlassCard(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                        .testTag("proxy_credentials_card")
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(GlassIcons.Lock, contentDescription = null, tint = look.accent, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = cardTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = look.textPrimary
                                )
                            }
                            TextButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("Nova Proxy", clipValue))
                                onPostMessage(copiedMsg)
                            }) {
                                Icon(GlassIcons.Copy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(copyLabel)
                            }
                        }
                        Text(
                            text = cardSummary,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                            color = look.textSecondary
                        )
                        if (status.lanShareActive) {
                            Spacer(modifier = Modifier.height(8.dp))
                            if (lanAddresses.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.home_lan_share_reachable),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = look.accent
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                lanAddresses.forEach { addr ->
                                    val label = when {
                                        LanShareHelper.isHotspotOrWifi(addr.iface) -> stringResource(R.string.home_lan_wifi_hotspot)
                                        LanShareHelper.isUsbOrEthernet(addr.iface) -> stringResource(R.string.home_lan_usb_eth)
                                        else -> addr.iface
                                    }
                                    val httpSuffix = status.httpPort?.let { " / :$it" } ?: ""
                                    val endpointLine = "$label: ${addr.ip}:${status.socksPort}$httpSuffix"
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                                cm?.setPrimaryClip(ClipData.newPlainText("Nova LAN Proxy", "${addr.ip}:${status.socksPort}"))
                                                onPostMessage(copiedMsg)
                                            }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = fmtDigits(endpointLine),
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                                            color = look.textPrimary
                                        )
                                        Icon(
                                            imageVector = GlassIcons.Copy,
                                            contentDescription = copyLabel,
                                            tint = look.textMuted,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            } else {
                                val hints = LanShareHelper.fallbackHints.joinToString(" · ") { "${it.ip}:${status.socksPort}" }
                                Text(
                                    text = fmtDigits("${stringResource(R.string.home_lan_share_hint)} $hints"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = look.textMuted
                                )
                            }
                        }
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
                            Icon(GlassIcons.Warning, contentDescription = null, tint = look.danger, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.home_connection_failure),
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
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GlassCapsuleButton(
                                text = stringResource(R.string.home_dismiss),
                                onClick = onDismissError
                            )
                            if (status.suggestedFix != SuggestedFixAction.NONE) {
                                Spacer(modifier = Modifier.width(8.dp))
                                GlassCapsuleButton(
                                    text = suggestedFixLabel(status.suggestedFix),
                                    icon = GlassIcons.Wand,
                                    prominent = true,
                                    onClick = { onSuggestedFixClick(status.suggestedFix) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Zone 3A: Unified Live Telemetry & Active Route Card
        if (settings.glass.homeSpeed || settings.glass.homeCard) {
            item {
                GlassCard(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                ) {
                    Column {
                        if (settings.glass.homeCard) {
                            GlassRow(
                                title = activeProfile?.name ?: status.activeProfileName,
                                subtitle = "${status.activeRoute.name.replace("_", " ")} · ${status.activeProtocol.name.replace("_", "-")}",
                                icon = GlassIcons.Routing,
                                iconTint = look.accent,
                                onClick = onSelectProfileClick,
                                modifier = Modifier.testTag("home_profile_row")
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = stringResource(R.string.home_switch),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = look.accent
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = GlassIcons.ChevronRight,
                                        contentDescription = null,
                                        tint = look.textMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        if (settings.glass.homeCard && settings.glass.homeSpeed) {
                            GlassDivider()
                        }

                        if (settings.glass.homeSpeed) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(GlassIcons.Down, contentDescription = null, tint = look.accent, modifier = Modifier.size(15.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(stringResource(R.string.home_download_tunnel), style = MaterialTheme.typography.labelMedium, color = look.textSecondary)
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
                                            Icon(GlassIcons.Speed, contentDescription = null, tint = look.textSecondary, modifier = Modifier.size(15.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(stringResource(R.string.home_ping), style = MaterialTheme.typography.labelMedium, color = look.textSecondary)
                                        }
                                        Text(
                                            text = status.pingMs?.let { fmtDigits("${it} ms") } ?: "—",
                                            style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMonoFamily),
                                            color = look.pingColor(status.pingMs)
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
                                            Icon(GlassIcons.Up, contentDescription = null, tint = look.secondaryAccent, modifier = Modifier.size(15.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(stringResource(R.string.home_upload_tunnel), style = MaterialTheme.typography.labelMedium, color = look.textSecondary)
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

                        if (settings.glass.homeCard) {
                            GlassDivider()
                            val exitTitle = if (!status.exitIp.isNullOrBlank()) {
                                "${countryCodeToFlag(status.exitCountryCode)} ${status.exitIp}"
                            } else {
                                stringResource(R.string.home_exit_ip_unknown)
                            }
                            val lastDropPrefix = if (status.lastDropReason != DropReason.NONE && status.lastDropReason != DropReason.USER_DISCONNECTED) {
                                stringResource(R.string.home_last_drop, status.lastDropReason.name)
                            } else null
                            val exitSub = buildString {
                                status.exitCity?.let { append("$it · ") }
                                if (lastDropPrefix != null) {
                                    append(lastDropPrefix)
                                } else {
                                    append(
                                        if (status.lanShareActive) "LAN Proxy: 0.0.0.0:${status.socksPort}"
                                        else "SOCKS5 127.0.0.1:${status.socksPort}"
                                    )
                                }
                            }
                            val agoText = if (telemetryUpdatedAtMs > 0L) {
                                val diffSec = ((nowMs - telemetryUpdatedAtMs) / 1000L).coerceAtLeast(0L)
                                when {
                                    diffSec < 10L -> stringResource(R.string.home_updated_just_now)
                                    diffSec < 60L -> fmtDigits(stringResource(R.string.home_updated_ago_sec, diffSec.toInt()))
                                    else -> fmtDigits(stringResource(R.string.home_updated_ago_min, (diffSec / 60L).toInt()))
                                }
                            } else null

                            GlassRow(
                                title = exitTitle,
                                subtitle = exitSub,
                                icon = GlassIcons.Globe,
                                iconTint = look.secondaryAccent,
                                onClick = onRefreshTelemetry
                            ) {
                                when {
                                    testingTelemetry -> {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = look.accent
                                        )
                                    }
                                    agoText != null -> {
                                        Text(
                                            text = agoText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = look.textMuted
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Zone 3B: Quick Country Flag Selector — respects country-independent Aether profile
        item {
            val isIndependent = activeProfile?.isCountryIndependent == true
            val currentCode = if (isIndependent) {
                ""
            } else {
                activeProfile?.psiphonRegion?.uppercase()?.ifBlank { "AUTO" } ?: "AUTO"
            }
            val currentRegion = PsiphonRegions.find(currentCode.ifBlank { "AUTO" })
            val routeUsesPsiphon = activeProfile?.routeChain == RouteChain.AETHER_PSIPHON ||
                activeProfile?.routeChain == RouteChain.PSIPHON_ONLY

            GlassCard(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .testTag("home_country_picker_card")
            ) {
                Column(modifier = Modifier.padding(vertical = 14.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isIndependent) {
                                Icon(
                                    imageVector = GlassIcons.Shield,
                                    contentDescription = null,
                                    tint = look.accent,
                                    modifier = Modifier.size(24.dp)
                                )
                            } else {
                                Text(
                                    text = countryCodeToFlag(currentRegion.code),
                                    fontSize = 22.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = if (activeProfile != null && activeProfile.isCountryIndependent) {
                                            activeProfile.name
                                        } else {
                                            stringResource(R.string.home_country_picker_title)
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = look.textPrimary
                                    )
                                    if (isIndependent) {
                                        GlassBadge(
                                            text = stringResource(R.string.profile_independent_badge),
                                            color = look.success
                                        )
                                    }
                                }
                                val regionDisplay = if (faUi) currentRegion.nameFa else currentRegion.nameEn
                                val sub = when {
                                    isIndependent -> stringResource(R.string.home_aether_no_country_desc)
                                    !routeUsesPsiphon && currentCode == "AUTO" ->
                                        "$regionDisplay · ${stringResource(R.string.home_country_picker_sub)}"
                                    !routeUsesPsiphon ->
                                        "$regionDisplay · ${stringResource(R.string.home_country_active_route_hint)}"
                                    else ->
                                        "$regionDisplay · ${stringResource(R.string.home_country_picker_sub)}"
                                }
                                Text(
                                    text = sub,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = look.textSecondary,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        GlassCapsuleButton(
                            text = stringResource(R.string.home_country_all),
                            icon = GlassIcons.Flag,
                            onClick = { showCountrySheet = true },
                            modifier = Modifier.testTag("home_country_all_button")
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(PsiphonRegions.POPULAR, key = { it.code }) { region ->
                            val isSelected = !isIndependent && region.code == currentCode
                            CountryFlagPill(
                                region = region,
                                selected = isSelected,
                                faUi = faUi,
                                onClick = { onSelectCountryAndConnect(region.code) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCountrySheet) {
        val currentCode = if (activeProfile?.isCountryIndependent == true) {
            ""
        } else {
            activeProfile?.psiphonRegion?.uppercase()?.ifBlank { "AUTO" } ?: "AUTO"
        }
        CountryPickerSheet(
            selectedCode = currentCode,
            faUi = faUi,
            onSelectCountry = { code ->
                showCountrySheet = false
                onSelectCountryAndConnect(code)
            },
            onDismiss = { showCountrySheet = false }
        )
    }
}

@Composable
private fun HeaderModeTogglePill(
    mode: VpnMode,
    onSelect: (VpnMode) -> Unit
) {
    val look = LocalGlassLook.current
    val haptic = LocalHapticFeedback.current
    val capsule = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .liquidGlass(shape = capsule, elevated = true)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(
            VpnMode.VPN to R.string.home_mode_vpn_short,
            VpnMode.PROXY_ONLY to R.string.home_mode_proxy_short
        ).forEach { (itemMode, resId) ->
            val selected = mode == itemMode
            Box(
                modifier = Modifier
                    .defaultMinSize(minHeight = 34.dp)
                    .clip(capsule)
                    .background(
                        if (selected) look.accent.copy(alpha = 0.28f)
                        else look.fill2.copy(alpha = 0f)
                    )
                    .clickable(
                        role = Role.Tab,
                        onClick = {
                            if (!selected) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(itemMode)
                            }
                        }
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(resId),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) look.textPrimary else look.textSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun CountryFlagPill(
    region: PsiphonRegionInfo,
    selected: Boolean,
    faUi: Boolean,
    onClick: () -> Unit
) {
    val look = LocalGlassLook.current
    val haptic = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    val bg = if (selected) look.accent.copy(alpha = 0.24f) else look.fill2
    val borderColor = if (selected) look.accent.copy(alpha = 0.7f) else look.separator
    val label = if (region.code == "AUTO") {
        if (faUi) "خودکار" else "Auto"
    } else {
        region.code
    }

    Row(
        modifier = Modifier
            .glassPress(source, scale = 0.94f)
            .defaultMinSize(minHeight = 48.dp)
            .clip(shape)
            .background(bg)
            .glassHighlight(shape, look.highlight, 0.7.dp)
            .border(1.dp, borderColor, shape)
            .clickable(
                interactionSource = source,
                indication = ripple(bounded = true, color = look.accent),
                role = Role.Button,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = countryCodeToFlag(region.code),
            fontSize = 18.sp
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) look.textPrimary else look.textSecondary
        )
    }
}

@Composable
private fun CountryPickerSheet(
    selectedCode: String,
    faUi: Boolean,
    onSelectCountry: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val look = LocalGlassLook.current
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        val q = query.trim()
        if (q.isEmpty()) PsiphonRegions.ALL
        else PsiphonRegions.ALL.filter {
            it.code.contains(q, ignoreCase = true) ||
                it.nameEn.contains(q, ignoreCase = true) ||
                it.nameFa.contains(q, ignoreCase = true)
        }
    }

    GlassSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.home_country_picker_title),
        icon = GlassIcons.Globe
    ) {
        Text(
            text = stringResource(R.string.home_country_picker_sub),
            style = MaterialTheme.typography.bodySmall,
            color = look.textSecondary
        )
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.home_country_search)) },
            leadingIcon = { Icon(GlassIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(10.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered, key = { it.code }) { region ->
                val isSelected = region.code == selectedCode
                val shape = RoundedCornerShape(18.dp)
                val source = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
                        .glassPress(source, scale = 0.98f)
                        .clip(shape)
                        .background(if (isSelected) look.accent.copy(alpha = 0.2f) else look.fill2)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) look.accent.copy(alpha = 0.65f) else look.separator,
                            shape = shape
                        )
                        .clickable(
                            interactionSource = source,
                            indication = ripple(color = look.accent),
                            role = Role.Button,
                            onClick = { onSelectCountry(region.code) }
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = countryCodeToFlag(region.code), fontSize = 24.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (faUi) region.nameFa else region.nameEn,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = look.textPrimary
                            )
                            Text(
                                text = if (faUi) "${region.nameEn} · ${region.code}" else region.code,
                                style = MaterialTheme.typography.bodySmall,
                                color = look.textSecondary
                            )
                        }
                    }
                    if (isSelected) {
                        Icon(
                            imageVector = GlassIcons.CheckCircle,
                            contentDescription = null,
                            tint = look.accent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun suggestedFixLabel(action: SuggestedFixAction): String = when (action) {
    SuggestedFixAction.GRANT_VPN_PERMISSION -> stringResource(R.string.fix_grant_vpn)
    SuggestedFixAction.GRANT_NOTIFICATION_PERMISSION -> stringResource(R.string.fix_allow_alerts)
    SuggestedFixAction.OPEN_BATTERY_SETTINGS -> stringResource(R.string.fix_battery)
    SuggestedFixAction.OPEN_VPN_SETTINGS -> stringResource(R.string.fix_vpn_settings)
    SuggestedFixAction.RUN_DIAGNOSTICS -> stringResource(R.string.fix_diagnostics)
    SuggestedFixAction.APPLY_SAFE_FIX -> stringResource(R.string.fix_apply_safe)
    SuggestedFixAction.SWITCH_TO_HEV_TUN -> stringResource(R.string.fix_hev_tun)
    SuggestedFixAction.RESET_PORTS -> stringResource(R.string.fix_reset_ports)
    SuggestedFixAction.FRESH_SCAN -> stringResource(R.string.fix_fresh_scan)
    SuggestedFixAction.NONE -> ""
}
