# KeepAdb

[![License: GPL-3.0](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-11%2B%20(API%2030%2B)-green.svg)](https://developer.android.com/about/versions/11)
[![Zero Dependencies](https://img.shields.io/badge/Dependencies-0%20(Pure%20AOSP)-orange.svg)](#features)
[![APK Size](https://img.shields.io/badge/APK%20Size-%3C100%20KB-brightgreen.svg)](#features)

A lightweight, zero-dependency Android utility to **keep Android's Wireless Debugging (WLAN-ADB) persistently active** and switch it with a single tap — via App, Home Screen Widget, or Quick Settings Tile.

---

## The Problem
Since Android 11, Google provides native **Wireless Debugging** (`Settings.Global.adb_wifi_enabled`), which uses dynamic ports and secure TLS pairing. However:
1. Android automatically turns Wireless Debugging **OFF** upon device reboot.
2. Android frequently disables Wireless Debugging after Wi-Fi network drops, AP handovers, or idle periods.
3. Re-enabling it requires navigating deep into *Settings → System → Developer Options → Wireless Debugging*.

## The Solution: KeepAdb
**KeepAdb** solves this with a tiny, standalone companion tool:
- **1-Tap Toggling**: Enable or disable Wireless ADB instantly from your Quick Settings or Home Screen.
- **Keep-Alive Watchdog**: Automatically restores Wireless ADB when you reconnect to Wi-Fi, switch access points, or restart your phone.
- **Live Endpoint Resolution**: Discovers the dynamic port and local IP address in real time using local mDNS and fast loopback scanning, displaying it right in the notification shade.
- **No Root Required**: Operates using Android's standard `WRITE_SECURE_SETTINGS` permission granted once via ADB.

---

## Features

- ⚡ **Triple Interface**:
  - **Quick Settings Tile**: Place the *Wireless ADB* tile in your status bar for instant toggling.
  - **Home Screen Widget**: 1x1 interactive widget showing live status.
  - **Main App**: Simple interface with status readout, keep-alive toggle, and current endpoint details.
- 🔄 **Keep-Alive Foreground Service**: Keeps Wireless Debugging alive across reboots, network changes, and sleep states.
- 🔍 **Instant Endpoint Discovery**: Combines mDNS NSD discovery with an ultra-fast local port probe to detect the active `adbd` port within milliseconds (even with active VPNs like Tailscale).
- 📋 **Persistent Notification**: Displays the active connection string (`Port <port> @ <ip>`) for quick reference on your lock screen or notification panel.
- 🛡️ **Zero Runtime Dependencies**: Built purely on native Android AOSP framework APIs — no third-party libraries, no trackers, no analytics. Total APK size is **< 100 KB**.
- 🌍 **Internationalized**: Full English and German localization.

---

## Getting Started

### 1. Download and Install
Download the latest APK from the [GitHub Releases](https://github.com/m00sfett/smartphone-wlan-adb-app/releases) page and install it on your device (Android 11 or higher).

### 2. Grant Permission (One-Time Setup)
Because Android protects system settings from unauthorized modification, grant `WRITE_SECURE_SETTINGS` **once** using ADB from your computer via USB:

```bash
adb shell pm grant de.moos.wifiadb android.permission.WRITE_SECURE_SETTINGS
```

> **Note:** This permission persists across reboots. You only need to run this command once after installation.

### 3. Usage
- **Quick Settings Tile**: Swipe down your notification shade twice, tap the Edit (pencil) icon, and drag the **Wireless ADB** tile into your active tiles. Tap to toggle on/off.
- **Home Widget**: Long-press on your home screen, choose Widgets, and add the **KeepAdb** widget.
- **Persistent Keep-Alive**: Open the KeepAdb app and enable **Keep persistently active**. KeepAdb will monitor network state and ensure Wireless Debugging stays active.

---

## How It Works

Without root access, third-party apps cannot modify read-only system properties like `service.adb.tcp.port 5555`. Instead, KeepAdb manages Android's modern native Wireless Debugging mechanism via `Settings.Global.adb_wifi_enabled` (values `0` or `1`).

When enabled, `adbd` binds to a dynamic high port (30000–50000) and announces itself via mDNS (`_adb-tls-connect._tcp`). KeepAdb resolves this service locally and presents the host and port directly in the UI and notification area.

---

## Building from Source

### Prerequisites
- JDK 17
- Android SDK (compileSdk 35, build-tools 35, minSdk 30)

### Build Debug APK
```bash
./gradlew assembleDebug
```
The compiled APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

### Run Lint & Checks
```bash
./gradlew lintDebug
```

---

## Privacy & Security

- **No Internet Telemetry:** KeepAdb does not send analytics, crash reports, or personal data to any external server.
- **No Third-Party SDKs:** 100% open-source code using only Android platform components.
- **Optional Webhook Sync:** For power users and automated developer setups, an optional custom webhook endpoint can be configured in preferences; by default, no network requests are sent.

---

## License

This project is licensed under the **GNU General Public License v3.0** — see the [LICENSE](LICENSE) file for details.

### Third-Party Assets
- **Fira Sans Font** (Mozilla Foundation / Carrois Apostrophe), licensed under the [SIL Open Font License 1.1](third_party/fonts/OFL.txt).
