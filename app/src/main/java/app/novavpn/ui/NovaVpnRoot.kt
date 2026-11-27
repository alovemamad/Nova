package app.novavpn.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.novavpn.domain.model.SuggestedFixAction
import app.novavpn.domain.model.VpnState
import app.novavpn.ui.glass.GlassCapsuleButton
import app.novavpn.ui.glass.GlassIcons
import app.novavpn.ui.glass.GlassModalDialog
import app.novavpn.ui.glass.GlassNavigationRail
import app.novavpn.ui.glass.GlassTabBar
import app.novavpn.ui.glass.GlassTopEdge
import app.novavpn.ui.glass.GlassWallpaperLayer
import app.novavpn.ui.glass.LocalGlassChrome
import app.novavpn.ui.glass.LocalGlassLook
import app.novavpn.ui.glass.LocalGlassTiltState
import app.novavpn.ui.glass.NovaDestination
import app.novavpn.ui.glass.layerBackdrop
import app.novavpn.ui.glass.rememberGlassChromeBackdrops
import app.novavpn.ui.glass.rememberGlassTilt
import app.novavpn.ui.home.HomeScreen
import app.novavpn.ui.onboarding.OnboardingScreen
import app.novavpn.ui.profiles.ProfilesScreen
import app.novavpn.ui.settings.LogsViewerDialog
import app.novavpn.ui.settings.SettingsScreen
import app.novavpn.ui.stats.StatsScreen
import app.novavpn.ui.theme.NovaVpnTheme
import app.novavpn.ui.theme.fmtDigits
import com.example.BuildConfig
import com.example.R

private fun destinationIndex(route: String?): Int = when {
    route == null -> 0
    route.contains("Profiles") -> 1
    route.contains("Stats") -> 2
    route.contains("Settings") -> 3
    else -> 0
}

@Composable
fun NovaVpnRoot(viewModel: NovaVpnViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val status by viewModel.runtimeStatus.collectAsStateWithLifecycle()
    val speedHistory by viewModel.speedHistory.collectAsStateWithLifecycle()
    val testingTelemetry by viewModel.testingTelemetry.collectAsStateWithLifecycle()
    val telemetryUpdatedAtMs by viewModel.telemetryUpdatedAtMs.collectAsStateWithLifecycle()
    val dailyUsage by viewModel.dailyUsage.collectAsStateWithLifecycle()
    val sessions by viewModel.sessionHistory.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val diagReport by viewModel.diagnosticReport.collectAsStateWithLifecycle()
    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val lanAddresses by viewModel.lanAddresses.collectAsStateWithLifecycle()
    val batteryIgnored by viewModel.batteryOptimizationIgnored.collectAsStateWithLifecycle()
    val geoResources by viewModel.geoResources.collectAsStateWithLifecycle()
    val isUpdatingGeoResources by viewModel.isUpdatingGeoResources.collectAsStateWithLifecycle()
    val unresolvedGeoTags by viewModel.unresolvedGeoTags.collectAsStateWithLifecycle()
    val geoActionError by viewModel.geoActionError.collectAsStateWithLifecycle()
    val uiMessage by viewModel.uiMessage.collectAsStateWithLifecycle()
    val pendingDeepLinkProfile by viewModel.pendingDeepLinkProfile.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiMessage) {
        val msg = uiMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeMessage()
        }
    }

    // Show non-blocking disconnect message when transitioning cleanly from active tunnel to DISCONNECTED
    var previousVpnState by remember { mutableStateOf(status.state) }
    LaunchedEffect(status.state) {
        val prev = previousVpnState
        val curr = status.state
        if (prev != curr) {
            previousVpnState = curr
            val wasActive = prev == VpnState.CONNECTED ||
                prev == VpnState.CONNECTING ||
                prev == VpnState.RECONNECTING ||
                prev == VpnState.PAUSED
            if (wasActive && curr == VpnState.DISCONNECTED) {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.msg_disconnect_exit_taunt),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    var pendingStartAfterPermission by remember { mutableStateOf(false) }
    var pendingRegionConnect by remember { mutableStateOf<String?>(null) }
    var showLogsDialog by remember { mutableStateOf(false) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val region = pendingRegionConnect
        val shouldStart = pendingStartAfterPermission
        pendingRegionConnect = null
        pendingStartAfterPermission = false
        if (res.resultCode == Activity.RESULT_OK) {
            if (region != null) {
                viewModel.selectCountryAndConnect(region)
            } else if (shouldStart) {
                viewModel.toggleConnect()
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.maybeAutoCheckUpdates3Days(BuildConfig.VERSION_NAME)
    }

    val handleConnectOrFix = remember(viewModel, vpnPermissionLauncher) {
        {
            val prep = viewModel.prepareVpnIntent()
            if (prep != null) {
                pendingRegionConnect = null
                pendingStartAfterPermission = true
                vpnPermissionLauncher.launch(prep)
            } else {
                viewModel.toggleConnect()
            }
        }
    }

    val handleSelectCountryAndConnect: (String) -> Unit = remember(viewModel, vpnPermissionLauncher) {
        { regionCode ->
            val prep = viewModel.prepareVpnIntent()
            if (prep != null) {
                pendingRegionConnect = regionCode
                pendingStartAfterPermission = true
                vpnPermissionLauncher.launch(prep)
            } else {
                viewModel.selectCountryAndConnect(regionCode)
            }
        }
    }

    NovaVpnTheme(settings = settings) {
        val look = LocalGlassLook.current
        val chrome = rememberGlassChromeBackdrops()
        val tiltState = rememberGlassTilt(settings.glass.motionHighlights && !look.reduced)

        CompositionLocalProvider(
            LocalGlassChrome provides chrome,
            LocalGlassTiltState provides tiltState
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                GlassWallpaperLayer(backdrop = chrome.wall)

                AnimatedContent(
                    targetState = settings.onboardingCompleted,
                    transitionSpec = {
                        fadeIn(tween(320)) togetherWith fadeOut(tween(240))
                    },
                    label = "root_onboarding_switch"
                ) { completed ->
                    if (!completed) {
                        OnboardingScreen(
                            prepareVpnIntent = { viewModel.prepareVpnIntent() },
                            onOpenBatterySettings = { viewModel.openBatteryOptimizationSettings() },
                            onOpenVpnSettings = { viewModel.openVpnSystemSettings() },
                            onFinish = { viewModel.completeOnboarding() }
                        )
                    } else {
                        val navController = rememberNavController()
                        val backStack by navController.currentBackStackEntryAsState()
                        val currentDest: NovaDestination = when {
                            backStack?.destination?.hasRoute<NovaDestination.Profiles>() == true -> NovaDestination.Profiles
                            backStack?.destination?.hasRoute<NovaDestination.Stats>() == true -> NovaDestination.Stats
                            backStack?.destination?.hasRoute<NovaDestination.Settings>() == true -> NovaDestination.Settings
                            else -> NovaDestination.Home
                        }

                        var tabBarShrunk by remember { mutableStateOf(false) }
                        LaunchedEffect(currentDest, settings.glass.shrinkTabBar) {
                            tabBarShrunk = false
                        }

                        val navigateTo: (NovaDestination) -> Unit = remember(navController) {
                            { target ->
                                navController.navigate(target) {
                                    popUpTo<NovaDestination.Home> { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }

                        BackHandler(enabled = currentDest == NovaDestination.Home) {
                            (context as? Activity)?.finish()
                        }

                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val useRail = maxWidth >= 600.dp

                            Scaffold(
                                containerColor = Color.Transparent,
                                snackbarHost = {
                                    SnackbarHost(
                                        hostState = snackbarHostState,
                                        modifier = Modifier.padding(bottom = if (useRail) 12.dp else 88.dp)
                                    )
                                }
                            ) { _ ->
                                Row(modifier = Modifier.fillMaxSize()) {
                                    if (useRail) {
                                        GlassNavigationRail(
                                            current = currentDest,
                                            onSelect = navigateTo
                                        )
                                    }

                                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .layerBackdrop(chrome.content)
                                        ) {
                                            NavHost(
                                                navController = navController,
                                                startDestination = NovaDestination.Home,
                                                enterTransition = {
                                                    val fromIdx = destinationIndex(initialState.destination.route)
                                                    val toIdx = destinationIndex(targetState.destination.route)
                                                    val dir = if (toIdx >= fromIdx) {
                                                        AnimatedContentTransitionScope.SlideDirection.Start
                                                    } else {
                                                        AnimatedContentTransitionScope.SlideDirection.End
                                                    }
                                                    slideIntoContainer(dir, tween(280)) + fadeIn(tween(260))
                                                },
                                                exitTransition = {
                                                    val fromIdx = destinationIndex(initialState.destination.route)
                                                    val toIdx = destinationIndex(targetState.destination.route)
                                                    val dir = if (toIdx >= fromIdx) {
                                                        AnimatedContentTransitionScope.SlideDirection.Start
                                                    } else {
                                                        AnimatedContentTransitionScope.SlideDirection.End
                                                    }
                                                    slideOutOfContainer(dir, tween(260)) + fadeOut(tween(200))
                                                },
                                                popEnterTransition = {
                                                    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(280)) + fadeIn(tween(260))
                                                },
                                                popExitTransition = {
                                                    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(260)) + fadeOut(tween(200))
                                                }
                                            ) {
                                                composable<NovaDestination.Home> {
                                                    HomeScreen(
                                                        status = status,
                                                        speedHistory = speedHistory,
                                                        settings = settings,
                                                        activeProfile = activeProfile,
                                                        lanAddresses = lanAddresses,
                                                        testingTelemetry = testingTelemetry,
                                                        telemetryUpdatedAtMs = telemetryUpdatedAtMs,
                                                        onToggleConnect = handleConnectOrFix,
                                                        onFreshScanReconnect = { viewModel.connectWithFreshScan() },
                                                        onRefreshTelemetry = { viewModel.refreshTelemetry() },
                                                        onRefreshLanAddresses = { viewModel.refreshLanAddresses() },
                                                        onOpenLogs = { showLogsDialog = true },
                                                        onSwitchVpnMode = { mode ->
                                                            viewModel.updateSettings { it.copy(vpnMode = mode) }
                                                        },
                                                        onPauseResume = { viewModel.pauseOrResume() },
                                                        onSelectProfileClick = { navigateTo(NovaDestination.Profiles) },
                                                        onSelectCountryAndConnect = handleSelectCountryAndConnect,
                                                        onSuggestedFixClick = { action ->
                                                            when (action) {
                                                                SuggestedFixAction.GRANT_VPN_PERMISSION -> handleConnectOrFix()
                                                                SuggestedFixAction.OPEN_BATTERY_SETTINGS -> viewModel.openBatteryOptimizationSettings()
                                                                SuggestedFixAction.OPEN_VPN_SETTINGS -> viewModel.openVpnSystemSettings()
                                                                SuggestedFixAction.RUN_DIAGNOSTICS -> {
                                                                    viewModel.runDiagnostics()
                                                                    navigateTo(NovaDestination.Stats)
                                                                }
                                                                SuggestedFixAction.APPLY_SAFE_FIX -> viewModel.applySafeConnectionFix()
                                                                SuggestedFixAction.SWITCH_TO_HEV_TUN -> viewModel.switchToHevTunFallback()
                                                                SuggestedFixAction.RESET_PORTS -> viewModel.resetLocalPorts()
                                                                SuggestedFixAction.FRESH_SCAN -> viewModel.connectWithFreshScan()
                                                                else -> Unit
                                                            }
                                                        },
                                                        onDismissError = { viewModel.clearVpnError() },
                                                        onPostMessage = { msg -> viewModel.postMessage(msg) },
                                                        onTabBarShrinkChanged = { tabBarShrunk = it }
                                                    )
                                                }
                                                composable<NovaDestination.Profiles> {
                                                    ProfilesScreen(
                                                        profiles = profiles,
                                                        activeProfileId = settings.activeProfileId,
                                                        onSelectProfile = { id -> viewModel.selectProfile(id) },
                                                        onSaveProfile = { prof, setActive -> viewModel.saveProfile(prof, setActive) },
                                                        onDuplicateProfile = { id -> viewModel.duplicateProfile(id) },
                                                        onDeleteProfile = { id -> viewModel.deleteProfile(id) },
                                                        onImportText = { raw -> viewModel.importProfileFromText(raw) },
                                                        onImportQrImage = { uri -> viewModel.importProfileFromQrImage(uri) },
                                                        onGenerateQr = { prof -> viewModel.exportProfileQrBitmap(prof) },
                                                        onNotify = { msg -> viewModel.postMessage(msg) },
                                                        onTabBarShrinkChanged = { tabBarShrunk = it }
                                                    )
                                                }
                                                composable<NovaDestination.Stats> {
                                                    StatsScreen(
                                                        dailyUsage = dailyUsage,
                                                        sessions = sessions,
                                                        diagnosticReport = diagReport,
                                                        onRunDiagnostics = { viewModel.runDiagnostics() },
                                                        onApplySafeFix = { viewModel.applySafeConnectionFix() },
                                                        onClearStats = { viewModel.clearUsageStats() },
                                                        onTabBarShrinkChanged = { tabBarShrunk = it }
                                                    )
                                                }
                                                composable<NovaDestination.Settings> {
                                                    SettingsScreen(
                                                        settings = settings,
                                                        installedApps = installedApps,
                                                        logs = logs,
                                                        updateInfo = updateInfo,
                                                        batteryOptimizationIgnored = batteryIgnored,
                                                        geoResources = geoResources,
                                                        isUpdatingGeoResources = isUpdatingGeoResources,
                                                        unresolvedGeoTags = unresolvedGeoTags,
                                                        geoActionError = geoActionError,
                                                        onRefreshBatteryOptimizationState = { viewModel.refreshBatteryOptimizationState() },
                                                        onUpdateSettings = { t -> viewModel.updateSettings(t) },
                                                        onLoadApps = { viewModel.loadInstalledApps() },
                                                        onExportBackup = { cb -> viewModel.exportBackupJson(cb) },
                                                        onImportBackup = { raw -> viewModel.importBackupJson(raw) },
                                                        onClearLogs = { viewModel.clearLogs() },
                                                        onCheckUpdates = { ver -> viewModel.checkForUpdates(ver) },
                                                        onDownloadUpdate = { viewModel.downloadAndInstallUpdate() },
                                                        onRetryInstallUpdate = { viewModel.retryInstallDownloadedApk() },
                                                        onOpenVpnSettings = { viewModel.openVpnSystemSettings() },
                                                        onOpenBatterySettings = { viewModel.openBatteryOptimizationSettings() },
                                                        onClearGeoError = { viewModel.clearGeoActionError() },
                                                        onAddGeoFromUri = { uri -> viewModel.addGeoResourceFromUri(uri) },
                                                        onAddGeoFromUrl = { url, name -> viewModel.addGeoResourceFromUrl(url, name) },
                                                        onImportGeoFromQr = { uri -> viewModel.importGeoResourceFromQrImage(uri) },
                                                        onUpdateAllGeoResources = { viewModel.refreshAllGeoResources() },
                                                        onRefreshSingleGeoResource = { id -> viewModel.refreshGeoResource(id) },
                                                        onDeleteGeoResource = { id -> viewModel.deleteGeoResource(id) },
                                                        onTabBarShrinkChanged = { tabBarShrunk = it }
                                                    )
                                                }
                                            }
                                        }

                                        GlassTopEdge(
                                            backdrop = chrome.combined,
                                            modifier = Modifier.align(Alignment.TopCenter)
                                        )

                                        if (!useRail) {
                                            GlassTabBar(
                                                current = currentDest,
                                                onSelect = navigateTo,
                                                shrunk = tabBarShrunk && settings.glass.shrinkTabBar,
                                                backdrop = chrome.combined,
                                                modifier = Modifier.align(Alignment.BottomCenter)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (showLogsDialog) {
                            LogsViewerDialog(
                                logs = logs,
                                onClear = { viewModel.clearLogs() },
                                onDismiss = { showLogsDialog = false }
                            )
                        }

                        pendingDeepLinkProfile?.let { incoming ->
                            GlassModalDialog(
                                onDismissRequest = { viewModel.dismissDeepLinkImport() },
                                title = stringResource(R.string.deeplink_import_title),
                                icon = GlassIcons.Download
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.deeplink_import_body,
                                        incoming.name,
                                        incoming.routeChain.name,
                                        incoming.aetherProtocol.name
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = look.textSecondary
                                )
                                Spacer(modifier = Modifier.height(18.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    GlassCapsuleButton(
                                        text = stringResource(R.string.common_cancel),
                                        onClick = { viewModel.dismissDeepLinkImport() }
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    GlassCapsuleButton(
                                        text = stringResource(R.string.deeplink_import_confirm),
                                        onClick = { viewModel.confirmDeepLinkImport() },
                                        prominent = true
                                    )
                                }
                            }
                        }

                        if (updateInfo.isAutoPrompt && updateInfo.updateAvailable) {
                            GlassModalDialog(
                                onDismissRequest = {
                                    if (!updateInfo.downloading) viewModel.snoozeUpdatePrompt()
                                },
                                title = stringResource(R.string.update_modal_title),
                                icon = GlassIcons.Download
                            ) {
                                Text(
                                    text = fmtDigits(
                                        stringResource(
                                            R.string.update_modal_body,
                                            updateInfo.latestTag.orEmpty()
                                        )
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = look.textPrimary
                                )
                                if (!updateInfo.releaseNotes.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = updateInfo.releaseNotes.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = look.textSecondary,
                                        maxLines = 6,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (updateInfo.downloading) {
                                    val pct = (updateInfo.downloadProgress * 100f).toInt().coerceIn(0, 100)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = fmtDigits(stringResource(R.string.updates_downloading, pct)),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = look.accent
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
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
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = updateInfo.downloadError.orEmpty(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = look.danger
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        GlassCapsuleButton(
                                            text = stringResource(R.string.update_modal_later),
                                            onClick = { viewModel.snoozeUpdatePrompt() }
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        when {
                                            updateInfo.needsInstallPermission && updateInfo.downloadedApkPath != null -> {
                                                GlassCapsuleButton(
                                                    text = stringResource(R.string.updates_grant_install_perm),
                                                    icon = GlassIcons.Shield,
                                                    prominent = true,
                                                    tint = look.warning,
                                                    onClick = { viewModel.retryInstallDownloadedApk() }
                                                )
                                            }
                                            updateInfo.downloadedApkPath != null -> {
                                                GlassCapsuleButton(
                                                    text = stringResource(R.string.updates_install_now),
                                                    icon = GlassIcons.Box,
                                                    prominent = true,
                                                    onClick = { viewModel.retryInstallDownloadedApk() }
                                                )
                                            }
                                            !updateInfo.apkDownloadUrl.isNullOrBlank() -> {
                                                GlassCapsuleButton(
                                                    text = stringResource(R.string.update_modal_download),
                                                    icon = GlassIcons.Download,
                                                    prominent = true,
                                                    onClick = { viewModel.downloadAndInstallUpdate() }
                                                )
                                            }
                                            !updateInfo.releaseUrl.isNullOrBlank() -> {
                                                GlassCapsuleButton(
                                                    text = stringResource(R.string.update_modal_download),
                                                    icon = GlassIcons.Link,
                                                    prominent = true,
                                                    onClick = {
                                                        val intent = Intent(
                                                            Intent.ACTION_VIEW,
                                                            Uri.parse(updateInfo.releaseUrl)
                                                        )
                                                        runCatching { context.startActivity(intent) }
                                                        viewModel.dismissUpdatePrompt()
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
