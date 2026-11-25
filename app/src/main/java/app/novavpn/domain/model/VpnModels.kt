package app.novavpn.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class VpnState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    PAUSED,
    RECONNECTING,
    ERROR
}

@Serializable
enum class VpnMode {
    VPN,
    PROXY_ONLY
}

@Serializable
enum class RouteChain {
    SMART_AUTO,
    AETHER,
    AETHER_PSIPHON,
    AETHER_TOR,
    PSIPHON_ONLY,
    TOR_ONLY
}

@Serializable
enum class AetherProtocol {
    MASQUE,
    WIREGUARD,
    WARP_IN_MASQUE,
    WARP_IN_WARP,
    MASQUE_IN_MASQUE
}

@Serializable
enum class ObfuscationProfile {
    AUTO,
    NONE,
    QUIC_TLS_CAMO,
    WIREGUARD_NOISE,
    AGGRESSIVE_DPI
}

@Serializable
enum class TunEngineType {
    NONE,
    SING_BOX_LIBBOX,
    HEV_SOCKS5_TUNNEL
}

@Serializable
enum class TunEnginePreference {
    AUTO_SINGBOX_FALLBACK_HEV,
    SINGBOX_ONLY,
    HEV_SOCKS5_ONLY
}

@Serializable
enum class SplitTunnelMode {
    DISABLED,
    EXCLUDE,
    INCLUDE
}

@Serializable
enum class DnsMode {
    DOH,
    DOT,
    PLAIN
}

@Serializable
enum class AppThemeMode {
    SYSTEM,
    DARK,
    LIGHT
}

@Serializable
enum class AppLanguage {
    SYSTEM,
    EN,
    FA
}

@Serializable
enum class DigitMode {
    LOCALE,
    LATIN,
    PERSIAN
}

@Serializable
enum class GlassMaterial {
    CLEAR,
    TINTED
}

@Serializable
enum class GlassWallpaper {
    MIST,
    LAGOON,
    DUSK,
    GRAPHITE,
    PLAIN
}

@Serializable
enum class GlassTint {
    TEAL,
    VIOLET,
    BLUE,
    CYAN,
    MINT,
    INDIGO,
    PURPLE,
    PINK,
    RED,
    ORANGE,
    GREEN,
    BROWN,
    GRAPHITE
}

@Serializable
enum class GlassConnectControl {
    ORB,
    ZED_GLASS,
    BUTTON,
    SWITCH,
    SLIDER
}

@Serializable
data class GlassPrefs(
    val material: GlassMaterial = GlassMaterial.CLEAR,
    val wallpaper: GlassWallpaper = GlassWallpaper.LAGOON,
    val tint: GlassTint = GlassTint.TEAL,
    val connectControl: GlassConnectControl = GlassConnectControl.ORB,
    val glassLists: Boolean = true,
    val shrinkTabBar: Boolean = true,
    val motionHighlights: Boolean = false,
    val reduceTransparency: Boolean = false,
    val homeSpeed: Boolean = true,
    val homeTimer: Boolean = true,
    val homeCard: Boolean = true
)

@Serializable
enum class DropReason {
    NONE,
    USER_DISCONNECTED,
    CORE_EXITED,
    PING_TIMEOUT,
    NETWORK_LOST,
    NETWORK_SWITCHED,
    VPN_REVOKED,
    PORT_CONFLICT,
    TUN_START_FAILED,
    MISSING_BINARY,
    INVALID_CONFIG
}

@Serializable
enum class SuggestedFixAction {
    NONE,
    GRANT_VPN_PERMISSION,
    GRANT_NOTIFICATION_PERMISSION,
    OPEN_BATTERY_SETTINGS,
    OPEN_VPN_SETTINGS,
    RUN_DIAGNOSTICS,
    APPLY_SAFE_FIX,
    SWITCH_TO_HEV_TUN,
    RESET_PORTS,
    FRESH_SCAN
}

@Serializable
data class VpnProfile(
    val id: String,
    val name: String,
    val routeChain: RouteChain = RouteChain.SMART_AUTO,
    val aetherProtocol: AetherProtocol = AetherProtocol.MASQUE,
    val masqueOverH2: Boolean = true,
    val tlsFragment: Boolean = false,
    val customEndpoint: String = "",
    val scanLevel: Int = 2, // 1 = Fast, 2 = Balanced, 3 = Deep
    val obfuscation: ObfuscationProfile = ObfuscationProfile.AUTO,
    val ipv4Enabled: Boolean = true,
    val ipv6Enabled: Boolean = false,
    val mtu: Int = 1280,
    val tcpBufferKb: Int = 64,
    val pingIntervalSec: Int = 15,
    val pingFailureLimit: Int = 3,
    val extraCoreArgs: String = "",
    val psiphonRegion: String = "AUTO",
    val requiresCountry: Boolean = true,
    val torUseBridges: Boolean = false,
    val torBridges: List<String> = emptyList(),
    val dnsMode: DnsMode = DnsMode.DOH,
    val customDnsPrimary: String = "https://cloudflare-dns.com/dns-query",
    val customDnsSecondary: String = "1.1.1.1",
    val upstreamProxyUri: String = "",
    val directRules: String = "10.0.0.0/8\n172.16.0.0/12\n192.168.0.0/16\n*.ir",
    val blockRules: String = "geosite:category-ads-all",
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
    val isCountryIndependent: Boolean
        get() = !requiresCountry || id == PRESET_AETHER_ID

    companion object {
        const val PRESET_AETHER_ID = "preset-aether"
    }
}

@Serializable
data class AutoConnectSettings(
    val startOnBoot: Boolean = false,
    val onUntrustedWifi: Boolean = false,
    val trustedWifiSsids: List<String> = emptyList(),
    val onAppLaunchEnabled: Boolean = false,
    val triggerPackages: List<String> = emptyList()
)

@Serializable
data class AppSettings(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val onboardingCompleted: Boolean = false,
    val activeProfileId: String = DEFAULT_PROFILE_ID,
    val vpnMode: VpnMode = VpnMode.VPN,
    val tunEnginePreference: TunEnginePreference = TunEnginePreference.AUTO_SINGBOX_FALLBACK_HEV,
    val keepTunOnReconnect: Boolean = true,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.DISABLED,
    val splitTunnelPackages: Set<String> = emptySet(),
    val localSocksPort: Int = 24808,
    val localHttpPort: Int = 24809,
    val portsInitialized: Boolean = false,
    val shareProxyOnLan: Boolean = false,
    val preferLastGoodGateway: Boolean = true,
    val autoConnect: AutoConnectSettings = AutoConnectSettings(),
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val dynamicColor: Boolean = false,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val digitMode: DigitMode = DigitMode.LOCALE,
    val glass: GlassPrefs = GlassPrefs(),
    val bestRoutePerNetwork: Map<String, RouteChain> = emptyMap(),
    val lastKnownGoodEndpoint: String = "",
    val wasVpnActiveBeforeKill: Boolean = false
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 5
        const val DEFAULT_PROFILE_ID = "default-smart-auto"
    }
}

data class VpnRuntimeStatus(
    val state: VpnState = VpnState.DISCONNECTED,
    val activeProfileName: String = "Nova Smart",
    val activeRoute: RouteChain = RouteChain.AETHER,
    val activeProtocol: AetherProtocol = AetherProtocol.MASQUE,
    val activeTunEngine: TunEngineType = TunEngineType.NONE,
    val uptimeSeconds: Long = 0L,
    val pingMs: Int? = null,
    val downSpeedBps: Long = 0L,
    val upSpeedBps: Long = 0L,
    val sessionDownBytes: Long = 0L,
    val sessionUpBytes: Long = 0L,
    val exitIp: String? = null,
    val exitCountryCode: String? = null,
    val exitCity: String? = null,
    val lastDropReason: DropReason = DropReason.NONE,
    val errorMessage: String? = null,
    val suggestedFix: SuggestedFixAction = SuggestedFixAction.NONE,
    val reconnectAttempt: Int = 0,
    val smartProbeStatus: String? = null,
    val socksPort: Int = 24808,
    val httpPort: Int? = null,
    val lanShareActive: Boolean = false,
    val sessionProxyUser: String? = null,
    val sessionProxyPass: String? = null
)

@Serializable
data class DataUsageDay(
    val dayEpoch: Long, // epochDay
    val downloadBytes: Long,
    val uploadBytes: Long
)

@Serializable
data class SessionRecord(
    val id: String,
    val profileName: String,
    val routeChain: RouteChain,
    val startedAtMs: Long,
    val durationSec: Long,
    val downloadBytes: Long,
    val uploadBytes: Long,
    val exitCountryCode: String? = null,
    val dropReason: DropReason = DropReason.USER_DISCONNECTED
)

data class EndpointLatencyResult(
    val label: String,
    val host: String,
    val latencyMs: Int?,
    val reachable: Boolean
)

data class DiagnosticReport(
    val running: Boolean = false,
    val exitIp: String? = null,
    val exitCountry: String? = null,
    val exitCity: String? = null,
    val dnsLeakSafe: Boolean? = null,
    val dnsSummary: String = "",
    val ipv6LeakSafe: Boolean? = null,
    val ipv6Summary: String = "",
    val latencies: List<EndpointLatencyResult> = emptyList(),
    val recommendedMtu: Int? = null,
    val mtuSummary: String = "",
    val timestampMs: Long = 0L,
    val safeFixAppliedNote: String? = null
)

data class UpdateInfo(
    val checking: Boolean = false,
    val latestTag: String? = null,
    val releaseUrl: String? = null,
    val releaseNotes: String? = null,
    val updateAvailable: Boolean = false,
    val error: String? = null,
    val apkDownloadUrl: String? = null,
    val apkAssetName: String? = null,
    val apkSizeBytes: Long? = null,
    val downloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadedApkPath: String? = null,
    val downloadError: String? = null,
    val needsInstallPermission: Boolean = false
)

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val isSystem: Boolean
)

data class LogEntry(
    val timestampMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
)

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

@Serializable
data class NovaBackupPayload(
    val version: Int = 1,
    val exportedAtMs: Long = System.currentTimeMillis(),
    val settings: AppSettings,
    val profiles: List<VpnProfile>
)
