# KeepADB

[![License: AGPL-3.0-or-later](https://img.shields.io/badge/License-AGPLv3%20or%20later-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-11%2B%20(API%2030%2B)-green.svg)](https://developer.android.com/about/versions/11)
![Runtime dependencies: none](https://img.shields.io/badge/Runtime--dependencies-0-orange.svg)

KeepADB switches **Wifi-ADB**, Android's “Wireless debugging” setting, from the app, a home-screen
widget, or a Quick Settings tile. The optional Keep-Alive service can turn it back on after a
network interruption or reboot, subject to Android, the selected network rule, and the user's most
recently saved intent.

- Android 11 or newer (API 30+)
- No root required
- No runtime dependencies in the shipped app
- Application ID: `de.hohnepeople.keepadb`

KeepADB controls Android's global `Settings.Global.adb_wifi_enabled` setting. Regular endpoint discovery uses Android mDNS (`_adb-tls-connect._tcp`) without scanning arbitrary network hosts; only the optional on-device self-pairing setup checks local loopback ports for an already-running `adbd` if mDNS misses it. Android still requires its own TLS pairing before a new ADB client can connect.

## Highlights

- **Setup without a PC (Android 11+ self-pairing):** Grant the one-time `WRITE_SECURE_SETTINGS` permission directly on your phone — no computer and no USB cable required. KeepADB pairs locally over loopback TLS 1.3 + SPAKE2 using Android's pairing dialog code and discards its ephemeral key afterwards.
- **Browser WebUSB & classic USB `adb` fallbacks:** If self-pairing cannot be used, open [`https://keepadb.roteson.de/setup`](https://keepadb.roteson.de/setup) in Chrome or Edge to grant the permission over USB right from your browser (nothing leaves the browser), or use the proven manual `adb` command (with a one-tap **Share command** action in the setup assistant).
- **Control from anywhere:** Main screen switch, Quick Settings tile, **launcher shortcuts** (long-press the app icon for Toggle Wifi-ADB, Wi-Fi networks, and Settings), and two adaptive home-screen widgets (**KeepADB controls** and **KeepADB overview**).
- **Network Protection & Force mode:** Keep-Alive recovers Wifi-ADB only where your protection level permits (**Maximum security** by BSSID or **Balanced** by Wi-Fi name), optionally turns off on untrusted Wi-Fi, checks for security-tier downgrades on Android 12+, and offers timed or unlimited **Force mode**.
- **Webhook Sync with opt-in periodic refresh:** Send verified endpoints via HTTP(S) and optionally re-send active endpoints on a configurable schedule (`15 min` to `23 h 59 min`, based on work by `@inaciopestana`) so external registers that expire idle entries stay current.
- **Zero bloat & four privacy switches:** No runtime dependencies, no ads, no analytics, and independent switches to mask names and addresses in the app, notifications, lock screen, and widgets/tile.

## Install and first start

Install KeepADB from the [official F-Droid catalog](https://f-droid.org/packages/de.hohnepeople.keepadb/)
or download the APK from [GitHub Releases](https://github.com/m00sfett/KeepADB/releases).

Then grant `WRITE_SECURE_SETTINGS` once in the setup assistant (**Permissions › System permission**) using the recommended method or either fallback:

1. **On the phone (Android 11+, recommended, no computer needed):** start **Grant on this phone**, open Wifi-ADB's pairing dialog in developer options, and enter the six-digit pairing code in KeepADB's notification. KeepADB grants the permission to itself over loopback, discards its in-memory pairing key, and switches Wifi-ADB back off when it turned it on for setup.
2. **Fallback — in a computer browser (`https://keepadb.roteson.de/setup`):** if self-pairing cannot be used or fails, open the setup page in Chrome or Edge, enable USB debugging, connect the phone with a USB cable, and follow the three steps (no device data leaves the browser).
3. **Fallback — manually over USB (`adb`):** if neither on-device self-pairing nor WebUSB is an option, use the setup assistant's **Share command** button or run the proven CLI command:

~~~sh
adb shell pm grant de.hohnepeople.keepadb android.permission.WRITE_SECURE_SETTINGS
~~~

This Android permission applies to the installed app and survives a reboot. It must be granted
again after uninstalling and reinstalling the app. KeepADB cannot grant it silently in the
background and guides you through all three options in the setup assistant while the permission is missing.

## Use KeepADB

- **App:** turn Wifi-ADB on or off and view its current state.
- **Widget:** toggle the state from the home screen.
- **Quick Settings:** add the KeepADB tile to the active tiles and toggle from the Quick Settings
  panel.
- **Connect a computer:** tap the endpoint (for example “Port 5555 @ 192.168.0.5”) on the home screen to copy the
  `adb connect` command. The
  link “Connect a computer…” below it, and the last page of the setup assistant, explain the one-time
  pairing Android requires for each new computer and open Android's developer options.
- **Keep-Alive:** enable it in the app if KeepADB should observe state changes and try to recover.
  Android may show its own confirmation the first time Wifi-ADB is enabled on a
  Wi-Fi network. If Android does not confirm the change, KeepADB waits instead of repeatedly trying.

The home-screen widget **KeepADB controls** grows with its size, from the main action alone to
Wifi-ADB, Keep-Alive and webhook synchronization with a status line; each placed widget
has its own main action, order and detail setting. The second widget, **KeepADB overview**, shows
the connection, Wi-Fi, Keep-Alive and webhook delivery at a glance and offers only the auto-sync
switch (see [docs/widgets.md](docs/widgets.md)).

Settings also include notification privacy, diagnostics export, and optional webhook
synchronization. Four optional privacy switches
(first page of the setup assistant, also in Settings → Privacy and on a long press of the eye) hide
Wi-Fi names and addresses in the app, in notifications, and in widgets and the Quick Settings tile,
and keep KeepADB notifications off the lock screen; they do not change the endpoint sent
by an enabled webhook. Notifications and widgets omit addresses entirely, port included. Inside
the app the masks are partial: an IPv4 address keeps its first number and the port stays visible
(for example “Port 41234 @ 192.*.*.*”), an IPv6 address keeps its first group (`fe80:***`), and a BSSID
keeps its first and last pair (`AA:*:*:*:*:01`); Wi-Fi names are replaced by a placeholder, and
every host name (DNS and Tailnet names, in endpoint rows, report history and the
webhook address) is masked as `***`.

On a new installation, the network rule initially allows automatic recovery only on trusted access
points. Existing installations keep their previous setting, including “In all Wi-Fi networks.” Change
the rule under **Settings → Protection**. It controls automatic actions; manually turning Wifi-ADB
on remains a separate user action.

For convenience, you can enable **Force mode** under **Settings → Protection** for 1 hour, 24 hours,
7 days, 30 days, or indefinitely. Keep-Alive will then turn Wifi-ADB back on in every
Wi-Fi network, including blocked networks and networks whose identity cannot be read. A warning
appears on the home screen and in the notification, and one tap ends Force mode. The
[trusted-networks guide](docs/trusted-networks.md) explains BSSID, the legacy Wi-Fi name list,
location permissions, Force mode, background starts, and measurement limits.

## Webhook

Webhook synchronization is off by default. After you configure it, KeepADB sends verified Wifi-ADB
endpoint data to the URL you entered. The sender currently allows only Wifi-ADB events to be
transmitted. `http://` is supported, but it is unencrypted. Use
HTTP only for an endpoint you operate on a trusted LAN or VPN, and heed the warning in the app.
The home screen shows the webhook status; while Webhook Sync is not set up or is off, it shows a
short hint with a button that opens **Settings → Webhook & Endpoint Sync**.

Under **Settings → Webhook & Endpoint Sync**, you can also turn on **Periodic refresh** (off by default, based on work by `@inaciopestana`): while Keep-Alive and Webhook Sync are active, KeepADB periodically re-sends the active endpoint (`15 min`, `30 min`, `59 min`, `3 h 59 min` default on enable, `11 h 59 min`, or `23 h 59 min`) so external receivers with expiring entries stay updated.

The [webhook contract](docs/webhook-register.md) describes the exact message, deletion requests,
local opt-in rule, and the boundary between the app sender and an external receiver.

## Privacy and security

KeepADB sends no analytics or crash reports. The privacy switches are off by default, so a new
installation shows network addresses in notifications; turn on “Hide in notifications” to keep
them out of every notification, or “Hide on the lock screen” to keep KeepADB notifications off the
lock screen (with a PIN, pattern or password screen lock) while the unlocked shade still shows the
details. Diagnostic history is not transmitted automatically; an export is shared only after you
select a destination in Android. The [security policy](SECURITY.md) and
[documentation index](docs/README.md) explain webhook, network-trust, and lock-screen behavior.

Android still requires its own TLS pairing before a new ADB client can connect over Wifi-ADB.
KeepADB does not replace or bypass that pairing.

## Contributing

Build requirements, local verification, test dependencies, CI, and the separate release toolchain
are documented in [CONTRIBUTING.md](CONTRIBUTING.md). Please report security vulnerabilities
privately; [SECURITY.md](SECURITY.md) lists the reporting channels.

## License

KeepADB is licensed under the **GNU Affero General Public License v3.0 or, at your option, any
later version**. See [LICENSE](LICENSE). Unless a file says otherwise, the license also covers the
included application images and icons.
