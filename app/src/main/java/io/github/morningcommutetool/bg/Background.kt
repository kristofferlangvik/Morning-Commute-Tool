package io.github.morningcommutetool.bg

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.morningcommutetool.data.Engine
import io.github.morningcommutetool.data.Entur
import io.github.morningcommutetool.data.OSLO
import io.github.morningcommutetool.data.Settings
import io.github.morningcommutetool.data.Store
import io.github.morningcommutetool.data.formatTime
import io.github.morningcommutetool.data.nextWatched
import io.github.morningcommutetool.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Decides what runs in the background and when. */
object Scheduler {
    private const val WORK = "refresh"

    /** Call after any change to stations, settings or follow, and on boot. */
    fun sync(context: Context) {
        val store = Store.get(context)
        val (settings, follow) = runBlocking { store.settings.first() to store.follow.first() }
        enqueueWorker(context)
        scheduleWindowAlarm(context, settings)
        if (settings.inWindow() || follow != null) startLive(context)
    }

    private fun enqueueWorker(context: Context) {
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Starting the live service is only allowed from the foreground or an exact alarm. */
    fun startLive(context: Context): Boolean = try {
        context.startForegroundService(Intent(context, LiveService::class.java))
        true
    } catch (e: Exception) {
        false
    }

    fun nextWindowStart(settings: Settings, now: ZonedDateTime = ZonedDateTime.now(OSLO)): ZonedDateTime? {
        var best: ZonedDateTime? = null
        for (dayOffset in 0..7) {
            val day = now.toLocalDate().plusDays(dayOffset.toLong())
            for (w in settings.windows) {
                if (day.dayOfWeek.value !in w.days) continue
                val t = day.atStartOfDay(OSLO).plusMinutes(w.start.toLong())
                if (t.isAfter(now) && (best == null || t.isBefore(best))) best = t
            }
            if (best != null) return best
        }
        return best
    }

    private fun scheduleWindowAlarm(context: Context, settings: Settings) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, 10, Intent(context, Receiver::class.java).setAction(Receiver.ACTION_WINDOW),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        am.cancel(pi)
        val next = nextWindowStart(settings) ?: return
        val at = next.toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

/** Every 15 minutes (when online): refresh, alert, update the widget. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Engine.refresh(applicationContext)
        return Result.success()
    }
}

/**
 * Runs during active windows and while following a train. Polls every minute,
 * keeps the status notification current and stops when neither applies.
 */
class LiveService : Service() {
    private var scope: CoroutineScope? = null
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.createChannels(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(
            this, Notifications.STATUS_ID, Notifications.nextTrain(this, null, "Updating…"), type,
        )
        // (Re)start the loop so a change (e.g. "stop following") shows up right away.
        loop?.cancel()
        val s = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "commute:live")
                .apply { setReferenceCounted(false) }
        }
        loop = s.launch { run() }
        return START_NOT_STICKY
    }

    private suspend fun run() {
        val store = Store.get(this)
        val nm = NotificationManagerCompat.from(this)
        while (scope?.isActive == true) {
            val settings = store.settings.first()
            var follow = store.follow.first()
            val now = System.currentTimeMillis()
            if (!settings.inWindow() && follow == null) break
            // Keeps minute-by-minute checks going with the screen off; renewed each tick.
            wakeLock?.acquire(3 * 60_000L)

            val snap = Engine.refresh(this)
            val stale = if (snap.lastError != null) {
                "${snap.lastError} · updated ${if (snap.lastSuccess > 0) formatTime(snap.lastSuccess) else "never"}"
            } else null

            val notification = if (follow != null) {
                val f = follow
                val d = snap.boards[f.stationId]?.departures?.firstOrNull {
                    it.serviceJourneyId == f.serviceJourneyId && it.date == f.date
                }
                val gone = (d == null && now > f.aimed + 10 * 60_000) ||
                    (d != null && d.effective < now - 2 * 60_000)
                if (gone) {
                    store.setFollow(null)
                    follow = null
                    null
                } else {
                    val p = runCatching { Entur.progress(f.serviceJourneyId, f.date, f.stationId) }.getOrNull()
                    Notifications.followed(this, f, d, p, stale)
                }
            } else null

            val shown = notification ?: if (settings.inWindow()) {
                val stations = store.stations.first()
                Notifications.nextTrain(this, nextWatched(stations, snap, now, 1).firstOrNull(), stale)
            } else break

            @Suppress("MissingPermission")
            if (Notifications.canPost(this)) nm.notify(Notifications.STATUS_ID, shown)
            delay(60_000)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        scope?.cancel()
        scope = null
        super.onDestroy()
    }
}

class Receiver : BroadcastReceiver() {
    companion object {
        const val ACTION_WINDOW = "io.github.morningcommutetool.WINDOW"
        const val ACTION_STOP_FOLLOW = "io.github.morningcommutetool.STOP_FOLLOW"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP_FOLLOW -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    Store.get(context).setFollow(null)
                    // The service notices on its next tick; nudge it so it reacts now.
                    Scheduler.startLive(context)
                    pending.finish()
                }
            }
            else -> Scheduler.sync(context) // window alarm, boot, app updated
        }
    }
}
