package io.github.morningcommutetool.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.morningcommutetool.MainActivity
import io.github.morningcommutetool.R
import io.github.morningcommutetool.bg.Receiver
import io.github.morningcommutetool.data.AlertRules
import io.github.morningcommutetool.data.Departure
import io.github.morningcommutetool.data.Entur
import io.github.morningcommutetool.data.Follow
import io.github.morningcommutetool.data.Watched
import io.github.morningcommutetool.data.formatTime

object Notifications {
    const val CH_ALERTS = "alerts"
    const val CH_STATUS = "status"
    const val STATUS_ID = 1

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Delays and changes", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Delays, cancellations, track changes and traffic notices for watched trains"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Next train", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Silent status during your active hours, and trains you follow"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openStation(context: Context, stationId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_STATION, stationId)
        return PendingIntent.getActivity(
            context, (stationId ?: "").hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun alert(context: Context, key: String, stationId: String, a: AlertRules.Alert) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_train)
            .setContentTitle(a.title)
            .setContentText(a.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openStation(context, stationId))
            .build()
        // Same departure + same kind replaces the earlier notification instead of stacking.
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify("$key|${a.tag}", 0, n)
    }

    private fun stopFollowAction(context: Context): NotificationCompat.Action {
        val pi = PendingIntent.getBroadcast(
            context, 3, Intent(context, Receiver::class.java).setAction(Receiver.ACTION_STOP_FOLLOW),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_train, "Stop following", pi).build()
    }

    fun statusLine(d: Departure): String = buildString {
        when {
            d.cancelled -> append("Cancelled")
            d.delayMin > 0 -> append("${formatTime(d.effective)} (+${d.delayMin} min)")
            else -> append(formatTime(d.effective))
        }
        d.track?.let { append(" · Track $it") }
    }

    /** Silent "next watched train" during active hours. */
    fun nextTrain(context: Context, next: Watched?, stale: String?): Notification {
        val b = NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_train)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openStation(context, next?.station?.id))
        if (next == null) {
            b.setContentTitle("No watched trains coming up")
                .setContentText("Set a filter on a station to watch trains")
        } else {
            val d = next.departure
            b.setContentTitle("${d.line} to ${d.destination} · ${statusLine(d)}")
                .setContentText(next.station.name + (stale?.let { " · $it" } ?: ""))
        }
        return b.build()
    }

    /** A followed train: promoted to a Live Update on Android 16+, ordinary ongoing otherwise. */
    fun followed(context: Context, f: Follow, d: Departure?, p: Entur.Progress?, stale: String?): Notification {
        val title = "${f.line} ${formatTime(f.aimed)} to ${f.destination}"
        val lines = mutableListOf<String>()
        if (d != null) lines += "${statusLine(d)} from ${f.stationName}"
        p?.let { lines += it.position + (it.stopsAway?.let { n -> if (n > 0) " · $n stops away" else "" } ?: "") }
        stale?.let { lines += it }
        val text = lines.joinToString("\n").ifBlank { "Getting live data…" }
        val chip = when {
            d == null -> formatTime(f.aimed)
            d.cancelled -> "Cancelled"
            else -> formatTime(d.effective)
        }
        return NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_train)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull() ?: text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(chip)
            .setContentIntent(openStation(context, f.stationId))
            .addAction(stopFollowAction(context))
            .build()
    }
}
