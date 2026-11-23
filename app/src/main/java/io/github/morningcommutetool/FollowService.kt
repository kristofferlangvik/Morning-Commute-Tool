package io.github.morningcommutetool

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Polls Entur for one train and raises alerts while it runs. */
class FollowService : Service() {
    companion object {
        const val ACTION_FOLLOW = "io.github.morningcommutetool.FOLLOW"
        const val ACTION_AUTO = "io.github.morningcommutetool.AUTO"
        const val EX_SJ = "sj"
        const val EX_DATE = "date"
        private const val POLL_SECONDS = 30L
        private const val MAX_HOURS = 4L
    }

    private var exec: ScheduledExecutorService? = null
    private var prefs: Prefs? = null
    @Volatile private var stopped = false

    private var sjId = ""
    private var date = ""
    private var fromId = ""
    private var toId = ""
    private var lastAlertDelay = 0L
    private var platform: String? = null
    private var cancelledAlerted = false
    private var routeAlerted = false
    private val seenSituations = HashSet<String>()
    private var startedAt = 0L
    private var alertId = 100

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = Prefs(this)
        prefs = p
        Notif.ensureChannels(this)
        startFg(Notif.ongoing(this, "Commute", "Starting…"))

        val from = p.fromId
        val to = p.toId
        if (from == null || to == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        exec?.shutdownNow()
        val ex = Executors.newSingleThreadScheduledExecutor()
        exec = ex
        stopped = false
        fromId = from
        toId = to
        lastAlertDelay = 0L
        platform = null
        cancelledAlerted = false
        routeAlerted = false
        seenSituations.clear()
        startedAt = System.currentTimeMillis()
        p.following = true
        p.status = "Starting…"
        p.setPosition(null)

        if (intent?.action == ACTION_AUTO) {
            ex.execute { pickUsualTrain() }
        } else {
            sjId = intent?.getStringExtra(EX_SJ) ?: ""
            date = intent?.getStringExtra(EX_DATE) ?: ""
            if (sjId.isEmpty() || date.isEmpty()) {
                stopSelf()
                return START_NOT_STICKY
            }
            startPolling()
        }
        return START_NOT_STICKY
    }

    private fun startFg(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notif.ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notif.ONGOING_ID, n)
        }
    }

    private fun pickUsualTrain() {
        try {
            val p = prefs ?: return
            val target = LocalDate.now(Fmt.OSLO).atStartOfDay(Fmt.OSLO)
                .plusMinutes(p.autoMinutes.toLong()).toOffsetDateTime()
            val deps = Entur.departures(fromId, toId, target.minusMinutes(15))
            val best = deps.minByOrNull { abs(Duration.between(target, it.aimedDep).toMinutes()) }
            if (best == null || abs(Duration.between(target, best.aimedDep).toMinutes()) > 30) {
                alert("Usual train not found", "No direct train found near ${Fmt.hm(target)} today.")
                finish()
                return
            }
            sjId = best.serviceJourneyId
            date = best.date
            startPolling()
        } catch (e: Exception) {
            alert("Couldn't start following", e.message ?: "Unknown error")
            finish()
        }
    }

    private fun startPolling() {
        exec?.scheduleWithFixedDelay({ safePoll() }, 0, POLL_SECONDS, TimeUnit.SECONDS)
    }

    private fun safePoll() {
        try {
            poll()
        } catch (e: Entur.RouteChanged) {
            if (!routeAlerted) {
                routeAlerted = true
                alert("Route changed", e.message ?: "")
            }
            update("Route changed", e.message ?: "")
        } catch (e: Exception) {
            update("Connection problem", "Retrying… (${e.message})")
        }
        if (System.currentTimeMillis() - startedAt > MAX_HOURS * 3_600_000) finish()
    }

    private fun poll() {
        val st = Entur.journeyStatus(sjId, date, fromId, toId)
        val pos = Entur.vehiclePosition(sjId)
        val now = OffsetDateTime.now()
        val expDep = st.expectedDep ?: st.aimedDep
        val expArr = st.expectedArr ?: st.aimedArr
        val departed = st.actualDep != null || expDep.isBefore(now.minusMinutes(2))
        val cancelled = st.depCancelled || st.arrCancelled
        val name = "${st.line} ${Fmt.hm(st.aimedDep)}"

        if (cancelled && !cancelledAlerted) {
            cancelledAlerted = true
            val where = if (st.depCancelled) st.fromName else st.toName
            alert("Train cancelled", "$name is cancelled at $where.")
        }

        if (!departed && st.platform != null) {
            val old = platform
            if (old != null && old != st.platform) {
                alert("Track change", "$name now leaves from track ${st.platform} (was $old).")
            }
            platform = st.platform
        }

        if (!cancelled) {
            val delay = if (!departed) Fmt.delay(st.aimedDep, expDep) else Fmt.delay(st.aimedArr, expArr)
            val threshold = (prefs?.threshold ?: 3).toLong()
            val detail = if (!departed) {
                "Expected to leave ${st.fromName} at ${Fmt.hm(expDep)}" +
                    (st.platform?.let { ", track $it" } ?: "") + "."
            } else {
                "Expected at ${st.toName} at ${Fmt.hm(expArr)}."
            }
            if (delay >= threshold && abs(delay - lastAlertDelay) >= 2) {
                alert("$name is $delay min late", detail)
                lastAlertDelay = delay
            } else if (delay < threshold && lastAlertDelay >= threshold) {
                alert("$name delay reduced", detail)
                lastAlertDelay = delay
            }
        }

        for (s in st.situations) {
            if (seenSituations.add(s.id)) {
                alert(s.summary.ifBlank { "Traffic notice" }, s.description.ifBlank { s.summary })
            }
        }

        val lines = mutableListOf<String>()
        lines += when {
            cancelled -> "CANCELLED"
            !departed -> "Leaves ${Fmt.hm(expDep)}${Fmt.delayText(st.aimedDep, st.expectedDep)}" +
                (st.platform?.let { " · track $it" } ?: "")
            else -> "Left ${st.fromName}"
        }
        lines += "Arrives ${st.toName} ${Fmt.hm(expArr)}${Fmt.delayText(st.aimedArr, st.expectedArr)}"
        lines += st.position
        if (st.situations.isNotEmpty()) lines += "⚠ ${st.situations.size} traffic notice(s)"
        prefs?.setPosition(pos)
        update("$name ${st.fromName} → ${st.toName}", lines.joinToString("\n"))

        val arrived = st.actualArr != null || now.isAfter(expArr.plusMinutes(15))
        val goneCancelled = cancelled && now.isAfter(st.aimedDep.plusMinutes(30))
        if (arrived || goneCancelled) finish()
    }

    private fun update(title: String, text: String) {
        if (stopped) return
        prefs?.status = "$title\n$text"
        getSystemService(NotificationManager::class.java)
            .notify(Notif.ONGOING_ID, Notif.ongoing(this, title, text))
    }

    private fun alert(title: String, text: String) {
        if (stopped) return
        Notif.alert(this, alertId++, title, text)
    }

    private fun finish() {
        stopped = true
        stopSelf()
    }

    override fun onDestroy() {
        stopped = true
        exec?.shutdownNow()
        prefs?.following = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
