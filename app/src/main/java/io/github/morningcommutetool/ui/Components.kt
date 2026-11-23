package io.github.morningcommutetool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.morningcommutetool.data.Departure
import io.github.morningcommutetool.data.formatTime

/** One sentence TalkBack reads for a departure. */
fun describeForAccessibility(d: Departure): String = buildString {
    append("${d.line} to ${d.destination}. ")
    append("Scheduled ${formatTime(d.aimed)}. ")
    when {
        d.cancelled -> append("Cancelled. ")
        d.delayMin > 0 -> append("Expected ${formatTime(d.effective)}, ${d.delayMin} minutes late. ")
        d.delayMin < 0 -> append("Expected ${formatTime(d.effective)}, early. ")
        d.realtime -> append("On time. ")
        else -> append("No live data. ")
    }
    d.track?.let { append("Track $it.") }
}

@Composable
fun LineBadge(line: String) {
    Text(
        line,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        maxLines = 1,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * Time first and large (as on departure boards), then line and destination,
 * then live status, with the track on the right.
 */
@Composable
fun DepartureRow(d: Departure, watched: Boolean, followed: Boolean, onClick: () -> Unit) {
    val late = d.delayMin > 0 && !d.cancelled
    val warn = MaterialTheme.colorScheme.error
    val description = describeForAccessibility(d) +
        (if (watched) " Watched." else "") + (if (followed) " Following." else "")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = "Options") { onClick(); true }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.widthIn(min = 76.dp)) {
            Text(
                formatTime(if (d.cancelled) d.aimed else d.effective),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (late || d.cancelled) warn else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (d.cancelled) TextDecoration.LineThrough else null,
            )
            if (late) {
                Text(
                    formatTime(d.aimed),
                    style = MaterialTheme.typography.bodySmall,
                    textDecoration = TextDecoration.LineThrough,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LineBadge(d.line)
                Text(
                    d.destination,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (watched) {
                    Icon(
                        Icons.Default.Notifications, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.size(2.dp))
            Status(d)
        }
        if (d.track != null) {
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                Text("Track", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(d.track, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Status(d: Departure) {
    val warn = MaterialTheme.colorScheme.error
    val (text, isWarn) = when {
        d.cancelled -> "Cancelled" to true
        d.delayMin > 0 -> "${d.delayMin} min late" to true
        d.delayMin < 0 -> "Early" to false
        d.realtime -> "On time" to false
        else -> "Scheduled" to false
    }
    val notice = d.notices.firstOrNull()?.summary
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (isWarn) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = warn, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text + (notice?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isWarn) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isWarn) warn else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
