package io.github.morningcommutetool.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.morningcommutetool.data.ActiveWindow
import io.github.morningcommutetool.data.Board
import io.github.morningcommutetool.data.Departure
import io.github.morningcommutetool.data.Engine
import io.github.morningcommutetool.data.Notice
import io.github.morningcommutetool.data.Snapshot
import io.github.morningcommutetool.data.Station
import io.github.morningcommutetool.data.Store
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders screens with made-up sample data. Output: app/build/screenshots/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun seed(empty: Boolean = false) = runBlocking {
        Engine.offlineForTests = true
        val store = Store.get(app)
        val now = System.currentTimeMillis()
        val m = 60_000L
        fun d(id: String, line: String, dest: String, inMin: Int, delay: Int? = 0, track: String = "1", cancelled: Boolean = false, notice: Boolean = false) =
            Departure("SJ:$id", "2030-01-01", line, "rail", dest, now + inMin * m, delay?.let { now + (inMin + it) * m }, track, cancelled, delay != null,
                if (notice) listOf(Notice("N$id", "Signal fault near Example", "")) else emptyList())
        val a = Station("A", "Northfield station", lines = setOf("X1"), destinations = setOf("Harbor Central"))
        val b = Station("B", "Harbor Central")
        store.updateStations { if (empty) emptyList() else listOf(a, b) }
        store.updateSettings { it.copy(windows = listOf(ActiveWindow("w", 6 * 60 + 30, 8 * 60 + 30))) }
        store.updateSnapshot {
            Snapshot(
                boards = mapOf(
                    "A" to Board("A", listOf(
                        d("1", "X1", "Harbor Central", 4, 6, "2", notice = true),
                        d("2", "X1", "Harbor Central", 34, 0, "2"),
                        d("3", "X1", "Harbor Central", 64, cancelled = true, track = "2"),
                        d("4", "X1", "Harbor Central", 94, null, "1"),
                    ), now),
                    "B" to Board("B", listOf(
                        d("5", "L5", "Lakeside", 2, 0, "11"),
                        d("6", "X1", "Northfield", 9, 3, "4"),
                        d("7", "R10", "Airport", 12, 0, "13"),
                    ), now),
                ),
                lastAttempt = now, lastSuccess = now,
            )
        }
    }

    @Test fun home() { seed(); shot("home") { HomeScreen(MainViewModel(app), {}, {}) } }
    @Test fun empty() { seed(empty = true); shot("empty") { HomeScreen(MainViewModel(app), {}, {}) } }
    @Test fun settings() { seed(); shot("settings") { SettingsScreen(MainViewModel(app)) {} } }

    @Config(qualifiers = "+night")
    @Test fun homeDark() { seed(); shot("home_dark") { HomeScreen(MainViewModel(app), {}, {}) } }

    private fun shot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { AppTheme { content() } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }
}
