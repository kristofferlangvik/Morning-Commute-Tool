package io.github.morningcommutetool.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "commute")

/** Everything the user sets lives here, on the phone only. */
class Store(context: Context) {
    private val ds = context.applicationContext.dataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val kStations = stringPreferencesKey("stations")
    private val kSettings = stringPreferencesKey("settings")
    private val kSnapshot = stringPreferencesKey("snapshot")
    private val kAlerts = stringPreferencesKey("alerts")
    private val kFollow = stringPreferencesKey("follow")

    private val stationsSer = ListSerializer(Station.serializer())
    private val alertsSer = MapSerializer(String.serializer(), AlertState.serializer())

    private fun <T> read(p: Preferences, key: Preferences.Key<String>, ser: KSerializer<T>, default: T): T =
        p[key]?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: default

    val stations: Flow<List<Station>> = ds.data.map { read(it, kStations, stationsSer, emptyList()) }
    val settings: Flow<Settings> = ds.data.map { read(it, kSettings, Settings.serializer(), Settings()) }
    val snapshot: Flow<Snapshot> = ds.data.map { read(it, kSnapshot, Snapshot.serializer(), Snapshot()) }
    val follow: Flow<Follow?> = ds.data.map { p ->
        p[kFollow]?.let { runCatching { json.decodeFromString(Follow.serializer(), it) }.getOrNull() }
    }

    suspend fun updateStations(f: (List<Station>) -> List<Station>) = ds.edit { p ->
        p[kStations] = json.encodeToString(stationsSer, f(read(p, kStations, stationsSer, emptyList())))
    }

    suspend fun updateSettings(f: (Settings) -> Settings) = ds.edit { p ->
        p[kSettings] = json.encodeToString(Settings.serializer(), f(read(p, kSettings, Settings.serializer(), Settings())))
    }

    suspend fun updateSnapshot(f: (Snapshot) -> Snapshot): Snapshot {
        var result = Snapshot()
        ds.edit { p ->
            result = f(read(p, kSnapshot, Snapshot.serializer(), Snapshot()))
            p[kSnapshot] = json.encodeToString(Snapshot.serializer(), result)
        }
        return result
    }

    suspend fun alertStates(): Map<String, AlertState> = read(ds.data.first(), kAlerts, alertsSer, emptyMap())

    suspend fun saveAlertStates(states: Map<String, AlertState>) = ds.edit { p ->
        p[kAlerts] = json.encodeToString(alertsSer, states)
    }

    suspend fun setFollow(f: Follow?) = ds.edit { p ->
        if (f == null) p.remove(kFollow) else p[kFollow] = json.encodeToString(Follow.serializer(), f)
    }

    companion object {
        @Volatile private var instance: Store? = null
        fun get(context: Context): Store =
            instance ?: synchronized(this) { instance ?: Store(context).also { instance = it } }
    }
}
