package io.github.morningcommutetool

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import java.time.DayOfWeek
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

object Fmt {
    val OSLO: ZoneId = ZoneId.of("Europe/Oslo")
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    fun hm(t: OffsetDateTime): String = t.atZoneSameInstant(OSLO).format(HM)

    fun parse(s: String?): OffsetDateTime? =
        if (s == null) null else try { OffsetDateTime.parse(s) } catch (e: Exception) { null }

    /** Whole minutes late (negative = early). */
    fun delay(aimed: OffsetDateTime, expected: OffsetDateTime): Long =
        (Duration.between(aimed, expected).seconds / 60.0).roundToLong()

    fun delayText(aimed: OffsetDateTime, expected: OffsetDateTime?): String {
        if (expected == null) return ""
        val m = delay(aimed, expected)
        return when {
            m > 0 -> " (+$m min)"
            m < 0 -> " ($m min)"
            else -> " (on time)"
        }
    }
}

/** Everything here lives only on the phone. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("commute", Context.MODE_PRIVATE)

    private fun str(k: String): String? = sp.getString(k, null)
    private fun putStr(k: String, v: String?) = sp.edit().putString(k, v).apply()

    var fromId: String?
        get() = str("fromId")
        set(v) = putStr("fromId", v)
    var fromName: String?
        get() = str("fromName")
        set(v) = putStr("fromName", v)
    var toId: String?
        get() = str("toId")
        set(v) = putStr("toId", v)
    var toName: String?
        get() = str("toName")
        set(v) = putStr("toName", v)

    var autoEnabled: Boolean
        get() = sp.getBoolean("autoEnabled", false)
        set(v) = sp.edit().putBoolean("autoEnabled", v).apply()

    /** Usual train's departure, minutes after midnight. */
    var autoMinutes: Int
        get() = sp.getInt("autoMinutes", 7 * 60)
        set(v) = sp.edit().putInt("autoMinutes", v).apply()

    var threshold: Int
        get() = sp.getInt("threshold", 3)
        set(v) = sp.edit().putInt("threshold", v).apply()

    var following: Boolean
        get() = sp.getBoolean("following", false)
        set(v) = sp.edit().putBoolean("following", v).apply()

    var status: String
        get() = str("status") ?: ""
        set(v) = putStr("status", v)

    val position: Pair<Double, Double>?
        get() {
            val lat = str("lat")?.toDoubleOrNull() ?: return null
            val lon = str("lon")?.toDoubleOrNull() ?: return null
            return Pair(lat, lon)
        }

    fun setPosition(p: Pair<Double, Double>?) {
        sp.edit().putString("lat", p?.first?.toString()).putString("lon", p?.second?.toString()).apply()
    }
}

object Notif {
    const val CH_ONGOING = "ongoing"
    const val CH_ALERT = "alerts"
    const val ONGOING_ID = 1

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ONGOING, "Following a train", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Delays and disruptions", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun ongoing(ctx: Context, title: String, text: String): Notification {
        val stop = PendingIntent.getBroadcast(
            ctx, 2, Intent(ctx, Receiver::class.java).setAction(Receiver.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(ctx, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_train)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull() ?: "")
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_train), "Stop", stop).build()
            )
            .build()
    }

    fun alert(ctx: Context, id: Int, title: String, text: String) {
        val n = Notification.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_train)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}

/** Wakes the app before the usual train on weekdays. */
object Scheduler {
    private const val LEAD_MINUTES = 20

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 10, Intent(ctx, Receiver::class.java).setAction(Receiver.ACTION_ALARM),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun reschedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx)
        am.cancel(pi)
        val p = Prefs(ctx)
        if (!p.autoEnabled || p.fromId == null || p.toId == null) return
        val t = nextTrigger(p.autoMinutes).toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        }
    }

    fun nextTrigger(trainMinutes: Int): ZonedDateTime {
        val now = ZonedDateTime.now(Fmt.OSLO)
        var day = now.toLocalDate()
        for (i in 0..8) {
            if (day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY) {
                val trig = day.atStartOfDay(Fmt.OSLO).plusMinutes((trainMinutes - LEAD_MINUTES).toLong())
                if (trig.isAfter(now.plusSeconds(30))) return trig
            }
            day = day.plusDays(1)
        }
        return now.plusDays(1)
    }
}

class Receiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ALARM = "io.github.morningcommutetool.ALARM"
        const val ACTION_STOP = "io.github.morningcommutetool.STOP"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ALARM -> {
                Scheduler.reschedule(ctx)
                try {
                    ctx.startForegroundService(
                        Intent(ctx, FollowService::class.java).setAction(FollowService.ACTION_AUTO)
                    )
                } catch (e: Exception) {
                    Notif.ensureChannels(ctx)
                    Notif.alert(ctx, 50, "Time for your train", "Open the app to follow it.")
                }
            }
            ACTION_STOP -> ctx.stopService(Intent(ctx, FollowService::class.java))
            else -> Scheduler.reschedule(ctx) // boot / app updated
        }
    }
}
