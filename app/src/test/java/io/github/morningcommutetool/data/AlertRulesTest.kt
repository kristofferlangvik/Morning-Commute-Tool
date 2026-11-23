package io.github.morningcommutetool.data

import io.github.morningcommutetool.data.AlertRules.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertRulesTest {
    private val t0 = 1_800_000_000_000L
    private fun dep(delay: Int = 0, track: String? = "1", cancelled: Boolean = false, notices: List<Notice> = emptyList()) =
        Departure("SJ:1", "2027-01-04", "X1", "rail", "Somewhere", t0, t0 + delay * 60_000L, track, cancelled, true, notices)

    private fun run(states: List<Departure>, threshold: Int = 3): List<List<Kind>> {
        var s: AlertState? = null
        return states.map { d -> AlertRules.evaluate(s, d, "Station", threshold).also { s = it.first }.second.map { it.kind } }
    }

    @Test fun onTimeIsQuiet() = assertEquals(listOf(emptyList<Kind>(), emptyList()), run(listOf(dep(), dep(1))))

    @Test fun delayAlertsOnceThenOnlyOnRealChange() =
        assertEquals(
            listOf(listOf(Kind.DELAY), emptyList(), emptyList(), listOf(Kind.DELAY), listOf(Kind.RECOVERED), emptyList()),
            run(listOf(dep(4), dep(4), dep(5), dep(7), dep(1), dep(0))),
        )

    @Test fun thresholdOfOneStillAlerts() = assertEquals(listOf(listOf(Kind.DELAY)), run(listOf(dep(1)), threshold = 1))

    @Test fun cancellationOnce() =
        assertEquals(listOf(listOf(Kind.CANCELLED), emptyList()), run(listOf(dep(cancelled = true), dep(cancelled = true))))

    @Test fun trackChangeOncePerChange() =
        assertEquals(
            listOf(emptyList(), listOf(Kind.TRACK), emptyList(), listOf(Kind.TRACK)),
            run(listOf(dep(track = "1"), dep(track = "2"), dep(track = "2"), dep(track = "1"))),
        )

    @Test fun noticesOncePerId() {
        val n1 = Notice("A", "Signal fault", "Expect delays")
        val n2 = Notice("B", "Bus for train", "")
        assertEquals(
            listOf(listOf(Kind.NOTICE), emptyList(), listOf(Kind.NOTICE)),
            run(listOf(dep(notices = listOf(n1)), dep(notices = listOf(n1)), dep(notices = listOf(n1, n2)))),
        )
    }

    @Test fun filterSemantics() {
        val d = dep()
        val none = Station("S", "S")
        assertTrue(none.shows(d)); assertEquals(false, none.watches(d))
        val byLine = Station("S", "S", lines = setOf("X1"))
        assertTrue(byLine.watches(d))
        assertEquals(false, Station("S", "S", lines = setOf("X1"), destinations = setOf("Elsewhere")).shows(d))
    }
}
