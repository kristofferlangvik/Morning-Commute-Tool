package io.github.morningcommutetool.data

import android.content.Context
import io.github.morningcommutetool.notify.Notifications
import io.github.morningcommutetool.widget.NextTrainWidget
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One refresh: fetch every saved station, store it, send alerts, update the widget. */
object Engine {
    private val mutex = Mutex()

    /** Screenshot tests turn the network off so their sample data stays put. */
    @Volatile var offlineForTests = false

    suspend fun refresh(context: Context): Snapshot = mutex.withLock {
        val store = Store.get(context)
        if (offlineForTests) return@withLock store.snapshot.first()
        val stations = store.stations.first()
        val now = System.currentTimeMillis()

        val results = coroutineScope {
            stations.map { st -> async { st.id to runCatching { Entur.board(st.id) } } }.awaitAll()
        }
        val failure = results.firstNotNullOfOrNull { it.second.exceptionOrNull() }
        val snapshot = store.updateSnapshot { old ->
            val boards = stations.associate { st ->
                val fresh = results.firstOrNull { it.first == st.id }?.second?.getOrNull()
                st.id to (fresh ?: old.boards[st.id] ?: Board(st.id, emptyList(), 0))
            }
            Snapshot(
                boards = boards,
                lastAttempt = now,
                lastSuccess = if (failure == null && stations.isNotEmpty()) now else old.lastSuccess,
                lastError = failure?.let { describe(it) },
            )
        }

        if (failure == null) sendAlerts(context, store, stations, snapshot, now)
        NextTrainWidget.updateAll(context)
        snapshot
    }

    private suspend fun sendAlerts(context: Context, store: Store, stations: List<Station>, snap: Snapshot, now: Long) {
        val settings = store.settings.first()
        val states = store.alertStates().filterValues { it.departureAt > now - 3 * 3_600_000 }.toMutableMap()
        if (settings.alertsEnabled) {
            for (st in stations) {
                for (d in snap.boards[st.id]?.departures.orEmpty()) {
                    if (!st.watches(d) || d.hasLeft(now) || d.effective - now > AlertRules.HORIZON_MS) continue
                    val key = AlertRules.key(st.id, d)
                    val (state, alerts) = AlertRules.evaluate(states[key], d, st.name, settings.thresholdMin)
                    states[key] = state
                    // State is updated either way, so switching a kind back on doesn't replay old alerts.
                    alerts.filter { settings.wants(it.kind) }.forEach { Notifications.alert(context, key, st.id, it) }
                }
            }
        }
        store.saveAlertStates(states)
    }

    fun describe(e: Throwable): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException -> "No internet connection"
        is java.net.SocketTimeoutException -> "Entur didn't answer in time"
        else -> e.message ?: "Couldn't update"
    }
}
