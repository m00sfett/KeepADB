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
- **Network Protection & Force mode:** Keep-Alive recovers Wifi-ADB only where your protection level permits (**Maximum security** by BSSID or **Balanced** by Wi-Fi name), optionally turns off on untrusted Wi-Fi, turns off on open Wi-Fi and behind captive portals (on by default for new installations), checks for security-tier downgrades on Android 12+, and offers timed or unlimited **Force mode**.
- **Webhook Sync with opt-in periodic refresh:** Send verified endpoints via HTTP(S) and optionally re-send active endpoints on a configurable schedule (`15 min` to `23 h 59 min`, based on work by `@inaciopestana`) so external registers that expire idle entries stay current.
- **Zero bloat & four privacy switches:** No runtime dependencies, no ads, no analytics, and independent switches to mask names and addresses in the app, notifications, lock screen, and widgets/tile.

## Why KeepADB?

Several open-source apps help with Android's “Wireless debugging”. They solve different problems
and use different privileges, so the right choice depends on what you need:

| App | What it is for | Privilege it needs (per its own README) |
|---|---|---|
| **KeepADB** | Switch Wifi-ADB and, optionally, turn it back on after reboots and network changes, limited by a network rule | One-time `WRITE_SECURE_SETTINGS` grant: on the phone via self-pairing, in a computer browser over WebUSB, or with `adb`; no root, no Shizuku |
| [WADBS](https://github.com/Smooth-E/wireless-adb-switch) (`wireless-adb-switch`) | Quick on/off switch with home-screen widgets and a Quick Settings tile | Root or Shizuku |
| [WADB](https://github.com/RikkaApps/WADB) | Switch for ADB over network, Quick Settings tile, option to start on boot | Root |
| [LADB](https://github.com/tytydraco/LADB) | Local ADB shell on the device itself, with a bundled ADB server (not a switch) | “Wireless debugging” pairing with the app; the README notes it is incompatible with Shizuku |

What sets KeepADB apart:

- **Privilege model:** switching Wifi-ADB needs only the one-time `WRITE_SECURE_SETTINGS` grant,
  which survives reboots. KeepADB needs neither root nor a running Shizuku service; other Android
  permissions serve individual features, such as precise location for recognizing trusted networks.
- **Keep-Alive and recovery:** the optional service turns Wifi-ADB back on after a reboot or a
  network interruption, honors the user's last saved intent, and backs off when Android does not
  confirm the change.
- **Network protection:** automatic recovery runs only where the selected rule allows it (trusted
  access points by default on new installations), with an optional switch-off on untrusted Wi-Fi
  and, by default on new installations, on open Wi-Fi and behind captive portals.
- **Setup without a PC:** on Android 11+ the permission can be granted on the phone itself; a
  computer is only a fallback.
- **Automation through the webhook:** because Android changes the Wifi-ADB port, scripts and
  tools on other machines need the current endpoint. With Webhook Sync on (off by default),
  KeepADB sends the verified Wifi-ADB endpoint to your own HTTP(S) receiver whenever it changes,
  and optionally re-sends it on a schedule, so a CI job, a test farm or a home-lab script can
  connect with `adb connect` without looking up the port by hand. See the
  [webhook contract](docs/webhook-register.md).

The table describes each project as its own documentation does; it is not a review. If a statement
about another project is out of date, please open an issue.

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
- **Wifi-ADB timer:** "On for 1 h" below the home switch (or the chevron for 15 min to 8 h, or the
  "Wifi-ADB with timer" launcher shortcut) turns Wifi-ADB on and off again when the time is up.
  While it runs, the notification counts down and offers "Off now" and "+1 h"; on the home screen
  "+1 h" and "No limit". The timer keeps Wifi-ADB alive like Keep-Alive within the network rules,
  continues after a reboot and ends as a manual off. See [docs/timed-wifi-adb.md](docs/timed-wifi-adb.md).
- **Automation:** with Settings → Automation switched on (off by default), apps such as Tasker,
  MacroDroid or Automate can switch Wifi-ADB and Keep-Alive with broadcast intents. Switching on
  needs the access token shown there and still respects the network protection. See
  [docs/automation.md](docs/automation.md).
- **Connect a computer:** tap the endpoint (for example “Port 5555 @ 192.168.0.5”) on the home screen to copy the
  `adb connect` command. The
  link “Connect a computer…” below it, and the last page of the setup assistant, explain the one-time
  pairing Android requires for each new computer and open Android's developer options.
- **Reconnect without typing the port:** Android picks a new random Wifi-ADB port each time
  “Wireless debugging” starts. A computer that is already paired can find the phone over mDNS
  instead: `adb mdns check` shows whether discovery works, and `adb mdns services` lists the phone
  as `_adb-tls-connect._tcp` with its current address and port. Current platform-tools (checked
  with 37.0.0) connect to paired phones found this way on their own, because
  `ADB_MDNS_AUTO_CONNECT` defaults to `adb-tls-connect`; `adb devices` then lists the phone under
  its mDNS name. If your adb does not, set `ADB_MDNS_AUTO_CONNECT=adb-tls-connect` and restart the
  server with `adb kill-server`. Both devices must be on the same network, and mDNS must not be
  blocked by the router or firewall.
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

**Turn off on open Wi-Fi** (Settings → Protection, on for new installations, off for existing ones
until you switch it on) turns Wifi-ADB off while Keep-Alive runs and keeps it off in a Wi-Fi without
secure password protection (open, Enhanced Open, WEP) or behind a captive portal, even at a trusted access point. A session you switch on
yourself in KeepADB stays on there, and Force mode overrides the rule. Android 11 cannot tell
whether a Wi-Fi has a password; there only captive portals count.

For convenience, you can enable **Force mode** under **Settings → Protection** for 1 hour, 24 hours,
7 days, 30 days, or indefinitely. Keep-Alive will then turn Wifi-ADB back on in every
Wi-Fi network, including blocked networks and networks whose identity cannot be read. A warning
appears on the home screen and in a red notification with "Off now", "End force mode" and "+1 h";
any manual switch-off ends Force mode too. The
[trusted-networks guide](docs/trusted-networks.md) explains BSSID, the legacy Wi-Fi name list,
location permissions, Force mode, background starts, and measurement limits.

## Settings backup

Under **Settings → Settings backup** you can export your settings to a file you pick and import
them again, for example after a reinstall, which otherwise loses every setting. The file holds the
protection level, trusted and blocked networks, the network rules, the privacy, notification and
webhook options, and the webhook URL unless you leave it out. KeepADB never uploads it; keep it
private, since it names your networks. An import checks the whole file first and applies it
completely or not at all. It never grants the system permission and never turns Wifi-ADB,
Keep-Alive or webhook reporting on. The [settings backup guide](docs/settings-backup.md) lists
what is and is not included.

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

Forks are welcome under the license. Please publish a fork under a different app name and icon so
users can tell it apart from KeepADB; the license covers the code and images, not the right to
present a fork as KeepADB.
