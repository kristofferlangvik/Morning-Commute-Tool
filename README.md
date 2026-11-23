# Morning Commute Tool

A small personal Android app for keeping an eye on Norwegian trains, using real-time data from [Entur](https://developer.entur.org)'s open APIs.

## Features

- **Saved stations.** Search for any station and add it. Each station shows its upcoming departures: time (large), line, destination, live status and track.
- **Filters = watched trains.** Per station, pick lines and/or destinations from chips. Matching trains are the only ones shown for that station, and they are *watched*.
- **Alerts** for watched trains leaving within 90 minutes: delayed beyond your threshold (default 3 min), cancelled, track change, or a new traffic notice. Each change is notified once; updates replace the earlier notification. Separate "Delays and changes" channel.
- **Home-screen widget** with the next watched departure (line, time, delay, track) and the one after.
- **Active hours.** During windows you set (e.g. weekday mornings), the app checks every minute and shows the next watched train in a silent ongoing notification. Outside them it checks every 15 minutes in the background.
- **Follow a train.** Tap a departure → *Follow this train* for a live status notification showing where the train is, until it leaves your station. On Android 16+ this is a Live Update.
- Material 3 with dynamic colour, light/dark, TalkBack labels, and colour is never the only signal for delays or cancellations.

Your stations, filters and settings are stored only on the phone. Nothing personal is in this repository.

## Install

Every push to `main` builds a signed APK and publishes it under **Releases**.

1. Install [Obtainium](https://github.com/ImranR98/Obtainium).
2. Add this repository's URL as an app source. Obtainium installs the latest release and picks up new ones.
3. Open the app, add a station, set a filter, and allow notifications when asked.

## Tips

- If alerts or the active-hours notification arrive late, set the app's battery use to *Unrestricted* (Settings → Reliability).
- Live Updates can be switched off per app in system settings; there's a shortcut in the app's settings.

## Development

- Kotlin, Jetpack Compose, Glance (widget), WorkManager, DataStore. AGP 9 / Gradle 9, compile SDK 37.2, min SDK 26.
- `./gradlew assembleDebug` builds; `./gradlew testDebugUnitTest` runs unit tests and renders screenshots of the main screens with sample data to `app/build/screenshots/`.
- `ENTUR_LIVE=1 ./gradlew testDebugUnitTest --tests '*EnturLiveTest*'` checks the queries against the real API.
- The signing key in `app/release.keystore` is intentionally public. It only lets new builds install over old ones; it is not a secret.

Departure data © Entur, licensed under NLOD.
