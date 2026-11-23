package app.novavpn.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.novavpn.domain.model.DataUsageDay
import app.novavpn.domain.model.SessionRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

private val Context.statsDataStore: DataStore<Preferences> by preferencesDataStore(name = "nova_vpn_stats")

class StatsRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    private object Keys {
        val DAILY_USAGE = stringPreferencesKey("daily_usage_json")
        val SESSIONS = stringPreferencesKey("sessions_json")
    }

    val dailyUsageFlow: Flow<List<DataUsageDay>> = context.statsDataStore.data.map { prefs ->
        val raw = prefs[Keys.DAILY_USAGE] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<DataUsageDay>>(raw) }.getOrDefault(emptyList())
    }

    val sessionsFlow: Flow<List<SessionRecord>> = context.statsDataStore.data.map { prefs ->
        val raw = prefs[Keys.SESSIONS] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<SessionRecord>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun recordSession(session: SessionRecord) {
        if (session.downloadBytes <= 0 && session.uploadBytes <= 0 && session.durationSec < 2) return
        mutex.withLock {
            context.statsDataStore.edit { prefs ->
                val todayEpoch = LocalDate.now().toEpochDay()
                val days = runCatching {
                    prefs[Keys.DAILY_USAGE]?.let { json.decodeFromString<List<DataUsageDay>>(it) }
                }.getOrNull().orEmpty().toMutableList()

                val idx = days.indexOfFirst { it.dayEpoch == todayEpoch }
                if (idx >= 0) {
                    val prev = days[idx]
                    days[idx] = prev.copy(
                        downloadBytes = prev.downloadBytes + session.downloadBytes,
                        uploadBytes = prev.uploadBytes + session.uploadBytes
                    )
                } else {
                    days.add(DataUsageDay(todayEpoch, session.downloadBytes, session.uploadBytes))
                }
                val trimmedDays = days.sortedBy { it.dayEpoch }.takeLast(90)
                prefs[Keys.DAILY_USAGE] = json.encodeToString(trimmedDays)

                val sessions = runCatching {
                    prefs[Keys.SESSIONS]?.let { json.decodeFromString<List<SessionRecord>>(it) }
                }.getOrNull().orEmpty().toMutableList()
                sessions.add(0, session)
                prefs[Keys.SESSIONS] = json.encodeToString(sessions.take(100))
            }
        }
    }

    suspend fun clearAll() {
        mutex.withLock {
            context.statsDataStore.edit { it.clear() }
        }
    }
}
