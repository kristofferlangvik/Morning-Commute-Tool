package io.github.morningcommutetool

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.morningcommutetool.bg.Scheduler
import io.github.morningcommutetool.notify.Notifications
import io.github.morningcommutetool.ui.App
import io.github.morningcommutetool.ui.AppTheme
import io.github.morningcommutetool.ui.MainViewModel

class CommuteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_STATION = "station_id"
    }

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            AppTheme { App(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        // Allowed to start the live service from here if we're inside an active window.
        Scheduler.sync(this)
    }

    private fun handle(intent: Intent?) {
        intent?.getStringExtra(EXTRA_STATION)?.let { vm.focusStation(it) }
    }
}
