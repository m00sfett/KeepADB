# Trusted Wi-Fi networks

KeepADB can limit automatic Wireless debugging recovery to Wi-Fi networks that you trust. This
rule protects automatic actions from an unexpected access point; it does not change the manual
on/off control.

## Model: trust, blocks, and precedence

One model (#760) decides whether every automatic recovery is allowed: the service (observer and
60-second check), recovery pulse, USB handover, and Keep-Alive switch all consult the same place.
The UI previously said “allowed” and “allowlist”; the policy means “trusted.” The wording was
updated with the Networks list (#762).

**Components**

- **Trust an access point by BSSID.** A new installation starts in allowlist mode: automatic
  recovery is allowed only when the current Wi-Fi identity is known and its BSSID appears exactly
  in the list.
- **Block a BSSID or Wi-Fi name (SSID).** BSSIDs are compared without regard to letter case; SSIDs
  are compared exactly, as Android compares saved networks. A placeholder (masked or empty BSSID,
  unknown name) cannot be blocked, because that would block every network whose identity cannot be
  read.
- **Convenience switch “Also trust by Wi-Fi name”** (`trust_by_name`, off by default): access
  points with the name of a trusted access point are trusted too. Names are derived from trusted
  access points; there is no separate name list. A blocked access point does not contribute its
  name. Because names can be imitated, this switch is off by default.
- **Previous “All Wi-Fi networks” setting** (saved value `all_wifi`): every network is trusted,
  including a network whose identity cannot be read, **unless it is blocked**. Existing
  installations keep this setting unchanged; it is neither Force mode nor one of the protection
  levels (see Migration).
- **Legacy name allowlist:** the previous SSID list with its “Also allow by Wi-Fi name (SSID)”
  switch. It stays in effect as saved and applies only in allowlist mode. SSIDs can be chosen
  freely and copied, so this is weaker than trusting a BSSID.

**Precedence** (top to bottom; the first match decides, implemented in one place,
`KeepADBTrustedNetwork.evaluate`):

1. Block by BSSID.
2. Block by Wi-Fi name (also applies when only the name is readable).
3. Previous “All Wi-Fi networks” setting: trusted.
4. Trusted access point.
5. Trusted name: derived (convenience switch) or legacy name allowlist, with an exact,
   case-sensitive match.
6. Otherwise: readable identity = unknown, and KeepADB asks; unreadable identity = pause.

A block wins over every kind of trust, including trust for the same BSSID. Trust remains saved and
applies again once the block is explicitly lifted. Adding trust never lifts a block, whether from a
notification, list, or card; nothing is saved in that case. Blocked networks do not prompt, are not
recorded as “recently prevented,” and are never enabled automatically. Only Force mode
([see below](#force-mode)) overrides a block; it is an overlay above this precedence order.

Known limit: under “All Wi-Fi networks,” a network whose identity cannot be read remains trusted,
as it did before #760. A block cannot identify it without a readable identity. In allowlist mode,
the same network is paused. Force mode does not change this behavior: while active it overrides
blocks and trust, and afterward the exact saved setting applies again.

## Limits of network identity (#863)

Trust decisions are only as strong as the identifiers they compare.

- **Both identifiers can be imitated.** An SSID is a freely chosen name. A BSSID is the access
  point's MAC address: it recognizes an access point, but it does not authenticate it
  cryptographically, and an access point can be configured with a copied BSSID. Matching by BSSID is
  therefore stronger than matching by name, but it is not proof of identity.
- **Imitating an identifier is not the same as being joined.** A copied BSSID alone does not defeat
  WPA2/WPA3 authentication or make Android connect to that access point. Whether the phone joins
  depends on the saved network, the credentials and platform behavior. With WPA2/WPA3-Personal,
  anyone who knows the shared password can run an access point the phone cannot tell apart from the
  real one. KeepADB does not and will not collect Wi-Fi passwords.
- **ADB has its own protection.** Android authenticates paired hosts and encrypts Wireless
  debugging with TLS. Imitating an access point identifier does not by itself give anyone an ADB
  shell; a host still has to be paired.
- **Trust gates automatic actions; shutdown is opt-in.** A trusted network allows automatic
  enabling and recovery. If Wireless debugging is already on when the phone moves to an untrusted
  network, KeepADB shows a notice and, by default, leaves it on. See "Turn off on untrusted Wi-Fi"
  below for the optional shutdown (#864).
- **Security type as an extra signal.** #860 evaluates comparing the connection's security type
  with what was seen when an access point was trusted. That can reveal some downgrades (WPA3 to
  WPA2, or to an open network), but not prove that an access point is genuine. Mesh networks can mix
  security types per access point, so a baseline would have to be kept per BSSID.

The in-app texts already describe name matching as weaker and easy to imitate, and they do not
promise more than recognition of an access point. No additional localized explanation was added
for #863.

## Turn off on untrusted Wi-Fi (#864)

An optional setting in the network section, off by default. While Keep-Alive runs, KeepADB turns
Wireless debugging off when it is on in a Wi-Fi that is not trusted, is blocked, or cannot be
identified, instead of only notifying:

- **When:** on network callbacks (new Wi-Fi, roam, capability change), on the one-minute heartbeat,
  and right when the setting is switched on. It is a best-effort reaction, not an instantaneous
  guarantee: a delayed callback or a suspended app delays it.
- **Unreadable identity:** checked again after 5 s and after a further 10 s, so the few seconds of
  an access-point roam do not switch anything off.
- **Your own session:** if you switched Wireless debugging on yourself, it stays on while the phone
  stays on that access point. Any other access point (a trusted one too), a lost Wi-Fi or Wireless
  debugging going off ends that exemption for good; returning to the first access point does not
  revive it. An unreadable identity cannot prove the same access point: switching on there grants
  no exemption, and while the identity is unreadable an existing exemption does not apply. Only
  switching on in KeepADB (app, tile, widget, notification, USB handover action) counts; switching
  on in Android's own settings grants no exemption. Switching on in KeepADB that Android does not
  carry out (the setting reads back off) grants none either (#880). A restart of the Keep-Alive service ends an
  exemption recorded before it, because what happened while nothing observed it is unknown.
- **Force mode** suspends the policy while it runs.
- **Recheck before writing:** every condition is checked again immediately before the OFF write, so
  a stale event cannot switch off a session that has become trusted meanwhile.
- **Not your "off":** the policy does not record a user OFF. Keep-Alive stays armed and switches
  Wireless debugging back on once the phone is on a trusted network again.
- **Notices:** "Wireless Debugging turned off" after a shutdown, or "Could not turn off Wireless
  Debugging" when the write failed. The trust prompt for the network appears as before.

## Prompt and decision dialog

When automatic enabling encounters a readable access point that is neither trusted nor blocked,
KeepADB shows the “New Wi-Fi network / new access point detected” notice (#446, #766). It has two
forms and one tap target.

- **With notification details:** the name and BSSID appear in the text, with two buttons: “Yes,
  allow” (requires unlocking/authentication) and “No, block.” The second button now actually blocks
  the access point, by BSSID only and not by name; previously it only dismissed the notice.
- **Without details (default):** the notice shows no name and has no buttons; tapping it opens the
  app. Neither form's public lock-screen version shows a name, address, action, or destination.
- **Tap:** opens the “Trust this network?” dialog (`NetworkDecisionActivity`: a dialog screen,
  absent from the recent-apps list and never shown over the lock screen). It shows the Wi-Fi name
  and access-point BSSID, deliberately even in privacy mode, and offers: Trust, “Block this access
  point only,” “Always block Wi-Fi name…” (unavailable if the name is hidden or unreadable), and
  “Decide later.” If the convenience switch is on, a note explains that other access points with
  this name will also be accepted. The dialog is built as a reusable view
  (`NetworkDecisionView`) that the Networks list (#762), assistant (#761), and home screen (#764)
  can use.

**Actions that do not decide.** “Decide later,” Back, tapping outside the dialog, rotating the
device, and dismissing the notice do not change status. The same access point is not prompted again
for at least 24 hours (previously 6 hours); a blocked access point is never prompted.

**Locked device.** The notice stays neutral (no name; #578/#592/#598). The dialog appears only
after the device is unlocked: the activity has neither `showWhenLocked` nor `turnScreenOn`, so
Android requires unlocking before opening it, and it checks the screen lock itself as well. While
the screen is locked, it does not bind a name or address; when paused (screen off or another app),
both are removed from the view again.

**Binding.** The dialog belongs to the access point that prompted it, not the current Wi-Fi
network; its name comes from the app's own record, never from the intent. If the request has
already been answered (trusted or blocked, for example through the Networks list) or there is no
record left (evicted, unknown, or placeholder BSSID), no dialog opens; a brief message appears
instead. If the user taps “Trust” in a dialog opened before a block was added, KeepADB refuses
(the block takes precedence, #760), saves nothing, and explains why.

A block made from a notice or dialog is lifted in the **Networks** list (next section). Lifting a
name block preserves saved trust, which becomes effective again.

**Unreadable network.** If the identity cannot be read, there is nothing to trust or block, so
KeepADB shows the separate “Current Wi-Fi is not readable” notice instead (#460), which leads to
the likely fix. Roaming between access points briefly makes the identity unreadable while Wi-Fi
still counts as connected, so the notice is not posted on the first unreadable read (#872): KeepADB
checks again after 5 seconds and after a further 10 seconds, and posts it only if the identity is
still unreadable on Wi-Fi at all three checks. Automatic enabling stays paused during these checks.
If the network becomes readable or Wi-Fi is lost, nothing is posted and the 24-hour throttle is not
used up.

## The “Networks” list

The list (#762, part a; Settings → Network → “Networks”) shows trust and blocks in one place: the
current network at the top, followed by saved access points grouped by Wi-Fi name. In #769, the
older views (“Allowed access points,” “Recently prevented re-enabling,” and observation list), mode
selector, observation option, and separate name list were removed from Settings. The network area
there still shows the current network with its action, the protection level (display only; the
assistant changes it, #761), the convenience switch, Force mode, the “Networks” entry point, and
background access.

**One status per network.** Status comes from the same evaluation as automatic enabling
(`KeepADBTrustedNetwork.evaluate`); the list does not calculate a separate policy. It shows
*Trusted*, *Blocked*, *Unknown*, or *Unreadable*, and for the current network also *Trusted by name*
(through the convenience switch). If an access point is trusted but its name is blocked, it shows
*Blocked* with the reason (“Blocked by name…”), never *Trusted*. The saved trust remains under the
name block. Force mode does not change the labels (it changes nothing stored); the list only notes
that trust and blocks are overridden while Force mode is active.

**Stored names (#796, #802).** When an access point is trusted, its entry stores the Wi-Fi name
under which it was trusted, even when its label contains only the BSSID (for example, after trusting
an access point from a block when its name is known). When it is blocked, the block stores the
Wi-Fi name and, if renamed, a custom name. The list files a row under that name when neither the
label nor the current connection provides one, and derives the status from it: a trusted access
point whose stored name is blocked still appears as *Blocked* (reason: name) even when it is not
the current network. The stored name is from the time it was trusted or blocked and may be stale.
It describes the row only and makes no decision: when connecting, the live connection name is used,
and derived name trust (the convenience switch) still reads only the label. In a blocked row filed
under a stored name, the dialog does not offer “Trust” while that name is blocked; the Trust action
itself checks only the name from the label or current connection, never the stored name. Entries
trusted and blocks saved before this feature have no name and are not backfilled. Such a trust entry
with only a BSSID label still appears as *Trusted* in the list, even if its former name is blocked
(unless it is the current network); the block still applies when connecting. New trust for that
access point stores the name when it is readable.

**What a tap changes.** On a trusted access point: Block, “Stop trusting,” Rename, or “Always block
Wi-Fi name.” On a blocked access point: “Lift block,” Trust (lifts only that address block and
fails while its Wi-Fi name is blocked), Rename (the custom name belongs to the block), and, for a
name block, “Lift name block…” with confirmation. On a group heading: block or unblock the name.
Both “Stop trusting” and “Lift block” for an access point end in *Unknown*, so KeepADB asks again
when it next connects. To ensure this also applies when legacy data contains both trust and a block
for an address, lifting the address block removes that trust; blocking from the list removes trust at
the time of blocking. Lifting a **name** block instead preserves all saved data: trusted access
points become trusted again, and unknown ones prompt. None of these actions turns Wireless
debugging on or off.

**Current network, undecided.** Instead of a separate dialog, the decision view from #766
(`NetworkDecisionView`) is embedded in the card, without “Decide later.” It also appears when the
network is accepted only through the convenience switch or previous “All Wi-Fi networks” setting,
so the user can explicitly trust or block that individual access point.

**Convenience switch.** The “Also trust by Wi-Fi name” switch replaces the list's three modes:
off = trusted access points only (BSSID); on = also access points with the name of a trusted one.
The protection level is shown beside it. If both the convenience switch and legacy name list are
on, the level is labeled “Name allowlist (previous setting)” because the saved list extends access
beyond names derived from trusted access points. Under the previous “All Wi-Fi networks” setting,
the switch has no effect and the list says so.

**Privacy mode.** It replaces the entire list with a placeholder. No views or descriptions with
Wi-Fi names or BSSIDs are built, including the embedded decision view that deliberately shows the
name (#766). The Settings count (“3 trusted · 1 blocked”) becomes “Hidden.”

**Limit.** A block can affect only a network whose name or address is readable. If the network
cannot be read and the previous “All Wi-Fi networks” setting is active, KeepADB continues enabling
it as before #760 (the card describes it as “unreadable” and says recovery is not paused).

## Migration and rollback

Migration rewrites nothing: the model reads the existing keys in place. It is idempotent, loses
nothing, and does not silently tighten or relax the policy.

| Saved state | Model interpretation (`getProtectionLevel`) | Behavior |
|---|---|---|
| Mode `all_wifi` | Previous “All Wi-Fi networks” setting | Unchanged; blocks also apply |
| Allowlist mode, name allowlist off or empty | Most secure | Unchanged |
| Allowlist mode, name allowlist on with entries | Previous name allowlist | Unchanged; the name list remains in effect |
| Allowlist mode, new convenience switch on | Balanced | Names of trusted access points |
| Nothing saved (new installation) | Most secure | Trusted BSSIDs only |

The two “previous” states are settings that do not map exactly to either protection level. They
are therefore shown as-is rather than mapped to a level. Mapping “All Wi-Fi networks” to “Most
secure” or “Balanced” would silently tighten it; mapping the name list to the derived convenience
switch would both lose names (those allowed only through the list without a trusted access point)
and add new ones (the names of every trusted access point). They are replaced only by an explicit
choice in the assistant (#761) or list (#762).

The mode is persisted on its first read so a later default change cannot shift an installation. A
saved mode remains unchanged; only the exact value `all_wifi` means the previous setting, and any
other value (missing or corrupt) is treated as allowlist mode. An installation with no saved mode
(new installation or a very old installation that has not evaluated the rule since 1.8.9) starts in
allowlist mode.

The “recently prevented” history and observation list have never decided trust and are not part of
the model. During cleanup (#769), **no key was deleted or rewritten**; both views were removed.
“Recently prevented” history remains: the decision dialog (#766) uses its entry as the request
record (name and BSSID) and deletes it once the request is answered. The observation list (BSSID
history, `bssid_history_*`) is no longer updated since #769, and its stored data was
**discarded once during update** since #778 (user decision 2026-10-06): `KeepADBBssidHistory.discardLegacyOnce`
deletes only keys with this prefix and sets the `observation_history_discarded` marker in the
same step, so it runs exactly once. A new installation has nothing to delete, and the marker does
not count as stored history there. All other keys remain unchanged. There is no UI path to delete
this history, and none is needed. The three readers were removed in #788: the mesh-node offer
(#686) is gone (allowing an access point now allows exactly that one); the Networks list no longer
reads names from history (a blocked access point with no trust entry and no known name appears in
the “Wi-Fi name unknown” group, as does a trust entry whose label is only its BSSID; new entries
and blocks save their own name, see “Stored names” above); and bands are no longer saved (only the
current band is shown). The previously enabled `wifi_aps_feature_enabled` option remains
unchanged, but no code reads or writes it. A saved name list (`trusted_ssid_*`,
`trusted_network_ssid_matching`) also remains active (“Name allowlist (previous setting)”); it
can no longer be edited without its former interface. The assistant's “Protection level” step
(#761) provides the way to change it: the previous setting (“All Wi-Fi networks” or name allowlist)
is preselected and marked “Less secure” or “Notice,” and a selected preset replaces it without
deleting saved lists (the name allowlist is disabled, not cleared). Open this step by selecting the
“Protection level” row in Settings, or open the full assistant using the top “Setup assistant”
entry. `SettingsCleanupMigrationTest` records this: saved data is unchanged, the decision is the
same, and the name list still applies.

**Rollback.** Only three new keys were introduced in `keepadb_prefs` (`blocked_bssids`,
`blocked_ssids`, `trust_by_name`); all previous keys keep their format and meaning. An older app
version therefore reads the same trusts, name allowlist, and mode, ignores the new keys, and does
not apply blocks. Rolling back loses only the effect of the blocks, not data. Backups and device
transfers do not include `keepadb_prefs` (`allowBackup="false"` and
`data_extraction_rules.xml`).

#796/#802 add further optional keys: `trusted_network_<id>_ssid` per trusted entry,
`blocked_bssid_ssid_<bssid>` and `blocked_bssid_name_<bssid>` per blocked address (address in
lowercase). They are written and deleted with their corresponding entry or block. Older data
without these keys means “no name” and is never rewritten or backfilled (there is no migration).
An older app ignores the keys: rolling back loses only the names in the list; trust and blocks are
unchanged. (If an older version later blocks the same address without knowing these name keys,
old name remnants for that address may reappear in the list as display text only.) Names never leave
the device: they are not included in diagnostics, the webhook, or any notification. The list shows
them only while privacy mode is off (privacy mode replaces the whole list, including custom names
for blocks).

Code evidence: `KeepADBTrustPrecedenceTest` (precedence in both directions),
`KeepADBTrustMigrationTest` (same decision as 1.9.28 for every saved state, no rewrite, rollback),
and `KeepADBBlockedNetworkCallPathTest` (blocks apply to the actual action paths).

Allowlist mode needs the location permission described in the next section. Without a readable
identity, KeepADB pauses and explains why.

## Force mode

Force mode (#763) is the only exception to the precedence above: for a chosen period,
Keep-Alive re-enables Wireless debugging on **every** Wi-Fi network, including foreign, blocked,
and unreadable networks. It is an overlay, not a mode in the trust model. Starting and ending it
do not read or change anything stored by the model (trusted access points, blocks, the convenience
switch, or the previous setting). “Return to the previous protection level” therefore means only
that the overlay is removed. The overlay acts at the one decision point used by every automatic
path (`KeepADBTrustedNetwork.evaluateCurrent`): the service (observer and minute interval),
recovery pulse, USB handover, and protection checks immediately before writing. It does not replace
Keep-Alive or a Wi-Fi connection, and a manual “Turn Wireless debugging off” remains in effect.
Adding trust to a blocked network remains impossible even in Force mode; Force mode changes neither
blocks nor trust.

**Starting and ending.** Force mode can be started only through the confirmation dialog
(Settings → Network → Force mode), with a required duration: 1 hour (preselected), 24 hours,
7 days, 30 days, or “No expiry.” The dialog gives staged warnings (a general warning; an
additional warning about using it while away from trusted networks for 7 days or longer; and, with
no expiry, an additional warning that it persists through reboots and updates). “No expiry” can
be confirmed only after checking its box. Keep-Alive is also enabled if it was off (the dialog
says so). Choosing another duration is a new full start through the same dialog; there is no silent
extension. The user can end Force mode at any time without confirmation: the card on the home
screen, the row in Settings, or the first notification action.

**Expiry.** The duration ends at the deadline itself: a read-only check returns “off” from the
first moment after the deadline, even if no timer has run. The visible transition (clear state,
update surfaces, show a one-time notice) is handled by the service's minute interval, an inexact
alarm (which can run without a process), the receivers for reboot, app update, and clock changes,
or opening the home screen or Settings. The notice appears exactly once, even when expiry occurs
during a reboot or update. It uses its own “Security notices” channel, offers no way to resume
Force mode, and names the restored protection level. If Wireless debugging is still on in a
network that the protection level does not trust, the notice says so and offers “Turn off now”;
nothing is turned off automatically.

**Time rules.** A single value in `keepadb_prefs` (`force_state`, written with `commit()`)
stores the duration, remaining time budget, and time bases used to count it (wall clock, monotonic
clock, and boot counter at that moment). The budget starts at the full duration. Remaining time is
the **smaller** of two measurements, so any clock discrepancy can only end Force mode sooner, never
extend it: wall-clock time (`base + budget - now`, based on the epoch and therefore unaffected by
time zone or daylight-saving changes), and, while the base boot counter is still current, the
monotonic clock (`SystemClock.elapsedRealtime`, which includes deep sleep). Setting the wall
clock backward therefore does not extend it within one boot; setting it forward ends it sooner.

After a reboot, the monotonic clock starts over. The first driver in the new boot (the service's
minute interval, a receiver for reboot, update, or a clock change, or opening the app—whichever
runs first) therefore binds the remaining budget to that boot and saves it. The wall clock,
monotonic clock, and boot counter at that moment become the new base. From then on, setting the
wall clock backward cannot extend the deadline during that boot; another reboot repeats this from
the new base. A reboot alone does not end Force mode or add time; the budget only decreases. Until
that binding happens, only the wall clock is used, counting forward from the base: if it is earlier
than the base (a clock reset), the remaining time cannot be determined and Force mode ends
(fail-closed).

A backward wall-clock change is also detected within a boot: if wall-clock time falls more than
five seconds behind the monotonic clock since the base, the first driver that notices (the clock
change notice immediately, the minute interval or opening the app no later than then) makes the
remaining time reported by the monotonic clock the new base. A later reboot then keeps the correct
state and does not credit the clock jump. A forward wall-clock change is never checkpointed: it
only shortens the duration, and correcting it afterward does not restore time. The reverse path
costs time: if a driver sees a backward-reset clock and it is later corrected forward, that
correction counts as a forward clock change and shortens Force mode by the jump size. This is the
safe direction: sooner, never later.

If the system boot counter cannot be read, a time-limited mode ends immediately: the first read
returns “off,” and the one-time expiry notice follows (#773, with its own wording that identifies
the early safety end rather than saying “The selected time has expired”; the same applies after
reboot if the wall clock is earlier than its base). A time-limited mode cannot be started while
the counter is unreadable; the dialog explains this and stays open. If the counter becomes
readable again, the mode remains ended and can be started again from its dialog; nothing remains
blocked. “No expiry” has no deadline, does not need the counter, and remains active until ended,
including across reboots and updates.

Known remaining case: a wall clock reset after reboot but before the first driver runs (a short
window, since the boot notice arrives after unlock) is visible only to the wall clock. Force mode
then lasts longer by the jump size. The same applies to a reset within the five-second tolerance
and to a reset followed by reboot before a driver could checkpoint it. All three require a clock
reset in exactly such a window; time-zone and daylight-saving changes do not cause them. It has not
been checked on all devices that `Settings.Global.boot_count` is readable; where it is not, a
time-limited mode cannot be started.

**Lock screen.** The warning row and end action are available only in the private notification.
Its public version remains neutral, and the expiry notice shows only the channel name there. As
with all private notification content, anyone who allows sensitive content on Android's lock
screen will see the private version.

**Rollback.** Only three extra keys were added to `keepadb_prefs` (`force_state`,
`force_expired_notice_pending`, `force_expired_reason`; the third holds only the reason for an
early end, `safety`, until the notice is delivered). An older app ignores them. Rolling back ends
Force mode (the more restrictive state), loses no data, and reports every ending as “expired,” as
before. These keys never leave the device (backup and device transfer are excluded).

Code evidence: `KeepADBForceModeTest` (time rules from both sides, reboot, update, clock changes,
time zones, exactly one notice, and alarm), `KeepADBForceModeCallPathTest` (precedence on every
action path, with counter-checks and expiry), `KeepADBForceNotificationTest`,
`KeepADBForceDialogTest`, `MainActivityForceCardTest`, and `KeepADBForceContractTest` (only the
dialog starts Force mode; the gate and display only read it).

## Permissions and background behavior

Android protects SSID and BSSID as location data. Allowlist mode requires **precise location
access** and enabled Android location services so KeepADB can read the Wi-Fi identity. Location
data is used only for this comparison; KeepADB does not determine or store physical device
location. Approximate location access is not enough.

When precise location is granted, the Keep-Alive foreground service also uses Android's location
service type so the Wi-Fi identity remains readable after leaving the app when the service was
started from the app. Android may still mask the identity after a background start, for example
after a reboot or app update. The user may optionally enable **Location access → Allow all the
time** on Android's app-permission page. KeepADB does not request this permission through the
Android permission dialog; after showing an explanation in the app, it opens system Settings.

Without a readable identity, access remains blocked. Starting with Android 14, Android may also
reject the foreground service's location type during a background start. KeepADB then tries to
continue with the connected-device type while the network identity remains blocked. A running
Keep-Alive service in this case does not mean that the current access point was approved.

## Reboot and manual controls

The boot receiver starts Keep-Alive only when it is enabled and the saved last user intent does not
contradict the start. It responds to completed system boot and app updates. Android may defer the
normal boot event until the first unlock when device storage is encrypted. Start timing and
recovery also depend on Android, the manufacturer, and permission state.

The trust rule applies to automatic recovery. Manual toggles in the app, widget, or Quick Settings
remain deliberate user actions and are not blocked by the allowlist. Lock-screen rules for the
tile and notification actions are in the [security policy](../SECURITY.md).

## Names and bands in the Networks list

The single Networks list groups trusted and blocked access points by Wi-Fi name. Each row can have
a custom display name, entered through the pencil action. A custom name is local to the app, up to
40 characters, and changes neither the SSID nor any trust decision: the BSSID remains authoritative.
For a blocked access point, the custom name belongs to the block and disappears when the block is
lifted; trusting a blocked access point carries the name to the trusted entry, and blocking a
trusted access point carries its custom name to the block. When privacy mode is on, the whole list
is replaced by a placeholder, including custom names.

The BSSID line may include a band in parentheses, for example
`AA:BB:CC:DD:EE:01 (5 GHz)`. If no band is known, the address appears without parentheses or
placeholder text. KeepADB does **not** start or schedule a Wi-Fi scan. When the list opens, it reads
only data Android already holds: the current connection frequency and the system's cached scan
results, by BSSID. Two BSSIDs for the same network, such as a router's 2.4 GHz and 5 GHz radios,
remain separate entries with their own bands. Android provides scan results only with precise
location access and location services enabled, and may hold stale results; when no frequency is
available, no band appears until Android provides one on a later list opening.

Earlier (#721), when “Observe access points” was enabled, KeepADB also saved the last-seen band for
each BSSID in observation history (`bssid_history_<id>_bands`). Since #769 no new history is
recorded; since #778 the old history and bands are discarded during update (see above), and since
#788 nothing reads them. The list therefore shows only the current band Android provides; if none
is available, no band appears. Band is display-only and never contributes to a trust decision.

## Measurement limits

Measurements on AOSP emulators and the Galaxy S20 FE support the cases described above for the
Android versions tested. They do not cover all manufacturers, Wi-Fi drivers, permission flows, or
reboot variants. The [measurement summary](trusted-networks-measurement.md) separates measured
results from open questions; the [full archive](archive/trusted-networks-measurements-2026-09.md)
preserves the original logs. The current code and manifest are authoritative if a historical
measurement note conflicts with today's implementation.
