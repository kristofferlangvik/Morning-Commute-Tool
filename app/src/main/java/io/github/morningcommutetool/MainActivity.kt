package io.github.morningcommutetool

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.time.OffsetDateTime

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var fromBtn: Button
    private lateinit var toBtn: Button
    private lateinit var message: TextView
    private lateinit var depList: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var mapBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var autoTimeBtn: Button
    private lateinit var thresholdBtn: Button

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Notif.ensureChannels(this)

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(root)
        })

        root.addView(header("Route"))
        fromBtn = button("") { pickStop(true) }
        toBtn = button("") { pickStop(false) }
        root.addView(fromBtn)
        root.addView(toBtn)
        root.addView(button("⇅  Swap direction") { swap() })
        root.addView(button("Show next direct trains") { loadDepartures() })
        message = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        root.addView(message)
        depList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(depList)

        root.addView(header("Following"))
        statusText = TextView(this).apply { textSize = 15f }
        root.addView(statusText)
        mapBtn = button("Show train on map") { openMap() }
        stopBtn = button("Stop following") {
            stopService(Intent(this, FollowService::class.java))
            prefs.following = false
            refreshStatus()
        }
        root.addView(mapBtn)
        root.addView(stopBtn)

        root.addView(header("Daily auto-follow (weekdays)"))
        root.addView(Switch(this).apply {
            text = "Follow my usual train automatically"
            isChecked = prefs.autoEnabled
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, checked ->
                prefs.autoEnabled = checked
                Scheduler.reschedule(this@MainActivity)
            }
        })
        autoTimeBtn = button("") { pickTime() }
        thresholdBtn = button("") { pickThreshold() }
        root.addView(autoTimeBtn)
        root.addView(thresholdBtn)
        root.addView(TextView(this).apply {
            text = "Uses the route above. Tracking starts 20 minutes before the train. " +
                "All settings are stored only on this phone."
            textSize = 12f
        })

        refreshLabels()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        Scheduler.reschedule(this)
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    // ---------- UI helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun bg(work: () -> Unit) = Thread {
        try {
            work()
        } catch (e: Exception) {
            runOnUiThread { message.text = "Error: ${e.message}" }
        }
    }.start()

    private fun refreshLabels() {
        fromBtn.text = "From: ${prefs.fromName ?: "tap to choose"}"
        toBtn.text = "To: ${prefs.toName ?: "tap to choose"}"
        val m = prefs.autoMinutes
        autoTimeBtn.text = "Usual train leaves at %02d:%02d".format(m / 60, m % 60)
        thresholdBtn.text = "Alert when ${prefs.threshold}+ min late"
    }

    private fun refreshStatus() {
        val following = prefs.following
        statusText.text = if (following) prefs.status else "Not following a train right now."
        stopBtn.visibility = if (following) View.VISIBLE else View.GONE
        mapBtn.visibility = if (following && prefs.position != null) View.VISIBLE else View.GONE
    }

    // ---------- Actions ----------

    private fun pickStop(isFrom: Boolean) {
        val input = EditText(this).apply {
            hint = "Station name"
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle(if (isFrom) "From station" else "To station")
            .setView(input)
            .setPositiveButton("Search") { _, _ -> searchStops(isFrom, input.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun searchStops(isFrom: Boolean, text: String) {
        if (text.isEmpty()) return
        message.text = "Searching…"
        bg {
            val stops = Entur.searchStops(text)
            runOnUiThread {
                if (stops.isEmpty()) {
                    message.text = "No stations found for \"$text\"."
                    return@runOnUiThread
                }
                message.text = ""
                val labels = stops.map { if (it.detail.isBlank()) it.name else "${it.name}\n${it.detail}" }
                AlertDialog.Builder(this)
                    .setTitle("Choose station")
                    .setItems(labels.toTypedArray()) { _, i ->
                        val s = stops[i]
                        if (isFrom) {
                            prefs.fromId = s.id; prefs.fromName = s.name
                        } else {
                            prefs.toId = s.id; prefs.toName = s.name
                        }
                        depList.removeAllViews()
                        refreshLabels()
                        Scheduler.reschedule(this)
                    }
                    .show()
            }
        }
    }

    private fun swap() {
        val id = prefs.fromId
        val name = prefs.fromName
        prefs.fromId = prefs.toId
        prefs.fromName = prefs.toName
        prefs.toId = id
        prefs.toName = name
        depList.removeAllViews()
        refreshLabels()
        Scheduler.reschedule(this)
    }

    private fun loadDepartures() {
        val from = prefs.fromId
        val to = prefs.toId
        if (from == null || to == null) {
            message.text = "Choose both stations first."
            return
        }
        message.text = "Loading…"
        depList.removeAllViews()
        bg {
            val deps = Entur.departures(from, to, OffsetDateTime.now(Fmt.OSLO))
            runOnUiThread {
                message.text = if (deps.isEmpty()) "No direct trains found." else "Tap a train to follow it:"
                for (d in deps) {
                    depList.addView(button(describe(d)) { confirmFollow(d) }.apply {
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    })
                }
            }
        }
    }

    private fun describe(d: Entur.Departure): String {
        val sb = StringBuilder(Fmt.hm(d.aimedDep))
        d.expectedDep?.let {
            val m = Fmt.delay(d.aimedDep, it)
            if (m != 0L) sb.append("  → now ${Fmt.hm(it)} (${if (m > 0) "+" else ""}$m)")
        }
        sb.append("   arr. ").append(d.aimedArr?.let { Fmt.hm(it) } ?: "?")
        sb.append("\n").append(d.line)
        d.destination?.let { sb.append(" to ").append(it) }
        d.platform?.let { sb.append(" · track ").append(it) }
        if (d.cancelled) sb.append("\nCANCELLED")
        return sb.toString()
    }

    private fun confirmFollow(d: Entur.Departure) {
        AlertDialog.Builder(this)
            .setTitle("Follow the ${Fmt.hm(d.aimedDep)} ${d.line}?")
            .setMessage("You'll get notifications for delays, track changes, cancellations and disruptions until it arrives.")
            .setPositiveButton("Follow") { _, _ ->
                startForegroundService(
                    Intent(this, FollowService::class.java)
                        .setAction(FollowService.ACTION_FOLLOW)
                        .putExtra(FollowService.EX_SJ, d.serviceJourneyId)
                        .putExtra(FollowService.EX_DATE, d.date)
                )
                prefs.following = true
                prefs.status = "Starting…"
                refreshStatus()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickTime() {
        val m = prefs.autoMinutes
        TimePickerDialog(this, { _, h, min ->
            prefs.autoMinutes = h * 60 + min
            refreshLabels()
            Scheduler.reschedule(this)
        }, m / 60, m % 60, true).show()
    }

    private fun pickThreshold() {
        val options = intArrayOf(1, 2, 3, 5, 10)
        AlertDialog.Builder(this)
            .setTitle("Alert when delayed by at least")
            .setItems(options.map { "$it min" }.toTypedArray()) { _, i ->
                prefs.threshold = options[i]
                refreshLabels()
            }
            .show()
    }

    private fun openMap() {
        val p = prefs.position ?: return
        val uri = Uri.parse("geo:${p.first},${p.second}?q=${p.first},${p.second}(Train)")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            message.text = "No map app found."
        }
    }
}
