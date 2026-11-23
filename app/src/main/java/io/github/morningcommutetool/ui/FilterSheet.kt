package io.github.morningcommutetool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.morningcommutetool.data.Departure
import io.github.morningcommutetool.data.Station

/** Pick lines and destinations from what actually leaves this station. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    station: Station,
    departures: List<Departure>,
    onSave: (Set<String>, Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var lines by remember { mutableStateOf(station.lines) }
    var dests by remember { mutableStateOf(station.destinations) }
    val allLines = (departures.map { it.line } + station.lines).filter { it.isNotBlank() }.distinct().sorted()
    val allDests = (departures.map { it.destination } + station.destinations).filter { it.isNotBlank() }.distinct().sorted()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
        ) {
            Text("Trains to watch at ${station.name}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(4.dp))
            Text(
                "Matching trains are the only ones shown for this station. They get delay alerts and appear in the widget. " +
                    "Choose nothing to show every departure without alerts.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Section("Lines", allLines, lines) { lines = it }
            Section("Destinations", allDests, dests) { dests = it }
            if (allLines.isEmpty() && allDests.isEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("No departures loaded yet. Pull down to refresh, then try again.")
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { lines = emptySet(); dests = emptySet() }) { Text("Clear") }
                Spacer(Modifier.padding(4.dp))
                Button(onClick = { onSave(lines, dests) }) { Text("Save") }
            }
        }
    }
}

@Composable
private fun Section(title: String, options: List<String>, chosen: Set<String>, onChange: (Set<String>) -> Unit) {
    if (options.isEmpty()) return
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val on = o in chosen
            FilterChip(
                selected = on,
                onClick = { onChange(if (on) chosen - o else chosen + o) },
                label = { Text(o) },
                leadingIcon = if (on) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.padding(0.dp)) }
                } else null,
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}
