# Security policy

## Threat model

KeepADB is intended to keep Wifi-ADB (Android's “Wireless debugging”) available. This lets `adbd` listen on a
dynamic port on the local network. That is the app's purpose and its central security trade-off:
KeepADB primarily limits **when automatic recovery happens**. It neither bypasses Android's TLS
pairing nor approves pairing requests for you.

KeepADB does not protect against a device that is already compromised, a user who approves an
unexpected pairing prompt, or an attacker on a Wi-Fi network explicitly trusted by the user.

## Automatic recovery and network trust

A new installation starts in allowlist mode: automatic re-enabling occurs only when KeepADB can
read the current Wi-Fi identity and match it to an allowed BSSID. An unknown or system-masked
identity remains blocked. Existing installations retain their previous setting unchanged,
including “In all Wi-Fi networks”; **Settings → Protection** still shows that setting until the user chooses one of the two
protection levels (“Maximum security” or “Balanced”).

The data model also supports blocks for individual BSSIDs and Wi-Fi names. Outside Force mode (below), a block
takes precedence over trust, does not prompt the user, and never enables Wifi-ADB
automatically. Only an explicit unblock action removes it; adding trust does not. Blocking occurs
through the unknown-network notice and the “Trust this network?” dialog; the Wi-Fi networks list provides
the interface for unblocking. Precedence, migration, and recovery behavior are documented in the
[trusted-networks guide](docs/trusted-networks.md).

Optional **Force mode** (Settings → Protection) deliberately overrides this protection. For a chosen
duration (1 hour, 24 hours, 7 days, 30 days, or indefinitely), Keep-Alive re-enables Wifi-ADB
on every Wi-Fi network and overrides blocks and trust rules. It requires confirmation
with staged warnings, remains visible on the home screen and in a red notification, and can be ended
there with one tap; since #1000 every manual "off" of Wifi-ADB ends it too, and the notification's
"Off now" ends it and switches Wifi-ADB off at once. "+1 h" on that notification extends a running
time-limited mode by one hour per tap, up to 30 days remaining; it needs an unlocked device, never
starts the mode and never turns it into an unlimited one. When it expires, the exact saved setting
applies again. The duration survives
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

The protection level **Balanced** (trust by Wi-Fi name; there has been no separate switch since 1.14.1, the first public release with this change) is a
disabled-by-default extension of the default level “Maximum security”. Because unrelated access
points can advertise the same user-chosen name, this rule is weaker than trusting an individual
BSSID. KeepADB shows this warning next to the level.

### What a network identity can and cannot prove

Both identifiers KeepADB matches can be imitated. An SSID is a name anyone can broadcast. A BSSID
is the access point's MAC address: it lets KeepADB *recognize* an access point, but it is not a
cryptographic identity and does not *authenticate* it. Someone can configure their own access point
with a copied BSSID.

A copied BSSID alone does not defeat WPA2/WPA3 authentication, and it does not make Android join a
rogue access point by itself. Whether the phone connects depends on the saved Wi-Fi configuration,
the credentials (for example, someone who knows the shared WPA2/WPA3-Personal password can run an
access point that the phone cannot tell apart from the legitimate one) and platform behavior.
Android's own pairing is a separate layer: it authenticates paired hosts and encrypts the ADB
connection with TLS, so imitating an access point identifier does not by itself grant an ADB shell.

Network trust gates **automatic** enabling and recovery only. When Wifi-ADB is already
on and the phone moves to a network that is not trusted, KeepADB shows a notice; by default it does
not switch Wifi-ADB off. The opt-in setting "Turn off on untrusted Wi-Fi" (#864) does, while
Keep-Alive runs: it is a best-effort reaction to network callbacks and the one-minute heartbeat, not
an instantaneous guarantee, and a failed write is reported rather than hidden.
The setting "Turn off on open Wi-Fi" (#997, on for new installations since 1.15.0, off for
existing ones until switched on) turns Wifi-ADB off, and keeps Keep-Alive from turning it back on,
in a Wi-Fi without secure password protection (open, Enhanced Open, WEP) or behind a captive portal, trusted access
points included. It reacts on the same best-effort basis while Keep-Alive runs; Force mode and a
session switched on manually in KeepADB override it. Android 11 cannot report whether a Wi-Fi has a
password, so there only captive portals are detected, and an unreadable security type is not
treated as open.
The opt-in setting "Check Wi-Fi security of trusted access points" (#860, Android 12 and later)
compares the connection's security tier with the one recorded when the access point was trusted and
blocks automatic enabling after a downgrade (for example from WPA2/WPA3-Personal to an open network)
or when the tier cannot be read. It does not prove that an access point is genuine: a lookalike with
the same BSSID and security tier passes.

Network trust controls automatic recovery. The tile and other manual controls do not approve a new
network identity. A locked device must be unlocked before enabling Wifi-ADB through the
Quick Settings tile; turning it off through the tile remains possible from the
lock screen. Approving a network through a notification also requires an unlocked device. While the
notification privacy switch is on, the notice has no action buttons; tapping it opens KeepADB's
“Trust this network?” dialog, where the user can see the network name to make an informed
decision. While the app privacy switch is on, the dialog masks the name and address until the user
chooses “Show” in it; the reveal lasts only until the dialog is left. It never shows the name on a
locked display: the dialog appears only after unlocking and checks the screen lock itself.
Dismissing the notice does not make a decision; the same network is not prompted again for at
least 24 hours.

### Endpoint discovery trust boundary

KeepADB reports the Wifi-ADB endpoint (home screen, notification, widget, webhook, automation
variables) from two sources, and neither proves that `adbd` is behind the port:

- **mDNS:** an `_adb-tls-connect._tcp` announcement on the device's own Wi-Fi address whose port
  accepts a TCP connection. Any app with network access can register such a service through
  `NsdManager`.
- **Fallback without mDNS (#1030):** when mDNS finds nothing while Wifi-ADB is on, the port
  remembered in this boot, or the only loopback port in 30000-65535, that answers ADB's `CNXN`
  message with `STLS` and is also reachable on the Wi-Fi address. An app with network access can
  listen on a free port and send those 24 bytes.

A malicious app on the device can therefore make KeepADB show and report a wrong port. A computer
that connects there may fail, or, if the app implements ADB over TLS, talk to that app instead of
`adbd`: the AOSP `adb` host does not verify the server certificate, so it could send the app its
commands and accept the app's answers (for example files from `adb pull`). This gives the app no
access to the device itself; pairing still protects genuine `adbd`. KeepADB has no way to
authenticate `adbd`. The fallback keeps the trust level of the mDNS path; accepting this boundary
is a documented decision of the maintainer (2026-11-20, #1030).

## Security-relevant interaction rules

KeepADB requires the one-time `WRITE_SECURE_SETTINGS` permission. Users can grant it directly on
the device via an authenticated self-pairing session with the device's own `adbd` (KeepADB connects
only to the loopback address; Android 11+: the user intentionally opens Android's pairing-code
dialog and enters the 6-digit code in KeepADB's notification; KeepADB discards its in-memory pairing
key afterwards). Cleanup is conditional and best-effort: KeepADB turns Wifi-ADB back off only if it
was off when the run started (or when an earlier, cancelled run of the same boot found it off, whose
cleanup the new run takes over) and the grant succeeded; it stays on if Keep-Alive is enabled, the
claim expired, or switching off fails, in which case the user switches it off manually. The pairing entry stays
in Android's list of paired devices until the user removes it; without the discarded key it cannot
be used. KeepADB does not check network trust for this run: the user switches Wifi-ADB on
themselves, as with any manual control.

Alternatively, users can grant it from Chrome or Edge over WebUSB at
`https://keepadb.roteson.de/setup`, or manually from a computer over USB ADB. Both need USB debugging
on the phone and the user's confirmation of the computer's key on the phone. While connected, that
computer holds an ADB authorization; if the user chose "Always allow", Android keeps the key after
disconnecting until it is revoked (Developer options → Revoke USB debugging authorizations). The
setup page runs exactly one command (`pm grant`) and uploads no device data or command output; its
WebADB library is served from the same site, not from a third party, and it works client-side in the
browser. Automatic recovery requires a Wi-Fi
connection, Keep-Alive, and the user's most recently saved explicit intent. Android may also
require its own confirmation; if the state is not confirmed, KeepADB waits instead of repeatedly
trying to enable it.

A locked device must be unlocked before enabling Wifi-ADB through the Quick Settings
tile or the launcher shortcut. Turning it off through the tile remains possible from the lock
screen. The widgets have no lock check of their own and declare only the home-screen category, so
whether a launcher or Android version shows them on the lock screen is not guaranteed. Approving an unknown network through a notice also requires unlocking and shows the access
point name in KeepADB when the notification privacy switch is on.

## Automation interface

From 1.15 KeepADB has an opt-in automation interface (#998): automation apps such as Tasker,
MacroDroid or Automate send explicit broadcasts to `KeepADBAutomationReceiver`, an exported
receiver of the app. The contract is documented in [Automation interface](docs/automation.md).

**Threat:** any installed app can send a broadcast to an exported receiver; Android names no
reliable sender below API 34. The interface is therefore built so that the broadcast itself
authorizes nothing:

- It is **off by default**. While it is off, every request is ignored: no reply, no change, only
  a local diagnostics line.
- **Everything that can switch something on** (Wifi-ADB on, toggle, Keep-Alive on) needs a secret
  token: 32 random bytes from `SecureRandom`, Base64url, generated by KeepADB when the interface is
  first enabled, compared in constant time. A new token invalidates the old one at once. Without a
  valid token, a rejection is counted and shown in Settings; there is no lockout, which would not
  help against a 256-bit token but would hide misconfiguration.
- **Turning off and reading the on/off state need no token.** An app without the token can
  therefore switch Wifi-ADB off (a denial of a debugging session, never an exposure) and learn
  whether it is on. The endpoint (address and port) is only in a reply to a request with a valid
  token.
- A Wifi-ADB "on" **never bypasses the network protection**: it is refused unless Wi-Fi is
  connected and the current network allows an automatic enable, re-checked inside the toggle lock
  immediately before the write. It applies the Protection settings exactly as Keep-Alive does: by
  default no untrusted, blocked, open or sign-in Wi-Fi, but a running Force mode allows every
  network, open and sign-in Wi-Fi are refused only while "Turn off on open Wi-Fi" is on, and the
  previous "All Wi-Fi networks" setting allows every unblocked network. Unlike a manual tap, it never
  exempts the current network from "Turn off on untrusted Wi-Fi".
- It does **not need an unlocked device**: the token is the authorization. Whoever holds the token
  can switch Wifi-ADB on while the phone is locked, on a network the user allowed.
- Replies go only to the package named in the request (`setPackage`), never as an implicit
  broadcast. Any app can name any package, so a reply is information, never an authorization; an
  optional `request_id` is echoed unchanged (never logged) so a client can match replies to its
  own requests.
- **Floods:** any app can send requests in a loop. Requests without a valid token write at most
  one diagnostics line per kind every 10 seconds (the next one counts what was left out), and the
  rejection counter is saved at most every 10 seconds, so a flood neither churns storage nor pushes
  useful history out of the bounded journal.
- The token is never logged, never in the diagnostics journal or its export, never in a webhook
  payload and never in the settings backup; neither is the interface's on/off switch, so an import
  never opens the entry point. Android backup and device transfer are disabled for all app data.

**Tasker/Locale plugin (#999).** The plugin adds four exported components: for the action
`KeepADBPluginFireReceiver` (`FIRE_SETTING`) and `KeepADBPluginEditActivity` (`EDIT_SETTING`), for
the condition `KeepADBPluginConditionReceiver` (`QUERY_CONDITION`) and
`KeepADBPluginConditionEditActivity` (`EDIT_CONDITION`). The receivers decide nothing themselves;
they pass the request and the token from the saved configuration to the same check as a broadcast,
so the opt-in, the token and the network protection apply unchanged. A condition query is a
`STATUS` read: it switches nothing and reveals only the on/off state and whether an endpoint is
known, unless the saved token is valid. While the interface is on, KeepADB sends the data-free
Locale `REQUEST_QUERY` broadcast when that state changes, and it declares Tasker, MacroDroid,
Automate and Locale as visible packages for it (`<queries>`). Two risks are specific to the plugin:

- **The token is stored in the host app.** On, toggle and status actions keep it in the automation
  app's configuration, and with it in that app's backups, exports and shared tasks. The edit screen
  says so; the two "off" requests store no token.
- **The edit screen hands out the token.** It must be exported for the host to find it, so any
  app can open it for result. It saves only while the interface is on, only when Android names a
  calling app (`getCallingPackage()`, set by the system), shows that app's label and package name,
  and asks again before saving for any app other than Tasker, MacroDroid, Automate or Locale. The
  user still has to tap Save. Residual risk: a known package name is not checked against a
  signature, so an app installed under the package name of a known host that is not installed
  would skip the second question.
- **The completion intent comes from the sender.** Tasker and MacroDroid pass an `intent:` URI
  that KeepADB sends back with the result (#1026). Whoever sends the fire broadcast chooses that
  URI, so KeepADB only ever sends it as a broadcast, only to an explicit, enabled, exported
  receiver of another app that requires no permission (KeepADB holds permissions such as
  WRITE_SECURE_SETTINGS, and Android checks the broadcaster's, so a protected receiver must not be
  reachable through KeepADB; never to KeepADB itself), and strips URI grant flags, selector and clip
  data. It carries the same result and variables as the ordered reply would, so address and port
  still need a valid token.

Anyone who can read the token (an app the user gave it to, someone with access to the unlocked
Settings page, or anyone with a backup or shared copy of a plugin action) can switch Wifi-ADB on
within these limits. Android's TLS pairing still protects
the debugging session itself.

## Notifications, webhook, and local data

KeepADB has four independent privacy switches, all opt-in and off on a new installation (#904):
one for the screens and dialogs of the app, one for notifications, one that keeps KeepADB
notifications off the lock screen (#920), one for widgets and the Quick Settings tile. All of them live in Settings → Privacy (#941). A switch masks Wi-Fi names, access point addresses and IP addresses in its own
area only. The notification and widget switches omit them and the port entirely. The app switch
masks names, BSSIDs, IPv6 addresses and every host name (DNS and Tailnet names alike, shown as
`***`, in endpoint rows, report history and the webhook address); **by decision,
inside the app an IPv4 address keeps its first number and the port stays visible**
(“Port 41234 @ 192.*.*.*”), so that “did it report to this port?” stays answerable. Only the app switch
has a “Show”. It lifts the mask for one part of one screen (the Wi-Fi networks list, the assistant's
network page, the trust dialog, the webhook field) until that part is
left, the screen stops or the switch is turned on again. A tap on the eye in the title bars (home
screen included, #941) toggles “Show on this screen” / “Hide on this screen”, which applies to
every part of that screen until it is left and never changes a stored switch; a long press opens a
menu with the four stored switches and a link to Settings → Privacy. Shared
diagnostics and toasts that name a network stay masked under any
“Show”, because they can outlive the screen; they are masked whenever the app switch is on or the
screen is hidden. In the trust dialog and on the Protection page, trusting needs a “Show”
while the network is masked; blocking does not.

**Change in #904 (deliberate):** earlier versions hid connection details in notifications by
default (#592); a new installation now shows them until the notification switch is turned on, and
they can then appear on the lock screen if Android shows sensitive notification content there. The
choice sits on the first page of the setup assistant and in the Settings. An installation that
updated from an earlier version keeps its behavior: details stay hidden unless it had turned them
on. The privacy switches are display settings; they do not change what an enabled webhook sends or
which network may enable Wifi-ADB.

**Copying the endpoint (#951):** a tap on the endpoint row of the home screen (and on a verified
transport row) puts `adb connect <host>:<port>` on the clipboard. The notification's "Copy" action
was removed in #1000, so nothing reaches the clipboard from the notification shade or the lock
screen any more. A tap is an explicit action, so the real address is copied even while the app switch
masks the row; the clip is then flagged sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`; the flag
is always set, but only Android 13 and later read it, so the system's clipboard preview does not
print the address there, while Android 11 and 12 ignore the flag). Without the switch the clip is
a plain one. Android 13 and later confirm a copy themselves; the clipboard is
readable by the app in the foreground and by the keyboard, as for any copied text.

**Wifi-ADB timer (#1000):** a timer switches Wifi-ADB on for 15 minutes to 8 hours (extendable to at
most 24 hours left) and then off. Starting it is a manual "on", exactly like the switch: it
switches Wifi-ADB on where the switch would and gets the same manual-session exemption on the
current Wi-Fi (#864). While it runs it keeps Wifi-ADB alive like Keep-Alive, but re-enables it only
where the network rules allow it (Force mode is what overrides them), and its end counts as a manual
"off", so Keep-Alive does not bring Wifi-ADB back afterwards. From the first moment after the deadline
nothing re-enables Wifi-ADB automatically, even if the end has not been processed yet. The
switch-off itself is best effort without a fixed upper bound: an inexact alarm, the service's minute
check and opening the app trigger it, usually within seconds, but deep sleep and Doze can delay it
(Android may batch such alarms by many minutes). The end is stored together with the "off" intent
and an outstanding switch-off that every driver and the next process start retry until Wifi-ADB is
seen off, so a crash or a rejected write cannot leave it on with Keep-Alive allowed. After a reboot
the remaining time continues; if it ran out while the phone was off, Wifi-ADB stays off. A clock that
cannot be trusted (unreadable boot count, wall clock before the timer's start after a reboot) ends
the timer at once. "+1 h" on its notification needs an unlocked device. Details:
[docs/timed-wifi-adb.md](docs/timed-wifi-adb.md).

**Notification channels (#1000):** the status notification moved from a low-importance channel to
one with default importance, and Force mode and the timer have their own channels (Force mode with
high importance); all three are silent. Settings a user had changed on the old status channel do not
carry over.

**Hide on the lock screen (#920, opt-in, default off):** under the notification switch. When on,
every KeepADB notification, the self-pairing ones included (#993), is posted with `VISIBILITY_SECRET`, so none of them (main, network
trust notice with its Allow/Block buttons, webhook warning, Force Mode and network shutdown notices)
appears on the lock screen, whether or not Android shows sensitive content there. The option is
read and each notification posted under one lock, so a post cannot slip past a change. A change
re-renders the main notification at once and removes a posted trust notice with network
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
KeepADB sends no analytics or crash reports. The feedback link in Settings opens the website with the
app version, device model, Android version and the selected report type as URL parameters; nothing
else is added.

Android cloud backup and device transfer are disabled. This includes the webhook URL, saved
network trusts, and diagnostics data. These data are lost when the app is uninstalled
or the device is changed, unless you export them yourself under Settings → Settings backup
(#1001). That file is written only to a location you pick, is never uploaded by KeepADB, and
holds the names and addresses of your trusted and blocked networks and, unless you leave it out,
the webhook URL; treat it like the webhook URL itself. It never contains diagnostics, the
Keep-Alive or webhook on/off state, or any permission, and an import cannot turn Wifi-ADB on. Diagnostics are not uploaded automatically; see
[Diagnostics export](docs/diagnostics.md) for sharing and redaction details.

## Supported versions

Security fixes apply to the latest published version. There is no separate long-term support branch.

## Report a vulnerability

Please do not report a vulnerability through a public GitHub issue. Use the feedback form in
Settings or [keepadb.roteson.de/feedback](https://keepadb.roteson.de/feedback), choose "Other", and
start the message with "SECURITY". Do not post details publicly.

Include the KeepADB version, Android version/API level, reproduction steps, and relevant network
conditions. Do not include a webhook URL, pairing key, or other credentials.
