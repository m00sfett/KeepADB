# Security Policy

## Threat model

KeepADB's entire purpose is to keep Android's Wireless Debugging (`adb_wifi_enabled`) available
across reconnects, reboots, and idle periods. That is a deliberate trade-off: an open debug
port on the local network is exactly what the app exists to maintain, not a bug to eliminate.
The security work in this project is about narrowing *when* and *how* that happens, not about
pretending the port isn't open. Concretely, KeepADB tries to:

- let the user restrict automatic Wireless Debugging re-enable to selected networks with the
  optional trusted-network setting under Settings → Network. On a fresh install, this restriction
  is off and any connected Wi-Fi network may be used; enabling it requires Android location
  access to identify networks. KeepADB's foreground service requests the `location` service type
  (with while-in-use `ACCESS_FINE_LOCATION`; `ACCESS_BACKGROUND_LOCATION` is only an optional
  grant the user sets on the app's permission page, #616) specifically so
  that identity stays readable during Keep-Alive, including in the background (#606, "C2" — see
  `docs/trusted-networks-measurement.md`). Recovery can still pause when identity genuinely isn't
  available: the permission is missing or only coarse, device location is off, or the running
  Keep-Alive service was started from the background — after a reboot, an app update, or a
  process restart of such a background-started service — and the app has not been opened in the
  foreground since (#626, tracked as #630; on Android 14+ a background-originated start with the
  permission already granted throws a `SecurityException` for the `location` type, which the
  service now catches and falls back from to `connectedDevice` alone instead of ending the
  service, tracked as #629). Existing choices and saved networks are preserved when upgrading;
- require an unlocked device to trust a network from the untrusted-network prompt notification:
  the "Yes, allow" action asks the platform to reauthenticate before it fires (API 31+), and
  `KeepADBReceiver` refuses the action and re-offers the same prompt if it is somehow reached
  while the device reports itself locked, on every Android version this app supports (#578);
- never offer a trust decision without naming the network: with connection details off (the
  default, see below) the prompt notification has no "Yes, allow" action. Tapping it opens the
  app, which shows the network name and BSSID of the access point the prompt was raised for and
  asks for allow or block there. The dialog binds to that access point as recorded by the app
  itself, not to the current connection or to anything in the launching intent, so a network
  change before the tap or before the click cannot make it trust a different access point. Allow
  goes through the same receiver path and locked-device check as the notification action (#598);
- require an unlocked device to switch Wireless Debugging on from the Quick Settings tile: on a
  locked, secured device the tile asks Android for the unlock first; switching it off from the
  lock screen stays possible without unlocking (#586);
- require an unlocked device to switch Wireless Debugging on from the USB notification's "Enable
  Wifi-ADB" handover action, with the same two layers as the trust action (#588);
- keep connection details out of notifications by default: unless the user turns on Settings →
  Notification → "Show connection details in notifications" (off by default), the persistent
  main notification shows only the status (no port or IP address), the USB-ADB notification shows
  neither the host profile name nor its IP/hostname, and the untrusted-network prompt shows
  neither the network name nor the BSSID; all three show neutral text, the details stay in the
  app (#592, #597), and the prompt's trust decision moves into the app as well (#598). This is the
  only reliable protection: Android shows a notification's `publicVersion` (neutral text, no
  actions, set on all three notifications since #578/#589/#597) only while sensitive lock-screen
  content is hidden. With sensitive content allowed on the lock screen, the private notification
  is shown there, so after opting in the details can be visible without unlocking. The unlock
  gates above apply in both modes;
- keep cleartext (unencrypted) HTTP scoped to the one feature that needs it — the optional,
  user-configured webhook — and warn in-app when a webhook URL is `http://` instead of `https://`;
- avoid persisting anything sensitive where Android backup or device transfer could pick it up
  (backup and device transfer are disabled entirely, via `allowBackup="false"` for API < 31 and
  `dataExtractionRules` excluding every domain for API 31+, independent of OEM `allowBackup`
  handling — see the README's "Privacy & Security" section);
- request the fewest permissions possible, and document why each one is needed (see the README
  and the comments next to each `<uses-permission>` in `AndroidManifest.xml`).

KeepADB does **not** try to protect against: a device that is already compromised, a user who
confirms an unexpected ADB pairing dialog or an unfamiliar RSA key fingerprint, or an attacker
who is already on a network the user has explicitly marked as trusted.

## Supported versions

Only the latest published release is supported with security fixes. There is no long-term
support branch; please update to the latest version before reporting an issue.

## Expected Wireless Debugging behavior

- Wireless Debugging requires `WRITE_SECURE_SETTINGS`, granted once via `adb shell pm grant`
  (see README "Getting Started") — KeepADB cannot grant this permission to itself.
- Automatic re-enable (Keep-Alive) only fires while Wi-Fi is connected. On a fresh install, it
  may use any connected Wi-Fi network; when the optional trusted-network restriction is enabled,
  it only acts when the current network can be verified as trusted. Since #606 ("C2"), that
  verification stays reliable in the background as long as the running Keep-Alive service was
  started while the app was in the foreground, or the app has been opened since. It can still be
  unavailable —
  missing or approximate location permission, location off, or a service that was started from
  the background and not followed by an app opening (see above) — in which case recovery may
  pause. Every automatic
  re-enable path checks the last explicit user intent before acting, so it does not fire while
  that intent is OFF;
  this is enforced in code (tracked and tested as of issue #309), not merely a design intention,
  but it is a property of the current implementation rather than an absolute physical guarantee.
- Android may require a system approval for Wireless Debugging on a Wi-Fi network. KeepADB backs
  off when Android does not confirm an automatic enable; approve only an expected system prompt.
- Android still requires TLS pairing authentication for a new client to actually use the port;
  KeepADB does not, and cannot, bypass that.

If you observe Wireless Debugging being enabled automatically in a situation that contradicts
the above, please report it (see below) — that's a bug, not intended behavior.

## Reporting a vulnerability

**Please do not open a public GitHub issue for a security vulnerability.**

Use GitHub's private vulnerability reporting instead:
[github.com/m00sfett/KeepADB/security/advisories/new](https://github.com/m00sfett/KeepADB/security/advisories/new).
This opens a private draft advisory visible only to you and the maintainer.

If you're unable to use that (e.g. you don't have a GitHub account), you may instead use the
feedback form linked from the app's Settings screen ("Report an issue"), which is also
reachable directly at https://hohnepeople.de/keepadb/feedback — please mark the report as
security-sensitive and avoid including exploit details in a channel you're not sure is private.

Please include:
- KeepADB version (Settings → About) and Android version/API level.
- Steps to reproduce, and what you'd expect to happen instead.
- Whether the issue requires a specific network configuration (trusted-network allowlist on/off,
  VPN, etc.) to reproduce.

Please do **not** include your webhook URL, pairing keys, or other credentials in a report —
none of this project's templates or forms ask for them, and they're never needed to reproduce
or fix a bug here.

We aim to acknowledge reports within a week and to keep you updated on progress toward a fix.
