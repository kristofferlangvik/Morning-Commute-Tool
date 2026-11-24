package io.github.morningcommutetool.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.morningcommutetool.data.ActiveWindow
import io.github.morningcommutetool.notify.Notifications

private val DAY_LETTERS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

fun hhmm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

fun describeDays(days: Set<Int>): String = when (days) {
    setOf(1, 2, 3, 4, 5) -> "Weekdays"
    setOf(6, 7) -> "Weekends"
    (1..7).toSet() -> "Every day"
    else -> days.sorted().joinToString(", ") { DAY_LETTERS[it - 1] }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<ActiveWindow?>(null) }
    var adding by remember { mutableStateOf(false) }

    // Re-read system states when coming back from system settings.
    var canNotify by remember { mutableStateOf(Notifications.canPost(context)) }
    var unrestricted by remember { mutableStateOf(isUnrestricted(context)) }
    var promoted by remember { mutableStateOf(canPromote(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            canNotify = Notifications.canPost(context)
            unrestricted = isUnrestricted(context)
            promoted = canPromote(context)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canNotify = it }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Header("Alerts")
            ListItem(
                headlineContent = { Text("Alerts for watched trains") },
                supportingContent = { Text("For trains leaving within 90 minutes") },
                trailingContent = {
                    Switch(checked = settings.alertsEnabled, onCheckedChange = { on -> vm.updateSettings { it.copy(alertsEnabled = on) } })
                },
            )
            Text(
                "Alert me about",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).semantics { heading() },
            )
            KindSwitch("Delays", "When a train is late, or back on time", settings.alertsEnabled, settings.alertDelays) { on ->
                vm.updateSettings { it.copy(alertDelays = on) }
            }
            Text(
                "Delay alert when at least this late",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            val options = listOf(1, 2, 3, 5, 10)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
                options.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = settings.thresholdMin == m,
                        onClick = { vm.updateSettings { it.copy(thresholdMin = m) } },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        label = { Text("$m min") },
                    )
                }
            }
            KindSwitch("Cancellations", null, settings.alertsEnabled, settings.alertCancellations) { on ->
                vm.updateSettings { it.copy(alertCancellations = on) }
            }
            KindSwitch("Track changes", null, settings.alertsEnabled, settings.alertTrackChanges) { on ->
                vm.updateSettings { it.copy(alertTrackChanges = on) }
            }
            KindSwitch(
                "Traffic notices",
                "Fewer carriages, works, other messages. Still shown in the app.",
                settings.alertsEnabled, settings.alertNotices,
            ) { on -> vm.updateSettings { it.copy(alertNotices = on) } }
            if (!canNotify && Build.VERSION.SDK_INT >= 33) {
                ActionRow("Notifications are off for this app", "Allow") {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            ActionRow("Sound, vibration and channels", "Open") {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Header("Active hours")
            Text(
                "During these times the app checks every minute and shows your next watched train as a silent notification. " +
                    "Outside them it checks about every 15 minutes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            settings.windows.forEach { w ->
                ListItem(
                    headlineContent = { Text("${hhmm(w.start)}–${hhmm(w.end)}") },
                    supportingContent = { Text(describeDays(w.days)) },
                    trailingContent = {
                        IconButton(onClick = { vm.removeWindow(w.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete ${hhmm(w.start)} to ${hhmm(w.end)}")
                        }
                    },
                    modifier = Modifier.clickable(onClickLabel = "Edit") { editing = w },
                )
            }
            OutlinedButton(onClick = { adding = true }, modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.padding(4.dp))
                Text("Add active hours")
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Header("Reliability")
            ActionRow(
                if (unrestricted) "Battery use: unrestricted" else "Battery saver may delay checks and alerts",
                if (unrestricted) "Change" else "Fix",
            ) {
                context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            if (Build.VERSION.SDK_INT >= 36) {
                ActionRow(
                    if (promoted) "Live Updates allowed for followed trains" else "Live Updates are off for this app",
                    "Open",
                ) {
                    val i = Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS")
                        .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                    runCatching { context.startActivity(i) }.onFailure {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "Departure data from Entur (entur.no), licensed under NLOD. Your stations and settings are stored only on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    if (adding) {
        WindowDialog(null, onDismiss = { adding = false }) { s, e, d -> vm.addWindow(s, e, d); adding = false }
    }
    editing?.let { w ->
        WindowDialog(w, onDismiss = { editing = null }) { s, e, d ->
            vm.replaceWindow(w.copy(start = s, end = e, days = d)); editing = null
        }
    }
}

@Composable
private fun KindSwitch(title: String, detail: String?, enabled: Boolean, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, enabled = enabled, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun ActionRow(text: String, action: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text) },
        trailingContent = { TextButton(onClick = onClick) { Text(action) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WindowDialog(initial: ActiveWindow?, onDismiss: () -> Unit, onSave: (Int, Int, Set<Int>) -> Unit) {
    val start = rememberTimePickerState(initial?.start?.div(60) ?: 7, initial?.start?.rem(60) ?: 0, is24Hour = true)
    val end = rememberTimePickerState(initial?.end?.div(60) ?: 9, initial?.end?.rem(60) ?: 0, is24Hour = true)
    var days by remember { mutableStateOf(initial?.days ?: setOf(1, 2, 3, 4, 5)) }
    val startMin = start.hour * 60 + start.minute
    val endMin = end.hour * 60 + end.minute
    val valid = endMin > startMin && days.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add active hours" else "Edit active hours") },
        text = {
            Column {
                Text("From", style = MaterialTheme.typography.labelLarge)
                TimeInput(start)
                Text("To", style = MaterialTheme.typography.labelLarge)
                TimeInput(end)
                Text("Days", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DAY_LETTERS.forEachIndexed { i, label ->
                        val d = i + 1
                        FilterChip(
                            selected = d in days,
                            onClick = { days = if (d in days) days - d else days + d },
                            label = { Text(label) },
                        )
                    }
                }
                if (!valid) {
                    Text(
                        if (days.isEmpty()) "Pick at least one day." else "End must be after start (same day).",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(startMin, endMin, days) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun isUnrestricted(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

private fun canPromote(context: Context): Boolean = try {
    if (Build.VERSION.SDK_INT >= 36) context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications() else false
} catch (e: Throwable) {
    false
}
