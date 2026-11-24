package app.novavpn.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.novavpn.domain.model.AetherProtocol
import app.novavpn.domain.model.AppSettings
import app.novavpn.domain.model.NovaBackupPayload
import app.novavpn.domain.model.ObfuscationProfile
import app.novavpn.domain.model.RouteChain
import app.novavpn.domain.model.VpnMode
import app.novavpn.domain.model.VpnProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import java.util.UUID

private val Context.novaDataStore: DataStore<Preferences> by preferencesDataStore(name = "nova_vpn_settings")

object SettingsMigrator {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }
    private val secureRandom = SecureRandom()

    fun generateRandomPorts(): Pair<Int, Int> {
        val socks = 20000 + secureRandom.nextInt(25000)
        return socks to (socks + 1)
    }

    fun migrateSettingsJson(rawJson: String?, legacyVpnMode: String?, legacyWasOn: Boolean): AppSettings {
        if (rawJson.isNullOrBlank()) {
            val mode = when (legacyVpnMode) {
                "PROXY" -> VpnMode.PROXY_ONLY
                else -> VpnMode.VPN
            }
            val (socks, http) = generateRandomPorts()
            return AppSettings(
                schemaVersion = AppSettings.CURRENT_SCHEMA_VERSION,
                vpnMode = mode,
                localSocksPort = socks,
                localHttpPort = http,
                portsInitialized = true,
                wasVpnActiveBeforeKill = legacyWasOn
            )
        }
        return runCatching {
            val obj = json.parseToJsonElement(rawJson) as? JsonObject
            val version = obj?.get("schemaVersion")?.jsonPrimitive?.intOrNull ?: 1
            val decoded = json.decodeFromString<AppSettings>(rawJson)
            if (version < AppSettings.CURRENT_SCHEMA_VERSION) {
                val safeSocks = if (decoded.localSocksPort in 1024..65535) decoded.localSocksPort else 10808
                val safeHttp = if (decoded.localHttpPort in 1024..65535 && decoded.localHttpPort != safeSocks) {
                    decoded.localHttpPort
                } else {
                    safeSocks + 1
                }
                decoded.copy(
                    schemaVersion = AppSettings.CURRENT_SCHEMA_VERSION,
                    localSocksPort = safeSocks,
                    localHttpPort = safeHttp,
                    portsInitialized = true
                )
            } else {
                decoded
            }
        }.getOrElse {
            AppSettings(schemaVersion = AppSettings.CURRENT_SCHEMA_VERSION)
        }
    }

    fun migrateProfilesJson(rawJson: String?): List<VpnProfile> {
        if (rawJson.isNullOrBlank()) return defaultProfiles()
        return runCatching {
            val parsed = json.decodeFromString<List<VpnProfile>>(rawJson)
            parsed.ifEmpty { defaultProfiles() }
        }.getOrElse { defaultProfiles() }
    }

    fun defaultProfiles(): List<VpnProfile> = listOf(
        VpnProfile(
            id = AppSettings.DEFAULT_PROFILE_ID,
            name = "Nova Smart (Auto)",
            routeChain = RouteChain.SMART_AUTO,
            aetherProtocol = AetherProtocol.MASQUE,
            masqueOverH2 = true,
            obfuscation = ObfuscationProfile.AUTO,
            mtu = 1280
        ),
        VpnProfile(
            id = "preset-aether-masque",
            name = "Aether · MASQUE",
            routeChain = RouteChain.AETHER,
            aetherProtocol = AetherProtocol.MASQUE,
            masqueOverH2 = true,
            obfuscation = ObfuscationProfile.QUIC_TLS_CAMO,
            mtu = 1280
        ),
        VpnProfile(
            id = "preset-aether-wg",
            name = "Aether · WireGuard",
            routeChain = RouteChain.AETHER,
            aetherProtocol = AetherProtocol.WIREGUARD,
            obfuscation = ObfuscationProfile.WIREGUARD_NOISE,
            mtu = 1280
        ),
        VpnProfile(
            id = "preset-aether-psiphon",
            name = "Aether → Psiphon",
            routeChain = RouteChain.AETHER_PSIPHON,
            aetherProtocol = AetherProtocol.WARP_IN_MASQUE,
            masqueOverH2 = true,
            psiphonRegion = "AUTO",
            mtu = 1280
        ),
        VpnProfile(
            id = "preset-aether-tor",
            name = "Aether → Tor",
            routeChain = RouteChain.AETHER_TOR,
            aetherProtocol = AetherProtocol.MASQUE,
            masqueOverH2 = true,
            torUseBridges = false,
            mtu = 1280
        )
    )
}

class SettingsRepository(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        coerceInputValues = true
    }
    private val mutex = Mutex()

    private object Keys {
        val SCHEMA_VERSION = intPreferencesKey("schema_version")
        val SETTINGS_JSON = stringPreferencesKey("settings_json")
        val PROFILES_JSON = stringPreferencesKey("profiles_json")
        val WAS_VPN_ACTIVE = booleanPreferencesKey("was_vpn_active")
        val LEGACY_MODE = stringPreferencesKey("vpn_mode")
    }

    val settingsFlow: Flow<AppSettings> = context.novaDataStore.data.map { prefs ->
        SettingsMigrator.migrateSettingsJson(
            rawJson = prefs[Keys.SETTINGS_JSON],
            legacyVpnMode = prefs[Keys.LEGACY_MODE],
            legacyWasOn = prefs[Keys.WAS_VPN_ACTIVE] ?: false
        )
    }

    val profilesFlow: Flow<List<VpnProfile>> = context.novaDataStore.data.map { prefs ->
        SettingsMigrator.migrateProfilesJson(prefs[Keys.PROFILES_JSON])
    }

    suspend fun getSettings(): AppSettings = settingsFlow.first()

    suspend fun getProfiles(): List<VpnProfile> = profilesFlow.first()

    suspend fun getActiveProfile(): VpnProfile {
        val settings = getSettings()
        val profiles = getProfiles()
        return profiles.firstOrNull { it.id == settings.activeProfileId } ?: profiles.first()
    }

    suspend fun ensurePerInstallRandomPorts() {
        updateSettings { current ->
            if (current.portsInitialized) current
            else {
                val (socks, http) = SettingsMigrator.generateRandomPorts()
                current.copy(localSocksPort = socks, localHttpPort = http, portsInitialized = true)
            }
        }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        mutex.withLock {
            context.novaDataStore.edit { prefs ->
                val current = SettingsMigrator.migrateSettingsJson(
                    rawJson = prefs[Keys.SETTINGS_JSON],
                    legacyVpnMode = prefs[Keys.LEGACY_MODE],
                    legacyWasOn = prefs[Keys.WAS_VPN_ACTIVE] ?: false
                )
                val updated = transform(current).copy(schemaVersion = AppSettings.CURRENT_SCHEMA_VERSION)
                prefs[Keys.SCHEMA_VERSION] = AppSettings.CURRENT_SCHEMA_VERSION
                prefs[Keys.SETTINGS_JSON] = json.encodeToString(updated)
                prefs[Keys.WAS_VPN_ACTIVE] = updated.wasVpnActiveBeforeKill
            }
        }
    }

    suspend fun setVpnActiveState(active: Boolean) {
        updateSettings { it.copy(wasVpnActiveBeforeKill = active) }
    }

    suspend fun saveProfile(profile: VpnProfile, setActive: Boolean = false) {
        mutex.withLock {
            context.novaDataStore.edit { prefs ->
                val list = SettingsMigrator.migrateProfilesJson(prefs[Keys.PROFILES_JSON]).toMutableList()
                val idx = list.indexOfFirst { it.id == profile.id }
                val stamped = profile.copy(updatedAtEpochMs = System.currentTimeMillis())
                if (idx >= 0) list[idx] = stamped else list.add(stamped)
                prefs[Keys.PROFILES_JSON] = json.encodeToString(list)
                if (setActive) {
                    val currentSettings = SettingsMigrator.migrateSettingsJson(
                        prefs[Keys.SETTINGS_JSON],
                        prefs[Keys.LEGACY_MODE],
                        prefs[Keys.WAS_VPN_ACTIVE] ?: false
                    )
                    prefs[Keys.SETTINGS_JSON] = json.encodeToString(
                        currentSettings.copy(activeProfileId = stamped.id)
                    )
                }
            }
        }
    }

    suspend fun duplicateProfile(id: String): VpnProfile? {
        val current = getProfiles().firstOrNull { it.id == id } ?: return null
        val copy = current.copy(
            id = UUID.randomUUID().toString(),
            name = "${current.name} (Copy)",
            updatedAtEpochMs = System.currentTimeMillis()
        )
        saveProfile(copy, setActive = false)
        return copy
    }

    suspend fun deleteProfile(id: String) {
        mutex.withLock {
            context.novaDataStore.edit { prefs ->
                val list = SettingsMigrator.migrateProfilesJson(prefs[Keys.PROFILES_JSON]).toMutableList()
                if (list.size <= 1) return@edit
                list.removeAll { it.id == id }
                prefs[Keys.PROFILES_JSON] = json.encodeToString(list)
                val currentSettings = SettingsMigrator.migrateSettingsJson(
                    prefs[Keys.SETTINGS_JSON],
                    prefs[Keys.LEGACY_MODE],
                    prefs[Keys.WAS_VPN_ACTIVE] ?: false
                )
                if (currentSettings.activeProfileId == id) {
                    prefs[Keys.SETTINGS_JSON] = json.encodeToString(
                        currentSettings.copy(activeProfileId = list.first().id)
                    )
                }
            }
        }
    }

    suspend fun exportBackupJson(): String {
        val payload = NovaBackupPayload(
            settings = getSettings(),
            profiles = getProfiles()
        )
        return json.encodeToString(payload)
    }

    suspend fun importBackupJson(raw: String): Result<Unit> = runCatching {
        val payload = json.decodeFromString<NovaBackupPayload>(raw)
        mutex.withLock {
            context.novaDataStore.edit { prefs ->
                val validProfiles = payload.profiles.ifEmpty { SettingsMigrator.defaultProfiles() }
                val activeId = if (validProfiles.any { it.id == payload.settings.activeProfileId }) {
                    payload.settings.activeProfileId
                } else {
                    validProfiles.first().id
                }
                val cleanSettings = payload.settings.copy(
                    schemaVersion = AppSettings.CURRENT_SCHEMA_VERSION,
                    activeProfileId = activeId,
                    wasVpnActiveBeforeKill = false
                )
                prefs[Keys.SCHEMA_VERSION] = AppSettings.CURRENT_SCHEMA_VERSION
                prefs[Keys.SETTINGS_JSON] = json.encodeToString(cleanSettings)
                prefs[Keys.PROFILES_JSON] = json.encodeToString(validProfiles)
            }
        }
    }
}
