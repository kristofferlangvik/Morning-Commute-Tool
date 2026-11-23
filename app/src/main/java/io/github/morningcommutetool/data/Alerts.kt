package io.github.morningcommutetool.data

import kotlin.math.abs

/** Decides which alerts a watched departure deserves, without repeating itself. */
object AlertRules {
    enum class Kind { DELAY, RECOVERED, CANCELLED, TRACK, NOTICE }

    data class Alert(val kind: Kind, val title: String, val text: String, val tag: String)

    /** Only departures this close get alerts, so nothing fires about tomorrow's trains at night. */
    const val HORIZON_MS = 90 * 60_000L

    fun evaluate(
        prev: AlertState?,
        d: Departure,
        stationName: String,
        thresholdMin: Int,
    ): Pair<AlertState, List<Alert>> {
        val label = "${d.line} ${formatTime(d.aimed)}" + if (d.destination.isNotBlank()) " to ${d.destination}" else ""
        val where = "$stationName" + (d.track?.let { ", track $it" } ?: "")
        var state = prev ?: AlertState(departureAt = d.aimed, track = d.track)
        val out = mutableListOf<Alert>()

        if (d.cancelled) {
            if (!state.cancelAlerted) {
                out += Alert(Kind.CANCELLED, "$label is cancelled", "Cancelled at $stationName.", "cancel")
                state = state.copy(cancelAlerted = true)
            }
        } else {
            val delay = d.delayMin
            val alreadyLate = state.delayAlerted >= thresholdMin
            if (delay >= thresholdMin && (!alreadyLate || abs(delay - state.delayAlerted) >= 2)) {
                out += Alert(
                    Kind.DELAY, "$label is $delay min late",
                    "New time ${formatTime(d.effective)} from $where.", "delay",
                )
                state = state.copy(delayAlerted = delay)
            } else if (delay < thresholdMin && alreadyLate) {
                out += Alert(
                    Kind.RECOVERED, "$label is back near schedule",
                    "Now leaving ${formatTime(d.effective)} from $where.", "delay",
                )
                state = state.copy(delayAlerted = delay.coerceAtLeast(0))
            }
        }

        val oldTrack = state.track
        if (d.track != null && oldTrack != null && d.track != oldTrack && !d.cancelled) {
            out += Alert(Kind.TRACK, "$label: track change", "Now from track ${d.track} (was $oldTrack) at $stationName.", "track")
        }
        if (d.track != null) state = state.copy(track = d.track)

        for (n in d.notices) {
            if (n.id !in state.notices) {
                out += Alert(Kind.NOTICE, n.summary.ifBlank { "Traffic notice for $label" }, n.description.ifBlank { label }, "notice-${n.id}")
                state = state.copy(notices = state.notices + n.id)
            }
        }
        return state to out
    }

    fun key(stationId: String, d: Departure) = "${d.serviceJourneyId}|${d.date}|$stationId"
}
