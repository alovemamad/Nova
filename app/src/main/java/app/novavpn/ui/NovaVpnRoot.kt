package app.novavpn.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.novavpn.domain.model.SuggestedFixAction
import app.novavpn.ui.components.AppLockGate
import app.novavpn.ui.glass.GlassNavigationRail
import app.novavpn.ui.glass.GlassTabBar
import app.novavpn.ui.glass.GlassTopEdge
import app.novavpn.ui.glass.GlassWallpaperLayer
import app.novavpn.ui.glass.LocalGlassTiltOffset
import app.novavpn.ui.glass.NovaDestination
import app.novavpn.ui.glass.rememberGlassTilt
import app.novavpn.ui.home.HomeScreen
import app.novavpn.ui.onboarding.OnboardingScreen
import app.novavpn.ui.profiles.ProfilesScreen
import app.novavpn.ui.settings.SettingsScreen
import app.novavpn.ui.stats.StatsScreen
import app.novavpn.ui.theme.NovaVpnTheme

@Composable
fun NovaVpnRoot(viewModel: NovaVpnViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val status by viewModel.runtimeStatus.collectAsStateWithLifecycle()
    val speedHistory by viewModel.speedHistory.collectAsStateWithLifecycle()
    val dailyUsage by viewModel.dailyUsage.collectAsStateWithLifecycle()
    val sessions by viewModel.sessionHistory.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val diagReport by viewModel.diagnosticReport.collectAsStateWithLifecycle()
    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val isUnlocked by viewModel.isUnlocked.collectAsStateWithLifecycle()
    val uiMessage by viewModel.uiMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiMessage) {
        val msg = uiMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeMessage()
        }
    }

    var pendingStartAfterPermission by remember { mutableStateOf(false) }
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && pendingStartAfterPermission) {
            pendingStartAfterPermission = false
            viewModel.toggleConnect()
        } else {
            pendingStartAfterPermission = false
        }
    }

    val handleConnectOrFix = {
        val prep = viewModel.prepareVpnIntent()
        if (prep != null) {
            pendingStartAfterPermission = true
            vpnPermissionLauncher.launch(prep)
        } else {
            viewModel.toggleConnect()
        }
    }

    NovaVpnTheme(settings = settings) {
        val tilt by rememberGlassTilt(settings.glass.motionHighlights)
        CompositionLocalProvider(LocalGlassTiltOffset provides tilt) {
            Box(modifier = Modifier.fillMaxSize()) {
                GlassWallpaperLayer()

                when {
                    settings.appLockEnabled && !isUnlocked -> {
                        AppLockGate(
                            hasPin = settings.appLockPinHash.isNotBlank(),
                            onBiometricUnlocked = { viewModel.unlockWithBiometric() },
                            onVerifyPin = { pin -> viewModel.unlockWithPin(pin) }
                        )
                    }

                    !settings.onboardingCompleted -> {
                        OnboardingScreen(
                            prepareVpnIntent = { viewModel.prepareVpnIntent() },
                            onOpenBatterySettings = { viewModel.openBatteryOptimizationSettings() },
                            onOpenVpnSettings = { viewModel.openVpnSystemSettings() },
                            onFinish = { viewModel.completeOnboarding() }
                        )
                    }

                    else -> {
                        val navController = rememberNavController()
                        val backStack by navController.currentBackStackEntryAsState()
                        val currentDest: NovaDestination = when {
                            backStack?.destination?.hasRoute<NovaDestination.Profiles>() == true -> NovaDestination.Profiles
                            backStack?.destination?.hasRoute<NovaDestination.Stats>() == true -> NovaDestination.Stats
                            backStack?.destination?.hasRoute<NovaDestination.Settings>() == true -> NovaDestination.Settings
                            else -> NovaDestination.Home
                        }

                        val navigateTo: (NovaDestination) -> Unit = { target ->
                            navController.navigate(target) {
                                popUpTo<NovaDestination.Home> { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
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
                                        NavHost(
                                            navController = navController,
                                            startDestination = NovaDestination.Home
                                        ) {
                                            composable<NovaDestination.Home> {
                                                HomeScreen(
                                                    status = status,
                                                    speedHistory = speedHistory,
                                                    settings = settings,
                                                    activeProfile = activeProfile,
                                                    onToggleConnect = handleConnectOrFix,
                                                    onFreshScanReconnect = { viewModel.connectWithFreshScan() },
                                                    onPauseResume = { viewModel.pauseOrResume() },
                                                    onSelectProfileClick = { navigateTo(NovaDestination.Profiles) },
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
                                                    onDismissError = { viewModel.clearVpnError() }
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
                                                    onNotify = { msg -> viewModel.postMessage(msg) }
                                                )
                                            }
                                            composable<NovaDestination.Stats> {
                                                StatsScreen(
                                                    dailyUsage = dailyUsage,
                                                    sessions = sessions,
                                                    diagnosticReport = diagReport,
                                                    onRunDiagnostics = { viewModel.runDiagnostics() },
                                                    onApplySafeFix = { viewModel.applySafeConnectionFix() },
                                                    onClearStats = { viewModel.clearUsageStats() }
                                                )
                                            }
                                            composable<NovaDestination.Settings> {
                                                SettingsScreen(
                                                    settings = settings,
                                                    installedApps = installedApps,
                                                    logs = logs,
                                                    updateInfo = updateInfo,
                                                    onUpdateSettings = { t -> viewModel.updateSettings(t) },
                                                    onLoadApps = { viewModel.loadInstalledApps() },
                                                    onEnableAppLock = { pin -> viewModel.enableAppLock(pin) },
                                                    onDisableAppLock = { viewModel.disableAppLock() },
                                                    onExportBackup = { cb -> viewModel.exportBackupJson(cb) },
                                                    onImportBackup = { raw -> viewModel.importBackupJson(raw) },
                                                    onClearLogs = { viewModel.clearLogs() },
                                                    onCheckUpdates = { ver -> viewModel.checkForUpdates(ver) },
                                                    onOpenVpnSettings = { viewModel.openVpnSystemSettings() },
                                                    onOpenBatterySettings = { viewModel.openBatteryOptimizationSettings() }
                                                )
                                            }
                                        }

                                        GlassTopEdge(modifier = Modifier.align(Alignment.TopCenter))

                                        if (!useRail) {
                                            GlassTabBar(
                                                current = currentDest,
                                                onSelect = navigateTo,
                                                modifier = Modifier.align(Alignment.BottomCenter)
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
