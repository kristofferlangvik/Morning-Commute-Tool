package io.github.morningcommutetool.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.morningcommutetool.R
import io.github.morningcommutetool.data.Departure
import io.github.morningcommutetool.data.Follow
import io.github.morningcommutetool.data.Snapshot
import io.github.morningcommutetool.data.Station
import io.github.morningcommutetool.data.formatTime
import io.github.morningcommutetool.notify.Notifications
import kotlinx.coroutines.delay

private const val COLLAPSED = 6
private const val EXPANDED = 20

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, onAddStation: () -> Unit, onSettings: () -> Unit) {
    val stations by vm.stations.collectAsState()
    val snapshot by vm.snapshot.collectAsState()
    val follow by vm.follow.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val focus by vm.focus.collectAsState()
    val context = LocalContext.current

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var selected by remember { mutableStateOf<Pair<Station, Departure>?>(null) }
    var filtering by remember { mutableStateOf<Station?>(null) }
    var removing by remember { mutableStateOf<Station?>(null) }
    var canNotify by remember { mutableStateOf(Notifications.canPost(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canNotify = it }

    // Keep the board fresh while it's on screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            canNotify = Notifications.canPost(context)
            var tick = 0
            while (true) {
                now = System.currentTimeMillis()
                if (tick % 4 == 0) vm.refreshSilently()
                tick++
                delay(15_000)
            }
        }
    }

    val list = stations
    val listState = rememberLazyListState()
    val showPermissionCard = !canNotify && list.orEmpty().any { it.hasFilter }

    // Opened from the widget or a notification: jump to that station.
    LaunchedEffect(focus, list) {
        val id = focus ?: return@LaunchedEffect
        val l = list ?: return@LaunchedEffect
        var index = 1 + (if (showPermissionCard) 1 else 0) + (if (follow != null) 1 else 0)
        for (st in l) {
            if (st.id == id) break
            index += 1 + visible(st, snapshot, now, st.id in expanded).size.coerceAtLeast(1) + 1
        }
        if (l.any { it.id == id }) listState.animateScrollToItem(index)
        vm.focusHandled()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Departures") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
            )
        },
        floatingActionButton = {
            if (!list.isNullOrEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onAddStation,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add station") },
                )
            }
        },
    ) { padding ->
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding))
            list.isEmpty() -> EmptyState(Modifier.padding(padding), onAddStation)
            else -> PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 96.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "status") { UpdatedLine(snapshot, now) }
                    if (showPermissionCard) {
                        item(key = "perm") {
                            InfoCard(
                                "Allow notifications to get alerts about delays, cancellations and track changes.",
                                "Allow",
                            ) {
                                if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    }
                    follow?.let { f ->
                        item(key = "follow") {
                            InfoCard(
                                "Following ${f.line} ${formatTime(f.aimed)} to ${f.destination} from ${f.stationName}. " +
                                    "Live status is in your notifications.",
                                "Stop",
                            ) { vm.stopFollowing() }
                        }
                    }
                    for ((i, st) in list.withIndex()) {
                        val isExpanded = st.id in expanded
                        val rows = visible(st, snapshot, now, isExpanded)
                        item(key = "h-${st.id}") {
                            StationHeader(
                                st, isFirst = i == 0, isLast = i == list.lastIndex,
                                onFilter = { filtering = st },
                                onMove = { vm.moveStation(st.id, it) },
                                onRemove = { removing = st },
                            )
                        }
                        if (rows.isEmpty()) {
                            item(key = "e-${st.id}") {
                                val board = snapshot.boards[st.id]
                                Text(
                                    when {
                                        board == null || board.fetchedAt == 0L -> "Loading…"
                                        st.hasFilter -> "No upcoming trains match your filter."
                                        else -> "No upcoming departures."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                            }
                        }
                        for (d in rows) {
                            item(key = "d-${st.id}-${d.serviceJourneyId}-${d.date}") {
                                DepartureRow(
                                    d, watched = st.watches(d),
                                    followed = follow?.serviceJourneyId == d.serviceJourneyId && follow?.stationId == st.id,
                                ) { selected = st to d }
                            }
                        }
                        item(key = "f-${st.id}") {
                            val total = snapshot.boards[st.id]?.departures.orEmpty().count { st.shows(it) && !it.hasLeft(now) }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                                if (total > COLLAPSED) {
                                    TextButton(onClick = {
                                        expanded = if (isExpanded) expanded - st.id else expanded + st.id
                                    }) { Text(if (isExpanded) "Show fewer" else "Show more") }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    selected?.let { (st, d) ->
        DepartureSheet(
            st, d, follow,
            onFollow = { vm.follow(st, d); selected = null },
            onStop = { vm.stopFollowing(); selected = null },
            onDismiss = { selected = null },
        )
    }
    filtering?.let { st ->
        FilterSheet(
            station = st,
            departures = snapshot.boards[st.id]?.departures.orEmpty(),
            onSave = { lines, dests -> vm.setFilter(st.id, lines, dests); filtering = null },
            onDismiss = { filtering = null },
        )
    }
    removing?.let { st ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${st.name}?") },
            text = { Text("Its filter will be removed too.") },
            confirmButton = { TextButton(onClick = { vm.removeStation(st.id); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

private fun visible(st: Station, snap: Snapshot, now: Long, expanded: Boolean): List<Departure> =
    snap.boards[st.id]?.departures.orEmpty()
        .filter { st.shows(it) && !it.hasLeft(now) }
        .take(if (expanded) EXPANDED else COLLAPSED)

@Composable
private fun EmptyState(modifier: Modifier, onAdd: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(R.drawable.ic_train), contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("No stations yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add the stations you travel from. Then choose which trains to watch, and you'll get alerts when they're late, cancelled or change track.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Add station")
        }
    }
}

@Composable
private fun UpdatedLine(snap: Snapshot, now: Long) {
    val stale = snap.lastSuccess > 0 && now - snap.lastSuccess > 5 * 60_000
    val error = snap.lastError
    if (error != null || stale) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.size(12.dp))
                Text(
                    (error ?: "Data may be out of date") + ". " +
                        if (snap.lastSuccess > 0) "Showing data from ${formatTime(snap.lastSuccess)}." else "No data yet.",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    } else if (snap.lastSuccess > 0) {
        Text(
            "Updated ${formatTime(snap.lastSuccess)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun InfoCard(text: String, action: String, onAction: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun StationHeader(
    st: Station,
    isFirst: Boolean,
    isLast: Boolean,
    onFilter: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                st.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                filterSummary(st),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onFilter) { Text(if (st.hasFilter) "Edit filter" else "Filter") }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options for ${st.name}")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!isFirst) DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; onMove(-1) })
                if (!isLast) DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; onMove(1) })
                DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

private fun filterSummary(st: Station): String {
    if (!st.hasFilter) return "All departures · none watched"
    val parts = mutableListOf<String>()
    if (st.lines.isNotEmpty()) parts += st.lines.sorted().joinToString(", ")
    if (st.destinations.isNotEmpty()) parts += "to " + st.destinations.sorted().joinToString(", ")
    return "Watching " + parts.joinToString(" · ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DepartureSheet(
    st: Station,
    d: Departure,
    follow: Follow?,
    onFollow: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isFollowed = follow?.serviceJourneyId == d.serviceJourneyId && follow.stationId == st.id
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            Text(
                "${d.line} ${formatTime(d.aimed)} to ${d.destination}",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(4.dp))
            Text(describeForAccessibility(d), style = MaterialTheme.typography.bodyMedium)
            d.notices.forEach { n ->
                Spacer(Modifier.height(12.dp))
                Text(n.summary, style = MaterialTheme.typography.titleSmall)
                if (n.description.isNotBlank()) Text(n.description, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(20.dp))
            if (isFollowed) {
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop following") }
            } else if (!d.cancelled) {
                Button(onClick = onFollow, modifier = Modifier.fillMaxWidth()) { Text("Follow this train") }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Shows a live status notification with where the train is, until it leaves ${st.name}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
