# Changelog

All notable changes to **KeepADB** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.5.39] - 2026-09-10

### Fixed
- Accessibility, multi-state and resource contract suites now exercise inflated Android views
  and runtime resources through Robolectric instead of parsing production source text (issue #325).

## [1.5.38] - 2026-09-10

### Fixed
- A rejected wireless-debugging setting write no longer leaves the app's remembered intent
  claiming that the requested state was applied (issue #333).
- Rapid manual OFF/ON toggles now receive only a short technical teardown gap, preserving the
  immediate manual behavior without restoring the former 1500 ms debounce (issue #335).

## [1.5.36] - 2026-09-09

### Fixed
- The in-flight wireless-debugging register test now waits for the actual disconnect request
  and terminal deregistration state instead of relying on request-list timing or order (issue #356).

## [1.5.35] - 2026-09-09

### Fixed
- Cached endpoint verification now releases the stopped endpoint and records the same
  Wi-Fi-disconnected discovery-skip diagnostic as the normal refresh path (issue #361).

## [1.5.34] - 2026-09-09

### Fixed
- Discovery callbacks now invalidate stale endpoint verification work at the point where an unavailable endpoint clears the cached endpoint, even if the caller already bumped the token (issue #360).

## [1.5.33] - 2026-09-09

### Changed
- Documented the Android permission boundary for reading and writing the wireless-debugging setting. The recovery-pulse exception handling remains defensive for provider-specific or future restrictions (issue #339).

## [1.5.32] - 2026-09-09

### Changed
- Simplified the successful toggle diagnostic path: after an accepted system-setting write, the outcome now depends directly on the reread state. Existing mismatch reporting is unchanged (issue #337).

## [1.5.31] - 2026-09-09

### Fixed
- App screen and settings screen now keep clear of the system bars. On Android 15 the system draws apps of this kind from edge to edge, which could put the title bar behind the clock and battery display and the lowest buttons behind the navigation bar. Title bar and content are now shifted by exactly the space the system bars occupy — including a display cutout at the side (issue #324).
- The settings screen now makes room for the on-screen keyboard: when a text field such as the webhook URL is tapped, the content moves up far enough that the field being edited stays visible instead of disappearing behind the keyboard (issue #324).

## [1.5.30] - 2026-09-09

### Fixed
- The main switch in the app now shows only whether wireless debugging is actually switched on in the Android system. Until now it stood on "ON" while the system setting was off and Keep-Alive was merely waiting to switch it back on — the app claimed a state the device did not have. The wait is now shown as a separate line of text below the switch instead (issue #318).
- Tile, home screen widget and the switch in the app now agree on what a tap means. Previously they disagreed most where it mattered: while wireless debugging was off and Keep-Alive was waiting, the tile switched it on while the widget switched it off. A tap now requests the opposite of the real system setting everywhere. The one deliberate exception stays: while wireless debugging is on but no endpoint has been found yet, a tap on the tile starts a new search instead of switching off (issue #318, keeps issue #267).
- A switch-over that was planned automatically and then dropped because the network changed in the meantime no longer leaves the "switching…" hint standing on the surfaces (issue #318).
- While a switch-over is still waiting out the internal delay of up to 1.5 seconds, all three surfaces now say so instead of continuing to show the old value (issue #318).
- A failed switch-over is now reported for what it is. So far every failure was reported as a missing permission, even when the permission was granted and only the write to the system setting was rejected (issue #318).

## [1.5.29] - 2026-09-09

### Fixed
- Webhook URLs shown in the app no longer reveal their query parameters. Only the fact that parameters exist is shown (`?***`), so a token or password passed as a parameter stays hidden even when someone is looking at the screen (issue #350).
- IPv6 addresses in a webhook URL are now hidden completely, in every notation — shortened, full, with or without an interface suffix, with or without a port. Previously only IPv4 addresses were partly masked and IPv6 hosts were shown in full (issue #350).
- Log entries now hide the same parts as the app screen. So far they showed IPv4 addresses unmasked, and an IPv6 address with an interface suffix made the redaction fail entirely (issue #350).
- A webhook URL saved by an older version of the app is cleaned up when it is read, not only when it is written. Such a stored value is also used as the target when deregistering from a previous URL, so a user name and password contained in it could previously be sent over the network. Existing installations are covered without any action by the user (issue #350).

## [1.5.28] - 2026-09-09

### Fixed
- Reconnecting to Wi-Fi now always requires a fresh verification of the access point: Android does not guarantee that the loss of the old network is reported before the new one becomes available, so automatic re-enable could briefly evaluate the new connection against the previous one's remembered trust. If the new connection's access point address was hidden from the app and its network name happened to match, wireless debugging could be switched on automatically on a network that was never checked (issue #354).
- Consequence of this fix, deliberately accepted: the convenience introduced in #270 — keeping trust while the access point address is hidden from the app in the background — no longer carries over a reconnect. After reconnecting, the network must be recognizable once (open the app or grant background location access) before automatic re-enable applies again.
- Remembered trust is no longer used at all while no Keep-Alive service is running to notice network changes. USB handover and the endpoint recovery pulse also read this state and are reachable without a running service, where a stale entry could otherwise survive any number of unnoticed network changes (issue #354).

## [1.5.27] - 2026-09-09

### Fixed
- A failed cleanup at the previous webhook URL no longer drops that registration: the app keeps a bounded retry backlog and flushes it at the next register activity, instead of persisting the new URL and leaving the old registration active on the server forever (issue #317).
- Switching the webhook URL now also deactivates the previous USB-ADB registration, which was reported to the old URL only for wireless debugging before (issue #317).
- A failed USB-ADB webhook report is recorded as a failed status and reported to the app, symmetric to the wireless-debugging path, instead of being dropped silently in the background (issue #317).
- The wireless-debugging webhook result (timestamp, URL, endpoint, status) is written in a single preferences transaction, so a crash can no longer leave a stored URL without its endpoint (issue #317).
- A queued cleanup is discarded as soon as a registration for that same URL succeeds. A cleanup for an already-absent record keeps failing (the server answers 404), so it could outlive a later successful registration at that URL and then delete or deactivate the live registration (issue #317).

## [1.5.26] - 2026-09-09

### Fixed
- Endpoint candidates are now bound to the active Wi-Fi interface: an mDNS service is only registered when its address is one currently held by this device's Wi-Fi network, instead of any loopback, link-local or otherwise "known local" address (issue #314).
- Without a determinable Wi-Fi address — for example with Wi-Fi disconnected — no endpoint is registered at all, rather than falling back to an unrelated local listener (issue #314).
- The quick port probe no longer stops at the first open loopback port: it checks the next candidates against the Wi-Fi address, so an unrelated local service can no longer shadow the real wireless-debugging listener (issue #314).

## [1.5.25] - 2026-09-09

### Fixed
- Discovery retries now use a bounded exponential backoff and stop after a finite retry budget when no endpoint can be found (issue #315).
- Cached endpoint verification now uses attempt tokens, so a stale verification thread cannot invalidate a newer discovery result for the same host and port (issue #315).
- Tile refreshes preserve an ongoing global verification; failed cache checks recheck Wi-Fi before rediscovery and stop when wireless debugging is disabled (issue #315).

## [1.5.24] - 2026-09-08

### Fixed
- The SSID fallback for automatically enabled wireless debugging now applies only to an exactly masked BSSID with previously verified trust; a missing or unknown identity discards the trust cache (issue #313).
- An unreadable or changed SSID alongside a masked BSSID also discards the cache, so returning to the earlier SSID alone no longer restores trust (issue #313).
- Losing the Wi-Fi network clears the trust cache even before foreground promotion has completed, so a masked reconnect requires fresh BSSID verification (issue #313).
- Unknown trusted-network mode values now fall back to the allowlist. Manual toggling and the explicitly selected "all Wi-Fi networks" mode are unchanged (issue #313).

## [1.5.23] - 2026-09-08

### Fixed
- Added live validation for the cleartext HTTP webhook warning in Settings so the warning appears immediately upon typing an unencrypted `http://` address before saving or toggling (issue #319).
- Stripped embedded credentials (`user:password@`) and fragments (`#...`) from webhook URLs before persisting or transmitting them, preventing accidental plaintext credential storage (issue #319).
- Masked IPv4 host octets in `MainActivity` when displaying the registered webhook URL and ensured embedded credentials are never displayed (issue #319).

## [1.5.22] - 2026-09-08

### Fixed
- Aborting an initial in-flight register POST when Wireless Debugging is turned OFF or USB is disconnected now reliably invalidates the pending operation and dispatches a cleanup transaction behind it, preventing the device from being retained as active on the register server (issue #316).
- Preserved USB profile parameters during in-flight registrations so a disconnect occurring before the initial registration finishes still delivers an inactive payload with the selected profile's metadata to the register server (issue #316).

## [1.5.21] - 2026-09-08

### Fixed
- Enabling Keep-Alive while disconnected from Wi-Fi now resets a previous manual-off intent, allowing `KeepADBService` to start in standby and automatically activate Wireless Debugging upon reconnecting to a trusted Wi-Fi network (issue #312).

## [1.5.20] - 2026-09-08

### Fixed
- Boot and package replacement recovery no longer starts `KeepADBService` when Keep-Alive is enabled but the user previously turned Wireless Debugging OFF (issue #311).
- `KeepADBService.onStartCommand` now evaluates `shouldRun` immediately after foreground promotion, cleanly stopping foreground mode and returning `START_NOT_STICKY` when the service should not run, rather than running indefinitely in the background (issue #311).

## [1.5.19] - 2026-09-08

### Fixed
- Automatic enable orders (Keep-Alive recheck, settings observer, USB handover) are now re-checked immediately before they write instead of only when they are planned, so an enable that was waiting out the 1.5 s toggle cooldown no longer switches wireless debugging on after the Wi-Fi changed, the network stopped being trusted, or Keep-Alive was switched off in the meantime (issue #310).
- Manual switching from the app, the quick-settings tile, the widget, the notification, and the USB notification's "Enable WLAN-ADB" action now takes effect immediately instead of being delayed by up to 1.5 seconds by the automatic-toggle cooldown (issue #310).
- The USB handover's automatic path and its manual notification action no longer share one internal source label, so diagnostics can tell an automatic handover apart from a user tap (issue #310).

## [1.5.18] - 2026-09-08

### Fixed
- Recovery pulse now re-checks the current toggle intent inside the same lock as each of its two writes, so a manual tap can no longer be overwritten by an already-superseded pulse (issue #309).
- A rejected `Settings.Global` write is no longer reported as a successful toggle and is no longer booked as an applied change (issue #309).
- The endpoint recovery-pulse cooldown now uses the monotonic clock instead of wall-clock time, so a clock or time-zone change can no longer suppress or retrigger recovery pulses (issue #309).

## [1.5.17] - 2026-09-08

### Fixed
- Bound `KeepADBUsbNotification` to the per-app locale via `KeepADBLocaleHelper.wrapContext` on Android < 33 (issue #323).
- Refreshed active USB notifications immediately when switching app language in Settings (issue #323).
- Replaced hardcoded colon concatenation in accessibility descriptions for language and USB handover mode with localized formatted strings across all 19 locales (issue #323).

## [1.5.16] - 2026-09-08

### Fixed
- Made trusted networks, profile selection, and profile edit dialogs scrollable to prevent clipping with many entries or large accessibility font scaling (issue #322).
- Added contextual TalkBack content descriptions to delete and edit action buttons in trusted network and host profile lists (issue #322).
- Preserved user drafts in the issue report and profile edit dialogs across screen orientation changes and activity recreations (issue #322).
- Prevented truncation of the issue report privacy notice by displaying full descriptive text (issue #322).

## [1.5.15] - 2026-09-08

### Fixed
- Added polite accessibility live regions to dynamic status and error texts so TalkBack announces state updates dynamically (issue #321).
- Improved color contrast for `link_red` (WCAG AA >= 4.5:1 text contrast) and `border_red` (WCAG 1.4.11 >= 3.0:1 non-text boundary contrast) against dark panels (issue #321).
- Expanded the touch target size of the HohnePeople website link in Settings to meet the 48x48 dp accessibility minimum (issue #321).

## [1.5.14] - 2026-09-08

### Changed
- Unified CI and release workflows: restricted documentation triggers to conserve Actions minutes, added `check-i18n` gate parity, unified release build to JDK 17, and added tag and changelog consistency checks (issue #327).

## [1.5.13] - 2026-09-08

### Added
- Added failure-injection support to `KeepADBFakeSettingsGateway` and introduced a package-private `HttpTransport` seam in `KeepADBRegisterClient` for deterministic test coverage of network errors and write failures (issue #326).

## [1.5.12] - 2026-09-08

### Fixed
- The webhook status now distinguishes never reported, successful, deregistered, and failed
  reports, with failed report results persisted for the main-screen status (issue #306).

## [1.5.11] - 2026-09-08

### Fixed
- The webhook URL draft now survives settings rotation and normal activity recreation without
  saving, enabling, disabling, or resetting the configured webhook state (issue #307).

## [1.5.10] - 2026-09-08

### Fixed
- The main-screen webhook status now observes reports before refreshing the cached endpoint,
  so a successful report updates the displayed endpoint and timestamp immediately.

## [1.5.9] - 2026-09-07

### Documentation
- Synchronized the release-signing and installation documentation with the current candidate
  version and the latest published release. No user-facing behavior change.

## [1.5.8] - 2026-09-07

### Changed
- Added an injectable `KeepADBWifiProbe` test-only seam to `KeepADBNetwork` (matching the existing
  `KeepADBNsdProbe`/`KeepADBScheduler` pattern) plus `KeepADBNotification.hasActiveDiscoveryAttemptForTesting()`/
  `resetForTesting()`, and migrated one example test (`KeepADBWifiGatedDiscoveryBehaviorTest`) from
  source-content contract checking to a real Robolectric-driven behavioral test of the #296 Wi-Fi
  discovery gate. Production code always falls through to the real transport-capability check;
  the existing `KeepADBWifiGatedDiscoveryContractTest`/`KeepADBNetworkContractTest` contract tests
  stay in place unmodified as a fast regression guard (issue #303, entry step; no other contract
  tests migrated yet, no production behavior change).

## [1.5.7] - 2026-09-07

### Fixed
- Discovery retry and the recovery pulse no longer keep hammering while Wi-Fi is disconnected.
  `KeepADBNotification.refreshInternal()` now checks the active Wi-Fi connection state
  (`KeepADBService.isWifiConnected()`) before starting discovery, `scheduleRetryLocked()` aborts
  its 2s/5s backoff retry chain once Wi-Fi drops instead of retrying unbounded, and
  `KeepADBEndpoint.maybeSendRecoveryPulse()` now also checks the actual Wi-Fi transport in
  addition to the existing trusted-network allowlist check. This also covers a service restart
  (process kill, reboot) while Wi-Fi is already off, which previously started a doomed discovery
  attempt right away. The event-driven reconnect (#22/#192) and mesh-roam re-verification
  (#276/#285) paths are unaffected (issue #296).

## [1.5.6] - 2026-09-07

### Changed
- Added `org.robolectric:robolectric:4.13` and `androidx.test:core:1.6.1` as `testImplementation`
  dependencies (test-scope only; runtime/release code stays dependency-free per issue #251) and
  migrated one contract test to drive `KeepADBNotification`/`KeepADBService` against real,
  Robolectric-shadowed `NotificationManager`/`ConnectivityManager` instances instead of parsing
  source as text. `android.useAndroidX=true` is required for `androidx.test:core` and is likewise
  test-scope only (issue #286, entry step; no other tests migrated yet).

## [1.5.5] - 2026-09-07

### Changed
- Trusted networks now default to allowlist-active on both new and existing installations
  (previously off, trusting every Wi-Fi network by default). Existing installations are not
  migrated specially -- the new default applies uniformly, so Wireless Debugging auto-enable
  may be blocked until at least one network is added to the allowlist (issue #260).

### Fixed
- The persistent notification no longer shows a stale/dead endpoint after a same-SSID mesh
  Wi-Fi roam (new BSSID, same Network object), which fires neither `onAvailable()` nor
  `onLost()` on the registered network callback; it now re-verifies the cached endpoint on
  `onCapabilitiesChanged()` (throttled to once per 5s), matching the Quick Settings Tile's
  behaviour instead of lagging behind it until the next 60s heartbeat (issue #276).
- Shortened the trusted-network management button label to "Manage whitelist" / "Whitelist
  verwalten" across all supported languages (issue #263).
- Resolved a test-only race in `KeepADBUsbRegisterClientTest` that could intermittently fail
  under CI load without any change to the tested production logic (issue #283).

## [1.5.4] - 2026-09-07

### Fixed
- `KeepADBNetworkIdentity` now treats the platform's `UNKNOWN_SSID` placeholder (BSSID known,
  SSID unreadable at query time) as no SSID instead of a real one, so it no longer files
  BSSID-history observations or mesh-add labels under the literal placeholder string
  (issue #269).

## [1.5.3] - 2026-09-07

### Fixed
- The Quick Settings Tile no longer shows the misleading "Disconnected" subtitle while
  Wireless Debugging is actually on (or Keep-Alive is still waiting to turn it back on) and a
  fresh endpoint is being searched for; it now shows a "Searching…" transitional state instead
  (issue #267, criterion 1).
- Tapping the tile while it is in that searching/`ENABLED_DISCONNECTED` state now retries
  discovery/reconnect instead of reading as "currently off" and switching Wireless Debugging
  off; this now also covers the case where Wireless Debugging is actually off and Keep-Alive is
  merely waiting, which previously stayed a no-op (issue #267, criterion 2).
- Briefly opening and closing the Quick Settings panel no longer aborts an in-flight endpoint
  discovery: `onStopListening()` now only schedules the discovery cancellation after a grace
  period, which `onStartListening()` cancels if the panel is reopened in time (issue #267,
  criterion 3).

## [1.5.2] - 2026-09-07

### Added
- Trusted networks now handle Wi-Fi mesh setups: the app keeps a local, size-bounded history of
  which BSSIDs have been seen broadcasting which SSID (capped at 8 BSSIDs per SSID, oldest
  evicted first), and after adding the current network offers to add any other already-seen
  BSSIDs of the same SSID in one step. BSSID stays the sole trust comparison key -- no SSID is
  ever trusted on its own -- and this history is never sent to the webhook/register-sync
  endpoint (issue #266).

## [1.5.1] - 2026-09-06

### Changed
- The trusted-network "Add current network" button now toggles to "Remove current network"
  when the currently connected Wi-Fi is already in the allowlist, instead of showing the same
  "Added" confirmation again (issue #262).
- The trusted-network management dialog now shows each entry's BSSID under its label, so it's
  visible which physical access point a listed entry actually matches (issue #264).

## [1.5.0] - 2026-09-06

Retrospective entry for a batch of 9 issues (#245–#253) merged in PR #254 without a version
bump or changelog entry at the time (see issue #257). Grouped here by theme rather than
individually, since they landed together.

### Added
- An opt-in trusted-network allowlist for Keep-Alive: automatic re-enable (service recheck,
  content-observer recovery, endpoint recovery pulse, and automatic USB→WLAN-ADB handover) can
  be gated on the current Wi-Fi network's identity (BSSID) matching a user-added entry; manual
  toggling is never gated. `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION` are requested only
  when the user turns the allowlist on, with an in-app rationale (issue #245).
- `CONTRIBUTING.md`, `SECURITY.md` (with private vulnerability reporting), and issue/PR
  templates (issue #253).

### Changed
- CI now runs on every push and pull request instead of only on manual dispatch, and includes a
  release-build (R8) step (issue #246).
- Cleartext-HTTP scope is documented and narrowed to the webhook use case; the app warns
  in-app when a configured webhook URL is `http://` (issue #247).
- Active-network detection no longer uses the deprecated
  `ConnectivityManager.getAllNetworks()`; a new `KeepADBNetwork` tracker uses two
  `NetworkCallback` registrations (Wi-Fi-scoped and default-route) instead, API 30-compatible
  (issue #250).
- Release builds now enable R8 minification and resource shrinking (issue #251).

### Security
- Android backup and device-to-device transfer are disabled entirely (`allowBackup="false"`)
  instead of maintaining a growing per-key exclusion list (issue #252).
- The trusted-network check now also treats the all-zero BSSID `00:00:00:00:00:00` (reported by
  some devices while not associated with any access point) as an unknown identity, so it can
  neither be stored as an allowlist entry nor match one later — closing a fail-open path in
  which every disconnected state would have counted as trusted.

### Testing
- Added deterministic tests for the toggle debounce/generation-token logic and endpoint
  discovery lifecycle, using hand-rolled fakes (issue #249).

## [1.4.6] - 2026-09-06

### Changed
- Internal refactor: extracted `KeepADB`'s toggle-state decision core (debounce, intent tokens,
  recovery pulses, user-disabled tracking) into a platform-independent `KeepADBToggleState`, and
  the notification/widget/service UI refresh fan-out into an explicit `KeepADBSurfaceRefresher`
  boundary, alongside the existing `Settings.Global`/scheduler boundaries. State transitions are
  now unit-testable without any Android framework dependency. No user-visible behavior change
  (issue #248).

## [1.4.5] - 2026-09-03

### Added
- Optional "Keep display on while KeepADB is in the foreground" setting (default off, issue #224).
  When enabled, the screen only stays on while the app is open; leaving the app or disabling the
  setting releases the screen-on flag again.
- The main screen shows a "Set up webhook" button instead of an empty panel when no webhook is
  configured or enabled; tapping it opens Settings scrolled and focused on the webhook section
  (issue #232).

### Changed
- The security advice banner on the main screen is readable again: dark amber surface with a drawn
  warning icon instead of white text and a yellow emoji on a yellow background (issue #228).
- The banner now carries the full security advice — it merges the former banner sentence and the
  separate "Security & Network Advice" section from Settings into one text, translated into all 19
  supported languages. It scrolls with the page instead of occupying the top of the screen
  permanently, and its dismiss button meets the 48 dp touch-target minimum.

### Removed
- The separate "Security & Network Advice" section in Settings; its content now lives in the
  main-screen banner. All other settings are unchanged, including the switch that shows or hides
  the banner.

### Documentation
- Renamed the canonical local project directory to `keepadb` and synchronized the local
  project registrations; the Android package and application identity remain unchanged.
- Backfilled the previously incomplete Fastlane "what's new" changelogs for all past versionCodes,
  and added a contract test requiring a changelog entry for the current versionCode (issue #229).
- `./bin/gradlew` now sets `JAVA_HOME` to JDK 17 before delegating to the real Gradle wrapper, so
  direct Gradle invocations no longer depend on the system default JDK (issue #230).
- `./bin/check-i18n` now allows an untranslated-looking value per language instead of per key. A
  loanword such as "Webhook" can stay verbatim in the 16 languages that keep it, while the same
  string copied into Hindi or Korean — which transliterate it — is still reported as a finding
  (issue #236).
- `./bin/check-i18n` now also parses `<plurals>` and `<string-array>` entries instead of only
  `<string>`, automatically skips `translatable="false"` reference keys, and reports orphaned
  `ALLOWLIST` entries that no longer suppress a finding (issue #237).
- Documented the detection limits of `./bin/check-i18n`: it proves the absence of an exact
  word-for-word copy, not that a translation is correct, complete, or in the right language. A
  partial translation with a leftover English fragment or text in the wrong target language both
  pass silently. A prototyped heuristic for wrong-language detection was evaluated and rejected as
  too noisy (issue #238).

### Fixed
- The advice banner toggle in Settings now updates MainActivity live; the banner's visibility is
  re-read on every resume instead of requiring an app restart (issue #226).
- The German Settings heading for the screen options section now reads "Bildschirm" instead of the
  untranslated English "Display" (issue #236).
- The security & network advice toggle panel in Settings is missing its panel background and now
  has a visible border like every other Settings panel (issue #240).

## [1.4.4] - 2026-09-02

### Changed
- The Settings feedback entry now opens the public `hohnepeople.de/keepadb/feedback` page instead
  of a prefilled GitHub issue; the app no longer links to `github.com/.../issues/new` from the
  normal user flow.
- Sharing or copying the editable, privacy-safe report draft remains available as a separate
  action so users without a GitHub account can still hand over a report.

### Fixed
- Keep-Alive now restarts after a KeepADB app update and can restore Wireless Debugging without
  requiring the user to open the app.

### Documentation
- Release- und Signierungshinweise trennen den veröffentlichten Stand `v1.4.3` vom aktuellen,
  noch unveröffentlichten Entwicklungsstand `1.4.4`; historische Nachweise bleiben unverändert.

## [1.4.3] - 2026-08-30

### Added
- Optional GitHub issue reporting from Settings with a prefilled, editable issue template and placeholders for additional user notes.
- An explicit opt-in for including redacted diagnostic data; the complete draft is shown to the user before the external GitHub page is opened.
- Localized issue-reporting texts for all 19 supported languages without hardcoded visible literals.

### Changed
- The report dialog now keeps the editable draft in a compact, scrollable preview with visible actions and readable section breaks.
- Opening GitHub remains an explicit user action; no issue or diagnostic data is uploaded automatically by the app.

## [1.4.2] - Unreleased

### Added
- Resource contract coverage for locale key parity, format arguments, and visible UI literals.

## [1.4.1] - Unreleased

### Added
- Distinct app, widget, and Quick Settings states for off, missing permission, disconnected, and connected conditions.
- Fresh-process endpoint discovery from the normal Quick Settings Tile without Keep-Alive.
- Version and version code display in Settings, read from installed package metadata.

### Fixed
- Activity, widget, and Tile now refresh after asynchronous endpoint discovery.
- Tile-owned discovery is cancelled or invalidated at the end of the Tile lifecycle, preventing stale notification, register, and recovery side effects.
- Keep-Alive survives reboot, Wi-Fi AP changes, and temporary connection drops more reliably.

### Changed
- Settings sections now follow the product order from language and security through version information.

### Versioning note
- Each implemented issue increments the next patch or minor version. The intermediate bumps below are recorded retrospectively; they were not published as separate tags or releases.

## Retrospective issue version history

- `1.2.1` — #192: Keep-Alive remains active across reboot, Wi-Fi AP changes, and temporary connection drops.
- `1.3.0` — #193: App, widget, and Quick Settings Tile distinguish all operational states.
- `1.3.1` — #196: Fresh-process Tile discovery starts without Keep-Alive.
- `1.3.2` — #197: All UI surfaces refresh after asynchronous endpoint discovery.
- `1.3.3` — #198: Tile discovery uses the normal Tile lifecycle in fresh processes.
- `1.3.4` — #200: Tile-owned discovery cannot publish stale effects after lifecycle end.
- `1.4.0` — #203: Settings displays the installed app version and version code.
- `1.4.1` — #204: Settings sections follow the defined product order.
- `1.4.2` — #205: Localization resource and hard-coded literal audit contracts.
- `1.4.3` — #213: Optional, localized GitHub issue reporting with an editable preview and redacted opt-in diagnostics.

## [1.2.0] - 2026-08-29

### Added
- Optional USB-ADB notification with named, editable host profiles.
- Manual or automatic USB-to-WLAN-ADB handover, disabled by default.
- Structured, redacted recovery diagnostics that can be exported from Settings.
- Battery-optimization guidance when Android may restrict Keep-Alive.
- Direct notification action for turning off WLAN-ADB.

### Changed
- USB host-profile registration now works alongside WLAN-ADB registration and survives process restarts.
- USB notifications can be shown independently of host-profile details.
- USB-to-WLAN handover now preserves a deliberate manual OFF state and handles reconnect edge cases more safely.
- Webhook diagnostics now redact URL paths, credentials, and query parameters consistently.
- Webhook documentation now explains the optional USB device and profile data precisely.

## [1.1.0] - 2026-08-23

### Added
- Optional suppression of the persistent status notification.
- In-app and README security guidance for using Wireless Debugging safely.
- Upstream Fastlane metadata for F-Droid and other compatible catalog tools.

### Changed
- Notification titles now communicate the Wifi-ADB state more clearly without duplicating endpoint details.
- Release APKs now use a stable upstream signing identity and an unsigned Gradle build prepared for reproducibility verification.

## [1.0.0] - 2026-08-21

### Added
- **Triple Interface**:
  - Clean, dedicated Activity UI with real-time status and keep-alive toggle.
  - Quick Settings Tile (`KeepADBTileService`) for instant 1-tap toggling directly from the notification shade.
  - Home Screen Widget (`KeepADBWidget`) with live status feedback.
- **First-Time Setup Assistance**:
  - In-app guided setup instructions displaying the exact `adb shell pm grant` command required for `WRITE_SECURE_SETTINGS`.
- **Keep-Alive Foreground Service**:
  - Persistent background watchdog (`KeepADBService`) that automatically restores Wireless Debugging on network drops, Wi-Fi reconnects, AP roaming, and device boot (`BootReceiver`).
- **High-Speed Endpoint & Port Discovery**:
  - Batched non-blocking NIO loopback scanner resolving active `adbd` ports in under 200 milliseconds.
  - mDNS Network Service Discovery (`NsdManager`) fallback.
  - Ongoing notification with quick status display and connection string (`Port <port> @ <ip>`).
  - Full compatibility with local VPNs and overlay networks (e.g. Tailscale).
- **Central Settings Screen**:
  - Dedicated settings screen for language selection and optional custom webhook sync configuration.
- **Multi-Language Support (19 Languages)**:
  - Full localization for English (default), German, Spanish, French, Portuguese, Italian, Dutch, Polish, Ukrainian, Russian, Turkish, Arabic, Hindi, Simplified Chinese, Traditional Chinese, Japanese, Korean, Indonesian, and Vietnamese.
  - In-app language picker and native Android 13+ Per-App Language Preferences (`locales_config.xml`).
  - Native Right-to-Left (RTL) layout support.
- **Adaptive Icon & CI Design System**:
  - Native adaptive icon (Terminal Prompt + Wi-Fi Broadcast) with Android 13+ Material You monochrome support.
  - Cohesive Dark/Red/Yellow design system with native system typography and distinct touch-feedback states.
- **Open Source & Release Infrastructure**:
  - GNU Affero General Public License v3.0, or (at your option) any later version (`LICENSE`).
  - Automated GitHub Actions CI workflow for build validation and lint checks.
  - Automated GitHub Actions release workflow for publishing APK artifacts on version tags (`v*`).
- **Privacy & Security**:
  - 100% native AOSP framework, zero 3rd-party runtime dependencies, zero analytics or telemetry (< 350 KB APK).
  - Optional custom register/webhook sync endpoint (disabled by default).
