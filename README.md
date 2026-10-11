# KeepADB

[![License: AGPL-3.0-or-later](https://img.shields.io/badge/License-AGPLv3%20or%20later-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-11%2B%20(API%2030%2B)-green.svg)](https://developer.android.com/about/versions/11)
![Runtime dependencies: none](https://img.shields.io/badge/Runtime--dependencies-0-orange.svg)

KeepADB controls Android's **Wireless debugging** from the app, a home-screen widget, or a Quick
Settings tile. The optional Keep-Alive service can turn it back on after a network interruption or
reboot, subject to Android, the selected network rule, and the user's most recently saved intent.

- Android 11 or newer (API 30+)
- No root required
- No runtime dependencies in the shipped app
- Application ID: `de.hohnepeople.keepadb`

KeepADB controls Android's global `Settings.Global.adb_wifi_enabled` setting. Android's mDNS
discovery finds the dynamic Wi-Fi ADB endpoint; KeepADB does not scan a general port range. Android
still requires its own TLS pairing before a new ADB client can connect.

## Install and first start

Install KeepADB from the [official F-Droid catalog](https://f-droid.org/packages/de.hohnepeople.keepadb/)
or download the APK from [GitHub Releases](https://github.com/m00sfett/KeepADB/releases).

Then grant `WRITE_SECURE_SETTINGS` once from a computer that can reach the device over ADB:

~~~sh
adb shell pm grant de.hohnepeople.keepadb android.permission.WRITE_SECURE_SETTINGS
~~~

This Android permission applies to the installed app and survives a reboot. It must be granted
again after uninstalling and reinstalling the app. KeepADB cannot grant it itself and displays the
command in the app while the permission is missing.

## Use KeepADB

- **App:** turn Wireless debugging on or off and view its current state.
- **Widget:** toggle the state from the home screen.
- **Quick Settings:** add the KeepADB tile to the active tiles and toggle from the Quick Settings
  panel.
- **Keep-Alive:** enable it in the app if KeepADB should observe state changes and try to recover.
  Android may show its own confirmation the first time Wireless debugging is enabled on a Wi-Fi
  network. If Android does not confirm the change, KeepADB waits instead of repeatedly trying.

Settings also include local USB host profiles, optional USB-to-Wi-Fi ADB handover, notification
privacy, diagnostics export, and optional webhook synchronization. Privacy mode hides network
addresses in the interface; it does not change the endpoint sent by an enabled webhook.

On a new installation, the network rule initially allows automatic recovery only on trusted access
points. Existing installations keep their previous setting, including “All Wi-Fi networks.” Change
the rule under **Settings → Network**. It controls automatic actions; manually turning Wireless
debugging on remains a separate user action.

For convenience, you can enable **Force mode** under **Settings → Network** for 1 hour, 24 hours,
7 days, 30 days, or indefinitely. Keep-Alive will then turn Wireless debugging back on in every
Wi-Fi network, including blocked networks and networks whose identity cannot be read. A warning
appears on the home screen and in the notification, and one tap ends Force mode. The
[trusted-networks guide](docs/trusted-networks.md) explains BSSID, optional SSID matching,
location permissions, Force mode, background starts, and measurement limits.

## Webhook

Webhook synchronization is off by default. After you configure it, KeepADB sends verified Wi-Fi
ADB endpoint data to the URL you entered. USB host profiles remain local; the sender currently
allows only Wi-Fi ADB events to be transmitted. `http://` is supported, but it is unencrypted. Use
HTTP only for an endpoint you operate on a trusted LAN or VPN, and heed the warning in the app.

The [webhook contract](docs/webhook-register.md) describes the exact message, deletion requests,
local opt-in rule, and the boundary between the app sender and an external receiver.

## Privacy and security

KeepADB sends no analytics or crash reports. Network addresses in notifications are hidden by
default. Diagnostic history is not transmitted automatically; an export is shared only after you
select a destination in Android. The [security policy](SECURITY.md) and
[documentation index](docs/README.md) explain webhook, network-trust, and lock-screen behavior.

Android still requires its own TLS pairing before a new ADB client can use Wireless debugging.
KeepADB does not replace or bypass that pairing.

## Contributing

Build requirements, local verification, test dependencies, CI, and the separate release toolchain
are documented in [CONTRIBUTING.md](CONTRIBUTING.md). Please report security vulnerabilities
privately; [SECURITY.md](SECURITY.md) lists the reporting channels.

## License

KeepADB is licensed under the **GNU Affero General Public License v3.0 or, at your option, any
later version**. See [LICENSE](LICENSE). Unless a file says otherwise, the license also covers the
included application images and icons.
