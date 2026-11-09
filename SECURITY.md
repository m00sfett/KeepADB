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
including “In all Wi-Fi networks”; **Settings → Network** still lets users choose between all Wi-Fi
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

The **Also trust by Wi-Fi name** switch (SSID matching) is a separate, disabled-by-default extension.
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
on and the phone moves to a network that is not trusted, KeepADB shows a notice; by default it does
not switch Wireless debugging off. The opt-in setting "Turn off on untrusted Wi-Fi" (#864) does, while
Keep-Alive runs: it is a best-effort reaction to network callbacks and the one-minute heartbeat, not
an instantaneous guarantee, and a failed write is reported rather than hidden.
The opt-in setting "Check Wi-Fi security of trusted access points" (#860, Android 12 and later)
compares the connection's security tier with the one recorded when the access point was trusted and
blocks automatic enabling after a downgrade (for example from WPA2/WPA3-Personal to an open network)
or when the tier cannot be read. It does not prove that an access point is genuine: a lookalike with
the same BSSID and security tier passes.

Network trust controls automatic recovery. The tile and other manual controls do not approve a new
network identity. A locked device must be unlocked before enabling Wireless debugging through the
Quick Settings tile or USB notification; turning it off through the tile remains possible from the
lock screen. Approving a network through a notification also requires an unlocked device. While the
notification privacy switch is on, the notice has no action buttons; tapping it opens KeepADB's
“Trust this network?” dialog, where the user can see the network name to make an informed
decision. While the app privacy switch is on, the dialog masks the name and address until the user
chooses “Show” in it; the reveal lasts only until the dialog is left. It never shows the name on a
locked display: the dialog appears only after unlocking and checks the screen lock itself.
Dismissing the notice does not make a decision; the same network is not prompted again for at
least 24 hours.

## Security-relevant interaction rules

KeepADB requires the one-time `WRITE_SECURE_SETTINGS` permission, granted from a computer over
ADB. The app cannot grant it itself. Automatic recovery requires a Wi-Fi connection, Keep-Alive,
and the user's most recently saved explicit intent. Android may also require its own confirmation;
if the state is not confirmed, KeepADB waits instead of repeatedly trying to enable it.

A locked device must be unlocked before enabling Wireless debugging through the Quick Settings
tile or a USB handover notification. Turning it off through the tile remains possible from the lock
screen. Approving an unknown network through a notice also requires unlocking and shows the access
point name in KeepADB when the notification privacy switch is on.

## Notifications, webhook, and local data

KeepADB has three independent privacy switches, all opt-in and off on a new installation (#904):
one for the screens and dialogs of the app, one for notifications, one for widgets and the Quick
Settings tile. A switch masks Wi-Fi names, access point addresses and IP addresses in its own
area only. The notification and widget switches omit them and the port entirely. The app switch
masks names, BSSIDs, IPv6 addresses and every host name (DNS and Tailnet names alike, shown as
`***`, in endpoint rows, report history, USB profiles and the webhook address); **by decision,
inside the app an IPv4 address keeps its first number and the port stays visible**
(`192.*.*.*:41234`), so that “did it report to this port?” stays answerable. Only the app switch
has a “Show”. It lifts the mask for one part of one screen (the Networks list, the assistant's
network page, the trust dialog, the webhook field, the USB profile dialog) until that part is
left, the screen stops or the switch is turned on again. The eye in the title bars (on the home
screen: the header menu) opens a menu with “Show on this screen” / “Hide on this screen”, which
applies to every part of that screen until it is left and never changes a stored switch, plus the
three stored switches. Shared diagnostics and toasts that name a network stay masked under any
“Show”, because they can outlive the screen; they are masked whenever the app switch is on or the
screen is hidden. In the trust dialog and on the Settings network card, trusting needs a “Show”
while the network is masked; blocking does not.

**Change in #904 (deliberate):** earlier versions hid connection details in notifications by
default (#592); a new installation now shows them until the notification switch is turned on, and
they can then appear on the lock screen if Android shows sensitive notification content there. The
choice sits on the first page of the setup assistant and in the Settings. An installation that
updated from an earlier version keeps its behavior: details stay hidden unless it had turned them
on. The privacy switches are display settings; they do not change what an enabled webhook sends or
which network may enable Wireless debugging.

**Hide on the lock screen (#920, opt-in, default off):** under the notification switch. When on,
every KeepADB notification is posted with `VISIBILITY_SECRET`, so none of them (main, USB, network
trust notice with its Allow/Block buttons, webhook warning, Force Mode and network shutdown notices)
appears on the lock screen, whether or not Android shows sensitive content there. The option is
read and each notification posted under one lock, so a post cannot slip past a change. A change
re-renders the main and USB notifications at once and removes a posted trust notice with network
details (it returns after the repeat interval, then secret; with details only if the notification
switch is off); notifications without network details follow at their next posting. Unlocked, the shade shows them
as the notification switch decides. Per-channel lock-screen settings chosen in Android can only
hide more: a channel set to hide on the lock screen stays hidden either way. Android applies this
only with a secure screen lock (PIN, pattern or password); a swipe-only lock screen shows every
notification, as anyone can open it.

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
