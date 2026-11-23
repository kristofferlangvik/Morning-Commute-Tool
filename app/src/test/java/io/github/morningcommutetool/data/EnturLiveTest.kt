package io.github.morningcommutetool.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Hits the real Entur API. Runs only with ENTUR_LIVE=1. */
class EnturLiveTest {
    private fun live() = assumeTrue(System.getenv("ENTUR_LIVE") == "1")

    @Test fun searchBoardAndProgress() = runBlocking {
        live()
        val hits = Entur.searchStations("Bergen stasjon")
        println(hits.take(3))
        val station = hits.first { it.isRail }
        val board = Entur.board(station.id)
        board.departures.take(5).forEach { println("${formatTime(it.aimed)} ${it.line} ${it.destination} track=${it.track} delay=${it.delayMin} cancelled=${it.cancelled}") }
        assertTrue(board.departures.isNotEmpty())
        val d = board.departures.first()
        println(Entur.progress(d.serviceJourneyId, d.date, station.id))
    }
}
