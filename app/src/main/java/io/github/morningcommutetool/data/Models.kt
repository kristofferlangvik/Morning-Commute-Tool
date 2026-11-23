package io.github.morningcommutetool.data

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

val OSLO: ZoneId = ZoneId.of("Europe/Oslo")
private val HM = DateTimeFormatter.ofPattern("HH:mm")

fun formatTime(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(OSLO).format(HM)

/** A station the user saved. The filter decides which trains are shown and watched. */
@Serializable
data class Station(
    val id: String,
    val name: String,
    val lines: Set<String> = emptySet(),
    val destinations: Set<String> = emptySet(),
) {
    val hasFilter: Boolean get() = lines.isNotEmpty() || destinations.isNotEmpty()

    /** Shown on the board. Without a filter everything is shown. */
    fun shows(d: Departure): Boolean =
        (lines.isEmpty() || d.line in lines) && (destinations.isEmpty() || d.destination in destinations)

    /** Watched trains get alerts, the widget and the status notification. Needs a filter. */
    fun watches(d: Departure): Boolean = hasFilter && shows(d)
}

@Serializable
data class Notice(val id: String, val summary: String, val description: String)

@Serializable
data class Departure(
    val serviceJourneyId: String,
    val date: String,
    val line: String,
    val mode: String,
    val destination: String,
    val aimed: Long,
    val expected: Long? = null,
    val track: String? = null,
    val cancelled: Boolean = false,
    val realtime: Boolean = false,
    val notices: List<Notice> = emptyList(),
) {
    val effective: Long get() = expected ?: aimed

    /** Whole minutes late at this station (negative = early). */
    val delayMin: Int get() = expected?.let { ((it - aimed) / 60_000.0).roundToInt() } ?: 0

    fun hasLeft(now: Long): Boolean = effective < now - 30_000
}

@Serializable
data class Board(val stationId: String, val departures: List<Departure>, val fetchedAt: Long)

@Serializable
data class Snapshot(
    val boards: Map<String, Board> = emptyMap(),
    val lastAttempt: Long = 0,
    val lastSuccess: Long = 0,
    val lastError: String? = null,
)

/** A time range on given weekdays (1 = Monday … 7 = Sunday), minutes after midnight. */
@Serializable
data class ActiveWindow(
    val id: String,
    val start: Int,
    val end: Int,
    val days: Set<Int> = setOf(1, 2, 3, 4, 5),
) {
    fun contains(t: ZonedDateTime): Boolean {
        val m = t.hour * 60 + t.minute
        return t.dayOfWeek.value in days && m >= start && m < end
    }
}

@Serializable
data class Settings(
    val alertsEnabled: Boolean = true,
    val thresholdMin: Int = 3,
    val windows: List<ActiveWindow> = emptyList(),
) {
    fun inWindow(now: ZonedDateTime = ZonedDateTime.now(OSLO)): Boolean = windows.any { it.contains(now) }
}

/** What has already been notified for one departure, so nothing is sent twice. */
@Serializable
data class AlertState(
    val departureAt: Long,
    val delayAlerted: Int = 0,
    val cancelAlerted: Boolean = false,
    val track: String? = null,
    val notices: Set<String> = emptySet(),
)

/** A departure the user explicitly follows (shown as a Live Update). */
@Serializable
data class Follow(
    val stationId: String,
    val stationName: String,
    val serviceJourneyId: String,
    val date: String,
    val aimed: Long,
    val line: String,
    val destination: String,
)

/** A watched departure together with the station it leaves from. */
data class Watched(val station: Station, val departure: Departure)

/** Soonest watched departures across all stations that haven't left yet. */
fun nextWatched(stations: List<Station>, snapshot: Snapshot, now: Long, limit: Int = 2): List<Watched> =
    stations.flatMap { st ->
        snapshot.boards[st.id]?.departures.orEmpty()
            .filter { st.watches(it) && !it.hasLeft(now) }
            .map { Watched(st, it) }
    }.sortedBy { it.departure.effective }.take(limit)
