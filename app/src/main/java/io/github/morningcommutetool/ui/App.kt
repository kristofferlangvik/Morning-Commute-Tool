package io.github.morningcommutetool.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

enum class Screen { Home, AddStation, Settings }

@Composable
fun App(vm: MainViewModel) {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
    when (screen) {
        Screen.Home -> HomeScreen(
            vm,
            onAddStation = { screen = Screen.AddStation },
            onSettings = { screen = Screen.Settings },
        )
        Screen.AddStation -> AddStationScreen(vm, onDone = { screen = Screen.Home })
        Screen.Settings -> SettingsScreen(vm, onBack = { screen = Screen.Home })
    }
}
