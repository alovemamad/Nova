package app.novavpn.data.store

import app.novavpn.domain.model.AppSettings
import app.novavpn.domain.model.VpnMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMigrationTest {

    @Test
    fun `migrates legacy pre-JSON proxy preferences into v4 schema with random ports`() {
        val migrated = SettingsMigrator.migrateSettingsJson(
            rawJson = null,
            legacyVpnMode = "PROXY",
            legacyWasOn = true
        )
        assertEquals(AppSettings.CURRENT_SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(VpnMode.PROXY_ONLY, migrated.vpnMode)
        assertTrue(migrated.wasVpnActiveBeforeKill)
        assertTrue(migrated.portsInitialized)
        assertTrue(migrated.localSocksPort in 20000..44999)
        assertEquals(migrated.localSocksPort + 1, migrated.localHttpPort)
    }

    @Test
    fun `migrates schema v1 JSON preserving user settings and clamping invalid ports`() {
        val v1Json = """
            {
              "schemaVersion": 1,
              "vpnMode": "VPN",
              "localSocksPort": 80,
              "localHttpPort": 10809,
              "keepTunOnReconnect": false,
              "activeProfileId": "custom-id-1"
            }
        """.trimIndent()

        val migrated = SettingsMigrator.migrateSettingsJson(v1Json, null, false)
        assertEquals(AppSettings.CURRENT_SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(10808, migrated.localSocksPort) // clamped from privileged 80 to 10808
        assertEquals(10809, migrated.localHttpPort)
        assertEquals(false, migrated.keepTunOnReconnect)
        assertTrue(migrated.portsInitialized)
        assertEquals("custom-id-1", migrated.activeProfileId)
    }

    @Test
    fun `generateRandomPorts yields valid distinct unprivileged ports`() {
        repeat(20) {
            val (socks, http) = SettingsMigrator.generateRandomPorts()
            assertTrue(socks in 20000..44999)
            assertEquals(socks + 1, http)
        }
    }

    @Test
    fun `falls back cleanly on corrupted profiles JSON`() {
        val profiles = SettingsMigrator.migrateProfilesJson("{not valid json")
        assertTrue(profiles.isNotEmpty())
        assertEquals(AppSettings.DEFAULT_PROFILE_ID, profiles.first().id)
    }
}
