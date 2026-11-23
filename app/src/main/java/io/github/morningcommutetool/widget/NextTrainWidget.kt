package io.github.morningcommutetool.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import io.github.morningcommutetool.MainActivity
import io.github.morningcommutetool.R
import io.github.morningcommutetool.data.Snapshot
import io.github.morningcommutetool.data.Station
import io.github.morningcommutetool.data.Store
import io.github.morningcommutetool.data.Watched
import io.github.morningcommutetool.data.formatTime
import io.github.morningcommutetool.data.nextWatched
import io.github.morningcommutetool.ui.describeForAccessibility
import kotlinx.coroutines.flow.first

class NextTrainWidget : GlanceAppWidget() {

    companion object {
        suspend fun updateAll(context: Context) {
            runCatching { NextTrainWidget().updateAll(context) }
        }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = Store.get(context)
        val stations = store.stations.first()
        val snapshot = store.snapshot.first()
        provideContent {
            GlanceTheme {
                Content(context, stations, snapshot, System.currentTimeMillis())
            }
        }
    }

    @Composable
    private fun Content(context: Context, stations: List<Station>, snap: Snapshot, now: Long) {
        val next = nextWatched(stations, snap, now, 2)
        val first = next.firstOrNull()
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_STATION, first?.station?.id)

        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(20.dp)
                .padding(14.dp)
                .clickable(actionStartActivity(open)),
            verticalAlignment = Alignment.Top,
        ) {
            when {
                stations.isEmpty() -> Message("Add a station in the app")
                first == null -> Message(
                    if (stations.none { it.hasFilter }) "Set a filter on a station to watch trains"
                    else "No watched trains in the next hours"
                )
                else -> {
                    Main(first)
                    next.getOrNull(1)?.let { Later(it) }
                }
            }
            Spacer(GlanceModifier.defaultWeight())
            val stale = snap.lastError != null || now - snap.lastSuccess > 20 * 60_000
            if (snap.lastSuccess > 0) {
                Text(
                    (if (stale) "⚠ " else "") + "Updated ${formatTime(snap.lastSuccess)}",
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = if (stale) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant,
                    ),
                )
            }
        }
    }

    @Composable
    private fun Message(text: String) {
        Text(text, style = TextStyle(fontSize = 14.sp, color = GlanceTheme.colors.onSurface))
    }

    @Composable
    private fun Main(w: Watched) {
        val d = w.departure
        val late = d.delayMin > 0 && !d.cancelled
        Text(w.station.name, style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = GlanceModifier.semantics { contentDescription = describeForAccessibility(d) },
        ) {
            Text(
                formatTime(if (d.cancelled) d.aimed else d.effective),
                style = TextStyle(
                    fontSize = 34.sp, fontWeight = FontWeight.Bold,
                    color = if (late || d.cancelled) GlanceTheme.colors.error else GlanceTheme.colors.onSurface,
                    textDecoration = if (d.cancelled) TextDecoration.LineThrough else null,
                ),
            )
            Spacer(GlanceModifier.width(10.dp))
            Column {
                when {
                    d.cancelled -> Status("Cancelled", warn = true)
                    late -> Status("+${d.delayMin} min", warn = true)
                    d.realtime -> Status("On time", warn = false)
                }
                d.track?.let {
                    Text("Track $it", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurface))
                }
            }
        }
        Text(
            "${d.line} to ${d.destination}",
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurface),
            maxLines = 1,
        )
    }

    @Composable
    private fun Status(text: String, warn: Boolean) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (warn) {
                Image(
                    ImageProvider(R.drawable.ic_warning), contentDescription = null,
                    modifier = GlanceModifier.size(14.dp),
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.error),
                )
                Spacer(GlanceModifier.width(3.dp))
            }
            Text(
                text,
                style = TextStyle(
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = if (warn) GlanceTheme.colors.error else GlanceTheme.colors.primary,
                ),
            )
        }
    }

    @Composable
    private fun Later(w: Watched) {
        val d = w.departure
        Spacer(GlanceModifier.height(6.dp))
        val status = when {
            d.cancelled -> " · Cancelled"
            d.delayMin > 0 -> " · +${d.delayMin} min"
            else -> ""
        }
        Text(
            "Then ${formatTime(d.effective)} ${d.line}$status",
            style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
            maxLines = 1,
        )
    }
}

class NextTrainWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = NextTrainWidget()
}
