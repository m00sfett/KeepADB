# KeepADB

[![License: AGPL-3.0-or-later](https://img.shields.io/badge/License-AGPLv3%20or%20later-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-11%2B%20(API%2030%2B)-green.svg)](https://developer.android.com/about/versions/11)
[![Zero Dependencies](https://img.shields.io/badge/Dependencies-0%20(Pure%20AOSP)-orange.svg)](#features)
[![APK Size](https://img.shields.io/badge/APK%20Size-%3C350%20KB-brightgreen.svg)](#features)

A lightweight, zero-dependency Android utility to keep Android's **Wireless Debugging** persistently active and switch it with a single tap — via app, Home Screen Widget, or Quick Settings Tile.

---

## The Problem
Since Android 11, Google provides native **Wireless Debugging** (`Settings.Global.adb_wifi_enabled`), which uses dynamic ports and secure TLS pairing. However:
1. Android automatically turns Wireless Debugging **OFF** upon device reboot.
2. Android frequently disables Wireless Debugging after Wi-Fi network drops, AP handovers, or idle periods.
3. Re-enabling it requires navigating deep into *Settings → System → Developer Options → Wireless Debugging*.

## The Solution: KeepADB
**KeepADB** solves this with a tiny, standalone companion tool:
- **1-Tap Toggling**: Enable or disable Wireless Debugging instantly from your Quick Settings or Home Screen.
- **Keep-Alive Watchdog**: Automatically restores Wireless Debugging when you reconnect to Wi-Fi, switch access points, or restart your phone.
- **Live Endpoint Resolution**: Discovers the dynamic port and local IP address (typically within 1-2 seconds) using mDNS as the primary path plus an opportunistic loopback probe, displaying it right in the notification shade.
- **No Root Required**: Operates using Android's standard `WRITE_SECURE_SETTINGS` permission granted once via ADB.

---

## Features

- ⚡ **Triple Interface**:
  - **Quick Settings Tile**: Place the *Wireless Debugging* tile in your status bar for instant toggling.
  - **Home Screen Widget**: 1x1 interactive widget showing live status.
  - **Main App**: Clean interface with status readout, keep-alive toggle, and current endpoint details.
- 🔄 **Keep-Alive Foreground Service**: Keeps Wireless Debugging alive across reboots, network changes, and sleep states.
- 🔍 **Endpoint Discovery**: mDNS (NSD) is the primary, continuously running discovery path, backed by a quick opportunistic loopback probe for the case where a listener is already up. Typically resolves the active `adbd` port within 1-2 seconds (even with active VPNs like Tailscale) — a full local port-range scan alone was measured to cost several seconds of Android framework overhead per attempt, so it is no longer relied on as the primary path.
- 📋 **Persistent Notification**: Displays the active connection string (`Port <port> @ <ip>`) for quick reference on your lock screen or notification panel.
- ⚙️ **Central Settings**: Dedicated settings screen with in-app language switching and optional webhook endpoint configuration.
- 🎨 **Adaptive Icon & Theme**: Native adaptive icon (Terminal Prompt + Wi-Fi Broadcast) with Android 13+ Material You monochrome support and a cohesive Dark/Red/Yellow palette using standard system typography.
- 🛡️ **Zero Runtime Dependencies**: Built purely on native Android AOSP framework APIs — no third-party libraries, no custom font bloat, no trackers, no analytics. Total APK size is **< 350 KB**.
- 🌍 **Multi-Language**: Full localization for 19 major world languages (English, German, Spanish, French, Portuguese, Italian, Dutch, Polish, Ukrainian, Russian, Turkish, Arabic, Hindi, Simplified & Traditional Chinese, Japanese, Korean, Indonesian, Vietnamese) with native Android 13+ Per-App Language Preferences and RTL support.

---

## Getting Started

### 1. Install over USB
Connect an Android 11 or newer device with USB debugging enabled and install the APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. Grant Permission (One-Time Setup)
Because Android protects system settings from unauthorized modification, grant `WRITE_SECURE_SETTINGS` **once** using ADB from your computer via USB:

```bash
adb shell pm grant de.hohnepeople.keepadb android.permission.WRITE_SECURE_SETTINGS
```

> **Note:** This permission persists across reboots. You only need to run this command once after installation.
> KeepADB also displays this exact command in the app until the permission has been granted.

### 3. Usage
- **Quick Settings Tile**: Swipe down your notification shade twice, tap the Edit (pencil) icon, and drag the **Wireless Debugging** tile into your active tiles. Tap to toggle on/off.
- **Home Widget**: Long-press on your home screen, choose Widgets, and add the **KeepADB** widget.
- **Persistent Keep-Alive**: Open the KeepADB app and enable **Keep persistently active**. KeepADB will monitor network state and ensure Wireless Debugging stays active.
- **Settings & Localization**: Tap **Settings** in the top header to choose your preferred language or configure an optional webhook endpoint.

---

## How It Works

Without root access, third-party apps cannot modify read-only system properties like `service.adb.tcp.port 5555`. Instead, KeepADB manages Android's modern native Wireless Debugging mechanism via `Settings.Global.adb_wifi_enabled` (values `0` or `1`).

When enabled, `adbd` binds to a dynamic high port (30000–50000) and announces itself via mDNS (`_adb-tls-connect._tcp`). KeepADB resolves this service locally and presents the host and port directly in the UI and notification area.

---

## Building and Verification

### Prerequisites
- JDK 17
- Android SDK (compileSdk 35, build-tools 35, minSdk 30)

### Local Verification & Gates
Run all checks (git diff, unit tests, lint, debug and release builds) with a single command:
```bash
./bin/verify
```

### Build Release APK
```bash
./gradlew assembleRelease
```
The release APK will be located at:
`app/build/outputs/apk/release/app-release.apk` (~311 KB)

---

## Privacy & Security

- **No Internet Telemetry:** KeepADB does not send analytics, crash reports, or personal data to any external server.
- **No Third-Party SDKs:** 100% open-source code using only Android platform components.
- **Optional Webhook Sync:** For power users and automated developer setups, an optional custom webhook endpoint can be configured in preferences; by default, no network requests are sent.

## Project Identity and Publication Status

- Android application ID and namespace: `de.hohnepeople.keepadb`
- The namespace is based on `hohnepeople.de`, a domain owned by the maintainer.
- DNS and the public website for `hohnepeople.de` still need to be configured before publication.
- The GitHub repository remains private until the application, setup flow, release process, and documentation have been fully reviewed.

The new application ID intentionally creates a separate Android app. Existing development installations are not upgraded in place; install and authorize KeepADB once over USB, verify it, and only then remove the previous development build if desired.

---

## License

This project is licensed under the **GNU Affero General Public License v3.0, or (at your option) any later version** — see the [LICENSE](LICENSE) file for details.
