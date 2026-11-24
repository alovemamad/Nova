package app.novavpn.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.VpnService
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.novavpn.ServiceLocator
import app.novavpn.core.net.GitHubUpdater
import app.novavpn.data.log.LogRepository
import app.novavpn.data.store.ProfileShareCodec
import app.novavpn.data.store.SettingsMigrator
import app.novavpn.domain.model.AppSettings
import app.novavpn.domain.model.DataUsageDay
import app.novavpn.domain.model.DiagnosticReport
import app.novavpn.domain.model.InstalledAppInfo
import app.novavpn.domain.model.LogEntry
import app.novavpn.domain.model.RouteChain
import app.novavpn.domain.model.SessionRecord
import app.novavpn.domain.model.UpdateInfo
import app.novavpn.domain.model.VpnMode
import app.novavpn.domain.model.VpnProfile
import java.io.File
import app.novavpn.domain.model.VpnRuntimeStatus
import app.novavpn.domain.model.VpnState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NovaVpnViewModel(application: Application) : AndroidViewModel(application) {
    private val appCtx = application.applicationContext
    private val settingsRepo = ServiceLocator.settingsRepository
    private val statsRepo = ServiceLocator.statsRepository
    private val diagnosticsEngine = ServiceLocator.diagnosticsEngine
    private val vpnController = ServiceLocator.vpnController

    val settings: StateFlow<AppSettings> = settingsRepo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val profiles: StateFlow<List<VpnProfile>> = settingsRepo.profilesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeProfile: StateFlow<VpnProfile?> = combine(settings, profiles) { s, list ->
        list.firstOrNull { it.id == s.activeProfileId } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val runtimeStatus: StateFlow<VpnRuntimeStatus> = vpnController.status
    val speedHistory: StateFlow<List<Pair<Long, Long>>> = vpnController.speedHistory
    val dailyUsage: StateFlow<List<DataUsageDay>> = statsRepo.dailyUsageFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val sessionHistory: StateFlow<List<SessionRecord>> = statsRepo.sessionsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val logs: StateFlow<List<LogEntry>> = LogRepository.logs

    private val _diagnosticReport = MutableStateFlow(DiagnosticReport())
    val diagnosticReport: StateFlow<DiagnosticReport> = _diagnosticReport.asStateFlow()

    private val _updateInfo = MutableStateFlow(UpdateInfo())
    val updateInfo: StateFlow<UpdateInfo> = _updateInfo.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _uiMessage = MutableStateFlow<String?>(null)
    val uiMessage: StateFlow<String?> = _uiMessage.asStateFlow()

    private val _pendingDeepLinkProfile = MutableStateFlow<VpnProfile?>(null)
    val pendingDeepLinkProfile: StateFlow<VpnProfile?> = _pendingDeepLinkProfile.asStateFlow()

    private val startupExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        LogRepository.e(
            "NovaVpnViewModel",
            "Startup coroutine error: ${throwable::class.java.simpleName}: ${throwable.message}"
        )
    }

    init {
        viewModelScope.launch(startupExceptionHandler) {
            try {
                settingsRepo.ensurePerInstallRandomPorts()
            } catch (t: Throwable) {
                LogRepository.e("NovaVpnViewModel", "ensurePerInstallRandomPorts failed: ${t.message}")
            }
            try {
                vpnController.checkAutoConnectRules()
            } catch (t: Throwable) {
                LogRepository.e("NovaVpnViewModel", "checkAutoConnectRules failed: ${t.message}")
            }
        }
    }

    fun consumeMessage() {
        _uiMessage.value = null
    }

    fun postMessage(msg: String) {
        _uiMessage.value = msg
    }

    fun handleIncomingProfileUri(rawUri: String) {
        val res = ProfileShareCodec.decodeFromText(rawUri)
        res.onSuccess { prof ->
            _pendingDeepLinkProfile.value = prof
        }.onFailure { err ->
            _uiMessage.value = err.message ?: "Could not parse profile link"
        }
    }

    fun confirmDeepLinkImport() {
        val prof = _pendingDeepLinkProfile.value ?: return
        _pendingDeepLinkProfile.value = null
        viewModelScope.launch {
            settingsRepo.saveProfile(prof, setActive = true)
            _uiMessage.value = "Imported profile: ${prof.name}"
        }
    }

    fun dismissDeepLinkImport() {
        _pendingDeepLinkProfile.value = null
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            settingsRepo.updateSettings { it.copy(onboardingCompleted = true) }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            settingsRepo.updateSettings(transform)
        }
    }

    fun toggleConnect() {
        vpnController.toggleConnect(useFreshScan = false)
    }

    fun connectWithFreshScan() {
        vpnController.reconnectFreshScan()
    }

    fun pauseOrResume() {
        if (runtimeStatus.value.state == VpnState.PAUSED) {
            vpnController.resume()
        } else {
            vpnController.pause()
        }
    }

    fun clearVpnError() {
        vpnController.clearError()
    }

    fun selectProfile(id: String) {
        viewModelScope.launch {
            settingsRepo.updateSettings { it.copy(activeProfileId = id) }
            if (runtimeStatus.value.state == VpnState.CONNECTED) {
                vpnController.reconnectFreshScan()
            }
        }
    }

    fun selectCountryAndConnect(regionCode: String) {
        viewModelScope.launch {
            val normalized = regionCode.trim().uppercase().ifBlank { "AUTO" }
            val current = settingsRepo.getActiveProfile()
            val needsPsiphonSwitch = normalized != "AUTO" &&
                current.routeChain != RouteChain.AETHER_PSIPHON &&
                current.routeChain != RouteChain.PSIPHON_ONLY
            val updated = current.copy(
                psiphonRegion = normalized,
                routeChain = if (needsPsiphonSwitch) RouteChain.AETHER_PSIPHON else current.routeChain
            )
            settingsRepo.saveProfile(updated, setActive = true)
            when (runtimeStatus.value.state) {
                VpnState.CONNECTED, VpnState.CONNECTING, VpnState.RECONNECTING, VpnState.PAUSED ->
                    vpnController.reconnectFreshScan()
                else -> vpnController.connect(useFreshScan = true)
            }
        }
    }

    fun saveProfile(profile: VpnProfile, setActive: Boolean = false) {
        viewModelScope.launch {
            settingsRepo.saveProfile(profile, setActive)
            _uiMessage.value = "Saved profile: ${profile.name}"
        }
    }

    fun duplicateProfile(id: String) {
        viewModelScope.launch {
            settingsRepo.duplicateProfile(id)
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            settingsRepo.deleteProfile(id)
        }
    }

    fun importProfileFromText(raw: String) {
        viewModelScope.launch {
            val res = ProfileShareCodec.decodeFromText(raw)
            res.onSuccess { prof ->
                settingsRepo.saveProfile(prof, setActive = true)
                _uiMessage.value = "Imported profile: ${prof.name}"
            }.onFailure { err ->
                _uiMessage.value = err.message ?: "Could not parse profile"
            }
        }
    }

    fun importProfileFromQrImage(uri: Uri) {
        viewModelScope.launch {
            val decoded = withContext(Dispatchers.IO) {
                runCatching {
                    appCtx.contentResolver.openInputStream(uri)?.use { stream ->
                        val bmp = BitmapFactory.decodeStream(stream) ?: error("Could not decode image")
                        ProfileShareCodec.decodeQrFromBitmap(bmp).getOrThrow()
                    } ?: error("Cannot open image")
                }
            }
            decoded.onSuccess { qrText ->
                importProfileFromText(qrText)
            }.onFailure { err ->
                _uiMessage.value = "QR decode failed: ${err.message}"
            }
        }
    }

    fun exportProfileQrBitmap(profile: VpnProfile): Pair<String, Bitmap?> {
        val uri = ProfileShareCodec.encodeToUri(profile)
        return uri to ProfileShareCodec.generateQrBitmap(uri)
    }

    fun runDiagnostics() {
        viewModelScope.launch {
            _diagnosticReport.value = _diagnosticReport.value.copy(running = true, safeFixAppliedNote = null)
            val prof = settingsRepo.getActiveProfile()
            val status = runtimeStatus.value
            val isConnected = status.state == VpnState.CONNECTED
            val port = if (isConnected) status.socksPort else null
            val auth = if (isConnected) vpnController.currentProxyAuth() else null
            val report = diagnosticsEngine.runFullDiagnostics(prof, port, auth, isConnected)
            _diagnosticReport.value = report
        }
    }

    fun applySafeConnectionFix() {
        viewModelScope.launch {
            val prof = settingsRepo.getActiveProfile()
            val (fixed, note) = diagnosticsEngine.applySafeFix(prof)
            settingsRepo.saveProfile(fixed, setActive = true)
            _diagnosticReport.value = _diagnosticReport.value.copy(safeFixAppliedNote = note)
            _uiMessage.value = note
        }
    }

    fun resetLocalPorts() {
        viewModelScope.launch {
            val (newSocks, newHttp) = SettingsMigrator.generateRandomPorts()
            settingsRepo.updateSettings {
                it.copy(localSocksPort = newSocks, localHttpPort = newHttp, portsInitialized = true)
            }
            vpnController.clearError()
            _uiMessage.value = "Reset local ports to $newSocks / $newHttp"
        }
    }

    fun switchToHevTunFallback() {
        viewModelScope.launch {
            settingsRepo.updateSettings {
                it.copy(tunEnginePreference = app.novavpn.domain.model.TunEnginePreference.HEV_SOCKS5_ONLY)
            }
            vpnController.clearError()
            _uiMessage.value = "Switched TUN engine to hev-socks5-tunnel"
        }
    }

    fun loadInstalledApps() {
        if (_installedApps.value.isNotEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val pm = appCtx.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            val list = resolved
                .mapNotNull { info ->
                    val appInfo = info.activityInfo?.applicationInfo ?: return@mapNotNull null
                    val pkg = appInfo.packageName
                    if (pkg == appCtx.packageName) return@mapNotNull null
                    val isSys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val label = pm.getApplicationLabel(appInfo).toString()
                    InstalledAppInfo(pkg, label, isSys)
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
            _installedApps.value = list
        }
    }

    fun exportBackupJson(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val raw = settingsRepo.exportBackupJson()
            onReady(raw)
        }
    }

    fun importBackupJson(raw: String) {
        viewModelScope.launch {
            settingsRepo.importBackupJson(raw)
                .onSuccess { _uiMessage.value = "Backup restored successfully" }
                .onFailure { _uiMessage.value = "Invalid backup JSON: ${it.message}" }
        }
    }

    fun checkForUpdates(currentVersion: String) {
        viewModelScope.launch {
            _updateInfo.value = UpdateInfo(checking = true)
            _updateInfo.value = diagnosticsEngine.checkForUpdates(currentVersion)
        }
    }

    fun downloadAndInstallUpdate() {
        val current = _updateInfo.value
        val url = current.apkDownloadUrl ?: return
        val name = current.apkAssetName ?: "nova-update.apk"
        if (current.downloading) return
        viewModelScope.launch {
            _updateInfo.value = _updateInfo.value.copy(
                downloading = true,
                downloadProgress = 0f,
                downloadError = null,
                needsInstallPermission = false
            )
            val result = GitHubUpdater.downloadApk(
                context = appCtx,
                downloadUrl = url,
                assetName = name,
                onProgress = { pct ->
                    _updateInfo.value = _updateInfo.value.copy(downloadProgress = pct)
                }
            )
            result.onSuccess { file ->
                val launched = runCatching {
                    GitHubUpdater.launchInstallOrRequestPermission(appCtx, file)
                }.getOrElse { err ->
                    _updateInfo.value = _updateInfo.value.copy(
                        downloading = false,
                        downloadedApkPath = file.absolutePath,
                        downloadError = err.message ?: "Failed to launch installer"
                    )
                    return@launch
                }
                _updateInfo.value = _updateInfo.value.copy(
                    downloading = false,
                    downloadProgress = 1f,
                    downloadedApkPath = file.absolutePath,
                    needsInstallPermission = !launched,
                    downloadError = null
                )
            }.onFailure { err ->
                _updateInfo.value = _updateInfo.value.copy(
                    downloading = false,
                    downloadError = err.message ?: "Download failed"
                )
            }
        }
    }

    fun retryInstallDownloadedApk() {
        val path = _updateInfo.value.downloadedApkPath ?: return
        val file = File(path)
        if (!file.exists()) {
            _updateInfo.value = _updateInfo.value.copy(downloadedApkPath = null)
            return
        }
        val launched = runCatching {
            GitHubUpdater.launchInstallOrRequestPermission(appCtx, file)
        }.getOrElse { err ->
            _updateInfo.value = _updateInfo.value.copy(downloadError = err.message)
            return
        }
        _updateInfo.value = _updateInfo.value.copy(needsInstallPermission = !launched)
    }

    fun clearLogs() {
        LogRepository.clear()
    }

    fun clearUsageStats() {
        viewModelScope.launch {
            statsRepo.clearAll()
            _uiMessage.value = "Usage statistics cleared"
        }
    }

    fun prepareVpnIntent(): Intent? {
        return if (settings.value.vpnMode == VpnMode.VPN) VpnService.prepare(appCtx) else null
    }

    fun openVpnSystemSettings() {
        val intent = Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appCtx.startActivity(intent) }
    }

    fun isBatteryOptimizationIgnored(): Boolean {
        val pm = appCtx.getSystemService(PowerManager::class.java) ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(appCtx.packageName) }.getOrDefault(false)
    }

    fun openBatteryOptimizationSettings() {
        val pkgUri = Uri.parse("package:${appCtx.packageName}")
        val candidates = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )
        for (candidate in candidates) {
            candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val launched = runCatching { appCtx.startActivity(candidate) }.isSuccess
            if (launched) return
        }
    }
}
