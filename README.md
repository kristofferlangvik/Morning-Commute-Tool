# Morning Commute Tool

A small personal Android app for following a Norwegian train in real time, with data from [Entur](https://developer.entur.org)'s open APIs.

- Pick any two stations and see the next direct trains
- Follow a train: live departure/arrival times, track, and where it is on the line
- Notifications for delays (configurable threshold), track changes, cancellations and traffic notices
- Optional weekday auto-follow of a usual train
- Train GPS position on a map where the operator reports it

All personal settings (stations, times) are stored only on the phone, never in this repo.

## Install

Every push to `main` builds a signed APK and publishes it under **Releases**.

1. Install [Obtainium](https://github.com/ImranR98/Obtainium) on the phone.
2. Add this repository's URL as an app source. Obtainium installs the latest release and picks up new ones.
3. On first launch, allow notifications.

(Or download `commute-tool.apk` from the latest release and install it manually.)

## Notes

- Only direct trains are listed (no changes). Rail replacement buses are not shown.
- While following, the app checks Entur every 30 seconds and stops automatically after arrival.
- Auto-follow uses an exact alarm 20 minutes before the usual train. Some phones' battery savers can delay it; exempting the app from battery optimisation helps.
- The signing key in `app/release.keystore` is intentionally public. It only makes updates install cleanly; it is not a secret.
