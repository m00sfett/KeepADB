# KeepADB

[![License: AGPL-3.0-or-later](https://img.shields.io/badge/License-AGPLv3%20or%20later-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-11%2B%20(API%2030%2B)-green.svg)](https://developer.android.com/about/versions/11)
[![Zero Dependencies](https://img.shields.io/badge/Dependencies-0%20(Pure%20AOSP)-orange.svg)](#features)

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
- **Webhook Sync & Dev-Automation**: Automatically notifies your local workstation, CI runner, or home server via HTTP whenever Wireless Debugging turns ON or OFF.
- **USB-ADB Host Profiles**: Shows an optional USB connection notification, associates it with an editable host profile, and can register that host alongside WLAN-ADB endpoints.
- **USB → WLAN-ADB Handover**: Optionally offers a notification action or automatically enables WLAN-ADB when a new USB debugging connection appears. The feature is off by default and respects a deliberate manual OFF state.
- **Recovery Diagnostics**: Keeps a small redacted event history on the device and exports it through Android's share sheet when troubleshooting is needed.
- **No Root Required**: Operates using Android's standard `WRITE_SECURE_SETTINGS` permission granted once via ADB.

---

## Features

- ⚡ **Triple Interface**:
  - **Quick Settings Tile**: Place the *Wireless Debugging* tile in your status bar for instant toggling.
  - **Home Screen Widget**: 1x1 interactive widget showing live status.
  - **Main App**: Clean interface with status readout, keep-alive toggle, and current endpoint details.
- 🔄 **Keep-Alive Foreground Service**: Keeps Wireless Debugging alive across reboots, network changes, and sleep states.
- 🔍 **Endpoint Discovery**: mDNS (NSD) is the primary, continuously running discovery path, backed by a quick opportunistic loopback probe for the case where a listener is already up. Typically resolves the active `adbd` port within 1-2 seconds (even with active VPNs like Tailscale).
- 🌐 **Automated Webhook Integration**: Configure a custom HTTP(S) endpoint (LAN, VPN/Tailscale, or local server) in Settings. KeepADB reports WLAN-ADB endpoints and optional USB host-profile state for local automation.
- 📋 **Persistent Notification**: Displays the active connection string (`Port <port> @ <ip>`) for quick reference on your lock screen or notification panel.
- 🔌 **USB-ADB Assistance**: Optional USB notification, editable host profiles, and manual or automatic USB-to-WLAN handover.
- 🧰 **Diagnostics & Reliability**: Exportable redacted diagnostics, battery-optimization guidance, and a direct notification action to turn off WLAN-ADB.
- ⚙️ **Central Settings**: Dedicated settings screen with language, notification, USB handover, diagnostics, and optional webhook controls.
- 🎨 **Adaptive Icon & Theme**: Native adaptive icon (Terminal Prompt + Wi-Fi Broadcast) with Android 13+ Material You monochrome support and a cohesive Dark/Red/Yellow palette using standard system typography.
- 🛡️ **Zero Runtime Dependencies**: Built purely on native Android AOSP framework APIs — no third-party libraries, no custom font bloat, no trackers, and no analytics.
- 🌍 **Multi-Language**: Full localization for 19 major world languages (English, German, Spanish, French, Portuguese, Italian, Dutch, Polish, Ukrainian, Russian, Turkish, Arabic, Hindi, Simplified & Traditional Chinese, Japanese, Korean, Indonesian, Vietnamese) with native Android 13+ Per-App Language Preferences and RTL support.

---

## Getting Started

### 1. Install APK
Download the latest APK from the [GitHub Releases](https://github.com/m00sfett/KeepADB/releases). Inclusion in the official F-Droid catalog is pending.

Or install manually via USB:
```bash
adb install -r KeepADB-v1.4.4.apk
```

### 2. Grant Permission (One-Time Setup)
Because Android protects system settings from unauthorized modification, grant `WRITE_SECURE_SETTINGS` **once** using ADB from your computer via USB:

```bash
adb shell pm grant de.hohnepeople.keepadb android.permission.WRITE_SECURE_SETTINGS
```

> **Note:** This permission persists across reboots. You only need to run this command once after installation.
> KeepADB also displays this exact command in the app until the permission has been granted.

### 3. Usage
- **Quick Settings Tile**: Swipe down your notification shade twice, tap the Edit (pencil) icon, and drag the **KeepADB** tile into your active tiles. Tap to toggle on/off.
- **Home Widget**: Long-press on your home screen, choose Widgets, and add the **KeepADB** widget.
- **Persistent Keep-Alive**: Open the KeepADB app and enable **Keep persistently active**. KeepADB will monitor network state and ensure Wireless Debugging stays active.
- **Settings**: Tap **Settings** in the top header to configure language, notifications, USB host profiles, USB-to-WLAN handover, diagnostics, battery guidance, or the optional webhook endpoint.

---

## Webhook Integration

For developers who want their PC, IDE, or CI setup to automatically discover and connect to their Android device:

1. Open **KeepADB Settings** and enter your webhook URL (e.g. `http://192.168.1.100:5000/api/adb-register` or a Tailscale endpoint).
2. When Wireless Debugging turns **ON**, KeepADB sends:
   ```http
   POST /api/adb-register HTTP/1.1
   Content-Type: application/json

   {"method":"wlan-adb","endpoint":"192.168.1.50:41234"}
   ```
3. When Wireless Debugging turns **OFF**, KeepADB sends:
   ```http
   DELETE /api/adb-register HTTP/1.1
   ```
4. If a USB connection has a selected host profile, KeepADB sends separate `POST` updates
   with `method: "usb-adb"`, an Android-provided device ID, the selected profile's name and
   optional address fields, and `active: true` or `false`.
   The USB notification is a separate user-visible setting and does not control webhook sync.
5. Cleartext HTTP is supported for private LAN / VPN setups. Sensitive URL parts are redacted
   from logs, and webhook URLs are excluded from Android cloud backups.

---

## How It Works

Without root access, third-party apps cannot modify read-only system properties like `service.adb.tcp.port 5555`. Instead, KeepADB manages Android's modern native Wireless Debugging mechanism via `Settings.Global.adb_wifi_enabled` (values `0` or `1`).

When enabled, `adbd` binds to a dynamic high port (30000–50000) and announces itself via mDNS (`_adb-tls-connect._tcp`). KeepADB resolves this service locally and presents the host and port directly in the UI and notification area.

---

## Building and Verification

### Prerequisites
- JDK 17
- Android SDK (compileSdk 35, build-tools 34.0.0, minSdk 30)

### Local Verification & Gates
Run all checks (git diff, unit tests, lint, debug and release builds) with a single command:
```bash
./bin/verify
```

### Build Release APK
```bash
./gradlew assembleRelease
```
The unsigned APK used for reproducibility verification will be located at:
`app/build/outputs/apk/release/app-release-unsigned.apk`.

Published release APKs are signed separately with the project's stable release key. The private key and credentials are never stored in this repository. F-Droid can rebuild the unsigned APK from the tagged source and publish the upstream-signed APK only after both builds match.

---

## Privacy & Security

- **No Internet Telemetry:** KeepADB does not send analytics or crash reports to any external server.
- **No Third-Party SDKs:** 100% open-source code using only Android platform components.
- **Optional Webhook Sync:** By default, no webhook requests are sent. When enabled, KeepADB
  sends the WLAN-ADB endpoint to the URL configured by the user. USB updates additionally
  contain an Android-provided device ID, the selected profile fields, and its active state.
  The device ID and profile data can identify the device or its configured host, so enable the
  webhook only for an endpoint you trust.

### Security Considerations & Best Practices for Wireless Debugging

Wireless Debugging (`adbd`) opens a network port on your local network interface:

1. **Trusted Networks Only:** Keep persistent Keep-Alive enabled primarily on trusted home/office Wi-Fi networks or isolated VPNs (e.g. Tailscale / WireGuard).
2. **Public Wi-Fi Precaution:** When connecting to public Wi-Fi hotspots, guest networks, or unmanaged shared Wi-Fi, turn Wireless Debugging **OFF** (via 1-tap Tile, Widget, or Main App) to prevent unauthorized devices on the local subnet from attempting pairing requests.
3. **Pairing Prompts:** Android requires TLS pairing authentication. **Never confirm unexpected pairing dialogs or unfamiliar RSA key fingerprints** on your device screen.

## Project Identity

- Android application ID and namespace: `de.hohnepeople.keepadb`
- Maintainer: `m00sfett` (Tobias Schultheiß)
- Open source on GitHub: [https://github.com/m00sfett/KeepADB](https://github.com/m00sfett/KeepADB)


---

## License

This project is licensed under the **GNU Affero General Public License v3.0, or (at your option) any later version** — see the [LICENSE](LICENSE) file for details.

This license also covers the bundled application artwork and icons unless a file explicitly states otherwise.
