# Security policy

## Threat model

KeepADB is intended to keep Android Wireless debugging available. This lets `adbd` listen on a
dynamic port on the local network. That is the app's purpose and its central security trade-off:
KeepADB primarily limits **when automatic recovery happens**. It neither bypasses Android's TLS
pairing nor approves pairing requests for you.

KeepADB does not protect against a device that is already compromised, a user who approves an
unexpected pairing prompt, or an attacker on a Wi-Fi network explicitly trusted by the user.

## Automatic recovery and network trust

A new installation starts in allowlist mode: automatic re-enabling occurs only when KeepADB can
read the current Wi-Fi identity and match it to an allowed BSSID. An unknown or system-masked
identity remains blocked. Existing installations retain their previous setting unchanged,
including “All Wi-Fi networks”; **Settings → Network** still lets users choose between all Wi-Fi
networks and the allowlist.

The data model also supports blocks for individual BSSIDs and Wi-Fi names. In every mode, a block
takes precedence over trust, does not prompt the user, and never enables Wireless debugging
automatically. Only an explicit unblock action removes it; adding trust does not. Blocking occurs
through the unknown-network notice and the “Trust this network?” dialog; the Networks list provides
the interface for unblocking. Precedence, migration, and recovery behavior are documented in the
[trusted-networks guide](docs/trusted-networks.md).

Optional **Force mode** (Settings → Network) deliberately overrides this protection. For a chosen
duration (1 hour, 24 hours, 7 days, 30 days, or indefinitely), Keep-Alive re-enables Wireless
debugging on every Wi-Fi network and overrides blocks and trust rules. It requires confirmation
with staged warnings, remains visible on the home screen and in the notification, and can be ended
there with one tap. When it expires, the exact saved setting applies again. The duration survives
reboots, app updates, and clock or time-zone changes (a changed clock can shorten it or extend it
only within the short windows described in the guide). A one-time notice reports its end. Force
mode is never a default and cannot be enabled by an assistant, update, or import without the
dialog. Details and limits are in the [trusted-networks guide](docs/trusted-networks.md#force-mode).

Network identity is used only to recognize the Wi-Fi network; KeepADB does not determine or store
device location. Android requires precise location access for this. KeepADB does not request the
optional “Allow all the time” permission: the app opens Android's app settings so the user can grant
it there. Without a readable identity, access remains fail-closed. Observed platform cases and
their remaining limits are documented in the
[trusted-networks guide](docs/trusted-networks.md) and
[measurement summary](docs/trusted-networks-measurement.md).

The **Also trust by Wi-Fi name (SSID)** switch is a separate, disabled-by-default extension.
Because unrelated access points can advertise the same user-chosen name, this rule is weaker than
trusting an individual BSSID. KeepADB shows this warning next to the option.

### What a network identity can and cannot prove

Both identifiers KeepADB matches can be imitated. An SSID is a name anyone can broadcast. A BSSID
is the access point's MAC address: it lets KeepADB *recognize* an access point, but it is not a
cryptographic identity and does not *authenticate* it. Someone can configure their own access point
with a copied BSSID.

A copied BSSID alone does not defeat WPA2/WPA3 authentication, and it does not make Android join a
rogue access point by itself. Whether the phone connects depends on the saved Wi-Fi configuration,
the credentials (for example, someone who knows the shared WPA2/WPA3-Personal password can run an
access point that the phone cannot tell apart from the legitimate one) and platform behavior.
Wireless debugging is a separate layer: Android authenticates paired hosts and encrypts the ADB
connection with TLS, so imitating an access point identifier does not by itself grant an ADB shell.

Network trust gates **automatic** enabling and recovery only. When Wireless debugging is already
on and the phone moves to a network that is not trusted, KeepADB shows a notice; it does not switch
Wireless debugging off. An optional shutdown on untrusted networks is a separate proposal (#864).
Checking the connection's security type in addition to the BSSID is being evaluated in #860; such a
check could detect some downgrades (for example WPA3 to WPA2 or to an open network), but it would
still not prove that an access point is genuine.

Network trust controls automatic recovery. The tile and other manual controls do not approve a new
network identity. A locked device must be unlocked before enabling Wireless debugging through the
Quick Settings tile or USB notification; turning it off through the tile remains possible from the
lock screen. Approving a network through a notification also requires an unlocked device. When
notification details are hidden, the notice has no action buttons; tapping it opens KeepADB's
“Trust this network?” dialog and shows the network name so the user can make an informed decision.
The dialog shows the name even in privacy mode, because the user must know which network is being
trusted. It never shows the name on a locked display: the dialog appears only after unlocking and
checks the screen lock itself. Dismissing the notice does not make a decision; the same network is
not prompted again for at least 24 hours.

## Security-relevant interaction rules

KeepADB requires the one-time `WRITE_SECURE_SETTINGS` permission, granted from a computer over
ADB. The app cannot grant it itself. Automatic recovery requires a Wi-Fi connection, Keep-Alive,
and the user's most recently saved explicit intent. Android may also require its own confirmation;
if the state is not confirmed, KeepADB waits instead of repeatedly trying to enable it.

A locked device must be unlocked before enabling Wireless debugging through the Quick Settings
tile or a USB handover notification. Turning it off through the tile remains possible from the lock
screen. Approving an unknown network through a notice also requires unlocking and shows the access
point name in KeepADB when Android hides notification details.

## Notifications, webhook, and local data

Network addresses and access point names are hidden in notifications by default. A setting can
show connection details; whether Android displays sensitive notification content on the lock screen
also depends on Android's own settings.

Webhook traffic is off by default and goes only to the URL entered by the user. The
[webhook contract](docs/webhook-register.md) describes the payload created by the app code and
distinguishes it from unverified claims about an external server. HTTPS is recommended; HTTP is
available for self-hosted LAN or VPN endpoints, but is unencrypted and identified in the app.
KeepADB sends no analytics or crash reports.

Android cloud backup and device transfer are disabled. This includes the webhook URL, saved
network trusts, USB profiles, and diagnostics data. These data are lost when the app is uninstalled
or the device is changed. Diagnostics are not uploaded automatically; see
[Diagnostics export](docs/diagnostics.md) for sharing and redaction details.

## Supported versions

Security fixes apply to the latest published version. There is no separate long-term support branch.

## Report a vulnerability

Please do not report a vulnerability through a public GitHub issue. Use
[GitHub's private vulnerability reporting](https://github.com/m00sfett/KeepADB/security/advisories/new).
If you cannot use GitHub, use the feedback form in Settings or
[keepadb.roteson.de/feedback](https://keepadb.roteson.de/feedback) and mark the report as
security-sensitive.

Include the KeepADB version, Android version/API level, reproduction steps, and relevant network
conditions. Do not include a webhook URL, pairing key, or other credentials.
