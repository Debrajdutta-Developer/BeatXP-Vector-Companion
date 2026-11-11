# BeatXP Vector Companion

A native Android companion app for the BeatXP Vector smartwatch. The project uses Kotlin and Jetpack Compose and communicates with the watch over Bluetooth Low Energy (BLE).

## Project status

This repository is intended to contain the Android Studio project directly at its root. The application module is `app/`.

## Features in this source snapshot

- BLE scanning and connection flow for the configured watch.
- Nordic UART and JieLi RCSP service support.
- RCSP frame encoding and acknowledgement handling.
- Watch-face image preparation and transfer UI.
- Device information, battery telemetry parsing, and diagnostic logs.
- Optional notification-listener bridge.

## Build

Requirements:

- JDK 17
- Android SDK Platform 35
- Gradle 8.9 or the generated Gradle wrapper

Build a debug APK:

```bash
gradle wrapper --gradle-version 8.9
./gradlew assembleDebug
```

The APK is written to:

```
app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions

The workflow at `.github/workflows/build-apk.yml` builds the debug APK on pushes to `main` and on manual dispatch. The APK is uploaded as the `BeatXP-Vector-Companion-debug-apk` artifact.

## Important protocol note

The BLE identifiers and RCSP framing are based on the protocol details available during development. Some watch-specific payload semantics, including transfer-start fields and notification forwarding, still require validation against real device captures. A successful Android build does not prove every watch operation is compatible with every BeatXP Vector firmware version.

## Permissions and privacy

The app does not require an account or cloud backend. Bluetooth and notification access are requested for their respective features. Notification forwarding is opt-in through Android's notification access settings.
