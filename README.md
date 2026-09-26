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
- **Keep-Alive Watchdog**: Tries to restore Wireless Debugging after Wi-Fi reconnects, access-point changes, or a restart. If Android does not confirm an automatic enable, KeepADB backs off instead of retrying rapidly.
- **Live Endpoint Resolution**: Discovers the dynamic port and local IP address through Android's mDNS service discovery (`_adb-tls-connect._tcp`). Discovery time depends on network conditions.
- **Webhook Sync & Dev-Automation**: When enabled, reports verified WLAN-ADB endpoint changes to the user-configured local workstation or automation service. Turning Wireless Debugging off triggers an unregister request.
- **USB-ADB Host Profiles**: Shows an optional USB connection notification and keeps editable host profiles locally on the device. USB profile data is not sent through the current webhook contract.
- **USB → WLAN-ADB Handover**: Optionally offers a notification action or automatically enables WLAN-ADB when a new USB debugging connection appears. The feature is off by default and respects a deliberate manual OFF state.
- **Recovery Diagnostics**: Keeps a small redacted event history on the device and exports it through Android's share sheet when troubleshooting is needed.
- **No Root Required**: Operates using Android's standard `WRITE_SECURE_SETTINGS` permission granted once via ADB.

---

## Features

- ⚡ **Triple Interface**:
  - **Quick Settings Tile**: Place the *Wireless Debugging* tile in your status bar for instant toggling.
  - **Home Screen Widget**: 1x1 interactive widget showing live status.
  - **Main App**: Clean interface with status readout, keep-alive toggle, and current endpoint details.
- 🔄 **Keep-Alive Foreground Service**: Monitors Wireless Debugging across reboots, network changes, and idle periods, and attempts recovery when needed.
- 🔍 **Endpoint Discovery**: Android mDNS (NSD) is the discovery path for the active WLAN-ADB endpoint; resolution time depends on the network.
- 🌐 **Automated Webhook Integration**: Configure an optional HTTP(S) endpoint in Settings. KeepADB currently sends WLAN-ADB events; it holds back additional verified transport events until the receiving server accepts their method names.
- 📋 **Persistent Notification**: Displays the active connection string (`Port <port> @ <ip>`) for quick reference on your lock screen or notification panel.
- 🔌 **USB-ADB Assistance**: Optional USB notification, local editable host profiles, and manual or automatic USB-to-WLAN handover.
- 🛜 **Network (Beta)**: Optional Wi-Fi and access-point tools, including trusted-network controls. On new installs, trusted-network filtering is off; enabling it requires Android location access to identify networks and can limit background recovery when Android masks that identity.
- 👁️ **Privacy Mode**: Hide network addresses in the app's UI. This is a display setting; it does not change the endpoint reported to a configured webhook.
- 🧰 **Diagnostics & Reliability**: Exportable redacted diagnostics, battery-optimization guidance, and a direct notification action to turn off WLAN-ADB.
- ⚙️ **Central Settings**: Dedicated settings screen with language, notification, USB handover, Network (Beta), privacy, diagnostics, and optional webhook controls.
- 🎨 **Adaptive Icon & Theme**: Native adaptive icon (Terminal Prompt + Wi-Fi Broadcast) with Android 13+ Material You monochrome support and a cohesive Dark/Red/Yellow palette using standard system typography.
- 🛡️ **Zero Runtime Dependencies**: Built purely on native Android AOSP framework APIs — no third-party libraries, no custom font bloat, no trackers, and no analytics.
- 🌍 **Multi-Language**: Full localization for 19 major world languages (English, German, Spanish, French, Portuguese, Italian, Dutch, Polish, Ukrainian, Russian, Turkish, Arabic, Hindi, Simplified & Traditional Chinese, Japanese, Korean, Indonesian, Vietnamese) with native Android 13+ Per-App Language Preferences and RTL support.

---

## Getting Started

### 1. Install APK
Download the latest APK from the [GitHub Releases](https://github.com/m00sfett/KeepADB/releases). Inclusion in the official F-Droid catalog is pending.

To install an APK you downloaded manually via USB:
```bash
adb install -r /path/to/KeepADB.apk
```

### 2. Grant Permission (One-Time Setup)
Because Android protects system settings from unauthorized modification, grant `WRITE_SECURE_SETTINGS` **once** using ADB from your computer via USB:

```bash
adb shell pm grant de.hohnepeople.keepadb android.permission.WRITE_SECURE_SETTINGS
```

> **Note:** This permission persists across reboots. You only need to run this command once after installation.
> KeepADB also displays this exact command in the app until the permission has been granted.

### 3. Usage
- **Quick Settings Tile**: Swipe down your notification shade twice, tap the Edit (pencil) icon, and drag the **KeepADB** tile into your active tiles. Tap to toggle on/off. On a locked device, switching on asks you to unlock first.
- **Home Widget**: Long-press on your home screen, choose Widgets, and add the **KeepADB** widget.
- **Persistent Keep-Alive**: Open the KeepADB app and enable **Keep persistently active**. KeepADB monitors network state and attempts recovery. Android may ask you to approve Wireless Debugging for a network. If Android does not confirm the change, KeepADB backs off instead of retrying rapidly; approve only a system prompt you expect.
- **Settings**: Tap **Settings** in the top header to configure language, notifications, local USB host profiles, USB-to-WLAN handover, Network (Beta), privacy mode, diagnostics, battery guidance, or the optional webhook endpoint.

---

## Webhook Integration

For developers who want their PC, IDE, or CI setup to automatically discover and connect to their Android device:

1. Open **KeepADB Settings** and enter your webhook URL (e.g. `http://192.168.1.100:5000/api/adb-register` or a Tailscale endpoint).
2. When Wireless Debugging is on and its endpoint is verified, KeepADB sends a contract-v2 event for the WLAN-ADB endpoint:
   ```http
   POST /api/adb-register HTTP/1.1
   Content-Type: application/json

   {"contract_version":2,"method":"wlan-adb","active":true,"endpoint":"192.168.1.50:41234","source":"keepadb-app","observed_at":"<UTC timestamp>","event_id":"<idempotency id>"}
   ```
3. When Wireless Debugging turns **OFF**, KeepADB sends an HTTP `DELETE` request to unregister the previously reported WLAN endpoint. Failed cleanup is retried later.
4. USB profiles remain on the device. KeepADB holds additional verified transport events back instead of sending methods the receiving server does not currently accept.
5. Cleartext HTTP is supported for private LAN / VPN setups. Sensitive URL parts are redacted
   from logs, and webhook URLs are excluded from Android cloud backups.

---

## How It Works

Without root access, third-party apps cannot modify read-only system properties like `service.adb.tcp.port 5555`. Instead, KeepADB manages Android's modern native Wireless Debugging mechanism via `Settings.Global.adb_wifi_enabled` (values `0` or `1`).

When enabled, `adbd` binds to a dynamic high port and announces itself via mDNS (`_adb-tls-connect._tcp`). KeepADB uses Android's NSD/mDNS discovery to resolve the service and presents its verified host and port in the UI and notification area. There is no loopback port-range scan; resolution time depends on Android and network timing.

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
./bin/gradlew assembleRelease
```
`bin/gradlew` sets `JAVA_HOME` to JDK 17 before invoking the real wrapper, so builds don't depend on the system default JDK. Prefer it over calling `./gradlew` directly.
The unsigned APK used for reproducibility verification will be located at:
`app/build/outputs/apk/release/app-release-unsigned.apk`.

Published release APKs are signed separately with the project's stable release key. The private key and credentials are never stored in this repository. F-Droid can rebuild the unsigned APK from the tagged source and publish the upstream-signed APK only after both builds match.

### Minification (R8)

The release build type enables `minifyEnabled`/`shrinkResources` (see `app/proguard-rules.pro`).
KeepADB has zero runtime dependencies, so R8's main effect is trimming unused platform-API
wrapper code rather than removing a large dependency graph; every manifest-declared component
(activities, services, receivers) is preserved automatically by AGP's manifest-based keep rules,
reinforced with explicit `-keep` rules in `proguard-rules.pro` as documentation. If a future
change ever needs to disable this (an R8-only crash that can't be fixed with a keep rule, or a
reproducibility regression against F-Droid's rebuild), set `minifyEnabled false` back in
`app/build.gradle` and record the reason next to it — R8 is a trade-off the project opted into,
not an assumed default.

---

## Privacy & Security

- **No Internet Telemetry:** KeepADB does not send analytics or crash reports to any external server.
- **No Third-Party SDKs:** 100% open-source code using only Android platform components.
- **Optional Webhook Sync:** By default, no webhook requests are sent. When enabled, KeepADB
  sends verified WLAN-ADB endpoint events to the URL configured by the user and attempts to
  unregister the endpoint when Wireless Debugging turns off. USB profile data stays local and is
  not included in the current webhook requests. Enable the webhook only for an endpoint you trust.
- **Cleartext HTTP Scope:** The app's network-security configuration permits cleartext
  (unencrypted) HTTP globally, but only one code path in the app ever issues an HTTP request:
  the optional webhook above, whose target is a URL you type in yourself. Android's
  network-security-config cannot scope cleartext permission to "arbitrary LAN/VPN hosts" —
  only to specific known domain names — so a global allowance is the narrowest option
  available when the host isn't known until you configure it. If you enter an `http://`
  webhook URL, the Settings screen shows an explicit warning that the payload will be sent
  unencrypted; prefer `https://` whenever your endpoint supports it, and only use `http://`
  on a network you trust (LAN/VPN).
- **Backup & Device Transfer:** KeepADB does not support Android cloud backup or
  device-to-device transfer of app data. `android:allowBackup="false"` covers pre-Android-12
  (API < 31) devices; on API 31+, `android:dataExtractionRules` (`res/xml/data_extraction_rules.xml`)
  additionally excludes every domain from both `<cloud-backup>` and `<device-transfer>`, because
  some OEMs honor `allowBackup="false"` for cloud backup but still perform device-to-device
  transfer regardless of that flag ([#573](https://github.com/m00sfett/KeepADB/issues/573)). All persisted
  configuration — including webhook URLs, endpoint data, USB profiles, and diagnostics — is
  lost on uninstall or device migration and must be reconfigured afterward. This is a
  deliberate choice: it removes any risk of sensitive configuration being restored onto a
  different device without the same trust assumptions.

### Security Considerations & Best Practices for Wireless Debugging

Wireless Debugging (`adbd`) opens a network port on your local network interface:

1. **Trusted Networks Only:** Keep persistent Keep-Alive enabled primarily on trusted home/office Wi-Fi networks or isolated VPNs (e.g. Tailscale / WireGuard).
2. **Public Wi-Fi Precaution:** When connecting to public Wi-Fi hotspots, guest networks, or unmanaged shared Wi-Fi, turn Wireless Debugging **OFF** (via 1-tap Tile, Widget, or Main App) to prevent unauthorized devices on the local subnet from attempting pairing requests.
3. **Pairing Prompts:** Android requires TLS pairing authentication. **Never confirm unexpected pairing dialogs or unfamiliar RSA key fingerprints** on your device screen.
4. **Trusted Networks (optional, Network Beta):** On a new installation, Keep-Alive can use any
   connected Wi-Fi network by default. You can opt into the trusted-network restriction in
   Settings → Network (Beta). Android requires location access to provide Wi-Fi network
   identifiers; KeepADB uses it only to identify the network and does not read or store location.
   Android may mask the network identity while KeepADB is in the background, which can pause
   automatic recovery when the app cannot verify the network. Existing mode choices and trusted
   entries are preserved when upgrading. The main switch, tile, and widget remain manual overrides
   not gated by this setting. Turning the Keep-Alive toggle on does respect it: on an untrusted
   network it falls through to the trust prompt instead of enabling immediately (#577). Trusting a
   network from that prompt notification requires an unlocked device (#578). Switching Wireless
   Debugging on from the Quick Settings tile also requires an unlocked device; switching it off
   from the lock screen works without unlocking (#586). Switching Wireless Debugging on from the
   USB notification's "Enable WLAN-ADB" handover action also requires an unlocked device (#588).
5. **Android network approval:** Android may show a system prompt the first time Wireless
   Debugging is enabled on a Wi-Fi network. Confirm only a prompt you expect. If Android does not
   confirm the automatic change, KeepADB backs off instead of retrying rapidly.

## Project Identity

- Android application ID and namespace: `de.hohnepeople.keepadb`
- Maintainer: `m00sfett` (Tobias Schultheiß)
- Open source on GitHub: [https://github.com/m00sfett/KeepADB](https://github.com/m00sfett/KeepADB)

## Contributing & Security

- See [CONTRIBUTING.md](CONTRIBUTING.md) for setup, verification, coding conventions, and
  translation instructions.
- See [SECURITY.md](SECURITY.md) for the threat model, supported versions, and how to report a
  vulnerability privately (please don't use a public GitHub issue for that).

---

## License

This project is licensed under the **GNU Affero General Public License v3.0, or (at your option) any later version** — see the [LICENSE](LICENSE) file for details.

This license also covers the bundled application artwork and icons unless a file explicitly states otherwise.
