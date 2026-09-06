# Security Policy

## Threat model

KeepADB's entire purpose is to keep Android's Wireless Debugging (`adb_wifi_enabled`) available
across reconnects, reboots, and idle periods. That is a deliberate trade-off: an open debug
port on the local network is exactly what the app exists to maintain, not a bug to eliminate.
The security work in this project is about narrowing *when* and *how* that happens, not about
pretending the port isn't open. Concretely, KeepADB tries to:

- avoid automatically re-enabling Wireless Debugging on networks the user hasn't marked as
  trusted (see the trusted-network allowlist under Settings → Trusted Networks; opt-in, off by
  default to preserve prior behavior);
- keep cleartext (unencrypted) HTTP scoped to the one feature that needs it — the optional,
  user-configured webhook — and warn in-app when a webhook URL is `http://` instead of `https://`;
- avoid persisting anything sensitive where Android backup or device transfer could pick it up
  (backup and device transfer are disabled entirely — see the README's "Privacy & Security"
  section);
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
- Automatic re-enable (Keep-Alive) only fires while Wi-Fi is connected, and — if the
  trusted-network allowlist is turned on — only on a network in that allowlist. It never
  overrides an explicit manual OFF.
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
