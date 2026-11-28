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
import app.novavpn.core.DevicePlatform
import app.novavpn.core.net.GitHubUpdater
import app.novavpn.core.net.LanShareHelper
import app.novavpn.core.net.SharedHttpClient
import app.novavpn.data.log.LogRepository
import app.novavpn.data.store.GeoDiagnosticsState
import app.novavpn.data.store.GeoResourceRepository
import app.novavpn.data.store.ProfileShareCodec
import app.novavpn.data.store.SettingsMigrator
import com.example.R
import okhttp3.Request
import java.io.ByteArrayOutputStream
import app.novavpn.domain.model.AetherProtocol
import app.novavpn.domain.model.AppSettings
import app.novavpn.domain.model.DataUsageDay
import app.novavpn.domain.model.DiagnosticReport
import app.novavpn.domain.model.GeoResourceItem
import app.novavpn.domain.model.InstalledAppInfo
import app.novavpn.domain.model.LogEntry
import app.novavpn.domain.model.RouteChain
import app.novavpn.domain.model.SessionRecord
import app.novavpn.domain.model.TunEnginePreference
import app.novavpn.domain.model.UpdateInfo
import app.novavpn.domain.model.VpnMode
import app.novavpn.domain.model.VpnProfile
import app.novavpn.domain.model.VpnRuntimeStatus
import app.novavpn.domain.model.VpnState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class NovaVpnViewModel(application: Application) : AndroidViewModel(application) {
    private val appCtx = application.applicationContext
    private val settingsRepo = ServiceLocator.settingsRepository
    private val statsRepo = ServiceLocator.statsRepository
    private val geoResourceRepo = ServiceLocator.geoResourceRepository
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
    val testingTelemetry: StateFlow<Boolean> = vpnController.testingTelemetry
    val telemetryUpdatedAtMs: StateFlow<Long> = vpnController.telemetryUpdatedAtMs
    val dailyUsage: StateFlow<List<DataUsageDay>> = statsRepo.dailyUsageFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val sessionHistory: StateFlow<List<SessionRecord>> = statsRepo.sessionsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val logs: StateFlow<List<LogEntry>> = LogRepository.logs

    val geoResources: StateFlow<List<GeoResourceItem>> = geoResourceRepo.resources
    val isUpdatingGeoResources: StateFlow<Boolean> = geoResourceRepo.isUpdatingAll
    val geoDiagnostics: StateFlow<GeoDiagnosticsState> = combine(
        geoResources,
        profiles,
        activeProfile
    ) { _, profileList, active ->
        geoResourceRepo.inspectGeoDiagnostics(profileList, active)
    }.stateIn(viewModelScope, SharingStarted.Lazily, GeoDiagnosticsState())
    val unresolvedGeoTags: StateFlow<List<String>> = geoDiagnostics
        .map { it.unresolvedTags }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _geoActionError = MutableStateFlow<String?>(null)
    val geoActionError: StateFlow<String?> = _geoActionError.asStateFlow()

    private val _diagnosticReport = MutableStateFlow(DiagnosticReport())
    val diagnosticReport: StateFlow<DiagnosticReport> = _diagnosticReport.asStateFlow()

    private val _updateInfo = MutableStateFlow(UpdateInfo())
    val updateInfo: StateFlow<UpdateInfo> = _updateInfo.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _lanAddresses = MutableStateFlow<List<LanShareHelper.Addr>>(emptyList())
    val lanAddresses: StateFlow<List<LanShareHelper.Addr>> = _lanAddresses.asStateFlow()

    private val _batteryOptimizationIgnored = MutableStateFlow(false)
    val batteryOptimizationIgnored: StateFlow<Boolean> = _batteryOptimizationIgnored.asStateFlow()

    private val _uiMessage = MutableStateFlow<String?>(null)
    val uiMessage: StateFlow<String?> = _uiMessage.asStateFlow()

    private val _pendingDeepLinkProfile = MutableStateFlow<VpnProfile?>(null)
    val pendingDeepLinkProfile: StateFlow<VpnProfile?> = _pendingDeepLinkProfile.asStateFlow()

    private val appsLoading = AtomicBoolean(false)
    private val updateCheckInFlight = AtomicBoolean(false)
    private val updateDownloadInFlight = AtomicBoolean(false)
    private val autoUpdateCheckedThisLaunch = AtomicBoolean(false)

    private val startupExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        LogRepository.e(
            "NovaVpnViewModel",
            "Startup coroutine error: ${throwable::class.java.simpleName}: ${throwable.message}"
        )
    }

    init {
        viewModelScope.launch(startupExceptionHandler) {
            try {
                vpnController.checkAutoConnectRules()
            } catch (t: Throwable) {
                LogRepository.e("NovaVpnViewModel", "checkAutoConnectRules failed: ${t.message}")
            }
            refreshBatteryOptimizationState()
        }
    }

    fun consumeMessage() {
        _uiMessage.value = null
    }

    fun postMessage(msg: String) {
        _uiMessage.value = msg
    }

    fun clearGeoActionError() {
        _geoActionError.value = null
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

    fun refreshTelemetry() {
        vpnController.refreshTelemetry()
    }

    fun refreshLanAddresses() {
        viewModelScope.launch(Dispatchers.IO) {
            _lanAddresses.value = LanShareHelper.addresses()
        }
    }

    fun refreshBatteryOptimizationState() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = appCtx.getSystemService(PowerManager::class.java)
            _batteryOptimizationIgnored.value = pm?.let {
                runCatching { it.isIgnoringBatteryOptimizations(appCtx.packageName) }.getOrDefault(false)
            } ?: false
        }
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
            if (current.isCountryIndependent) {
                if (normalized == "AUTO") {
                    // Independent Aether connects directly with no country selection required
                    when (runtimeStatus.value.state) {
                        VpnState.CONNECTED, VpnState.CONNECTING, VpnState.RECONNECTING, VpnState.PAUSED ->
                            vpnController.reconnectFreshScan()
                        else -> vpnController.connect(useFreshScan = false)
                    }
                    return@launch
                }
                // Preserve independent Aether unmodified; route explicit country selection to Psiphon preset
                val existingPsiphon = settingsRepo.getProfiles().firstOrNull {
                    !it.isCountryIndependent &&
                        (it.routeChain == RouteChain.AETHER_PSIPHON || it.routeChain == RouteChain.PSIPHON_ONLY)
                }
                val target = (existingPsiphon ?: VpnProfile(
                    id = "preset-aether-psiphon",
                    name = "Aether → Psiphon",
                    routeChain = RouteChain.AETHER_PSIPHON,
                    aetherProtocol = AetherProtocol.WARP_IN_MASQUE,
                    masqueOverH2 = true,
                    requiresCountry = true,
                    mtu = current.mtu
                )).copy(psiphonRegion = normalized, requiresCountry = true)
                settingsRepo.saveProfile(target, setActive = true)
            } else {
                val needsPsiphonSwitch = normalized != "AUTO" &&
                    current.routeChain != RouteChain.AETHER_PSIPHON &&
                    current.routeChain != RouteChain.PSIPHON_ONLY
                val updated = current.copy(
                    psiphonRegion = normalized,
                    routeChain = if (needsPsiphonSwitch) RouteChain.AETHER_PSIPHON else current.routeChain
                )
                settingsRepo.saveProfile(updated, setActive = true)
            }
            when (runtimeStatus.value.state) {
                VpnState.CONNECTED, VpnState.CONNECTING, VpnState.RECONNECTING, VpnState.PAUSED ->
                    vpnController.reconnectFreshScan()
                else -> vpnController.connect(useFreshScan = true)
            }
        }
    }

    fun saveProfile(profile: VpnProfile, setActive: Boolean = false) {
        viewModelScope.launch {
            val normalized = if (profile.id == VpnProfile.PRESET_AETHER_ID) {
                profile.copy(requiresCountry = false, psiphonRegion = "")
            } else if (!profile.requiresCountry) {
                profile.copy(psiphonRegion = "")
            } else {
                profile
            }
            settingsRepo.saveProfile(normalized, setActive)
            _uiMessage.value = "Saved profile: ${normalized.name}"
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
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                _uiMessage.value = "Could not parse profile"
                return@launch
            }
            val payloadResult = if (
                trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("http://", ignoreCase = true)
            ) {
                fetchRemoteProfileText(trimmed)
            } else {
                Result.success(trimmed)
            }
            payloadResult.onSuccess { textPayload ->
                decodeAndSaveImportedProfile(textPayload)
            }.onFailure { err ->
                _uiMessage.value = err.message ?: "Failed to fetch profile URL"
            }
        }
    }

    fun importProfileFromUri(uri: Uri) {
        viewModelScope.launch {
            val readResult = withContext(Dispatchers.IO) {
                runCatching {
                    val maxBytes = 512 * 1024
                    appCtx.contentResolver.openInputStream(uri)?.use { stream ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val read = stream.read(buf)
                            if (read <= 0) break
                            total += read
                            if (total > maxBytes) error("Selected file is too large (max 512 KB)")
                            out.write(buf, 0, read)
                        }
                        out.toByteArray().toString(Charsets.UTF_8)
                    } ?: error("Cannot open selected file")
                }
            }
            readResult.onSuccess { fileContent ->
                decodeAndSaveImportedProfile(fileContent)
            }.onFailure { err ->
                _uiMessage.value = err.message ?: "Could not read profile file"
            }
        }
    }

    private suspend fun fetchRemoteProfileText(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val client = SharedHttpClient.forTimeout(connectMs = 10_000L, readMs = 15_000L)
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body ?: error("Empty response body")
                val maxBytes = 512 * 1024L
                val source = body.source()
                if (source.request(maxBytes + 1)) error("Profile response exceeds 512 KB")
                source.readString(Charsets.UTF_8)
            }
        }
    }

    private suspend fun decodeAndSaveImportedProfile(rawPayload: String) {
        val trimmed = rawPayload.trim()
        val candidate = trimmed
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("novavpn://", ignoreCase = true) || it.startsWith("{") }
            ?: trimmed
        val res = ProfileShareCodec.decodeFromText(candidate)
        res.onSuccess { prof ->
            settingsRepo.saveProfile(prof, setActive = true)
            _uiMessage.value = "Imported profile: ${prof.name}"
        }.onFailure { err ->
            _uiMessage.value = err.message ?: "Could not parse profile"
        }
    }

    fun importProfileFromQrImage(uri: Uri) {
        viewModelScope.launch {
            val decoded = withContext(Dispatchers.IO) {
                decodeQrTextFromImageUri(uri)
            }
            decoded.onSuccess { qrText ->
                importProfileFromText(qrText)
            }.onFailure { err ->
                _uiMessage.value = "QR decode failed: ${err.message}"
            }
        }
    }

    suspend fun exportProfileQrBitmap(profile: VpnProfile): Pair<String, Bitmap?> =
        withContext(Dispatchers.Default) {
            val uri = ProfileShareCodec.encodeToUri(profile)
            uri to ProfileShareCodec.generateQrBitmap(uri)
        }

    // --- Geo-Resource (.dat) Management ---

    fun addGeoResourceFromUri(uri: Uri) {
        viewModelScope.launch {
            _geoActionError.value = null
            geoResourceRepo.importFromUri(uri)
                .onSuccess { item ->
                    _uiMessage.value = "Added geo-resource: ${item.name}"
                }
                .onFailure { err ->
                    _geoActionError.value = err.message ?: "Invalid .dat file"
                }
        }
    }

    fun addGeoResourceFromUrl(url: String, customName: String? = null) {
        viewModelScope.launch {
            _geoActionError.value = null
            geoResourceRepo.addFromUrl(url, customName)
                .onSuccess { item ->
                    _uiMessage.value = "Downloaded geo-resource: ${item.name}"
                }
                .onFailure { err ->
                    _geoActionError.value = err.message ?: "Invalid URL or .dat download failed"
                }
        }
    }

    fun importGeoResourceFromQrImage(uri: Uri) {
        viewModelScope.launch {
            _geoActionError.value = null
            val decoded = withContext(Dispatchers.IO) {
                decodeQrTextFromImageUri(uri)
            }
            decoded.onSuccess { qrText ->
                val validUrl = GeoResourceRepository.validateUrl(qrText)
                validUrl.onSuccess { cleanUrl ->
                    geoResourceRepo.addFromUrl(cleanUrl)
                        .onSuccess { item ->
                            _uiMessage.value = "Imported geo-resource from QR: ${item.name}"
                        }
                        .onFailure { err ->
                            _geoActionError.value = err.message ?: "Failed to download QR resource"
                        }
                }.onFailure { err ->
                    _geoActionError.value = err.message ?: "QR does not contain a valid HTTP/HTTPS URL"
                }
            }.onFailure { err ->
                _geoActionError.value = "QR decode failed: ${err.message}"
            }
        }
    }

    fun refreshGeoResource(id: String) {
        viewModelScope.launch {
            _geoActionError.value = null
            geoResourceRepo.updateSingleUrlResource(id)
                .onSuccess { item ->
                    _uiMessage.value = "Updated ${item.name}"
                }
                .onFailure { err ->
                    _geoActionError.value = err.message ?: "Update failed"
                }
        }
    }

    fun refreshAllGeoResources() {
        viewModelScope.launch {
            _geoActionError.value = null
            val summary = geoResourceRepo.updateAllUrlResources()
            if (summary.skippedDuplicate) return@launch
            when {
                summary.totalUrlResources == 0 ->
                    _uiMessage.value = "No URL geo-resources to update"
                summary.failedCount == 0 ->
                    _uiMessage.value = "Updated ${summary.updatedCount} geo-resource(s)"
                else ->
                    _geoActionError.value = "Updated ${summary.updatedCount}, failed ${summary.failedCount} (previous healthy files preserved)"
            }
        }
    }

    fun deleteGeoResource(id: String) {
        viewModelScope.launch {
            _geoActionError.value = null
            geoResourceRepo.deleteResource(id)
        }
    }

    fun applySuggestedIranGeoRules() {
        viewModelScope.launch {
            val current = settingsRepo.getActiveProfile()
            val updated = GeoResourceRepository.applyIranDatSuggestionToProfile(current)
            if (updated != current) {
                settingsRepo.saveProfile(updated, setActive = true)
                _uiMessage.value = "Updated ${updated.name} rules for iran.dat (geosite:other, geosite:ir, geosite:ads)"
            }
        }
    }

    private fun decodeQrTextFromImageUri(uri: Uri): Result<String> = runCatching {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appCtx.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOpts)
        } ?: error("Cannot open image")

        val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
        var sampleSize = 1
        while (maxDim / sampleSize > 1024) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bmp = appCtx.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: error("Could not decode image")

        try {
            ProfileShareCodec.decodeQrFromBitmap(bmp).getOrThrow()
        } finally {
            bmp.recycle()
        }
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
                it.copy(tunEnginePreference = TunEnginePreference.HEV_SOCKS5_ONLY)
            }
            vpnController.clearError()
            _uiMessage.value = "Switched TUN engine to hev-socks5-tunnel"
        }
    }

    fun loadInstalledApps() {
        if (_installedApps.value.isNotEmpty()) return
        if (!appsLoading.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
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
            } finally {
                appsLoading.set(false)
            }
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
        if (!updateCheckInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                _updateInfo.value = UpdateInfo(checking = true)
                val info = diagnosticsEngine.checkForUpdates(
                    currentVersion = currentVersion,
                    isTv = DevicePlatform.isTv(appCtx)
                )
                settingsRepo.updateSettings {
                    it.copy(lastAutoUpdateCheckMs = System.currentTimeMillis())
                }
                _updateInfo.value = info
            } finally {
                updateCheckInFlight.set(false)
            }
        }
    }

    fun maybeAutoCheckUpdates3Days(currentVersion: String) {
        if (!autoUpdateCheckedThisLaunch.compareAndSet(false, true)) return
        if (!updateCheckInFlight.compareAndSet(false, true)) return
        viewModelScope.launch(startupExceptionHandler) {
            try {
                val s = settingsRepo.getSettings()
                val now = System.currentTimeMillis()
                val intervalMs = 3L * 24 * 60 * 60 * 1000L
                if (now - s.lastAutoUpdateCheckMs < intervalMs) {
                    return@launch
                }
                settingsRepo.updateSettings { it.copy(lastAutoUpdateCheckMs = now) }
                val info = diagnosticsEngine.checkForUpdates(
                    currentVersion = currentVersion,
                    isTv = DevicePlatform.isTv(appCtx)
                )
                val snoozedForThisTag = info.latestTag != null &&
                    info.latestTag == s.snoozedUpdateTag &&
                    now < s.snoozedUpdateUntilMs
                if (info.updateAvailable && info.error == null && !snoozedForThisTag) {
                    _updateInfo.value = info.copy(isAutoPrompt = true)
                }
            } finally {
                updateCheckInFlight.set(false)
            }
        }
    }

    fun snoozeUpdatePrompt() {
        val current = _updateInfo.value
        val tag = current.latestTag
        val until = System.currentTimeMillis() + (3L * 24 * 60 * 60 * 1000L)
        _updateInfo.value = current.copy(isAutoPrompt = false)
        viewModelScope.launch {
            settingsRepo.updateSettings {
                it.copy(snoozedUpdateTag = tag, snoozedUpdateUntilMs = until)
            }
        }
    }

    fun dismissUpdatePrompt() {
        _updateInfo.value = _updateInfo.value.copy(isAutoPrompt = false)
    }

    fun downloadAndInstallUpdate() {
        val current = _updateInfo.value
        val url = current.apkDownloadUrl ?: return
        val name = current.apkAssetName ?: "nova-update.apk"
        if (!updateDownloadInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
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
                    expectedSizeBytes = current.apkSizeBytes,
                    expectedSha256 = current.expectedSha256,
                    onProgress = { pct ->
                        _updateInfo.value = _updateInfo.value.copy(downloadProgress = pct)
                    }
                )
                result.onSuccess { file ->
                    val launched = runCatching {
                        GitHubUpdater.launchInstallOrRequestPermission(appCtx, file)
                    }.getOrElse { err ->
                        val releaseLink = current.releaseUrl ?: GitHubUpdater.REPO_HTML_URL
                        val msg = "${err.message ?: "Failed to launch installer"} · $releaseLink"
                        _updateInfo.value = _updateInfo.value.copy(
                            downloading = false,
                            downloadedApkPath = file.absolutePath,
                            downloadError = msg
                        )
                        _uiMessage.value = msg
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
            } finally {
                updateDownloadInFlight.set(false)
            }
        }
    }

    fun retryInstallDownloadedApk() {
        val current = _updateInfo.value
        val path = current.downloadedApkPath ?: return
        val file = File(path)
        if (!file.exists()) {
            _updateInfo.value = _updateInfo.value.copy(downloadedApkPath = null)
            return
        }
        val launched = runCatching {
            GitHubUpdater.launchInstallOrRequestPermission(appCtx, file)
        }.getOrElse { err ->
            val releaseLink = current.releaseUrl ?: GitHubUpdater.REPO_HTML_URL
            val msg = "${err.message ?: "Failed to launch installer"} · $releaseLink"
            _updateInfo.value = _updateInfo.value.copy(downloadError = msg)
            _uiMessage.value = msg
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
        return if (settings.value.vpnMode == VpnMode.VPN) {
            runCatching { VpnService.prepare(appCtx) }.getOrNull()
        } else {
            null
        }
    }

    fun openVpnSystemSettings() {
        val launched = DevicePlatform.safeStartActivity(appCtx, Intent(Settings.ACTION_VPN_SETTINGS))
        if (!launched) {
            _uiMessage.value = appCtx.getString(R.string.msg_system_settings_unavailable)
        }
    }

    fun openBatteryOptimizationSettings() {
        val pkgUri = Uri.parse("package:${appCtx.packageName}")
        val candidates = listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )
        for (candidate in candidates) {
            if (DevicePlatform.safeStartActivity(appCtx, candidate)) return
        }
        _uiMessage.value = appCtx.getString(R.string.msg_battery_settings_unavailable)
    }
}
