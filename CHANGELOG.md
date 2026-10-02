# Changelog

All notable changes to **KeepADB** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## How to read this changelog

`CHANGELOG.md` is the technical maintainer history: issue references and implementation details
remain useful here when they explain behavior, risk, or traceability. User-facing release notes
are kept separately in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`; those
entries lead with the user benefit and omit implementation-only details. `Added`, `Changed`,
`Deprecated`, `Removed`, `Fixed`, and `Security` are the primary categories. `Documentation`,
`Testing`, and retrospective notes are intentional secondary categories when they explain the
project history rather than a product change.

## Release status

`v1.8.38` is the latest public release before the unreleased `1.9.1`, `1.9.2`, `1.9.3`, `1.9.4`, `1.9.5`, `1.9.6`, `1.9.7`, `1.9.8`, `1.9.9`, `1.9.10`, `1.9.11`, `1.9.12`, `1.9.13`, `1.9.14`, `1.9.15`, `1.9.16`, `1.9.17`, `1.9.18`, `1.9.19`, `1.9.20` and `1.9.21` candidates below (`1.9.1` was never published on its own). `v1.4.5` was the
latest public release before `v1.8.38` was published. Sections from `1.4.6` through `1.7.3`
record development snapshots; their dates describe implementation history, not publication proof.
A version is released only when a corresponding tag or public release exists. `1.4.1` and `1.4.2`
are retrospective issue-version records and were never published as separate releases.

## [1.9.21] - Unreleased

### Added
- #721: While "Observe access points" is on, the last Wi-Fi band seen for a BSSID is stored with the observation history, so an access point that is out of the scan cache keeps showing where it was last seen, e.g. `AA:BB:CC:DD:EE:01 (5 GHz)`. Storage is an additive field of `KeepADBBssidHistory` in the same `keepadb_prefs` (`bssid_history_<id>_bands`, `BSSID=band` pairs): only the latest band per BSSID, overwritten by a newer reading, no band history and no timestamp. The bounds of the history carry over (8 BSSIDs per SSID, 50 SSIDs; an evicted BSSID or SSID takes its band along), and it disappears on uninstall like the rest. Data written by earlier versions has no such field and simply shows no stored band. The band is recorded from the connection's live reading together with the observation of the current access point (`KeepADBNetworkCard.refresh`); a reading without a usable frequency changes nothing. No new setting, receiver, permission or scan trigger.
- #721: Display priority in all three lists (allowed, observed, prevented): the live band (cached scan or current connection) wins, the stored band fills the gap, and with neither known nothing is shown (`KeepADBAccessPointBand.displayBand`).
- #725: The privacy-mode eye now sits in every red title bar, not only on the main view: Settings and the three network list views (allowed, prevented, observed access points). The button is one shared layout (`view_privacy_toggle.xml`, included by each bar) with one behavior class (`KeepADBPrivacyToggle`: flips the single `KeepADBPreferences` privacy setting, updates icon and content description, redraws the page, then refreshes the endpoint coordinator and the tile). Same icon, same effect, same state everywhere; there is no second setting. The page redraws at once on tap (main view as before, Settings through `refresh()`, lists through `render()`), and the icon is re-synced on resume so a change made on another page shows when returning.
- #725: Test `PrivacyToggleTitleBarsTest` (presence per page, a guard that every layout with a red `header_bar` includes the toggle, shared state between pages in both directions, immediate masking and unmasking in the list view and in Settings).

### Changed
- #722: Row text of the allowed, observed and prevented access point lists is the same in every language: line 1 is the network name, line 2 the BSSID with its band in brackets (unchanged). The stable entry number no longer leads the title as `#<id> · `; it follows the name in brackets (`Office (2)`) and only where the same network name is carried by several known access points (`meshCount > 1`, the same rule as the mesh label). A name carried by one access point has no number; rows that are no stored entry have none either. With the privacy mode on the title is `Name hidden #n` (n per network name, unchanged) with the entry number behind it (`Name hidden #1 (2)`), so a row has exactly one `#`. Origin of the old prefix: it was hard-coded in `NetworkListActivity.buildAccessPointRow` (`"#" + entry.id + " · " + title`, #714), not in `KeepADBNetworkDisplay`; the new bracket format is composed by `KeepADBNetworkDisplay.withApNumber`. #722 itself changed no string resources; the privacy mode, trust decisions and the BSSID/band line are untouched. The "Current" badge in front of the current row (`Current · title`) is unchanged.
- #721: With no known band the BSSID line now shows the bare address: no brackets and no placeholder text. The string `network_band_unknown` ("Band unknown") is removed from all 19 locales; `network_bssid_with_band` stays for the known-band case.
- #721: `network_observation_hint` (the explanation under "Observe access points") now mentions the band in all 19 locales.
- #725: Title of the allowed and observed access point lists shortened to make room for the eye: "Allowed APs" / "Observed APs" with a fitting abbreviation in every language (own string `network_view_allowed_title`; the Settings row `network_row_allowed` keeps its full wording). What the privacy mode hides is unchanged; no change to trust.
- `MainActivity` no longer carries its own copy of the toggle logic; it uses `KeepADBPrivacyToggle` and only adds its page-specific re-render.

### Removed
- #721: The `network_band_unknown` string in every locale and the "(Band unknown)" suffix.

### Security
- #721 (privacy): Turning "Observe access points" off deletes every stored band (`KeepADBPreferences.setWifiApsFeatureEnabled(false)` calls `KeepADBBssidHistory.clearBands`), including orphaned band fields of SSIDs that are no longer listed. Turning it on again does not bring them back, and while the option is off the stored bands are not read either, so only the live band shows. Note: the BSSID history itself is retained when the option is turned off (#654, pinned by `turningObservationOffStopsSettingsRefreshAndKeepsHistory`); only the bands are deleted. The band stays display only and never enters a trust decision; the privacy mode still masks the address only, not the band.

### Testing
- #722: `NetworkListActivityTest` gains two tests (normal and privacy mode, several access points of one name plus a single one: number behind the name, never in front, no ` · `, at most one `#`) and 17 existing assertions were adjusted to the new row text. Without the production change the two new tests are red.
- #721: `KeepADBBssidHistoryTest` (+9: store, overwrite, unknown keeps the earlier band, no band without SSID/BSSID, 8-per-SSID and 50-SSID eviction taking bands along, clearing incl. orphans while the history stays, data without a bands field, malformed stored values), `KeepADBAccessPointBandTest` (display priority, garbage values, stored bands read only with the option on, no `network_band_unknown` in any locale, contract list of band users extended by the history and the Network card), `KeepADBPreferencesTest` (+1: off deletes the bands and keeps the history, on deletes nothing), `SettingsNetworkCardTest` (+3: recorded and overwritten with the observation, nothing stored with the option off, switching off in Settings deletes), `NetworkListActivityTest` (+6, 10 assertions adjusted to the bare address: no brackets or placeholder, stored band shown, live beats stored, allowed and prevented view, privacy mask, off shows only live and the setter deletes), `KeepADBNetworkDisplayTest` (adjusted). Thirteen production mutations in a disposable copy (live/stored swapped, setter not clearing, clearing on enable instead of disable, card storing without the option, stored bands read with the option off, unknown band overwriting, no pruning on BSSID and on SSID eviction, empty brackets for an unknown band, a known BSSID ignoring a new band, clearing that misses orphans, clearing that also removes the history, stored bands only in one view) each turn at least one test red. Not checked on a device.

### Documentation
- #721: `docs/trusted-networks.md` describes the stored band, its limits, its deletion and that nothing is shown without a band.

## [1.9.20] - Unreleased

### Testing
- #720: `KeepADBTrustedNetworkNamingTest.theNameLimitIsFortyCharacters` pins the optional AP-name limit of 40 as a literal (the existing tests only measured with `MAX_CUSTOM_NAME_LENGTH`). Proof by mutation in a throwaway copy: `MAX_CUSTOM_NAME_LENGTH` 40 to 41 turns the test red, restored it is green. No change to rename behavior or trust logic.
- #724: `KeepADBRegisterCleanupLifecycleTest.aFailedReplacementPostDoesNotEvictFromAFullBacklogForAnUnqueuedCleanup` closes the remaining gap M3 of #718. With a full backlog of four foreign entries, a failed cleanup of the old URL (not queued) and a failed replacement POST, the pending order and all four spent retry records stay untouched. No callback fixture is needed: the state is the plain transaction state (old URL as local report, full backlog). Proof by mutation in a throwaway copy: `addPendingWebhookCleanupUrl(cleanupToRemember)` in the failure branch evicts the oldest entry and the test turns red (it was green for M3 before, in the #718 test). Test-only; no production change.

### Removed
- #720: Unused string resource `wifi_aps_mesh_label` removed from all `values*/strings.xml` and from the resource contract test. It was no longer referenced by any code or layout. No user-visible change.

## [1.9.19] - Unreleased

### Testing
- #718: `KeepADBRegisterCleanupLifecycleTest.aFailedReplacementPostLeavesTheQueuedCleanupAndItsSpentRetryRecordAlone` guards the POST-failure branch of the client commit path (follow-up to #711). When the replacement POST fails, an already queued cleanup keeps its FIFO position and its spent retry record (`attempts`, `nextAttemptAt`, `expiresAt`); nothing is removed, reset or queued again. The state is built with the same request-callback fixture as #711. Proof by mutation in a throwaway copy: removing the record of `cleanupToRemember` in the failure branch, and re-queueing plus removing it, turn the test red. Test-only; no production change, no change to FIFO, retry policy or persistence format.

## [1.9.18] - Unreleased

### Added
- #714: The list of allowed access points (Settings, Network card, "Allowed access points") tells several access points of one network apart. Each stored entry shows its stable entry number (`#1`, `#2`, ...), which is the existing entry id: it is handed out once, never reused and does not change when other entries are added or removed (a gap stays a gap). Entries that already exist keep the number they already have. Rows that are no stored entry (observed or prevented access points that are not allowed) have no number.
- #714: A small pencil directly next to the name opens a simple popup (text field, OK, Cancel, and Reset once a name exists) in which the user gives that entry a name of their own (up to 40 characters, one line). The name is stored locally under `trusted_network_<id>_name` in `keepadb_prefs`, replaces the network name as the row title, and the unchanged SSID stays visible in the line below. An empty field with OK resets like Reset; Cancel and dismissing change nothing; removing the entry removes its name. With the privacy mode on, the name is hidden like the network name and the pencil is not offered (the popup would otherwise show the name, and a blank field would read as a reset).
- #714: The BSSID line of every row in the three views ends with the Wi-Fi band in brackets, `(2.4 GHz)`, `(5 GHz)`, `(6 GHz)` or `(Band unknown)`. The band belongs to the BSSID: two BSSIDs of one network are never merged. It comes from data Android already holds and is read once per drawing: the frequency of the current connection and the system's cached scan results (`WifiManager.getScanResults()`). KeepADB never calls `startScan`, registers no scan receiver and schedules nothing; Android hands out scan results only with precise location access and location services on, and a refused or empty read simply shows "Band unknown" until a later drawing finds data. No permission was added, and the band is not stored.
- #714: Ten new strings (naming popup, band labels, `"%1$s (%2$s)"` pattern) in all 19 locales. `bin/check-i18n` allowlists the GHz labels and the pure format string per language where they are identical to the English reference by nature (the allowlist is per language, not key-wide).

### Changed
- #714: The row text of the allowed, observed and prevented views changed: a stored entry's title starts with `#<id> · `, and the BSSID is followed by its band. An unnamed entry without a readable network name now shows `BSSID (band)` as its title. Allow, remove and mesh actions, their descriptions, the privacy masking and every trust decision are unchanged: the BSSID stays the technical identity and the only trust key, number, name and band are display only (`KeepADBTrustedNetworkContractTest` pins that no deciding method reads the name and that only the display classes use the band).

### Testing
- #714: `KeepADBTrustedNetworkNamingTest` (new) covers the stable number (consecutive, no renumbering on removal, no reuse after removal or after removing the last entry, an already listed BSSID keeps number and name), the name (save, change, reset by null or blank, unknown entry, normalization, removal with the entry, surrogate-safe cut) and that a name never changes a trust decision in either direction. `KeepADBAccessPointBandTest` (new) covers the frequency-to-band mapping including its boundaries, one band per BSSID without grouping, the live connection winning over the cache, refused reads of either source, and, as a source contract, that no production code calls `startScan` or listens for scan results and that only the display classes reference the band class. `NetworkListActivityTest` (+21, 7 adjusted to the new row text) covers numbers staying put on removal and addition, the pencil next to the name (48 dp, no labelled button), the popup (OK, Cancel, Reset, empty field, dismissal, destruction), the SSID staying visible, privacy on both sides, and the band per BSSID in all three views including a later drawing picking up data. `KeepADBNetworkDisplayTest` (+3) pins the band suffix and the hidden own name, `KeepADBTrustedNetworkContractTest` (+1) that no deciding method reads the name, and `KeepADBResourceContractTest` gets format-argument witnesses for the new strings.
- #714: The new assertions were checked against ten targeted production mutations in a disposable copy (entry number derived from the list size, name left behind after removal, a band boundary moved, a name granting trust, a list position instead of the entry id, band lookup ignoring the BSSID, Cancel saving, a `startScan` call, the pencil offered in privacy mode, the own name not hidden in privacy mode); each turned red and the unmodified copy is green. Not checked on a device or emulator: the layout of the pencil row, the popup's look, keyboard behavior, TalkBack reading and what Android's scan cache really holds on a phone are covered only by Robolectric view-tree tests.

### Candidate history
- 1.9.18 / versionCode 190 follows 1.9.17 / 189 (#710/#711, merged as PR #717): this branch was merged with `master` after that merge, and the version and changelog entries were reconciled in that merge.

## [1.9.17] - Unreleased

### Fixed
- #710: `KeepADBRegisterClient.flushPendingCleanups` now skips a snapshot entry that is no longer in the current pending FIFO. When two spellings of the same legacy URL (e.g. different userinfo) were queued, the first successful DELETE already removed both entries, but the loop still processed the stale second one: a redundant DELETE and, if that failed, an orphan retry record until the next flush's orphan sweep. Retry keys, FIFO cap, retry policy, locks and the orphan sweep are unchanged.

### Testing
- #710: Two behavior tests in `KeepADBRegisterCleanupLifecycleTest`: the stale second spelling causes no second DELETE and leaves no retry record; later entries of other resources are still flushed (and a failing one keeps its entry and record).
- #711: A behavior test through the client commit path guards that queueing a pending cleanup again, without evicting a FIFO entry, keeps its spent retry record (`attempts`, `nextAttemptAt`, `expiresAt`). The commit block of `KeepADBRegisterClient` previously had this guarded only by a source-shape pin (review #707, O2). Test-only; no production change. The test builds its state through a request-callback fixture (the old URL becomes the live registration and is queued with a spent record during the flush), because an ordinary transaction cannot reach it: the flush in front of the commit block drops a pending entry whose URL is the live registration (`hasLiveRegistrationAtUrl`). The guard is therefore defensive, not observable product behavior.

### Candidate history
- #710 and #711 independently bumped separate branches from 1.9.16 / versionCode 188 to 1.9.17 / 189. Both bumps were merged into one candidate (Stage A integration), so the entries are consolidated here under a single 1.9.17 / versionCode 189 and one `189.txt`. No further bump was needed and no 1.9.17 was published.

## [1.9.16] - Unreleased

### Testing
- #706: The network-address determinism tests use handles that expose an unsorted map iteration and verify both callback delivery orders. The default-callback contract check strips comments before matching the executable registration call.

## [1.9.15] - Unreleased

### Documentation
- #703: Document the ownership and lifecycle delegation of the network-card and diagnostics controllers in the architecture index.

### Candidate history
- #702 and #704 independently bumped separate branches from 1.9.13 / versionCode 185 to 1.9.14 / 186. #702's bump reached master before #704 was integrated, so the duplicated bump was consolidated: the full #702 and #704 entries remain under the superseded candidate below, while the combined #702/#704/#703 candidate uses 1.9.15 / versionCode 187. No 1.9.14 release was published.

## [1.9.14] - Superseded, unreleased

### Testing
- #702: The background-location runtime-request contract scans every Java source under `app/src/main`, so a request added in a newly extracted source file is detected.

### Fixed
- #704: Repeated details-off trust prompts no longer stack a second confirmation. Mesh and issue dialogs clear their references on dismissal only while they are still the active dialog. Trust still requires the explicit allow action and preserves the existing locked-device gate.

### Testing
- #704: Regression coverage checks duplicate trust prompts, dismissed mesh references in both callers, and stale mesh/issue dismissals that must leave a newer reference intact.

## [1.9.13] - Unreleased

### Fixed
- #707: The persisted retry record of a pending register cleanup (`register_webhook_pending_cleanup_retry_state:<stored entry>`, #317/#701) no longer outlives its entry. A full four-entry backlog evicts its oldest entry for a newer one (#368) but left that entry's record behind, so the record leaked, and an entry queued again later inherited the old record (its used attempts and its original expiry) instead of starting with three attempts and a fresh 24 h expiry. Two parts, decided by the user on 2026-10-01 (A + C):
  - **A, at eviction:** `KeepADBPreferences.addPendingWebhookCleanupUrl` now returns the entry it evicted (`null` when nothing was evicted: blank url, entry already queued, free slot). `KeepADBRegisterClient` removes that entry's record with `PendingCleanupRetryRepository.remove` in the same commit block, right behind the write that evicted it and before the obsolete cleanups of the new URL are dropped and before the report snapshot moves on, inside the existing generation guard (a superseded update neither queues its cleanup nor evicts anything). The raw stored entry is the key, as before, so a legacy entry with credentials loses its record under its raw spelling.
  - **C, orphan sweep:** `PendingCleanupRetryRepository.removeOrphans` removes every stored record key (prefix `register_webhook_pending_cleanup_retry_state:`, colon included) whose entry is not in the current backlog, by exact string comparison and without judging the value, so records that earlier builds left behind (and a record left by a crash between removing an entry and its record) are cleaned up too. The records of entries that are still pending are never touched, a malformed one included, and neither are the stored entries, the FIFO keys or any other preference. The client calls it first in `flushPendingCleanups`, handing it the backlog read through `KeepADBPreferences` (which also migrates a legacy set without `_order`): on the register executor, outside the class monitor, without a request. It writes once and only when something is removed. A `null` entry list removes nothing.
  - Unchanged: the record key and format, the FIFO keys, the cap of four and its `_order` key, the legacy `StringSet` migration, the budget of three attempts, the 30 s to 5 min backoff, the 24 h expiry, the #562 `markUnavailableAsync` gate, the HTTP adapter and the generation guards. `PendingCleanupRetryRepository` still holds only constants and static methods (no queue, thread, timer, cache, lock or generation) and `KeepADBPreferences` still does not know the record key. Deriving the record key from the canonical resource or a hash (privacy, migration) is not part of this patch.
  - With the sweep in place the client path is covered twice: a record that an eviction left behind would also be removed by the flush of the transaction that queues the entry again. The removal at eviction closes the leak at once instead of at the next flush and does not depend on that flush.
  - Changed method bodies (comments and whitespace ignored, token comparison against `c4f6e7e5`): `addPendingWebhookCleanupUrl` and `addPendingCleanup` differ only by the `String` return (`return null` on the three early exits, `evicted` captured from `oldest.next()` and returned); `KeepADBRegisterClient` gained exactly the sweep statement in `flushPendingCleanups` and the evicted-record removal in the commit block; the existing members of `PendingCleanupRetryRepository` are token-identical, `removeOrphans` and its private helper `isActive` are the only additions.

### Testing
- #707: `PendingCleanupRetryRepositoryTest` (+9) pins the sweep with literal keys: dead records go and records of active entries stay (including their used attempts), only keys of the record are looked at (the FIFO keys, a key without the colon, a longer lookalike and a prefix in the middle stay), a dead entry's record goes whatever its value or type, the record of an active entry stays even when it is malformed, an entry is active only by exact string equality (case, trailing slash, default port, userinfo, blanks and token case are other entries), an entry containing the separator is matched exactly, an empty list means every record is dead, a `null` list or context removes nothing, and a second sweep removes nothing.
- #707: `KeepADBRegisterCleanupLifecycleTest` (+14, one assertion added to the legacy-migration test) drives the client: the evicted entry's record goes and only that one (and nothing else is sent), an evicted legacy entry with credentials loses its record under its raw key, an entry queued again right after its eviction starts with a fresh record, the same through a later transaction, the generation guard around the eviction, the flush sweep (dead records go, active ones stay, no request, no other preference changed, a legacy backlog without `_order` keeps its records, an empty backlog leaves no record), the sweep in both transactions, that only the register executor sweeps (the caller of a queued update has not swept), and that the sweep does not wait for the class monitor (the test holds it while the executor sweeps). `KeepADBRegisterClientTest` (+1) checks the commit-block guard: an update superseded during its secondary transports neither queues its cleanup nor evicts the oldest entry and its record. The existing single-apply tests also guard that a flush with nothing to sweep writes nothing.
- #707: `PendingCleanupRetryRepositoryContractTest` (+2, two extended) pins what a behavior test cannot see: the commit block removes the evicted entry's record right behind the eviction and before the obsolete cleanups, the in-memory state and the snapshot; the sweep has exactly one call site, first in the flush, no call inside a synchronized block or method, the flush is called only by the two transactions (before they take the monitor) and the transactions only inside register executor tasks; only the repository spells the record key and `KeepADBPreferences` knows neither the key nor the repository. The pin counting record removals in the client goes from two to three because of the removal at eviction; no assertion was removed or weakened and the four generation increments are unchanged.
- #707: Each new assertion was checked against 36 targeted production mutations in a disposable copy (eviction not reported, newest reported, record not removed, newest's or every record removed, removal under the canonical key, generation guard removed, removal moved behind the snapshot, `KeepADBPreferences` knowing the key or using the repository; sweep removed, handed an empty list, skipped for an empty backlog, fed from the `_order` key only, moved into the commit block, run on the caller thread (additionally and instead), wrapped in the monitor, sending a request, run after the loop; active always false or true, bare FIFO key or key without the colon as prefix, comparison ignoring case, trailing slashes or blanks, prefix matches in both directions, malformed records of active entries removed, well-formed dead records kept, `null` list read as empty, a static field in the repository, an unconditional write, stopping after the first record, a clock in the sweep). All 36 are caught. Four are caught only by structure pins, where the property is not observable in a unit test: the removal behind the snapshot, `KeepADBPreferences` knowing the key or the repository, and the sweep after the loop. The client path "queued again by a later transaction" stays green when only one of the removal at eviction and the sweep is missing (see above); each of them is pinned on its own by the tests around it.
- #707 (independent review): `PendingCleanupRetryRepositoryTest` (+1) pins that the sweep's log line names neither an entry nor a record key (the key of a legacy record holds its userinfo and token). A sweep log line that leaked the stored key was green before this test and is red with it.
- #707 (independent review): `KeepADBRegisterCleanupLifecycleTest` (+1) pins that the sweep also runs on a full backlog (four entries), which is where an eviction leaves its records. A sweep that was skipped on a full backlog was green before this test and is red with it.

No visible change in the app: a small bug fix in the register webhook sync. When the app gives up on an old webhook address that could not be reached, it no longer keeps that address's retry notes around, and notes that earlier versions left behind are cleaned up. If such an address is queued for cleanup again later, it starts with a fresh retry budget. When and how often the app retries, and your settings, stay exactly the same.

## [1.9.12] - Unreleased

### Changed
- #700: Internal refactor, no behavior change: the default HTTP adapter `DefaultHttpTransport`, a private nested class of `KeepADBRegisterClient`, is now the package-private `KeepADBHttpTransport` in its own file. It still implements the existing `KeepADBRegisterClient.HttpTransport` seam (`postJson`/`delete`), so `setHttpTransport`/`resetHttpTransport`, `KeepADBFakeHttpTransport` and the RegisterClient, payload and multi-transport wiring tests work unchanged. The method bodies are token-identical to the previous ones (comments and whitespace ignored; the only difference is that the two `sanitizeUrl` calls are qualified as `KeepADBRegisterClient.sanitizeUrl`). The adapter now bundles the HTTP configuration and the connection release in one place: only a 2xx response is success for POST and DELETE, redirects stay disabled, connect and read timeout stay 2000 ms (`TIMEOUT_MS` moved with it), the POST body stays UTF-8 with fixed-length streaming, and `disconnect()` stays in a `finally` block on every path. The log lines are unchanged and still use the redacted URL (`sanitizeUrl`) or the masked endpoint label, under the same `KeepADBRegisterClient` log tag. The adapter is stateless: no executor, queue, timer, retry, operation generation or preferences access. `KeepADBRegisterClient` stays the single owner of the executor, `currentOpGeneration`, the pending-cleanup queue and retry decision, the commit order, the report snapshot and the listener, and still decides what a transport result means. It no longer imports `java.io`/`java.net` HTTP types.
- Testing (#700): `KeepADBHttpTransportTest` exercises the real adapter against a loopback socket server (2xx-only success, no redirect for POST and DELETE even to a successful target, 2000 ms timeout, fixed-length UTF-8 body and headers, socket release, redacted and masked logs); `KeepADBHttpTransportContractTest` guards the adapter source (no state or scheduling, the HTTP configuration and `finally`-disconnect in both methods, every log statement redacted) and the client's lack of HTTP configuration of its own. The existing source contract `PendingCleanupRetryRepositoryContractTest` now expects the adapter, not the client, as the only class that opens an `HttpURLConnection`; the executor, generation and repository-ownership guards stay on the client.

No change to the app itself: this patch only moves the code that sends the register requests into its own file, so future work on it is easier to review. Which requests are sent, when, how long the app waits and what is logged stays exactly the same, and your settings stay as they are.

## [1.9.11] - Unreleased

### Changed
- #701: Internal refactor, no behavior change: the persisted retry record of the pending register cleanups (#317) now lives in the package-private `PendingCleanupRetryRepository` -- the record key `register_webhook_pending_cleanup_retry_state:<stored entry>` with its `attempts,nextAttemptAt,expiresAt` format, reading with the fresh-record fallback for a missing or malformed value, writing, removing, the backoff table (30 s doubling up to 5 min), the 24 h expiry and the budget of three attempts. The moved member bodies are token-identical to the previous ones (comments and whitespace ignored; only names changed and three methods lost `private`). The repository holds only constants and static methods -- no queue, thread, timer, registration cache, lock or operation generation -- and reads no clock: every time in a record is an absolute wall-clock millisecond value handed in by the client, so a persisted backoff or expiry stays meaningful after a reboot. The in-memory #562 retry gate of `markUnavailableAsync` stays a separate circle on the monotonic clock. `KeepADBRegisterClient` stays the single owner of the flush, the DELETE, the commit monitor, the executor and `currentOpGeneration`; it still decides when an entry is attempted, discarded or dropped (`shouldAttemptPendingCleanup` and `recordPendingCleanupFailure` keep their order and call the repository's `isExpired`/`isExhausted`/`isDue` and `recordFailure`), persists the cleanup to remember before the report snapshot moves on, drops the obsolete cleanups of a freshly registered resource, keeps HTTP outside the commit monitor and checks the generation after the primary POST before any secondary transport. The four-entry FIFO with its `_order` key and the legacy `StringSet` migration (`KeepADBPreferences`), the resource canonicalization and the exact removal of the raw stored entry are untouched. No preference key, persistence format or request changes, so users see no difference. The constants moved with the record: `MAX_PENDING_CLEANUP_ATTEMPTS`, `PENDING_CLEANUP_EXPIRY_MS`, `PENDING_CLEANUP_INITIAL_BACKOFF_MS` and `PENDING_CLEANUP_MAX_BACKOFF_MS` are now `MAX_ATTEMPTS`, `EXPIRY_MS`, `INITIAL_BACKOFF_MS` and `MAX_BACKOFF_MS` of the repository.

### Testing
- #701: New `PendingCleanupRetryRepositoryTest` (12 tests) pins, with literal numbers, the budget of three attempts, the 24 h expiry, the 30 s start and 5 min ceiling of the backoff table, the record key and its stored format after each failure, that the failure which uses up the budget stores nothing, that a record belongs to the raw stored entry and never to its canonical resource, that removing a record leaves the stored entries alone, the fresh-record fallback for missing and malformed values, the clamp of a negative count, saturation near `Long.MAX_VALUE` and the boundaries of expiry, exhaustion and due time.
- #701: New `PendingCleanupRetryRepositoryContractTest` (7 tests) pins what a behavior test cannot see: the repository declares only constants and static methods, imports nothing but the preferences store and the logger and contains no lock, thread, handler, timer, collection, generation, clock or HTTP; `KeepADBRegisterClient` is its only caller and the only owner of `currentOpGeneration` (declared once, advanced in exactly four places), the register executor and `HttpURLConnection`; the client holds no copy of the record and keeps the wall clock for the persisted record and the monotonic clock for the #562 gate; no synchronized block or method reaches the transport; and the update transaction checks its generation after the primary POST before any secondary transport and persists the cleanup to remember before the obsolete cleanups are dropped and before the report snapshot moves on.
- #701: `KeepADBRegisterCleanupLifecycleTest` gained 12 tests driven through the client with literal numbers (the older tests use the repository constants): three attempts with the 30 s and 60 s backoff and then the drop, the record and the budget surviving two process restarts, the expiry boundary measured from the first failure, absolute wall-clock times (no clock seam) that still gate after a restart, a spent stored budget dropped without a request, the FIFO of four entries on its `register_webhook_pending_cleanup` and `_order` keys, a legacy credential entry keeping its record across restarts and being dropped with it, a delivered retry removing the raw entry and its record, a new registration dropping every spelling of its own resource (case, default port, trailing slash) and only those, a flush never deleting a live resource, a successful DELETE dropping the obsolete cleanups of that resource, and the cleanup to remember being in the preferences file before the report snapshot is written (the fake sees each editor transaction just before it is applied).
- #701: `KeepADBRegisterClientTest` gained 11 tests: operations that were superseded while queued (an update, a disconnect and an unregister) send nothing at all, a superseded migration never posts to its own target after its old-URL DELETE, a superseded update does not commit during its secondary transports, a superseded failed update and a superseded failed or successful DELETE leave status, retry gate and the newer registration alone, no request of any kind runs while the class monitor is held (asserted inside the transport for the flush of every transaction, both DELETEs, both POSTs and a secondary transport), the drop of a legacy credential entry is logged without its credentials, and the #562 gate runs on the monotonic clock without a seam.
- #701: The constant references in `KeepADBRegisterCleanupLifecycleTest` now point at the repository; no assertion was removed or weakened, and the directory scans of the other contract tests pick up the new file.
- #701: Each new assertion was checked against 84 targeted production mutations in a disposable copy (record key and format, FIFO keys, cap and eviction, legacy migration, attempt budget, TTL, backoff table and boundaries, resource canonicalization, raw-entry and record removal, legacy credentials in requests and logs, the two clocks, write-ahead order, obsolete cleanups, every generation check and commit guard, HTTP inside the monitor and a lock, cache, thread, handler, second generation or second owner in or around the repository). 83 are caught; nine of them (the order of the in-memory move against the write-ahead and the shape of the repository) only by the structure pins, which is where those properties are observable. The one survivor keeps the case of the scheme in the resource key and is equivalent: `sanitizeWebhookUrl` already lowercases the scheme on both of its paths. Besides that equivalent one, ten mutations survived at first -- the generation checks at the start of the three executor tasks and between the old-URL DELETE and the POST, the generation guards of the four commit blocks, the DELETE confirmation guard of the delete transaction and the removal of obsolete cleanups after a successful DELETE -- and got their own tests; the monotonic clock of the #562 gate and the redacted drop log were first pinned only by structure or not at all and now have behavior tests.

## [1.9.10] - Unreleased

### Changed
- #699: Internal refactor, no behavior change: the stateless part of the active Wi-Fi endpoint binding now lives in the package-private `WifiAddressPolicy` -- the accept/reject decision `matchesActiveWifiAddress` with its `ScopeFallbackSink` (loopback, wildcard and multicast candidates, candidates of foreign bytes and byte-identical link-local candidates on a known different scope are rejected; a link-local match with an unresolved scope on either side stays accepted and is reported through the sink) and the byte-match comparison `resolveScopeInterfaceByByteMatch` with its `ScopeCandidate` snapshot. The policy holds no field, cache, lock or listener, performs no I/O and has no Android dependency; the moved method bodies are token-identical to the previous ones. `KeepADBNetwork` stays the single owner of the Wi-Fi, default-route and VPN callbacks and their maps, of the observed/registered flags and the synchronous fallback permission (`isWifiTrackingAuthoritative()`), of the `NetworkInterface` enumeration and lookup (`resolveScopeInterface`, `stampLinkLocalScope` and the private snapshot adapter `scopeCandidateOf`, formerly `ScopeCandidate.of`) and of the `scope_fallback` diagnostics sink it hands to the policy. No preference key, persistence format, diagnostics event or endpoint decision changes, so users see no difference.

### Testing
- #699: New `KeepADBWifiAddressPolicyWiringTest` (9 tests) drives the decision through the real entry point `KeepADBNetwork.isActiveWifiAddress` with a live tracker fed by shadowed callbacks: only tracked addresses are accepted and foreign bytes rejected, tracked loopback and wildcard addresses and a multicast or loopback address from the synchronous snapshot are rejected, an unknown, lost or different network never yields a positive decision, a known different link-local scope is rejected and the same scope accepted without an event, an unresolved scope on either side is accepted and reported exactly once as `scope_fallback` with both scopes, only a scope-blind link-local match is ever reported, and deciding adds no callback to the tracker's three.
- #699: New `WifiAddressPolicyContractTest` pins what a behavior test cannot see: the policy declares no field, only static methods and exactly the two moved nested types, imports nothing but the address and collection types it decides on, calls no method on a `NetworkInterface`, performs no I/O and knows no Android class; `KeepADBNetwork` is its only caller and holds no copy of the decision while it keeps the interface I/O. New `WifiAddressPolicyTest` (9 tests) covers multicast and the IPv6 wildcard in the active list, a disagreeing entry never hiding an agreeing or an unresolved one, at most one report per match, and the byte match for a non-matching and an ineligible-twin candidate. `KeepADBScopeFallbackVisibilityTest` gained two tests for the I/O adapter that stays in `KeepADBNetwork` (the loopback interface never stamps; a live interface is found by its own address).
- #699: `KeepADBEndpointAddressBindingTest` and `KeepADBScopeFallbackVisibilityTest` now call `WifiAddressPolicy` directly; the guards that read `KeepADBNetwork.java` (`KeepADBNetworkContractTest`) still find everything they pin in that file, and the directory scans pick up the new file. No assertion was removed or weakened.
- #699: Each new or moved assertion was checked against 73 targeted mutations in a disposable copy (loopback, wildcard or multicast accepted, foreign bytes, same-family or same-subnet addresses accepted, known different scope accepted, scope fallback unreported, reported twice or swapped, reported for resolved scopes, rejections or global IPv6, no sink or a silent sink on the live path, unknown network decided positive, byte match ignoring eligibility or bytes, state, cache, volatile flag, I/O, Android dependency, second listener or second caller in or around the policy, a copy of the decision in `KeepADBNetwork`, the fallback permission always or never granted, and the preserved neighbours). 72 are caught. The mutations of the interface scan that stays in `KeepADBNetwork` need a live interface: four are caught by environment-dependent tests (skipped on a machine without a loopback or an up, non-loopback interface), one only by the structure pin (a down interface counted as eligible). The one survivor is outside this refactor: removing the sort in `eligibleWifiEntriesInDeterministicOrder` does not fail `getWifiIpv4AddressIsDeterministicRegardlessOfDeliveryOrder`.

## [1.9.9] - Unreleased

### Changed
- #698: Internal refactor, no behavior change: the diagnostics export button and the feedback report dialog of `SettingsActivity` -- the two button bindings, the active dialog with its editable preview and the diagnostics opt-in checkbox, and their save/restore/destroy -- now live in the package-private `KeepADBDiagnosticsController`, following `KeepADBNetworkCard`, `KeepADBWebhookForm` and `KeepADBUsbProfileEditor`. `SettingsActivity` keeps the screen composition and the Android lifecycle and calls explicit `restore`/`saveState`/`destroy` hooks. `KeepADBIssueReporter` stays the body and section builder, `KeepADBDiagnostics.export` stays the redaction owner, and the reset-app dialog stays completely in the activity. The feedback page is still opened through the activity's own `openWebLink`, so a device without a browser keeps getting the toast. The Bundle keys (`settings_issue_report_showing`/`_draft`/`_diagnostics`), string resources and layout ids are unchanged, so users see no difference. As before, diagnostics enter the report only through the opt-in checkbox, and a restored dialog sets that checkbox before its listener is attached, so a restore neither rebuilds nor duplicates the diagnostics section.

### Testing
- #698: New `SettingsIssueReportDialogTest` (20 tests) drives the real dialog: nothing from the diagnostics store before the opt-in and exactly one section after it, withdrawal removes it and keeps the handwritten text, Share sends `text/plain` with the section only when checked and leaves dialog and draft standing, the feedback button dismisses and opens only the static page (also without a browser), a restore uses the saved draft verbatim and sets the box before the listener (no rebuilt or duplicated section, also for a stale section with an unchecked box), repeated rotations keep text, box and a single section, destroy closes the dialog, the Bundle keys and string resources are unchanged, and the export button shares `text/plain` and records its diagnostic event once per request. A diagnostics canary proves the "absent" assertions are not vacuous.
- #698: New `KeepADBDiagnosticsControllerContractTest` pins `KeepADBDiagnosticsController` as the single owner of the export and the dialog, the reset-app flow staying in the activity, and that no other main source reads diagnostics for sharing.
- #698: `KeepADBIssueReporterContractTest` and `KeepADBDiagnosticsTest` now read the controller for their wiring assertions, and the no-GitHub guards scan the activity and the controller, so the guards do not go blind by the code moving; the Bundle-key symbols in `SettingsActivityTest` moved with the code. No assertion was removed or weakened.
- #698: Each new assertion was checked against targeted production mutations in a disposable copy (45 mutations: diagnostics without opt-in, section kept after withdrawal, duplicate section, box set after the listener, lost draft or box state on rotation, dialog left open on destroy, Share/feedback behavior, export event/format, browser bypass, second owner or second diagnostics reader, Bundle key and resource changes). One survived at first (the listener's duplicate-section guard) and got its own test; all 45 are now caught.

## [1.9.8] - Unreleased

### Changed
- #697: Internal refactor, no behavior change: the Network card of `SettingsActivity` -- its views, the access point its action button was rendered for, the Wi-Fi status callback and the allowlist-permission, background-location, trust-confirmation and mesh dialogs -- now lives in the package-private `KeepADBNetworkCard`, following `KeepADBWebhookForm` and `KeepADBUsbProfileEditor`. `SettingsActivity` keeps the screen composition, the one-time intent consumption and the Android lifecycle and calls explicit `restore`/`start`/`refresh`/`stop`/`saveState`/`destroy`/`onRequestPermissionsResult` hooks. Preferences (including the Activity's `location_permission_requested` flag), Bundle keys, the request codes 3001/3002 and layout ids are unchanged, so users see no difference. The one theoretical difference: should several dialogs be restored at once after a rotation, the Network card's three are now re-shown together, before the reset-app, USB handover and language dialogs.

### Testing
- #697: New `SettingsNetworkCardLifecycleTest` pins the transitions that changed owner: stop removes only the card's own Wi-Fi callback and every restart registers exactly one, each of the three Wi-Fi callbacks (available, lost, capabilities changed) refreshes the card on its own, the action acts on the access point that was last rendered (neither a stale one nor the one the device roamed to), destroy closes the trust and background-location dialogs, a save before destroy keeps the trust binding and the restore ignores a roam, the mesh question is neither saved nor restored, a restored background rationale grants and requests nothing, rendering and restoring write no preference in 48 mode/grant/connection combinations, Wi-Fi-name matching stays off and the card reopens collapsed after a rotation, and the preference flag, Bundle keys and request codes stay unchanged.
- #697: `SettingsActivityTrustConfirmationTest` gained the restore cases (record gone, saved BSSID never recorded, dismissed dialog not saved); `SettingsBackgroundLocationDialogTest` gained approximate-only, interrupted and actual-permission-state cases and the check that no other request code enables the allowlist. New `KeepADBNetworkCardContractTest` pins `KeepADBNetworkCard` as the single owner of the card's state, dialogs and request codes and the main-looper handler of the Wi-Fi callback (the Robolectric shadow ignores the handler).
- #697: `KeepADBServiceManifestContractTest` now also scans `KeepADBNetworkCard.java` for a runtime `ACCESS_BACKGROUND_LOCATION` request (the `requestPermissions` calls moved there; the guard would otherwise have gone blind), the reflection call in `SettingsActivityTest` now opens the dialog through the card's button, and the request-code symbol moved with the code. No assertion was removed or weakened.
- #697: Each new assertion was checked against targeted production mutations in a disposable copy (35 mutations, all caught; one equivalent mutation, a same-value preference write, is unobservable by design).

## [1.9.7] - Unreleased

### Documentation
- #695: Consolidated setup, trusted-network, webhook, diagnostics, contributor and security guidance; moved detailed network measurements into a dated archive and marked external receiver claims as unverified.

## [1.9.6] - Unreleased

### Fixed
- #690: Debug builds mark the brand label with `(DBG)` in every locale, including the Quick Settings tile and the in-app title: `app_name`, `title_keepadb` and `tile_label` are now `translatable="false"` and defined only in `values/`, so the debug overlay is no longer shadowed by locale-specific copies. The release label stays `KeepADB`.
- #690: The same applies to notification titles, the widget permission text and the tile permission toast: their leading brand name is now a `%1$s` argument filled from `app_name`, so debug shows `(DBG) KeepADB:` there and release stays `KeepADB:`.

### Documentation
- #689: Keep-Alive click comment in `MainActivity` names the guard actually used since #680.

### Testing
- #693: Restored automatic full CI on pushes to `master` and pull requests. CI runs `bin/verify`, including both APK builds and their brand-label checks; standard GitHub-hosted runners are free for this public repository.
- #690: `KeepADBDebugBrandLabelTest` checks the `(DBG)` label in all 19 locales (debug variant); `bin/check-i18n` no longer needs allowlist entries for the three brand strings, and `KeepADBResourceContractTest` now requires those brand keys to exist in `values/` only.
- #690: Automatic debug/release label guard: `KeepADBVariantLabelContractTest` (the `(DBG)` marker may only come from `src/debug`) and `bin/check-variant-labels`, a new step 5/5 of `bin/verify` that inspects the built APKs (debug: `(DBG) KeepADB` in all locale buckets and the manifest; release: no `DBG` anywhere, plain `KeepADB`).

## [1.9.5] - Unreleased

### Fixed
- #680: Switching Keep-Alive on in the app enables wireless debugging immediately, even during an active automatic recovery backoff on a trusted network; untrusted networks still get the trust prompt.
- #686: The mesh access-point hint dialog shown after allowing an access point no longer leaks a window when Settings or the network list is destroyed or rotated.
- #685 (Privacy): The warning for foreign mDNS ADB services masks the address in logcat.

### Testing
- #687: Three unused imports removed from tests.
- #680, #685, #686: Regression tests for each of the fixes above.

## [1.9.4] - Unreleased

### Fixed
- #681 (Privacy): The logcat warning "Cached endpoint ... no longer reachable" masks the endpoint host (port stays visible; diagnostics event line unchanged).
- #682: The trusted-network location-permission rationale dialog in Settings survives rotation and no longer leaks a window (permission is only requested after the tap).

### Changed
- #683: Javadoc of `isEligibleWifiTransport` no longer refers to the removed `getAllNetworks()` logic; unused imports removed from three tests.

### Testing
- #681, #682: Regression tests for the masked stale-endpoint warning and the rotation-safe permission rationale dialog.

## [1.9.3] - Unreleased

### Fixed
- #670: An explicit Allow tap at the network trust prompt enables wireless debugging immediately, even during an active automatic recovery backoff (keep-alive, Wi-Fi and trust checks still apply).
- #671: Register: additional transports are only reported if the operation is still current after the primary POST.
- #674: (Privacy) logcat shows the WLAN endpoint and BSSID masked; diagnostics export and ring buffer are unchanged.
- #675: A SecurityException in applyNow rolls the last desired intent back; a locked handover tap no longer re-posts the USB notification after unplug.

### Testing
- #670, #671, #674, #675: Regression tests for each of the fixes above.

## [1.9.2] - Unreleased

### Changed
- VPN/Tailscale detection uses callback tracking in `KeepADBNetwork` instead of the deprecated `getAllNetworks()`; behavior is unchanged, and `inactiveEvent` is documented as a contract-v2 API reserve (#676).
- The proguard documentation list includes `NetworkListActivity`; `buildToolsVersion` is 35.0.0 (#677).

### Fixed
- Website and feedback links in Settings no longer crash on devices without a browser; a neutral toast is shown instead (#673).
- The reset-app, background-location, USB handover and language dialogs survive rotation; reset still needs the confirm tap (#672).
- The main Settings cards expose their expanded/collapsed state to TalkBack (#677).

### Testing
- Regression tests for the missing-browser link handling (#673), dialog rotation (#672) and VPN callback tracking (#676); test teardowns reset static seams (#677).

## [1.9.1] - Unreleased

### Testing
- #668: Correct the location-grant scope in service test comments and keep Wi-Fi connected in the scheduling regression test, so it detects removal of the trust check during cooldown. No application behavior changes.

## [1.9.0] - Unreleased

### Added
- Optional background-location setup with a second-step explanation and clear foreground/background access status, so network recovery can work after a background start (#616, #619, #644, #645).
- Separate management views for allowed, observed and recently prevented access points, with the current connection and its next action first in Network settings (#654, #655).

### Changed
- Connection details in notifications are opt-in; trust decisions move into the app when details are hidden (#592, #597, #598).
- Wi-Fi-name matching stays an advanced, off-by-default option. Allowing an access point afterwards grants only that allowance; the normal Keep-Alive path decides when to enable debugging (#654, #655).
- #650: Prepare version 1.9.0 / versionCode 172 for the next release. All unpublished development snapshots from 1.8.39 through 1.8.74 belong to this release candidate; their technical records and version codes below remain unchanged. The minor increment accounts for the new user-facing background-access flow (#644), without claiming publication.

### Fixed
- Network identity remains fail-closed when unreadable; Android 14+ background service starts fall back safely when the location service type is unavailable (#625, #628, #629, #630).
- Trust and enable actions require an unlocked device where specified; privacy mode hides network details in app confirmations and success messages (#578, #586, #588, #658).
- The observation switch stops recording, the open Network card follows Wi-Fi changes, and BSSID formatting is consistent without changing stored identities (#659, #661, #662).
- Network guidance is mode-neutral in all 19 languages (#660); earlier localization, UI, diagnostics and reliability fixes remain documented in the development snapshots below.

## [1.8.74] - Unreleased

### Fixed
- #662: When a Wi-Fi name is unreadable, trust notification actions retain the original BSSID fallback label for storage; uppercase conversion is applied only to displayed text. Independent review repair of the 1.8.73 candidate.

## [1.8.73] - Unreleased

### Fixed
- #658: The in-app trust confirmation and success toast mask the prompted network name and BSSID in privacy mode; the allow action keeps the raw network identity.
- #659: Disabling access-point observation also stops Settings from recording new observations; existing history is retained.
- #660: Background-access and unreadable-network guidance use mode-neutral explanations in all 19 languages.
- #661: The open Settings Network card refreshes on Wi-Fi availability, loss and capability changes and unregisters its callback when the activity stops.
- #662: BSSIDs display uppercase hex in network views, trust prompts, toasts and privacy masks; stored addresses and trust matching are unchanged.

## [1.8.72] - Unreleased

### Changed
- #654: The Network card in Settings is reordered top to bottom: the current connection first
  (network, allowed or not, the cause and the one fitting action), then the mode choice, the
  background-access facts, the access-point entries, the observation option and, last, the
  advanced Wi-Fi-name section. The active mode ("Automatic re-enable: ...") is readable in the
  closed card head and is read back from the stored settings. The former on/off switch for the
  restriction became a two-option choice ("In all Wi-Fi networks" / "Only allowed access points",
  or "Allowed access points and Wi-Fi names" while the name matching is on); the location permission
  and background-access steps behind it are unchanged, and tapping the already selected option
  never re-opens a dialog.
- #654: Connection, decision and permission are shown as separate facts. The status names the
  connection as allowed access point, allowed by Wi-Fi name, not allowed, not readable or no Wi-Fi,
  always in words with colour only as a reinforcement. The cause and action fit the situation:
  allow this access point, grant the location permission, open the location or Wi-Fi settings, or
  set up background access. In "all networks" mode the texts say that the allow lists do not count
  there and that nothing is paused: the status cause names only the active mode, and the hint next
  to the saved lists names the label the allowed option shows at that moment ("Only allowed access
  points", or "Allowed access points and Wi-Fi names" while the name matching is on), taken from the
  same resource as the option itself.
- #654: Background access and the current reading are two separate lines. The access status reads
  allowed, restricted or not required in the current mode (texts of
  `background_location_status_*` reworded; the setup button reads "Set up background access" while
  restricted); "Right now" states whether the network is readable this moment and explicitly does
  not claim that a background start could read it, while a missing grant does not claim a current
  failure.
- #654: Allowing an access point afterwards -- from the current-connection action, the allowed and
  observed lists, the recently-prevented history and the mesh offer -- now only writes the allowlist
  entry and clears the question it answers (`KeepADBReceiver#allowBssidOnly`). It no longer starts
  an enable action itself; the regular Keep-Alive path decides from the current connection, mode
  and settings (the service is only asked to look again). The allow action of the untrusted-network
  prompt notification and its in-app confirmation (#598) are unchanged and still attempt the
  connection at once.
- #654: The observation option ("Observe access points", former "Enable Wi-Fi & access point
  discovery") now controls only the observed-access-points list. Allowed and recently prevented
  access points, the state and the advanced section stay reachable with it off. The stored key is
  unchanged.
- #655: The Wi-Fi-name matching switch moved from the former Trusted Networks section into the
  advanced section "Allow by Wi-Fi name" at the very bottom of the Network card, together with its
  risk warning (unchanged: any access point broadcasting an allowed name is accepted), the current
  name action and the name list. The section is collapsed by default, but its closed header states
  the effect -- off, on, or saved without effect in the current mode -- and the number of names,
  never a name. The setting stays off by default, every stored value and name is kept as is, and the
  redesign never switches the matching or the mode by itself. No trust rule changed: a known
  identity is still required, then a listed BSSID or, only with the matching on, an exactly listed
  name. The effect line now says that only readable access points are accepted by name.
- #654: With the privacy mode on, network names and addresses are hidden on the whole card and in
  all list views, in descriptions and in toasts; a hint explains why. A hidden name reads "Name
  hidden #n" with one number per Wi-Fi name, counted separately in each list or view in the order
  the rows are shown: the same name always gets the same number (also on several access points of
  one mesh), different names never share one, and the current access point counts together with
  the list entries of its view. A single name outside a list stays "Name hidden". A hidden BSSID
  keeps its first and its last octet (`de:*:*:*:*:ad`), the four in between stay masked. The trust
  confirmation of the prompt notification (#598) keeps naming the network it asks about.

### Added
- #654: `NetworkListActivity` (not exported) with three management views opened from the card:
  allowed access points (current access point first, then every explicitly allowed one, with
  allow/remove actions and the "list does not count in all-networks mode" hint), recently prevented
  re-enabling (the automatic history, newest first, with reason and an allow action -- no block list,
  no manual entries) and observed access points. Long lists collapse behind "Show more" after 20
  rows.
- #654: `KeepADBNetworkCardState` (pure derivation of mode, name-matching effect, connection,
  cause, action and the two background facts), `KeepADBNetworkCardText`, `KeepADBNetworkDisplay`,
  `KeepADBNetworkActions` and `KeepADBAddressMask#maskBssid`. New strings in all 19 languages.

### Removed
- #654: The "show trusted access points only" filter, the inline access-point list, the "Security
  management" block and the "recently blocked" dialog (replaced by the views above), the Beta group
  texts and badge string of the former Wi-Fi & access points section, and the unused id
  `btn_wifi_aps_grant_location_permission`. 26 obsolete strings removed from all 19 languages.

### Fixed
- #655: At font scale 2.0 the Allow and Remove buttons of the Wi-Fi-name section had the label
  filling the button and touching both edges: the Material default button has no horizontal padding
  (Robolectric layout pass: 0, about 10dp vertical). Both now come from one helper that mirrors the
  row buttons of `NetworkListActivity` (16dp/8dp padding, 48dp minimum height, start edge below the
  name, 15sp condensed bold); in the same layout pass at font scale 2.0 the label keeps a 16dp inset
  on both sides and the buttons measure above the 48dp minimum. The 48dp minimum itself was already
  met and is unchanged.
- #654: The inactive-list hint and the status cause of the "all networks" mode quoted a truncated
  mode name ("Only allowed ...") that matched no visible label once the name matching renamed the
  second option. The hint is now a format string (`network_list_inactive_hint`, new argument) that
  names the visible label in the card, in the advanced section and in the allowed-access-points
  view; the cause no longer names an allowed mode. Both reworded in all 19 languages.

### Testing
- #654/#655: `KeepADBNetworkCardStateTest` and `KeepADBNetworkCardStateTrustAgreementTest` (the card
  shows "allowed" exactly when `KeepADBTrustedNetwork` trusts, over every identity, list and switch
  combination), `SettingsNetworkCardTest`, `NetworkListActivityTest`, `NetworkListActivityLocaleTest`,
  `KeepADBAllowOnlyTest` (with a control test proving the old path would have enabled in the same
  setup), `KeepADBNetworkDisplayTest` and `KeepADBNetworkCardTextTest`. The former
  `MainActivityAccessPointOverviewTest` and `MainActivityTrustedNetworkTest` were ported into these
  classes; their "allowing enables Wireless Debugging at once" assertions were inverted on purpose.
  Visual acceptance on a real rendering (dark theme, large font, small displays) is still pending;
  its first round (emulator, font scale 2.0) led to the fixes above, each with a regression test
  in `SettingsNetworkCardTest`, `NetworkListActivityTest` and `KeepADBResourceContractTest`
  (per-state label checks, an all-locale guard against truncated mode names, font-scale-2.0 button
  measurements). The later privacy-mode refinement (user decision of 2026-09-30: per-name
  numbering, first and last BSSID octet) is guarded in `KeepADBNetworkDisplayTest`,
  `KeepADBAddressMaskTest`, `NetworkListActivityTest` and `SettingsNetworkCardTest` from both
  sides: the same name gets one number and two names never share one, a name outside a list stays
  unnumbered, and a hidden address shows exactly the first and the last octet and no other.

## [1.8.71] - Unreleased

### Documentation
- #646: `docs/trusted-networks-measurement.md` gains "Nachtrag 6" with the end-to-end emulator
  matrix for API 30 to 36.1 on 1.8.70 (measured 2026-09-30): foreground control with a running
  Keep-Alive service, the two-step location grant driven through the real UI (rationale dialog,
  "Allow all the time" on the system page, three-state status line), reboot with and without the
  background grant, and the identity-unavailable notification and status texts. It closes the gaps
  listed in "Nachtrag 5". No app behavior changed.

### Fixed
- #646: The French background-location texts (`background_location_panel_body`,
  `background_location_status_granted`, `background_location_status_missing`) addressed the user
  informally while the rest of the French resources use the formal "vous". Unified to "vous".

## [1.8.70] - Unreleased

### Added
- #644: Settings now show a rationale dialog for the optional background location grant ("Allow
  all the time") in two situations, both only while the grant is missing: right after
  trusted-network mode has been switched on, and when the "Check background access" button is
  pressed. The dialog explains that the access is only used to recognize the trusted Wi-Fi and
  offers "Open settings", "Trust all Wi-Fi networks instead" and "Later". The trust-all action is
  omitted when all Wi-Fi networks are already trusted. With the grant present, the button still
  jumps straight to the app's system settings page. The grant itself is never requested through a
  runtime dialog. New string `background_location_dialog_later` in all 19 languages.

### Changed
- #645: The background access status line in Settings now shows three states in color as well as
  text: active (green), restricted (amber) and neutral. The new green keeps a contrast of at
  least 4.5:1 on the panel background (8.62:1).

## [1.8.69] - Unreleased

### Documentation
- #642: Javadocs in `KeepADBService` and `SettingsActivity` now mirror "Nachtrag 5" of
  `docs/trusted-networks-measurement.md` (foreground service type and location grant behavior).
  Comments only; no app behavior changed.

### Changed
- #643: The hints shown when the current network identity cannot be read now name "Allow all the
  time" as the lasting fix. Updated in all 19 languages; the tap target is unchanged.

## [1.8.68] - Unreleased

### Documentation
- #642: `docs/trusted-networks-measurement.md` gains "Nachtrag 5" with the emulator API matrix
  (API 30 to 36.1) measured on 2026-09-29. With the optional "Allow all the time" grant, a
  background-started keep-alive service reads the real Wi-Fi identity on every supported API, and
  on API 34+ keeps the `location` service type without triggering the #629 fallback. The note
  also lists the matrix's measurement gaps. No app behavior changed.

## [1.8.67] - Unreleased

### Documentation
- #640: README now links to KeepADB's official F-Droid catalog listing. GitHub Releases remain
  available as another download source. No app behavior changed.

## [1.8.66] - Unreleased

### Documentation
- #634: `fastlane/metadata/android/en-US/full_description.txt` described the background recovery
  pause (missing/approximate permission, location off, or a background-originated service start)
  without mentioning the #616 opt-in that covers the background-start case. Now names the optional
  "Allow all the time" location grant as the way to keep identity readable across a background
  start too, while keeping the no-location-determination/storage guarantee explicit. No code
  change.
- #635: the C2 binding-decision's point 2 ("no background-location grant needed") in
  `docs/trusted-networks-measurement.md` predates #616 and did not mark itself as describing the
  pre-#616 default-mode state. Added a note that #616 later introduced `ACCESS_BACKGROUND_LOCATION`
  as an optional opt-in for the background-start case found in #626/#629/#630, without changing
  the underlying default-mode decision. No code change.

## [1.8.65] - Unreleased

### Documentation
- #631: `KeepADBTrustedNetwork.isCurrentNetworkTrusted()`'s Javadoc said `MODE_ALLOWLIST` trust
  required identity known and a listed BSSID, omitting the #492 opt-in SSID match that `isTrusted()`
  (#625) already documented correctly. Now names both paths (BSSID match, or with SSID matching
  enabled, an exact SSID match), each gated on a known identity. No behavior change.
- #632: several comments and docs still claimed an unconditional background unmasking of Wi-Fi
  identity, or a `SecurityException`/service-death outcome for a background-originated FGS
  promotion with `ACCESS_FINE_LOCATION` granted, both superseded by the #629 fallback (retry with
  `connectedDevice` alone instead of ending the service) and the #630 finding (masking persists
  until a foreground-originated restart, worked around in practice by the #616 opt-in). Corrected
  `KeepADBService`'s class and `determineForegroundServiceType()` Javadoc, `SettingsActivity`'s
  opt-in switch Javadoc, `KeepADBNetworkTrustPrompt`'s class and method comments, `README.md`'s
  Trusted Networks section, `SECURITY.md`'s threat-model bullet, and
  `docs/trusted-networks-measurement.md`'s binding-decision point 4 to state the foreground-origin
  dependency and the actual #629 fallback behavior. No code/test behavior change.

## [1.8.64] - Unreleased

### Fixed
- #629: on API 34+, `KeepADBService.onStartCommand()` requesting FGS type `connectedDevice|
  location` from a background start (boot, `MY_PACKAGE_REPLACED`, or a background `sync()`) with
  `ACCESS_FINE_LOCATION` granted made `startForeground()` throw `SecurityException` every time --
  `failForegroundStart()` then tore the whole Keep-Alive service down with no retry. Measured
  (#626) to kill Keep-Alive after every reboot and every app update on Android 14+ once the user
  had granted location for the trusted-network allowlist. Bound decision (2026-09-28): on that
  exception, retry once with `connectedDevice` alone instead of stopping the service. The service
  now survives every background start on API 34/35; Wi-Fi identity resolution stays fail-closed/
  masked, as before, until a foreground-originated start (e.g. opening the app, #630/#628)
  promotes it with `location` again. Covered end to end by new Robolectric tests that exercise
  the real `onStartCommand()` retry control flow (the `SecurityException` catch, the fallback
  type computation, and the diagnostics) via a test-only `promoteToForeground()` seam, since no
  mocking framework is available and Robolectric's own shadow does not model this API 34
  restriction. Confirmed end to end in independent review on a freshly booted API 34 emulator
  (real `adb reboot` -> `BootReceiver` -> background `startForeground()`): the platform throws the
  same `SecurityException` as in Nachtrag 3, the service logs the `#629` retry and lands on
  `connectedDevice` alone, and `dumpsys activity services` shows the `ServiceRecord` alive and
  foregrounded afterwards instead of torn down. See `docs/trusted-networks-measurement.md`,
  Nachtrag 4.

### Documentation
- #630: investigated a fully automatic workaround for the API 33 background-`ServiceRecord`
  masking (`IDENTITY_UNAVAILABLE` until `MainActivity` is opened) before considering it a
  documented platform limit, per the 2026-09-28 decision on #630. A periodic self-trigger and a
  service-originated activity trampoline were both ruled out (the former never re-evaluates an
  existing `ServiceRecord`'s While-in-Use grant; the latter is blocked by Android 10+'s
  background-activity-start restrictions without user interaction). Measured instead, on the S20,
  that the already-shipped #616 opt-in (`ACCESS_BACKGROUND_LOCATION`) *does* lift the masking for
  a background-originated `ServiceRecord` end to end -- automatic Wireless-Debugging re-enable
  succeeded with zero app-foreground interaction -- even though the record's own
  `allowWhileInUsePermissionInFgs` flag stays `false`. A controlled revoke/grant A/B comparison on
  the same access point confirms the permission, not some other factor, causes the difference. No
  code change: #630 is resolved for users who complete the #616 setup card; for users who decline
  it, the masked-until-`MainActivity`-opens behavior (#628) remains the documented, deliberate
  tradeoff, since no clean automatic alternative exists. See `docs/trusted-networks-measurement.md`,
  Nachtrag 4, which also corrects the #616 changelog entry below from an untested assumption to a
  measured fact.

## [1.8.63] - Unreleased

### Added
- #616: Optional `ACCESS_BACKGROUND_LOCATION` ("Allow all the time") for trusted networks, so a
  Keep-Alive service started from the background can still read the Wi-Fi identity (#626/#630;
  originally an untested assumption, confirmed end to end by the #630 Nachtrag 4 S20 A/B
  measurement).
  The permission is now declared in the manifest but never requested through a runtime dialog.
  While trusted-network mode is on and the grant is missing, the main screen shows a dismissible
  setup card next to the other setup hints; "Set up now" opens KeepADB's system app-details page,
  where the user picks "Location > Allow all the time" himself. The card re-reads the grant on
  return, disappears once it is granted and reappears after a later revocation, even if it was
  dismissed before. Dismissing never changes the permission, the trusted-network mode or the
  allowlist. Settings → Network always shows the background-access status with a button to the
  same system page, independent of the card. New helper `KeepADBBackgroundLocation`.

### Changed
- #616: `KeepADBServiceManifestContractTest` now requires `ACCESS_BACKGROUND_LOCATION` to be
  declared and forbids runtime `requestPermissions` calls for it, reversing the former
  "never declared" contract from #606 per the reopened #616 decision. README and SECURITY.md
  describe the optional grant. Fail-closed behavior is unchanged: without the grant an
  unreadable or masked identity is never trusted.

## [1.8.62] - Unreleased

### Removed
- #625: Removed the in-process SSID trust continuity cache from `KeepADBTrustedNetwork`
  (`lastVerifiedTrustedSsid`, `verifiedTrustObserverActive`, `rememberVerifiedTrust()`,
  `forgetVerifiedTrust()`, `setVerifiedTrustObserverActive()`, `resetVerifiedTrustForTesting()`)
  and its invalidation/observer calls in `KeepADBService`'s Wi-Fi `NetworkCallback`. The cache
  (#270/#313/#353/#354/#355/#620) assumed Android masks only the BSSID in the background; AOSP
  masks SSID and BSSID together (audit #624), so it could only ever widen trust and carried a
  theoretical stale-read TOCTOU. Network generation (#310) and `availableWifiNetworks` are unchanged.

### Fixed
- #628: `KeepADBNetworkTrustPrompt#identityUnavailableFixIntent` now opens `MainActivity` instead
  of falling back to `SettingsActivity` when location permission is granted, location services
  are on, and the network identity is still unavailable. Measured for #626 (see
  `docs/trusted-networks-measurement.md`, "Nachtrag 3"): on API 33 that combination means the
  Keep-Alive service was started from the background (boot, a sticky restart, or a background
  `sync()`) and never received a While-in-Use location grant for its foreground-service record.
  `SettingsActivity` does not call `KeepADBService#sync` on resume and therefore cannot re-promote
  the service; `MainActivity#onResume()` does call it, promoting the service back to a foreground
  record and unmasking the identity. Updated `network_prompt_identity_unavailable_text`,
  `status_off_keep_alive_blocked_identity_unavailable`, and
  `settings_trusted_network_status_identity_unavailable` in all 19 languages to name this
  additional cause. No change to `isTrusted`/allowlist behavior; an unknown identity still fails
  closed. Out of scope: API 34+, where the same background-start combination throws
  `SecurityException` and stops the service outright instead of running masked -- tracked
  separately as #629.

### Security
- #625: `KeepADBTrustedNetwork.isTrusted()` is now a pure, stateless decision: a network is trusted
  only if its identity is known and its BSSID is listed or, with the SSID opt-in (#492), its SSID is
  listed exactly. A masked or unknown identity is always rejected, also directly after the same SSID
  was verified. The only behavior change is in the split-masking case that AOSP and the S20 FE never
  produce, and only toward stricter.

### Documentation
- #627: Corrected the "Android can mask network identifiers in the background" claim in
  `SECURITY.md`, `README.md`, and `fastlane/metadata/android/en-US/full_description.txt` to
  reflect the C2 measurement (#606, #626): identity stays readable in the background while the
  running Keep-Alive service was started with the app in the foreground (or the app has been opened
  since); the remaining masking cases are a missing/approximate location permission, location being
  off, or a service started from the background (boot, a process restart of such a service, or an
  app update) until the app is opened again (#630) -- which on Android 14+ can end the service
  outright (see #629).
- #627: Removed the stale "(Beta)" wording for the Network settings section from `README.md`
  and `fastlane/metadata/android/en-US/full_description.txt`; the settings UI itself already
  dropped Beta status in #618.
- #627: Marked conclusions 1-5 in `docs/trusted-networks-measurement.md` as superseded by the
  C2 decision (#606) and its follow-up measurement (#626), and corrected the "Nachtrag 2" table:
  the C2 rows, the C1 `wifi_reconnect`/`display_off` rows and the control `fg_act`/`bg_fg` rows
  were actually `untrusted` because the access point used in that run was not on the allowlist -- what was measured is `identity_known`,
  not a positive trust outcome. Clarified that the `bg_no_act` case's foreground service kept
  running throughout (the originally intended `bg_no_fgs` scenario could not be produced without
  root, since `am stopservice` does not stop an unexported FGS).
- #627: Corrected the `MODE_ALLOWLIST (default, #260)` Javadoc reference in
  `KeepADBTrustedNetwork.isCurrentNetworkTrusted` to reflect #492's default flip
  (`MODE_ALL_WIFI` is the default since #492; `MODE_ALLOWLIST` is the opt-in).
- #627: Documented `KeepADBNetworkIdentity#displaySsid()`'s `UNKNOWN_SSID` handling (#269) as a
  defensive measure for a split BSSID-known/SSID-unreadable state that no measurement has
  actually observed.
- #626: Added "Nachtrag 3" to `docs/trusted-networks-measurement.md` with the C2 measurement-gap
  results (boot, sticky restart, background `sync()`, runtime grant, API 34/35): identity
  readability depends on the origin of the service record, not on the requested FGS type; a
  background-originated record stays masked on API 33 (#630) and is terminated on API 34+ (#629).
  The throwaway measurement probe used for this run was not merged.

### Testing
- #625: Replaced the cache/observer tests with fail-closed and statelessness tests, a reflection
  guard against mutable static state in `KeepADBTrustedNetwork`, and a Robolectric test that drives
  the running service's callbacks; sensitivity shown by re-inserting an SSID cache (tests turn red).
- #627: Added `KeepADBServiceManifestContractTest`, a static contract test verifying
  `KeepADBService` declares `foregroundServiceType="connectedDevice|location"`,
  `FOREGROUND_SERVICE_LOCATION` is declared, and `ACCESS_BACKGROUND_LOCATION` is never declared.
- #628: Added a `KeepADBNetworkTrustPromptTest` case pinning that the identity-unavailable fix
  intent opens `MainActivity` when permission and location services are both fine.

## [1.8.61] - Unreleased

### Added
- #619: Added network onboarding banner on `MainActivity` for users with Keep-Alive enabled
  who have not yet restricted protection to trusted Wi-Fi networks (`all_wifi` mode).
  Includes direct setup action with `EXTRA_FOCUS_NETWORK` in `SettingsActivity` and dismiss persistence.

### Changed
- #620: Updated Javadoc and docstrings across `KeepADBTrustedNetwork`, `KeepADBNetworkIdentity`,
  `KeepADBNetworkTrustPrompt`, and `KeepADBService` to accurately reflect while-in-use FGS location
  privileges under Android 12+ (C2, #606); documented in-memory BSSID masking fallback as a defensive safeguard.

## [1.8.60] - Unreleased

### Changed
- #618: Consolidated network settings in `SettingsActivity`: flattened nested collapsible
  cards into visually structured direct sections under a single "Network" card, removed
  experimental BETA badges, and updated the trade-off warning across all 19 languages to reflect
  reliable background BSSID detection via `FOREGROUND_SERVICE_LOCATION`.
- #613: Updated developer comment for `NO_PROFILE_CONTENT_REQUEST_CODE` in
  `KeepADBUsbNotification.java` to list request code 14 (`REQUEST_CODE_IDENTITY_UNAVAILABLE`).

## [1.8.59] - Unreleased

### Added
- #606: Declared `FOREGROUND_SERVICE_LOCATION` permission in `AndroidManifest.xml` and configured
  `KeepADBService` with `android:foregroundServiceType="connectedDevice|location"`. Under Android
  platform rules, this classifies the running Keep-Alive foreground service as while-in-use for
  location, unmasking Wi-Fi SSID and BSSID during background keep-alive without requiring invasive
  background location grants (`ACCESS_BACKGROUND_LOCATION`).
- #606: Implemented dynamic foreground service type selection in `KeepADBService#onStartCommand`.
  `ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION` is included when `ACCESS_FINE_LOCATION` is granted,
  and omitted dynamically when running in default `all_wifi` mode without location permissions,
  preventing `SecurityException`s on Android 14+ (API 34+).
- #606: Added unit tests in `KeepADBServiceLifecycleRobolectricTest` verifying
  `determineForegroundServiceType` and dynamic `onStartCommand` service type binding for both granted
  and denied location states.
- #606: Updated `docs/trusted-networks-measurement.md` with the full C1, C2, and control measurement
  matrix from the Samsung Galaxy S20 FE, and documented the privacy rationale in `README.md`.

## [1.8.58] - Unreleased

### Fixed
- #604 (follow-up from #598): the details-off in-app trust confirmation dialog
  (`SettingsActivity`, `ACTION_CONFIRM_IN_APP`) now survives a configuration change (rotation).
  Previously the dialog's only state was the one-shot intent extra `KeepADBNetworkTrustPrompt
  .EXTRA_BSSID`, consumed by `onResume()`; a rotation recreated the activity with that extra
  already gone, so the dialog simply vanished and the user had to find the access point again
  under "Recently blocked". The BSSID the dialog is currently bound to is now also carried in
  `onSaveInstanceState`/`onCreate(savedInstanceState)` (new `STATE_TRUST_CONFIRMATION_BSSID`) and,
  after a recreate, re-resolved through the same `KeepADBNetworkTrustPrompt#pendingConfirmation`
  lookup against `KeepADBBlockedNetworkHistory` that a fresh notification tap uses -- the #598
  binding invariant is unchanged: no BSSID from the intent or the current connection is read on
  restore, and the click still confirms exactly the BSSID captured when the dialog first opened.
  The accepted #598 restriction (a real process death can still reopen the dialog, correctly
  naming the network) is unaffected and remains out of scope here.
- #603: `KeepADBUsbNotification`'s no-profile content intent and two `KeepADBNetworkTrustPrompt`
  content intents (`showIdentityUnavailable`, and the details-on branch of `show`) each opened
  `SettingsActivity` with an extras-free `PendingIntent.getActivity(context, 0, ...)`.
  `PendingIntent#filterEquals` ignores extras, so all three -- request code 0, same component,
  same action-less intent -- shared one `PendingIntent` identity; harmless today since none of
  them relies on extras, but a future extra added to any one of them would have had
  `FLAG_UPDATE_CURRENT` silently overwrite another's pending tap target. Gave each its own request
  code (`KeepADBUsbNotification.NO_PROFILE_CONTENT_REQUEST_CODE = 2`;
  `KeepADBNetworkTrustPrompt.REQUEST_CODE_DETAILS_ON_CONTENT = 13` and
  `REQUEST_CODE_IDENTITY_UNAVAILABLE = 14`, alongside the existing 10/11/12), none colliding with
  each other or with the existing `HANDOVER_ACTION_REQUEST_CODE = 1` and
  `REQUEST_CODE_CONFIRM_IN_APP = 12`. No behavior change. New
  `KeepADBUsbNotificationTest#noProfileContentIntentUsesARequestCodeDistinctFromNetworkTrustPromptOnes`
  reads the request code back via Robolectric's `ShadowPendingIntent#getRequestCode()` and asserts
  it avoids every `KeepADBNetworkTrustPrompt` request code.

## [1.8.57] - Unreleased

### Testing
- #601 (follow-up from #596): replaced `KeepADBAsyncSurfaceRefreshContractTest`'s two remaining
  static source-content contracts with real Robolectric behavior tests. New
  `MainActivityEndpointListenerGenerationTest#activityDropsQueuedEndpointCallbacksAfterPauseAndRecreation`
  drives a real `MainActivity` through `ActivityController` pause/resume/recreate and asserts that
  a callback delivered through a superseded endpoint-listener generation never moves the rendered
  `R.id.endpoint` text, while a current-generation callback does -- exercising the exact
  `endpointListenerGeneration`/`endpointSurfaceActive` guard `isEndpointSurfaceActive()` enforces,
  via reflective access to `KeepADBEndpointCoordinator`'s private `endpointListener` field rather
  than a new production seam (same pattern `KeepADBEndpointCoordinatorTest`'s `setStatic` already
  uses). New `KeepADBWidgetMainThreadRefreshTest#widgetStateRefreshRunsOnMainThreadWithoutStartingDiscovery`
  drives `KeepADBWidget.refreshAllState(Context)` from a genuine background `Thread` against a
  real `ShadowAppWidgetManager`-backed widget and asserts both that the rendered `RemoteViews`
  text only changes once the (Robolectric-paused) main looper runs the posted update, and that no
  discovery attempt is ever started (`KeepADBEndpointCoordinator#hasActiveDiscoveryAttemptForTesting()`).
  Both new tests were mutation-tested in an isolated worktree against representative production
  mutations (the generation guard forced to always-active and always-discard; the widget's
  main-thread post skipped) to confirm each goes red for the right reason. The class's
  remaining five static tests and their javadoc explanation are unchanged; the class went from
  seven tests to five.

## [1.8.56] - Unreleased

### Documentation
- Unified the wording for Android's ADB-over-Wi-Fi feature across all 19 UI languages plus
  README.md, SECURITY.md, and the Fastlane store texts: the product term is now consistently
  **"Wifi-ADB"** everywhere, replacing the previous mix of "WLAN-ADB", "WiFi-ADB", and
  "Wi-Fi-ADB" (#599, user-decided glossary). About 116 mixed-spelling occurrences from the
  original audit collapse onto one canonical spelling: 94 in `values*/strings.xml` (19 locales,
  5 strings each except `de`, which phrases one of the five without naming the term), 11 in
  README.md, 1 in SECURITY.md, and 2 in `fastlane/metadata/android/en-US/full_description.txt`.
  The existing `KeepADBUsbActionLabelBudgetTest` #593 8-character USB handover slot stays
  satisfied ("Wifi-ADB" is 8 Latin characters, same as "WLAN-ADB" before it).
- Left two things deliberately untouched: the term for the Wi-Fi network itself (unrelated to
  the ADB feature name) already follows each language's own convention -- German "WLAN", English
  "Wi-Fi", and the equivalent system term elsewhere -- and technical values (the `wlan-adb`
  register/webhook protocol method, Java identifiers, log tokens) that must not change. Dutch
  (`values-nl`) was the one language actually mixing "Wi-Fi-netwerk"/"Wi-Fi-verbinding" with the
  already-dominant lowercase "wifi-netwerk"/"wifi-verbinding" spelling; normalized 10 occurrences
  to the lowercase compound form used elsewhere in the same file and matching the Taalunie
  wordlist ("wifinetwerk"). Historical `CHANGELOG.md` sections and the numbered
  `fastlane/metadata/android/en-US/changelogs/*.txt` files that already shipped keep their
  original "WLAN-ADB" wording, per the user decision not to rewrite published release notes.
- `bin/check-i18n`'s `usb_notification_enable_wlan_handover` ALLOWLIST entry (#593) updated to
  describe the renamed "Wifi-ADB" term instead of "WLAN-ADB"; the exemption itself is unchanged
  (the label is still identical across all 19 locales by design).

## [1.8.55] - Unreleased

### Security
- Persistent main notification's port/IP endpoint moved behind the existing
  `notification_details_enabled` opt-in (#597, user decision option 2 on the #592 follow-up):
  with the setting off (the default), the main notification -- previously always showing
  `Port <port> @ <ip>` -- now shows only the status title with a neutral content text (new string
  `notification_text_active_hidden`, "Tap to see connection details in the app.", translated in
  all 18 locales) that opens the app on tap. With the setting on, the notification text is unchanged
  from before: `Port <port> @ <ip>` with the port in bold and `maskHostForDisplay`'s privacy-mode
  masking applied. The disable action, ongoing/no-when flags, and content intent are unaffected
  either way -- only `KeepADBNotification#buildNotification`'s content text changed (the title
  stays the status); the "searching"/"disabled, waiting"/permission-missing placeholders already carried no
  endpoint and stay as they were.
- `publicVersion` added to the main notification, reusing the same neutral
  `notification_text_active_hidden` text regardless of the details setting -- consistent with the
  #589/#592 USB and trust-prompt notifications: with details on, the private card shows the
  endpoint while `publicVersion` still does not, so a redacting lock screen configuration cannot
  leak it even after the opt-in. No content intent or actions on `publicVersion` (same rule as the
  other two notifications).
- `SettingsActivity`'s `notificationDetailsToggle` click listener now also calls
  `KeepADBEndpointCoordinator.refresh(this)` (alongside the existing `KeepADBUsbReceiver.refresh`
  call from #592) so a currently visible main notification re-renders immediately when the setting
  is flipped, without needing a service restart.

### Documentation
- README's "Persistent Notification" feature bullet and "Connection Details in Notifications"
  privacy section, and SECURITY.md's connection-details paragraph, updated to describe the main
  notification's port/IP as opt-in rather than always shown; `settings_notification_details_subtext`
  extended (all 18 locales) to name the persistent status notification alongside the USB profile
  and new-Wi-Fi-network details it already covered.
- Existing installs upgrading to this version see the neutral status text in the main
  notification, not the port and IP address, until they turn on Settings → Notification →
  "Show connection details in notifications" themselves; no migration reads or writes the
  preference, it simply defaults to off as it already did for the USB and trust-prompt
  notifications since #592.

### Testing
- `KeepADBNotificationRobolectricTest`: `notificationHidesPortAndIpByDefault` (fresh prefs, details
  off -- title unchanged, content text is the neutral fallback, neither port nor IP appear
  anywhere on the notification or its `publicVersion`), `notificationShowsPortAndIpWhenDetailsEnabled`
  (details on reproduces the previous `Port <port> @ <ip>` text exactly), and
  `publicVersionNeverMentionsPortOrIpRegardlessOfDetailsSetting` (the counter-proof: `publicVersion`
  stays neutral in both modes while the private card of the same "details on" notification does
  carry the endpoint, so a fix that fed `publicVersion` from the same content would fail here).
- `SettingsActivityTest#notificationDetailsToggleAlsoRerendersTheVisibleMainNotification`: seeds a
  cached endpoint, confirms the default-off notification hides it, toggling on re-renders the
  already-posted notification with the endpoint without a service restart, and toggling back off
  re-hides it -- same shape as the existing USB-card test from #592.
- Each new assertion was checked against a targeted production mutation (dropping the
  `isNotificationDetailsEnabled` check so the endpoint always shows; feeding `publicVersion` from
  the same content as the private card instead of the neutral fallback; dropping the new
  `KeepADBEndpointCoordinator.refresh` call from the toggle listener) and confirmed to go red
  under it before being reverted, uncommitted (#597).

## [1.8.54] - Unreleased

### Security
- Trust decision moved into the app while connection details are off (#598, user decision
  option 2 on the #592 follow-up): with `notification_details_enabled` off (the default), the
  untrusted-network prompt no longer carries a "Yes, allow" action -- the user could not see which
  network it would trust. Its text (and `publicVersion`) now reads "New Wi-Fi network detected —
  tap to confirm it in the app" (new string `network_prompt_confirm_in_app_text`, translated in
  all 18 locales); "No, block" stays. The tap opens `SettingsActivity` with a confirmation dialog
  naming the network and its BSSID, with allow/block. With details on, the prompt is unchanged
  (named network, allow action with the #578 unlock requirement, plain Settings content intent).
- Binding: the dialog binds to the access point the prompt was raised for, as recorded by the app
  in `KeepADBBlockedNetworkHistory` -- the content intent carries only the BSSID as a selector,
  and `KeepADBNetworkTrustPrompt#pendingConfirmation` resolves it against that record (label
  included). It is never bound to the current connection and never re-read at click time, so a
  roam between notification and tap, or between dialog and click, cannot make it trust a
  different BSSID; whether Wireless Debugging is then switched on stays
  `KeepADBService#isAutoEnableStillPermitted`'s decision. A BSSID without a pending record
  (already trusted, evicted, placeholder, never seen) opens no confirmation, only the
  recently-blocked list. `SettingsActivity` stays non-exported and the PendingIntent immutable.
- Allow goes through `KeepADBReceiver#handleTrustNetworkAction` -- the notification action's own
  path with its locked-device check and placeholder-BSSID rejection, ending in
  `trustBssidAndAttemptConnect`/`KeepADBTrustedNetwork#addBssid` -- and block through
  `handleDismissNetworkPromptAction`; no second way into the allowlist.

### Fixed
- The confirmation content intent uses its own action and request code: the USB notification also
  posts a `SettingsActivity` PendingIntent with request code 0 and `FLAG_UPDATE_CURRENT`, and
  PendingIntent matching ignores extras, so sharing that identity would let either notification
  overwrite the other's extras (#598).

### Testing
- New `SettingsActivityTrustConfirmationTest` drives the dialog with the intent saved in the real
  posted notification: allow trusts exactly the named BSSID, block trusts nothing, a roam before
  the tap or while the dialog is open still trusts only the prompted BSSID, an unrecorded or
  placeholder BSSID and a forged label extra cannot introduce a network or name, a BSSID extra
  without the confirm action opens nothing, the intent is consumed, and a click while the device
  reports locked trusts nothing. `KeepADBNetworkTrustPromptTest` pins both modes (no allow action
  and neutral text/publicVersion with details off; allow action and plain content intent with
  details on) and `pendingConfirmation`. Each new assertion was checked against a targeted
  production mutation (allow action in both modes, trusting the current BSSID at click time,
  resolving without the recorded entry, content intent without its action, SettingsActivity
  accepting the extra without the action) and goes red under it (#598).

## [1.8.53] - Unreleased

### Changed
- Internal refactor, no behavior change (codequality review CQ-02, #595): `SettingsActivity` used
  to hold the USB profile dialog family (switch/edit/delete) and the register-webhook form
  (input, toggle, save/clear, normalization) directly in its own `onCreate`/`onResume`/
  `onSaveInstanceState`/`onDestroy`. Extracted into two Activity-bound classes:
  `KeepADBUsbProfileEditor` owns the profile dialogs' state, Bundle round-trip and dismiss-time
  cleanup, and `KeepADBWebhookForm` owns the webhook views, its draft/Bundle round-trip and its
  three explicit actions. `SettingsActivity` stays the lifecycle owner, calling explicit
  `restore`/`saveState`/`destroy` (profile editor) and `restoreDraft`/`ensureDraftInitialized`/
  `saveState`/`refreshVisual` (webhook form) hooks at the matching lifecycle points. The webhook
  URL trim-and-sanitize step, previously duplicated between the enable and save actions, now
  lives once in `KeepADBWebhookForm#readAndNormalizeInput`. `SettingsActivity#resolveWebhookDraft`
  was left in place -- already a good, narrow, independently tested boundary. No layout, ID,
  notification-intent, or `notification_details_enabled` (#592) change.

### Testing
- New `KeepADBUsbProfileEditorTest` and `KeepADBWebhookFormTest` cover the new boundary's own
  draft/Bundle contract directly on a bare `Activity`, independent of `SettingsActivity`'s own
  lifecycle wiring (already covered end-to-end by the existing `SettingsActivityTest` recreation
  tests, left unchanged). `KeepADBNetworkSecurityContractTest`'s source-content check for the
  webhook cleartext warning now reads `KeepADBWebhookForm.java` instead of `SettingsActivity.java`,
  keeping the same invariant. Every new assertion was mutation-tested against a representative
  production mutation (draft not written to the Bundle; restore falling back to the saved URL
  instead of the draft) in an isolated worktree to confirm it actually goes red. (#595)
- Review repair (#595): the Bundle keys of the profile edit draft and the webhook draft moved
  from `SettingsActivity` into `KeepADBUsbProfileEditor`/`KeepADBWebhookForm` (key strings
  unchanged), so a new profile field no longer touches `SettingsActivity`. New tests pin the
  webhook form's different empty-input rules (empty save clears the URL while disabled, is
  rejected while enabled; enabling with an empty URL snaps the toggle back) and the USB
  notification's profile-action extra (SWITCH/CREATE open the matching dialog once, the extra is
  consumed); each goes red under its targeted mutation.

## [1.8.52] - Unreleased

### Changed
- Internal refactor, no behavior change (codequality review CQ-01, #594): endpoint state and its
  lifecycle moved out of `KeepADBNotification` into the new `KeepADBEndpointCoordinator`, which
  alone owns the cached WLAN-ADB endpoint (host/port/verification time), the discovery owner
  (global vs. short-lived Tile), discovery generation and verification token, the verification
  worker, the bounded retry and cancellation/stop, all under one monitor. `KeepADBNotification`
  only renders and owns no endpoint state; `KeepADBEndpoint` stays the low-level discovery.
  Tile, widget, `KeepADB.getState()`, transport overview, diagnostics and the foreground-service
  notification now read one immutable endpoint snapshot instead of separate host/port/time
  getters, so no surface can combine values of two different endpoints. The moved logic keeps
  its guards, order and lock scope unchanged.

### Testing
- New `KeepADBEndpointCoordinatorTest` covers snapshot consistency, stale verification workers,
  Tile owner cancellation versus the global owner and listener/snapshot agreement; each guard was
  mutation-tested in an isolated worktree. Source-content contracts that pinned the moved logic
  now read the coordinator source with unchanged invariants. (#594)

## [1.8.51] - Unreleased

### Testing
- Codequality review finding CQ-03 (#596): four source-content contract test suites grepped
  method bodies for snippet presence/order/occurrence counts instead of observing runtime
  behavior, so a semantically equivalent refactor could turn them red while a coincidental text
  match could turn them green without the guard actually working. Replaced with real behavior
  tests wherever a practical equivalent exists, keeping intentionally documented static checks
  only where no behavioral equivalent is reachable at this project's test layer (each explained
  in its class javadoc): `KeepADBWifiGatedDiscoveryBehaviorTest` gained three retry-chain tests
  and `KeepADBEndpointRecoveryPulseBehaviorTest` two entry-gate tests for the #296 Wi-Fi gate;
  `SettingsActivityTest` gained a real empty-draft activity-recreation test and strengthened the
  existing partial-draft one; new `KeepADBEndpointNetworkDelegationBehaviorTest` proves
  `KeepADBEndpoint`'s address queries actually delegate to a live `KeepADBNetwork` tracker;
  `KeepADBMultiStateContractTest` gained a Wi-Fi-priority test and `MainActivityWebhookStatusTest`
  gained three webhook-report-text tests for `KeepADBAsyncSurfaceRefreshContractTest`'s removed
  assertions. Every new/changed assertion was mutation-tested against a representative production
  mutation in an isolated worktree to confirm it actually goes red. (#596)
- Review repair (#596): `KeepADBWifiProbe`'s javadoc no longer claims that contract tests pin
  every `isWifiConnected` call site's source text.

## [1.8.50] - Unreleased

### Changed
- Shortened the three USB-ADB notification action labels ("Switch profile", "New profile",
  "Enable WLAN-ADB") plus "Create host profile" across all 19 supported locales so they no longer
  clip when three actions sit side by side on a 1080px device (e.g. the s20, One UI 5, which
  gives each action an equally wide slot of about 8 Latin characters). The WLAN-ADB handover
  action now comes first and is labeled just "WLAN-ADB" in every language (allowlisted in
  `bin/check-i18n`), followed by "Switch" and "New" (DE "WLAN-ADB | Wechseln | Neu"); every
  label fits one slot (at most 8 columns, CJK/Hangul counted double), pinned by
  `KeepADBUsbActionLabelBudgetTest`. Fixed
  `settings_usb_profile_notification_subtext`, which since #592 no longer matched behavior: it
  now says that this toggle shows host profile status and actions, while the profile name and
  endpoint details (IP, host) additionally need the separate "Show connection details in
  notifications" opt-in (#593).
- The "Switch host profile" dialog now shows each profile's name and endpoint details (IP · host
  · tailnet) as two separate lines instead of one long radio label, and moved the Edit/Delete
  buttons to their own row below the text instead of sharing the radio button's row. A profile
  with a long IP/hostname/tailnet combination used to force that row into a character-by-character
  wrap; the accessibility content description on the radio button still carries the full summary
  for screen readers (#593).

## [1.8.49] - Unreleased

### Security
- Connection details are no longer written into notifications by default. On a device that
  allows sensitive notification content on the lock screen (as the s20 does), Android ignores a
  notification's `publicVersion` and shows the private copy, so the USB-ADB notification revealed
  the host profile name, IP and hostname, and the new-network prompt the network name and BSSID,
  without unlocking. New setting Settings → Notification → "Show connection details in
  notifications", **off by default**: while off, the USB notification shows "Host profile
  selected — details in the app" and the network prompt its neutral lock-screen text; the details
  remain visible in the app. Turning it on restores the previous detailed text; the settings text
  warns that the details may then also appear on the lock screen. Toggling it re-renders a visible
  USB notification immediately; an already posted network prompt keeps its text until it is shown
  again. Actions, their unlock gates (#578/#586/#588) and the `publicVersion` are unchanged. Note
  for existing users: after updating, the USB notification no longer shows the profile summary
  until the setting is turned on (#592).

### Documentation
- `SECURITY.md` and the README's "Privacy & Security" section now describe the actual lock-screen
  behavior: `publicVersion` only applies while Android hides sensitive content (#592).

## [1.8.48] - Unreleased

### Security
- The USB-ADB notification's `contentText` can name the selected host profile plus its IP/hostname
  summary; on a lock screen configured to show private notification content (as the s20 tested
  against #578/#586/#588 was), that was readable without unlocking. `KeepADBUsbNotification#build`
  now sets a `publicVersion` -- title and text both reuse the already-neutral
  `usb_notification_title` string, the same fallback the private notification itself already uses
  when no profile applies, so no new translatable strings are needed -- on the single shared
  `Notification.Builder` before either contentText branch runs, so every state (with or without a
  profile notification, with or without the handover action, and the handover error text) carries
  it. The publicVersion has no actions and no content intent: a locked screen must not be able to
  trigger anything from it. The private notification is unchanged and still shows the profile
  summary once unlocked (#589, follow-up to #578/#586/#588).

## [1.8.47] - Unreleased

This candidate bundles two independently developed lock-screen authentication gates integrated
together: #586 (Quick Settings tile) and #588 (USB handover notification).

### Security
- The Quick Settings tile no longer switches Wireless Debugging **on** from the lock screen
  without authentication. Tiles stay reachable while the device is locked, and Android leaves the
  lock check to the tile itself; an already paired host could connect once Wireless Debugging was
  on. When `KeyguardManager#isDeviceLocked()` reports a secured device as locked -- the same
  criterion as the trust-action gate from #578 -- an enable tap now goes through
  `TileService#unlockAndRun`, so Android asks for the credential first. After a successful unlock
  the tile re-reads the current state and only enables if Wireless Debugging is still off, so a
  stale tap never turns into a disable or a second toggle. Switching Wireless Debugging **off**
  from the tile stays immediate on the lock screen (explicit user decision): it only reduces
  exposure. Devices without a secure lock and unlocked devices behave as before (#586).
- The USB notification's "Enable WLAN-ADB" action (USB-to-WLAN handover, MANUAL mode) no longer
  switches Wireless Debugging **on** from the lock screen without authentication; an already
  paired host could connect afterwards. The action now asks the platform to reauthenticate before
  it fires (API 31+), and `KeepADBUsbReceiver` refuses it while
  `KeyguardManager#isDeviceLocked()` reports a secured device as locked, on
  every supported Android version including API 30 -- the same criterion as the trust-action gate
  from #578. A refused tap is logged as a diagnostics event, is not reported as a permission
  error, and re-posts the notification so the action can be tapped again after unlocking. The
  AUTOMATIC handover (no tap, trusted network only) is unchanged. The receiver was already
  non-exported, so other apps could not send this action (#588).

## [1.8.46] - Unreleased

This candidate bundles two independently developed fixes integrated together on branch
`integration/e4-582-578`: #582 (single-read-API refactor for `isEnabled`) and #578 (trust-action
authentication gate).

### Fixed
- An OEM read restriction on `adb_wifi_enabled` (`SecurityException`, see #580) could still crash
  or misbehave at several call sites #580 deliberately left unguarded: the foreground service's
  `shouldRun`/`sync`/`recheckAndEnable` and its ContentObserver callback, the recovery pulse, USB
  handover, the quick settings tile, the endpoint notification, `MainActivity`, and the debug-only
  diagnostics snapshot -- most seriously, the cleanup path in a failed toggle write
  (`KeepADB.applyNow`'s `SecurityException` catch) could itself crash by calling
  `KeepADBService.sync()`, which read the same restricted setting unguarded. `KeepADB.isEnabledOrNull`
  (introduced in #580) is now the single sanctioned way to read this setting anywhere in the app;
  every call site has its own documented, context-appropriate fallback for an unconfirmed value --
  a display surface treats it as "off", an automatic path never turns it into a write, and a
  post-write readback treats it as "not confirmed" rather than a failure (#582).

### Security
- The untrusted-network prompt's "Yes, allow" action now requires the platform to reauthenticate
  the user before it fires (`Notification.Action.Builder#setAuthenticationRequired`, API 31+),
  since tapping it can re-enable Wireless Debugging. As defense in depth on every API level --
  this flag is enforced by the platform's own lock screen, not by this app, and does not exist
  below API 31 -- `KeepADBReceiver` now also refuses to trust a network if `ACTION_TRUST_NETWORK`
  still reaches it while `KeyguardManager#isDeviceLocked()` reports the device locked, and instead
  re-posts the same prompt so the decision remains available once the user unlocks. The "No,
  block" action is unchanged: declining never trusts anything, so gating it would only make an
  unwanted prompt harder to dismiss while locked. The prompt notification also now carries a
  `publicVersion` without the network's label or BSSID, so a device configured to show private
  notification content on the lock screen no longer shows which access point is asking to be
  trusted there (#578).

## [1.8.45] - Unreleased

This candidate bundles three independently developed fixes integrated together on branch
`integration/e3-576-581-579`: #576 (register bookkeeping), #581 (Tailscale `tun0` detection),
and #579 (webhook draft recreation test coverage).

### Fixed
- `KeepADBRegisterClient` bookkeeping around a URL migration's old-URL DELETE could leave
  `lastRegisteredUrl` pointing at a resource that had already been confirmed deleted: a migration
  whose old-URL DELETE succeeded but was then superseded by a second, overtaking migration never
  recorded that success (the write only happened on the non-superseded success path), so the next
  transaction saw the already-deleted URL as "still registered" and re-issued the exact same
  DELETE against it. A confirmed deletion is now booked immediately and unconditionally (guarded
  by re-checking the currently registered URL right before writing, so a different, already-newer
  registration is never cleared by a stale confirmation) (#576, NET-01).
- The in-memory `markUnavailableAsync` retry gate added for #562 read the wall clock
  (`System.currentTimeMillis()`); a backward wall-clock jump (NTP correction, manual time change)
  could stall it far longer than the real elapsed time justified. It now uses
  `SystemClock.elapsedRealtime()`, matching the existing `KeepADBEndpoint` cooldown rationale
  (#309). The persisted #317 pending-cleanup queue deliberately keeps the wall clock, since
  `elapsedRealtime()` resets on every reboot and would make a stored 24h expiry or backoff
  unreachable after one (#576, NET-02).
- A single WLAN-ADB disconnect could fire two (observed 13-14ms apart on-device) or three
  DELETEs to the register endpoint: the toggle path, the lifecycle observer, and `service_sync`
  all react to the same disconnect and each called `markUnavailableAsync` independently. Repeated
  calls now coalesce into the single DELETE already queued or running for the same registered
  state; a later call is not swallowed once a newer registration or disconnect has actually
  superseded the outstanding one, so a genuinely new disconnect after a fresh registration still
  gets its own DELETE (#576, part C).
- Tailscale status detection (#537) only ever matched an interface literally named
  `tailscale0`, the Linux/desktop client's interface name. On Android, the Tailscale app runs as
  a `VpnService` whose TUN device is named `tun0` (or `tun1`, ...), so the status always reported
  "not active" there even while Tailscale was verifiably active and reachable -- contradicting the
  independently derived Tailscale/VPN transport row that did find the tunnel. Detection now
  requires a tunnel-named interface (`tun*` or `tailscale*`, so a carrier's own CGNAT deployment
  on e.g. `rmnet_data0` can never match) that also carries a Tailscale-shaped address (its CGNAT
  IPv4 range `100.64.0.0/10`, or its ULA IPv6 prefix `fd7a:115c:a1e0::/48`). `tailscale0` keeps
  working unchanged. Still purely local, read-only display logic (#537/#548): KeepADB never
  starts, binds, or configures Tailscale. (#581)

### Testing
- `KeepADBTailscaleStatusTest` previously drove `detect()` through a seam that replaced the whole
  interface check (name filter included), so it never actually exercised the real name-and-address
  decision logic that shipped the #581 bug. The decision logic is now a pure function,
  `KeepADBTailscaleStatus.looksLikeTailscale`/`isTailscaleActive`, over a plain interface
  description (name, up, addresses); the test seam now only supplies interface snapshots, and new
  tests drive the decision function directly with a realistic `tun0` (with CGNAT and ULA
  addresses), a foreign VPN on `tun0` without a Tailscale-shaped address, and a carrier CGNAT
  interface (`rmnet_data0`) -- confirming neither is misreported as Tailscale. (#581)
- Added a real activity-recreation test for the unsaved register-webhook URL draft in Settings
  (`SettingsActivityTest.unsavedWebhookDraftSurvivesActivityRecreation`, #579 / review finding
  UI-02): it types an unsaved URL, recreates the activity via `saveInstanceState` and
  `setup(bundle)`, and fails if the draft is replaced by the saved preference. The second #579
  point (SEC-05, case-sensitive cleartext warning) turned out to be a false positive: the warning
  has normalized the scheme since #319 and `HTTP://` is already covered by an existing test; its
  sensitivity was re-confirmed by mutation. No app behavior change.

## [1.8.44] - Unreleased

This candidate bundles two independently developed fixes integrated together on branch
`integration/e2-573-574`: #573 (data extraction rules) and #574 (BSSID/SSID redaction).

### Security
- `android:allowBackup="false"` alone does not reliably disable Android 12+ device-to-device
  (D2D) transfer: per the Android 12 (API 31) behavior changes, some OEMs honor it for cloud
  backup but still transfer app data between devices regardless of that flag. Added
  `android:dataExtractionRules` (`res/xml/data_extraction_rules.xml`), which explicitly excludes
  every domain (`root`, `file`, `database`, `sharedpref`, `external`, and their `device_*`
  device-protected-storage counterparts) from both `<cloud-backup>` and `<device-transfer>`,
  closing the gap `allowBackup="false"` alone leaves open on OEMs that don't honor it for D2D.
  `allowBackup="false"` is kept for API < 31, which never reads `dataExtractionRules`.
  `KeepADBBackupPolicyContractTest` now asserts both the manifest attribute and the exclusion
  rules exist instead of asserting their absence (#573, follow-up to #252). OEM D2D behavior
  itself was not device-verified as part of this change.
- The diagnostics export (Settings > Diagnostics > Export diagnostics,
  `KeepADBDiagnostics.export`/`exportForIssueReport`) shortened a Wi-Fi access point's BSSID to
  its OUI (first three octets) plus a mask for the rest, and would mask an SSID in full the same
  way -- both were previously exported unredacted whenever the trust-prompt or trust-network-action
  diagnostics events fired. The feedback report draft's own redaction
  (`KeepADBIssueReporter.redactDiagnostics`) now masks both fully on top of that, matching the
  existing `host=`/`port=`/secret/URL rules (#574). Nothing about where diagnostic events are
  written, or the ring buffer/journal's own retention or format, changed; the redaction is applied
  only at the export/draft read path.

## [1.8.43] - Unreleased

This candidate bundles four independently developed fixes/changes integrated together on branch
`integration/e1-575-572-580-577`: #575 (CI dispatch-only), #572 (recovery pulse self-abort),
#580 (`isEnabledOrNull` `SecurityException` fallback), and #577 (Keep-Alive trust gate).

### Changed
- The `CI` GitHub Actions workflow (`.github/workflows/ci.yml`) no longer starts automatically on
  pushes to `master` or on pull requests; it runs only when started manually via
  `workflow_dispatch` (#575, closing the remaining #327 trigger point). Pull requests and
  development commits are accepted through the local `bin/verify` gate, as the project policy
  already required. The release workflow deliberately keeps JDK 21 to match the F-Droid build
  toolchain; this is now documented in `release.yml` instead of being aligned to JDK 17. No change
  to the app itself.
- The Keep-Alive toggle's immediate "turn Wireless Debugging on now" side effect now respects the
  same trusted-network guard the automatic Keep-Alive recheck already uses
  (`KeepADBService#isAutoEnableStillPermitted`), instead of writing `adb_wifi_enabled` immediately
  and unconditionally. On an untrusted Wi-Fi network (allowlist mode active, access point not
  listed), switching Keep-Alive on now falls through to the existing trust prompt instead of
  enabling right away -- "Keep-Alive ON" means "keep it alive wherever that's permitted", not
  "switch it on here regardless of trust". The main switch, the quick settings tile, and the home
  screen widget remain manual overrides and are unaffected by this gate (#245, #577).

### Fixed
- A recovery pulse (AUS -> pause -> AN, triggered when mDNS finds no adbd listener while enabled)
  could abort its own re-enable and leave Wireless Debugging stuck off: with Keep-Alive disabled
  and the app foregrounded, the pulse's own AUS write was observed by the ContentObserver, which
  tore the `KeepADBEndpoint` discovery session down (`KeepADBNotification.refresh()` ->
  `stop()` -> `endpoint.stop()`), invalidating the very discovery generation the pulse's EIN-stage
  guard checked -- confirmed on a real s20 (`stage=enable reason=preconditions_changed`, Wireless
  Debugging left off). The EIN-stage guard no longer depends on the endpoint's own discovery
  generation; a real network change, lost Wi-Fi, an untrusted network, or a manual toggle during
  the pause still cancel the pulse exactly as before (#347, #309 unaffected). Every pulse abort
  after the AUS write has already landed now also refreshes the surfaces (service/notification/
  widget), and a `SecurityException` during the pulse now shows the same permission-missing
  notification the other automatic re-enable paths already show (#572).
- `KeepADB.getState()`, the `observed` read at the top of `KeepADB.setEnabled()`, and
  `KeepADBEndpoint.maybeSendRecoveryPulse()` no longer crash when the settings gateway's read
  throws `SecurityException` -- an OEM read restriction on `adb_wifi_enabled` that
  `KeepADBAndroidSettingsGateway`'s own javadoc already anticipated, independent of whether
  `WRITE_SECURE_SETTINGS` is granted. All three now go through a new shared helper,
  `KeepADB.isEnabledOrNull(Context, String)`, which returns `null` on a failed read instead of
  propagating; `getState()` maps that to `PERMISSION_MISSING`, the other two treat it as "not
  enabled". Each fallback records one `read_failed` diagnostics event via the existing
  `KeepADBDiagnostics.event` API. `maybeSendRecoveryPulse()` runs as a delayed Handler callback on
  the main looper, so an uncaught exception there would have crashed the app outright (#580).
  `KeepADB.applyNow()`'s own post-write readback and `performRecoveryPulse()`'s three reads were
  explicitly out of scope for this fix; a wider, pre-existing exposure at several other unguarded
  `KeepADB.isEnabled()` call sites (`KeepADBService`, `KeepADBDiagnostics`, `KeepADBUsbHandover`,
  `MainActivity`, `KeepADBNotification`, `KeepADBUsbNotification`, `KeepADBTileService`) was found
  while writing this fix's regression tests and is tracked separately as #582.

## [1.8.42] - Unreleased

### Added
- When the register webhook keeps failing and the configured URL looks tailnet-bound (an IPv4
  literal in the Tailscale CGNAT range `100.64.0.0/10`, or a `.ts.net` MagicDNS hostname), the
  webhook status panel now shows an additional, clearly speculative hint: Tailscale might be
  disconnected or not auto-started after a reboot, and points at Android's "Always-on VPN"
  setting for Tailscale as the manual place to check. Any other target, or an unclassifiable
  failure, keeps the existing generic "failed" status unchanged. No webhook URL, host, or other
  sensitive value is added to the hint text or to logs. KeepADB still never inspects, starts,
  stops, or configures Tailscale/VPN state itself -- the scope boundary from #537/#548 is
  unchanged (#561).
- Four cards now carry a small leading icon next to their title, matching the existing
  security/network advice banner's warning icon: the home screen's Notifications
  permission card (bell) and the Settings screen's "USB-ADB" (USB plug), "Network
  (Beta)" (Wi-Fi arcs), and "Other" (three-dot) cards. Purely visual/structural: no
  string, permission, or default changed. Every new icon is a plain, self-drawn vector
  (no external icon library), decorative and marked
  `importantForAccessibility="no"` since the adjacent title text already names the
  section — the same pattern the app already used for `ic_warning` on the advice banner
  and `ic_keepadb` in the header bar, so no new `contentDescription` string (and no
  i18n follow-up) was needed. The advice banner's existing warning icon and yellow
  accent (border/text/background) are unchanged (#514).

### Changed
- `markUnavailableAsync()` (fired on every notification/service refresh and network callback once
  Wireless Debugging or the last verified transport drops) used to issue an immediate register
  DELETE on every single call, with no coordination between repeated calls. A P60 run logged 51
  failed DELETEs, mostly ~60s apart. Repeat calls are now throttled with the staffing agreed in
  #562: the first attempt is still immediate, then failures wait 5s, 10s, 15s, 30s, 1min, 3min,
  settling into a flat 5min ceiling afterwards -- with no age-based cutoff. The last confirmed
  register state is kept until a DELETE actually succeeds, and a confirmed new registration
  (`performUpdateTransaction` succeeding) resets the backoff outright, so a live reconnect is never
  blocked or undone by a stale retry. Only affects users who enabled the optional register webhook
  (#562).

## [1.8.41] - Unreleased

### Fixed
- Debug builds only: the diagnostics journal's hourly disk write (`AtomicFile`, at most once per
  hour) ran synchronously on the caller, including the 60s main-thread service heartbeat, which
  could block a UI frame while writing. The write now runs on a single background thread; the
  hourly budget decision and the entries snapshot still happen synchronously under the journal's
  lock, so persisted content and timing are unchanged. No user-visible behavior change; release
  builds never create this journal (#568).
- Debug builds only: `KeepADBDiagnosticJournal.prune()` budgeted `MAX_CHARS` (200,000) against
  each entry's raw `entry.text` only, not the rendered export line. `renderEntries()` appends a
  `" samples=N lastSampleAt=…"` suffix (~55 chars) for coalesced entries, and that suffix keeps
  growing on every `recordSample()` coalescing hit without ever calling `prune()` again (only a
  new, non-coalesced entry does). In the worst case the actual rendered export could end up
  roughly 50% over the documented `MAX_CHARS` bound (the comment on that constant references the
  Binder/share-intent size limit). `prune()` and `renderEntries()` now share one
  `renderedLine(Entry)` helper, so the budget is enforced against the size that is actually
  exported (#569).

## [1.8.40] - Unreleased

### Added
- Debug builds only: the diagnostics export now covers at least the last 48 hours (50-hour
  retention, bounded to 4000 entries / 200,000 characters so it still fits one share intent). A
  new `KeepADBDiagnosticJournal` keeps all diagnostics events plus a per-minute
  `state_snapshot` from the existing 60s service heartbeat: the default network's transport
  (`wifi`, `cellular`, `vpn`, …, or `none`/`unknown`), KeepADB's own Wi-Fi eligibility, the
  Tailscale status (`active`/`inactive`/`not_installed`/`unknown`), Wireless Debugging,
  Keep-Alive, the endpoint shown in the notification and, separately, whether that endpoint is
  currently `confirmed` reachable, `stale`, `unverified` or `none`. A changed snapshot names the
  changed fields (e.g. `changed=tailscale`), so Tailscale and mobile/Wi-Fi transitions are
  timestamped to the minute. Unchanged consecutive samples are counted into one entry
  (`samples=N lastSampleAt=…`); a sampling gap always starts a new entry. The snapshot only
  reads state and changes no Keep-Alive, recovery, endpoint or Tailscale setting (#566).

### Changed
- Debug builds only: diagnostics are persisted at most once per hour (atomic file in the app's
  private files directory, budget measured against the file's last write so process restarts do
  not reset it). Events recorded after the last write are lost if the process is killed — an
  accepted trade-off decided on #566. Repeated identical heartbeat events are now counted into
  one journal entry instead of one export line per tick; logcat still receives every tick. The
  feedback-report draft keeps a compact excerpt of the newest 128 entries. Release builds are
  unchanged: same `KeepADB diagnostics v1` ring buffer, no snapshots, no journal file (#566).

## [1.8.39] - Unreleased

### Documentation
- Clarify that Android's “Always allow on this network” choice is a system approval and is
  separate from KeepADB's optional trusted-network restriction.

## [1.8.38] - 2026-09-24

### Changed
- The Wireless Debugging notification title (`notification_title_active` /
  `notification_title_disabled`) previously used the abbreviated "Wifi-ADB" term, diverging from
  the "Wireless Debugging" / "Drahtloses Debugging" terminology used by the main toggle and the
  home screen widget. Both notification title strings now use the same localized long-form
  wording as `toggle_label` in all 19 language variants (e.g. "KeepADB: Wireless Debugging
  ENABLED" instead of "KeepADB: Wifi-ADB ENABLED"), keeping the compact notification format.
  Pure string-resource change, no logic change; verified with `bin/check-i18n` (#556).

### Documentation
- Refreshed the README, security policy, and Fastlane metadata for the 1.8.38 candidate: mDNS-only
  endpoint discovery, the optional trusted-network restriction and its fresh-install default,
  current WLAN-only webhook sending, Android approval/backoff behavior, and current setup and
  privacy controls. Added user-facing release notes for version code 135; older technical entries
  remain implementation history.

### Testing
- Set the GitHub release workflow to Temurin JDK 21, matching the JDK major version observed in
  the existing F-Droid build job. The v1.8.38 F-Droid build still needs its own pipeline result.

## [1.8.37] - Unreleased

### Added
- A short explanatory line ("Turns Wireless Debugging on or off for ADB over Wi-Fi.") now sits
  directly under the main "Wireless Debugging" switch on the home screen, above the dynamic
  status/endpoint lines, so first-time users see what the switch does without opening Android's
  own settings. New string resource `toggle_subtext`, translated in all 19 language variants and
  verified with `bin/check-i18n`. A new Robolectric test
  (`MainActivityToggleSubtextTest`) pins the string binding, visibility, and its position between
  the toggle and the status `TextView`. No change to `adb_wifi_enabled`, Keep-Alive,
  endpoint-discovery, or webhook behavior (#553).

### Documentation
- Reviewed the last 15 tracked issues (#522, #523, #526, #528, #529, #530, #536, #537, #538,
  #539, #543, #545, #548, #550, #552) for visible/accessibility text drift as required by #553.
  No missing translations or contradictory short descriptions found across the main toggle,
  status, widget, quick-settings tile, notification, and setup surfaces beyond what #553 itself
  addresses; the #530 debug-only Tailscale badge remains an intentional, non-localized technical
  exception. Details in the #553 pull request description / issue comment.

## [1.8.36] - Unreleased

### Fixed
- The register webhook could get stuck reporting a freshly discovered, still-correct WLAN-ADB
  endpoint as failed (HTTP 409 "stale") after a fresh install, until Wireless Debugging was
  toggled off and on again. Root cause: the shared `phone-register-server`'s `event_status()`
  ordered every incoming event purely by its `observed_at` timestamp, but that value comes from
  two independent clocks depending on the source -- the phone's own clock for a KeepADB webhook
  event, and the host machine's clock for a `phone-register record` call (e.g. from
  `android-target`'s resolver, which records the very same WLAN-ADB endpoint while resolving the
  install target). Whenever those two clocks disagreed even slightly, the app's own confirmation
  of the endpoint the host had *just* recorded could look older and get permanently rejected as
  stale, blocking every retry of that same, unchanged endpoint. Fixed server-side (outside this
  repository, in the shared `~/agent/bin/phone_register_common.py` tool -- see
  `~/agent/protocols/2026-09-22/065100-keepadb-552-register-stale-fix.yaml`): an incoming event
  that reports exactly the `active`/`endpoint` state already on record is now accepted as an
  idempotent confirmation regardless of clock ordering; a genuinely outdated report of a
  since-replaced endpoint, or a real endpoint change, are unaffected. No app code change was
  needed -- `KeepADBRegisterClient` already computes a fresh `observed_at` on every retry; a new
  Robolectric test (`testFailedRetryOfSameEndpointSendsFreshObservedAtEachTime`) locks in that
  precondition (#552).

## [1.8.35] - Unreleased

### Fixed
- Webhook URL display now shows the full, unredacted host whenever the privacy mode is off (open
  eye), instead of still applying the #350 default two-octet IPv4 mask. Turning privacy mode on
  (crossed-out eye) keeps the existing #483 stricter redaction (one visible IPv4 octet, IPv6 fully
  masked, hostnames readable) unchanged. Userinfo stripping, query masking and fragment removal
  are unaffected in both states -- only the host-octet rule depended on the toggle. The toggle's
  `OnClickListener` in `MainActivity` already re-rendered the webhook status line synchronously on
  every flip, so no separate refresh wiring was needed (#550).

## [1.8.34] - Unreleased

### Changed
- Tailscale remains a debug-only beta diagnostic: the #537 status card and the #538
  Tailscale/VPN transport row on the main screen are now hidden entirely on a release build,
  even when Tailscale detection reports itself active/verified. A debug build keeps showing
  both, unchanged. The underlying status-vs-transport detection disagreement itself is not
  addressed here and remains a separate follow-up (#548).

## [1.8.33] - Unreleased

### Fixed
- Diagnostics ring buffer (`MAX_EVENTS = 128`) no longer gets crowded out by the 60s
  heartbeat's per-tick `keep_alive_check` events on release builds; only an actual outcome
  change (waiting for network / retry deferred / recheck due / recovery result) is now stored,
  restoring the export's historical coverage well beyond roughly an hour (#545). Debug builds
  keep every tick unchanged, matching the existing debug/release diagnostics distinction; the
  Keep-Alive/recovery logic itself is unaffected, only diagnostic volume changed.

## [1.8.32] - Unreleased

This section is the combined integration of issue packages #536, #537, #538 and #539, which
were implemented on four independent branches. Each branch proposed the same bump (1.8.28 ->
1.8.29, versionCode 126); on merge they are collapsed into a single release that carries one
patch step per merged package (versionCode 125 + 4 = 129, 1.8.28 + 4 = 1.8.32). The codes 126,
127 and 128 are therefore intentionally never published.

### Added
- Optional, purely local Tailscale status in the network/endpoint view on MainActivity (#537).
  Detected from two platform-level, permission-free reads: whether the Tailscale app
  (`com.tailscale.ipn`) is installed (`PackageManager`, gated by a new `<queries>` manifest
  entry for API 30+ package visibility) and whether a `tailscale0` interface is up with an
  address in Tailscale's CGNAT range (100.64.0.0/10, plain JDK `NetworkInterface`). Four states:
  hidden when not installed, active, inactive (installed-but-unconfigured and
  configured-but-disconnected are deliberately not distinguished -- Android has no reliable,
  permission-free way to tell those apart), and unknown when a platform read itself fails.
  Display-only: never consulted by `KeepADB`, Keep-Alive, or endpoint/transport discovery, and an
  active Tailscale interface is never treated as an ADB endpoint by itself.
- Connection view now shows every currently *verified* ADB transport separately -- WLAN/LAN,
  Tailscale/VPN and USB -- instead of only the WLAN endpoint, with one clearly marked primary
  transport and privacy-mode masking applied consistently across all of them (#538).
- Tailscale/VPN detection (`KeepADBVpnTransport`) is deliberately independent and narrow: it
  identifies a VPN network by its Tailscale-range address (100.64.0.0/10, Tailscale's documented
  CGNAT allocation) and only then verifies real ADB reachability on it via the already-known
  WLAN/LAN port, reusing the existing socket-connect probe. An active VPN interface alone --
  wrong address range, or ADB simply not reachable there -- is never presented as an ADB
  endpoint, and produces no transport row at all: whether Tailscale is up is stated by the #537
  status card alone, so the app never shows two independently derived answers to that question.
  The verified row is labelled "ADB via Tailscale/VPN" to keep it distinguishable from that
  status (review repair during integration).
- USB-ADB is now shown as its own active transport (no fabricated network endpoint) whenever the
  system reports a genuine connected+configured+adb USB link, reusing the existing
  `KeepADBUsbReceiver` sticky-broadcast check.
- A transport that stops being verified (network change, VPN drop, cable pull) simply disappears
  from the next render -- the aggregation (`KeepADBTransportOverview`) holds no state of its own
  and is recomputed fresh on every refresh.
- `KeepADBRegisterPayload` builds one independent event per verified transport (WLAN/LAN,
  Tailscale/VPN, USB), so parallel transports occupy separate register slots and cannot clear one
  another. Only transports that were actually verified are reported; methods the deployed
  register does not accept yet are held back instead of being sent (#539).
- The live report path uses that multi-transport reporting: an endpoint change now reports every
  other currently verified transport as its own event as well, from the same trigger, to the same
  user-entered webhook URL and under the same `register_webhook_enabled` opt-in. Because the
  deployed `phone-register-server` still accepts only `wlan-adb`, the Tailscale and USB events are
  currently built and held back rather than sent, so this adds no requests until the server side
  (#543) lands; widening `SERVER_SUPPORTED_METHODS` is the single switch. WLAN/LAN itself keeps
  being reported from the transaction's own authoritative endpoint, and the additional transports
  stay outside that transaction's success accounting (review repair during integration).

### Changed
- The register webhook now speaks the versioned contract v2: every report carries
  `contract_version`, `observed_at` and a state-derived `event_id`, while `method` and `endpoint`
  keep their previous place so the register's legacy projection and all existing
  `GET /register/<alias>` consumers are unaffected (#539). Repeating an unchanged state is now
  idempotent, and a late-arriving older report can no longer overwrite a newer endpoint.

### Fixed
- Keep-Alive's automatic re-enable now reliably retries after a readback-mismatch backoff
  (#496/#500) instead of possibly sitting idle until some unrelated event happens to touch it:
  the 60s foreground-service heartbeat is now the real timer that re-triggers the check once the
  backoff window elapses, so a due retry always happens on its own, not just when the app is
  reopened or the network changes. The backoff itself now follows the two-stage cadence the repo
  owner specified: the first unconfirmed automatic attempt retries after roughly 2 minutes, and
  every attempt after that is capped at 5 minutes apart (previously a flat, purely reactive 15
  minutes). Diagnostics also now distinguish "waiting for network" from "retry deferred" (backoff
  active) from "recheck due" (the retry firing), so the three states are separately traceable
  (#536).

### Testing
- Added deterministic backoff-cadence unit tests (2-minute first retry, capped 5-minute
  interval) and a Robolectric test that drives the real heartbeat ticker with no manual recheck
  call, proving the retry fires on its own once the window elapses (#536).
- `KeepADBTransportOverviewTest` covers the four required transport scenarios (WLAN only; WLAN +
  Tailscale both verified; VPN active but ADB unreachable there; USB only) plus a generic
  non-Tailscale VPN, an unconfigured USB cable, the empty snapshot, and the synchronous-vs.
  background-thread dispatch behavior of `currentAsync`. `KeepADBVpnTransportTest` and
  `KeepADBTransportEndpointTest` add focused unit coverage for the CGNAT-range check and the
  value type itself (#538).
- `MainActivityTransportOverviewTest` pins that a merely active (Tailscale-range or generic) VPN
  renders no transport row, and that a verified one does, driving the activity's real render path
  (#537/#538).
- `KeepADBRegisterMultiTransportWiringTest` exercises the real `updateEndpointAsync` trigger: only
  the WLAN event reaches the current server, nothing is sent with the webhook opt-in off, and with
  the server-supported set widened the same trigger also emits the USB event -- the counter-test
  that fails if the production call into `postTransports` is removed (#539).
- Closed a pre-existing test-isolation race in the register cleanup lifecycle tests: a trailing
  background request of one test could be recorded against the next test's fake transport,
  because the transport is a static field. The tests now drain the register executor between
  cases instead of relying on timing (#539).
- Integration fix while merging #538 and #539: `KeepADBRegisterPayload.fromSnapshot` adapts a
  #538 `KeepADBTransportOverview.Snapshot` into the #539 payload input -- the single mapping the
  #539 package had deferred until #538 landed. Covered by tests for the full three-transport
  mapping (order and all three wire methods preserved) and for the two cases that must yield no
  events at all: an active-but-unverified VPN, and a missing snapshot.

### Documentation
- Added the multi-transport register contract in
  `docs/design/issue-539-multi-transport-register-contract.md`, including schema, versioning,
  stale/TTL behaviour, the primary/compatibility projection, the remaining server-side gap and
  the migration/rollback path. It documents how this design differs from the discarded #416
  approach (#539).
- Patch version bump (1.8.28 -> 1.8.32, versionCode 129): four merged patch-level packages, all
  either display-only or additive on the wire, with an unchanged legacy register projection and a
  revert-only rollback.

## [1.8.28] - Unreleased

### Fixed
- Final language/hardcode gate fixes: Turkish permission guidance no longer repeats its
  instruction, all localized permission guidance preserves its command-line break, and both
  Beta badges now use the shared nonlocalized string resource (#523).

### Testing
- Added the reproducible #523 pre-release language and hardcode audit plus a resource-contract
  regression test for the repaired Turkish guidance.

## [1.8.27] - Unreleased

### Changed
- Debug builds now show exactly `⚠ DEBUG BUILD` in Settings → Version. The badge remains
  intentionally English and nonlocalized, appears only in debug builds, and is absent from
  release builds (#530).
- Patch version bump (1.8.26 -> 1.8.27, versionCode 124): this shortens the existing debug-build
  warning without changing launcher/package/version text or release behavior.

## [1.8.26] - Unreleased

### Changed
- The Android 13+ notification-permission panel can now be dismissed without requesting the
  permission or opening system settings. The choice is stored locally and survives app restarts;
  permission requests, permanent-denial handling, and notification behavior stay unchanged
  (#528).
- Patch version bump (1.8.25 -> 1.8.26, versionCode 123): this refines the existing #501
  permission panel with a local visibility preference and does not add or change any Android
  permission or notification capability.
- USB-ADB settings now need only one expand step: opening the main USB-ADB card shows the
  notification controls and USB -> WLAN-ADB handover mode directly, without nested collapsible
  headers. Existing controls, preferences, defaults, notifications, and transport behavior are
  unchanged (#529).

## [1.8.25] - Unreleased

### Changed
- Debug builds now show `(DBG) KeepADB` as their launcher and tile label, making a debug
  installation visibly distinct during first-install testing (#526).

## [1.8.24] - Unreleased

Note: this and the following sections down to `1.8.21` (#518-#521) were developed on a branch
that originally started counting from `1.8.18`/115, in parallel with the `1.8.18`-`1.8.20`/
115-117 sequence below (#515-#517), which merged to `master` first. Renumbered on integration
so the version sequence stays linear and non-colliding; no functional content changed.

### Changed
- "Sonstiges" restructured into a single collapsible card: the former plain, permanently visible
  "Sonstiges" group heading (#510) is now itself an expand/collapse card like every other
  settings section. Unlike Network (Beta) (#519) and USB-ADB (#520), its four contained sections
  -- persistent notification, keep display on, security/network advice banner, and battery-
  optimization advice -- are not independently collapsible sub-cards; they appear directly, one
  below another, separated by horizontal divider lines, as soon as the outer card is expanded.
  Purely a UI/structure refactor -- no change to defaults, preferences, or the underlying
  notification/display/advice logic (#521).

## [1.8.23] - Unreleased

### Changed
- USB-ADB restructured into a collapsible outer card with two independently collapsible
  sub-cards: the previously separate, top-level "USB-ADB notification" and "USB → WLAN-ADB
  Handover" cards now nest inside a single "USB-ADB" card, collapsed by default, matching the
  same collapse pattern introduced for Network (Beta) (#519). Both sub-cards keep their own
  independent expand state and unchanged notification/handover logic. Purely a UI/structure
  refactor -- no change to defaults, preferences, or behavior (#520).

## [1.8.22] - Unreleased

### Changed
- Network (Beta) restructured into a collapsible outer card with two independently collapsible
  sub-cards: the former plain, permanently visible "Network (Beta)" group heading (#510) is now
  itself an expand/collapse card like every other settings section, and only shows its
  description once opened. Trusted Networks and Wi-Fi & access points move from being direct
  siblings under that heading to nested sub-cards inside its body, each keeping its own
  independent expand state, beta badge, and opt-in switch. Purely a UI/structure refactor --
  no change to defaults, preferences, or the underlying trusted-network/Wi-Fi detection logic
  (#519).

## [1.8.21] - Unreleased

### Changed
- Language selector moved to the Settings toolbar: replaced the large Language section in the
  Settings content column with a compact translate icon button in the top-right of the header.
  Tapping it opens the same language selection dialog as before (all supported languages, each
  shown in its own endonym, "System default" first); the underlying selection/storage logic in
  `KeepADBLocaleHelper`/`KeepADBPreferences` is unchanged. The icon is a newly drawn "A" +
  stylized CJK-character glyph (a self-authored AOSP vector drawable, `ic_translate.xml`),
  replacing the old `ic_globe` icon, which rendered as a near-blank ellipse at small size and
  was being reused unchanged from the previous large-card layout (#518).

## [1.8.20] - Unreleased

### Changed
- Security & network advice: now rendered as a card in the same style as the other
  first-time-setup cards (rounded panel, warning icon, title, body), instead of a full-bleed
  banner. The standalone "X" close icon is replaced by an explicit red "Dismiss notice" button
  with yellow text, matching the other primary card buttons. Notice text, gold accent
  (background/border), warning icon, and the existing dismiss persistence are unchanged --
  only the container and the dismiss control changed. Localized in all 19 supported languages
  (#517).

## [1.8.19] - Unreleased

### Changed
- Notification permission card: shorter, more natural copy. Title is now just
  "Notifications" instead of repeating the app name; the body explains the benefit (seeing
  whether the background service and Keep-Alive are running), the connection-drop alert, and
  that Wireless Debugging keeps working without the permission. Button labels rephrased
  ("Allow notifications" / "Open notification settings"). Permission and fallback logic
  unchanged, text only. Localized in all supported languages (#516).

## [1.8.18] - Unreleased

### Documentation
- First-time setup card: explains that with multiple connected ADB devices/emulators, the
  serial number must first be read from `adb devices` and passed explicitly via
  `adb -s <device-serial> shell pm grant ...`; the single-device case (plain `adb shell ...`)
  is kept as a shorter alternative. Also explains the `offline` and `unauthorized` device
  states directly on the card, with their respective next step. Text localized in all 19
  supported languages (#515).

## [1.8.17] - Unreleased

### Added
- Debug build indicator: the Version section in Settings now shows a visible warning badge
  when running a debug build (detected via the `.debug` applicationId suffix), so it is no
  longer necessary to inspect the package name via adb to tell debug and release builds apart
  on-device. Release builds are unaffected (#512).

## [1.8.16] - Unreleased

Two independent fixes land together in this release candidate: a privacy-default bugfix (#509)
and a settings-screen regrouping refactor (#510). Both were reviewed and merged as a single
integration batch since neither depends on the other.

### Fixed
- Privacy mode default: a fresh install now shows network addresses fully (open-eye icon)
  instead of starting in privacy mode. `KeepADBPreferences.isPrivacyModeEnabled()` fell back
  to `true` when no preference entry existed yet, masking addresses on every clean install.
  Privacy mode is now off by default and remains an opt-in toggle; existing masking behavior
  at all display surfaces (home screen endpoint, webhook display, last reported endpoint,
  notification, quick settings tile) is unchanged once the user enables it (#509).

### Changed
- Settings regrouping: bundled the four notice/display-preference cards (persistent
  notification, keep display on, security/network advice banner, battery-optimization advice)
  under a shared "Other" heading, and grouped "Trusted Networks" and "Wi-Fi & access points"
  under a shared "Network (Beta)" heading that explains their common trust logic and beta
  status. Both beta features now carry a consistent "BETA" badge (previously only Wi-Fi &
  access points had one), stay independently collapsible, and keep their existing default-off
  preference values -- only the visual layout changed (#510).

## [1.8.15] - Unreleased

### Changed
- Wi-Fi & Access Points moved to Settings: moved the access point overview card and its
  associated network controls (current connection row, trusted/all filter, SSID allowlist,
  mesh grouping, recently blocked networks dialog, and location permission prompt) from the
  home screen (`MainActivity`) into `SettingsActivity` (`settings_wifi_aps_panel`) between USB
  handover and trusted network settings (#507).
- Opt-in & Beta indicator: Wi-Fi access point discovery is now an experimental opt-in feature
  (`settings_wifi_aps_feature_toggle`, disabled by default). Gated content is only rendered
  and active when opted in, reducing home screen clutter and background scanning.

## [1.8.14] - Unreleased

### Added
- App Reset in Settings: added a "Reset App Completely" button to the "Diagnostics & Maintenance"
  card (`settings_reset_app`, styled with warning styling). Tapping it shows a confirmation dialog
  explaining that all saved networks, preferences, and permissions will be deleted and the app
  closed. On confirmation, revokes runtime permissions on kill (`POST_NOTIFICATIONS`,
  `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`) on Android 13+ and clears all application user
  data via `ActivityManager.clearApplicationUserData()` (#505).
- Location permission request for Wi-Fi discovery: decoupled `location_permission_panel` from
  `isAllowlistMode` so missing `ACCESS_FINE_LOCATION` permissions are highlighted and requestable
  on the home screen even in `MODE_ALL_WIFI`. Added in-context grant button directly on the
  "Wi-Fi & Access Points" card when current network identity is unreadable, with automatic fallback
  to app settings if permanently denied (#504).

### Changed
- Settings section title updated from "Diagnostics" to "Diagnostics & Maintenance" across all
  18 supported locale variants (#505).

## [1.8.13] - Unreleased

### Changed
- Notification permission onboarding: `MainActivity.onCreate()` no longer fires the
  `POST_NOTIFICATIONS` system prompt automatically on cold start. `notification_permission_panel`
  is now shown on the main screen whenever the permission isn't granted yet on Android 13+ (before
  the first request as much as after a denial), with a body text explaining that the permission
  lets KeepADB show whether its background service and Keep-Alive are running and notify
  immediately on a dropped connection -- Wireless Debugging itself keeps working regardless. The
  panel's single button is context-sensitive: before the first request (or while the system would
  still show its own rationale), it triggers `requestPermissions` directly; once the system has
  permanently denied further prompts, it instead opens the app's notification settings. New
  `MainActivityNotificationPermissionPanelTest` pins the no-auto-prompt behavior, the button's
  dual action, and the panel's grant/deny visibility transitions (#501).

### Added
- Battery-optimization advice panel on the main screen can now be dismissed via a close ("X")
  button, mirroring the existing security/network advice banner's own dismiss pattern
  (`btn_dismiss_advice_banner` / `ic_close`, 48x48dp touch target, reusing the existing
  `action_dismiss` content description). The dismiss state persists in `KeepADBPreferences`
  (`battery_optimization_panel_visible`, default on) and survives app restarts. `SettingsActivity`
  gained a new "Show Battery Optimization Advice" toggle card, using the same
  collapsible-card/switch pattern as the advice-banner toggle, to restore the panel's visibility.
  The panel's visibility in `MainActivity.refresh()` now combines this dismiss preference with the
  existing live `KeepADBBatteryOptimization.isExempt()` check -- a granted system exemption always
  wins and keeps the panel hidden regardless of dismiss state (#502).

## [1.8.12] - Unreleased

### Fixed
- Automatic Keep-Alive recovery: an enable write that `Settings.Global` accepted but whose
  readback stayed off (e.g. Android's own "always allow Wireless Debugging on this network"
  pairing dialog was never confirmed) no longer retries in an unbounded ~1.5s loop. A new
  framework-free `KeepADBRecoveryBackoff` allows exactly one controlled automatic attempt per
  "unchanged state" cycle, then pauses further automatic writes until an explicit trigger
  reopens the cycle (an observed successful readback, a Wi-Fi network change, a manual user
  action, or an app/service restart) or a 15-minute fallback interval elapses. `KeepADBService`'s
  `recheckAndEnable()` heartbeat and `ContentObserver` re-enable path both consult the new
  `KeepADB.isAutomaticEnableBackoffBlocked()` gate before writing; `KeepADB.applyNow()` records
  the mismatch/success outcome centrally so both call sites share one decision. The main screen's
  `OFF_KEEP_ALIVE_WAITING` status card gained a fourth `KeepAliveWaitingDetail` outcome
  (`BLOCKED_RECOVERY_BACKOFF`) so the paused state is explained rather than silent; the existing
  manual toggle/tile/widget override still writes immediately and reopens a blocked cycle, so no
  new UI control was needed. Reproduced on real hardware (Redmi Note 8T, Android 13, F-Droid
  review) as ~160 re-enable attempts/minute; the fastlane description now documents the Android
  pairing-dialog prerequisite for unattended recovery (#496).
- Repair within the same unreleased version: the backoff above was proven ineffective on the
  physical S20 (Android 13, fresh never-approved network) -- 186 `recovery_attempt` events in 72
  seconds, in the old ~1.5s cadence, without a single `state_mismatch` or
  `recovery_backoff_active` (#500). Cause: success was decided by the readback taken immediately
  after the write, and Android reports `adb_wifi_enabled` as 1 on that read even when the pairing
  dialog was never confirmed, reverting it to 0 only moments later. Every attempt therefore booked
  a success and reset the cycle; the `ContentObserver`'s own unconditional reset on any observed
  "on" reset it a second time, so the following revert always looked like a fresh, unblocked
  cycle. Success is now decided by survival, not by one read: an accepted automatic enable counts
  as an attempt and blocks immediately, and only a value that is still on after
  `KeepADBRecoveryBackoff.SUCCESS_CONFIRMATION_MS` (3s) releases the block -- logged as a
  `stage=confirmation` success/`state_mismatch` event. The `ContentObserver` now calls
  `KeepADB.noteObservedEnabled()`, which ignores an observed "on" while one of our own attempts is
  still awaiting its verdict and keeps reopening the cycle for a genuinely external enable. New
  `KeepADBRevertingSettingsGateway` test fake reproduces exactly this accepted-then-reverted
  sequence, which `KeepADBStuckOffSettingsGateway` structurally could not (#500).

## [1.8.11] - Unreleased

### Fixed
- First-setup card: the displayed `pm grant` command now uses the actually running
  application ID (`de.hohnepeople.keepadb.debug` in debug builds,
  `de.hohnepeople.keepadb` in release builds) instead of a hardcoded release package
  name, so a fresh debug installation no longer needs a manual command correction (#497).

## [1.8.10] - Unreleased

### Changed
- Home screen first card: the Wireless Debugging on/off switch now sits above the status line
  (on/off/connected/waiting) instead of below it, so it is the topmost interactive element (#493).
  Pure layout reorder -- toggle semantics, persistence, permissions, accessibility text and the
  endpoint/connection info below the status line are unchanged.

## [1.8.9] - Unreleased

### Changed
- Trusted networks reordered as a deliberate security decision (#492). The global "restrict
  Keep-Alive to trusted networks" switch moved back from the home screen into SettingsActivity's
  own collapsible card (collapsed on open, expand state not persisted, as for every other card),
  together with a red warning marker and an explanation that stays readable while the switch is
  off. Its status text remains on the home card, next to the list the policy acts on.
- The restriction is now opt-in and off by default for new or never-initialized installations,
  reversing #260's default. Reason: measurement on the registered S20 (Android 13) showed the
  platform masks SSID and BSSID *together* outside a visible activity -- a running
  `connectedDevice` foreground service does not lift that -- so a restricted installation cannot
  confirm a trusted network in the background at all and automatic re-enable largely stops
  working. The full matrix is in `docs/trusted-networks-measurement.md`.
- Existing installations are not silently widened: an installation that never wrote a mode but
  does hold allowlist entries was running restricted under the old default, so the migration
  persists allowlist mode for it. The decision is written once and never recomputed, so a later
  opt-out cannot be re-migrated by an entry added afterwards. Only the exact stored value
  `allowlist` now enables the restriction; an unrecognized value is not treated as an opt-in.
- Only the manual Wireless Debugging toggle is untouched by all of this, as before -- the policy
  applies to the automatic Keep-Alive re-enable only.

### Added
- Optional SSID allowlist as a second, separate opt-in, off by default (#492), with its own red
  warning about the weaker model at the switch. Matching is exact (no case folding, trimming or
  substring rule) and only ever evaluated for a reading whose BSSID the platform actually
  disclosed, so it never rescues a masked or unknown identity -- unreadable identities stay
  fail-closed. Its real and only benefit is that one entry covers several access points sharing
  that name, which on the test network is measurably the case; that wider allowance is the
  trade-off the warning names.
- SSID management on the home screen's Wi-Fi card, in the same visual logic as the BSSID rows:
  the current network name on top with an "Allow" action, allowed names listed below with
  "Remove". Only the currently connected, fully readable network can be added -- there is no free
  text field and no "add from history", so no entry can widen the allowance beyond the network
  the user is actually on.

### Removed
- The separate "Manage whitelist" entry point (#492), as a redundant second management surface.
  The Wi-Fi card now lists allowlisted access points as rows in their own right -- including one
  that was never observed and is not the current connection, which was the only thing the dialog
  could show that the card could not -- so removing it deduplicates rather than loses reach.
- Settings' "Add current network" / "Remove current network" button, which maintained the same
  BSSID allowlist as the home card's per-access-point rows. The mesh convenience prompt that hung
  off it moved to the card's trust action, still BSSID-by-BSSID and still only for the access
  point the device is actually connected to.
- "Recently blocked" was reviewed against the same redundancy test and deliberately kept: it
  reports denials rather than allowances and lets an access point be allowed after the fact
  without being connected to it, which no other surface does.

### Documentation
- `docs/trusted-networks-measurement.md`: the reproducible foreground/background identity matrix
  for SSID, BSSID, permission state, location services and service state on the S20, including the
  two explicit measurement gaps (no real AP/mesh switch could be forced remotely; single platform).

### Testing
- Unit/semantic coverage for the opt-in defaults, the upgrade migration in both directions and its
  idempotence, exact SSID matching including near-miss names, same-name access points, masked and
  unknown identities, BSSID and SSID management from the card, and the unchanged manual toggle.

## [1.8.8] - Unreleased

### Changed
- Privacy mode now masks the endpoint in the persistent notification and Quick Settings tile as
  well as the existing in-app surfaces. The home-screen widget remains unchanged because it only
  displays the port. New installations default to privacy mode enabled; the toggle still reveals
  the full address when explicitly disabled (issue #488).
- Changing privacy mode now refreshes an already-visible notification and Quick Settings tile
  immediately.
- Patch version bump (1.8.7 -> 1.8.8, versionCode 105): this extends the existing display-only
  privacy behavior without changing stored endpoints, transport, or Wireless Debugging state.

## [1.8.7] - Unreleased

### Changed
- Home screen security card (#484/#485): the "Wi-Fi & access points" card now also holds a
  "Trusted Networks" section (the trust-restriction toggle, its explanation and status text --
  moved here from Settings) and a closing "Security management" section ("Manage whitelist" and
  "Recently blocked", also moved from Settings, with a short explanation of why keeping this list
  accurate matters). The existing trusted-only filter and access point list stay exactly where and
  how they worked before. Settings no longer duplicates any of these three entry points; only
  "Add current network" (unaffected functionally) stays there. No trust/mesh semantics, stored
  data or blocking logic changed -- this is a pure UI relocation and addition (issues #484, #485).
- Patch version bump (1.8.6 -> 1.8.7, versionCode 104): purely additive UI relocation, no stored
  data, transport or blocking-logic changes, so a Patch bump applies.

### Testing
- `MainActivityTrustedNetworkTest` (new): covers the "manage whitelist" dialog's ScrollView
  wrapping and contextual accessibility, the "recently blocked" dialog's listing/allow/empty-state
  behavior (moved from `SettingsActivityTest`, adapted to `MainActivity`), and the trust-
  restriction toggle's permission-rationale flow. `SettingsActivityTest` and
  `KeepADBAccessibilityContractTest` were updated to drop the now-removed Settings ids and assert
  the same behavior no longer duplicated in Settings (issues #484, #485).

## [1.8.6] - Unreleased

### Added
- New `KeepADBAddressMask`: the single display-only rule for the #482 privacy mode. An IPv4 literal keeps its first octet and masks the remaining three (`192.168.1.100` -> `192.*.*.*`), an IPv6 literal keeps its first group and collapses the rest, zone id included (`fe80::1%wlan0` -> `fe80:***`), a registered name (DNS host) is never masked, and the port always stays visible -- per the user decision of 2026-09-18 recorded in issue #483 (issue #483).

### Changed
- The privacy toggle now drives three surfaces through one rule: the endpoint line on the main screen, the webhook address in the main screen's webhook status panel, and the last reported endpoint in that same panel. Toggling re-renders all three immediately instead of waiting for the next discovery tick (`MainActivity.renderEndpoint()` now renders from a cached, unmasked `lastEndpointHost`). There was no separate webhook-only masking switch to retire; the settings screen's webhook input field is deliberately left unmasked because it is the editing field for the stored value (issue #483).
- `KeepADBUrlRedaction` gained `forDisplay(url, privacyMode)`. Privacy mode narrows only the IPv4 host from two visible octets to one; the #350/#378 redaction of userinfo, query, fragment and IPv6 literals is unchanged and still applied first, so this masking is additive rather than a replacement. IPv6 literals inside a webhook URL stay fully masked (`[***]`) in both modes -- privacy mode must never reveal more than the default redaction does (issue #483).
- Patch version bump (1.8.5 -> 1.8.6, versionCode 103): #483 changes rendered text only. No stored value, transport URL or webhook behavior changes, so a Patch bump applies.

### Testing
- `KeepADBAddressMaskTest` (16 cases) covers IPv4 masking, IPv6 masking including zone ids and the leading-`::` case, port visibility for bracketed and unbracketed endpoints, hostname pass-through, and both toggle states on the webhook URL including the legacy IPv4 notation path. `KeepADBPreferencesTest` gained three cases asserting that privacy off leaves every display value byte-identical, that privacy on masks addresses while keeping ports and hostnames, and that a toggle round trip leaves the stored webhook URL and last reported endpoint untouched (issue #483).

## [1.8.5] - Unreleased

### Added
- Main screen header: a new privacy-mode toggle (eye / crossed-out eye icon) sits directly next to the settings button, with a 48dp touch target and a content description naming the action the next tap performs ("Enable privacy mode (hide network addresses)" / "Disable privacy mode (show network addresses)"). Persisted in `KeepADBPreferences` (`isPrivacyModeEnabled`/`setPrivacyModeEnabled`) as a display-only preference -- it never touches `adb_wifi_enabled`, any other persisted original value, or the real ADB transport. The actual masking of displayed network addresses (endpoint host, BSSIDs) reading this flag is left to a follow-up issue (#483) (issue #482).

### Changed
- The settings button's icon changed from the three-dot overflow glyph (`ic_overflow_menu`) to a gear icon (new `ic_settings_gear` vector drawable), so it reads unambiguously as "settings" rather than "more options" (issue #482).
- Patch version bump (1.8.4 -> 1.8.5, versionCode 102): #482 adds a new, purely additive display toggle and swaps one icon -- no breaking change, so a Patch bump applies.

## [1.8.4] - Unreleased

### Changed
- Settings: the first entry (language) and the last entry (version/app info) are now permanently visible and no longer collapsible -- no expand/collapse arrow, no click-to-toggle header -- and are rendered directly on the screen background instead of inside the framed panel card the other, still individually collapsible, settings cards use. The language entry is now marked with a locale-independent globe icon (new `ic_globe` vector drawable), including when "system default" is selected, so the setting stays clearly identifiable without relying on per-language flags in the main view (issue #478).
- `MainActivity`'s Wi-Fi & Access Points card AP rows now focus on SSID and BSSID only; the redundant "Trusted"/"Not trusted" status label and the mesh-count label ("AP X of Y") were removed from each row (`KeepADBAccessPointOverview` still computes the mesh position/count; only the row's display of it was dropped) (issue #479).
- The per-row trust action button now uses the app's primary/affirmative style (`bg_btn_primary`, `title_yellow` text) for "Trust" and keeps the existing red-bordered secondary/warn style (`bg_btn_secondary`, `text_yellow` text) for "Untrust", instead of both actions sharing one button background -- the same primary-vs-secondary distinction the app already draws elsewhere (e.g. Settings' Save/Clear pair). Touch target (48dp min height) and content descriptions are unchanged (issue #480).
- Patch version bump (1.8.3 -> 1.8.4, versionCode 101): #478, #479 and #480 are all UI-focus/layout/styling refinements on existing surfaces, no new capability or behavior change beyond layout/visibility, so a Patch bump applies. All three are integrated and released together as one build.

### Added
- A "Show trusted access points only" filter switch above the Wi-Fi & Access Points list. When enabled, both the current connection row and the "others" list are filtered down to `trusted == true` entries; disabled (the default) keeps the previous unfiltered behavior. In-memory only, like the existing "show more/less" expansion state -- not a persisted setting (issue #479, acceptance criterion 3, per the user decision recorded in the issue's comment).

## [1.8.3] - Unreleased

### Fixed
- `SettingsActivity`'s blocked-networks "Allow" dialog, the manual "add current network" button and the mesh-BSSID convenience prompt all trusted an access point through a bare `KeepADBTrustedNetwork.addBssid()`/`clearPromptState()` pair, so none of them attempted the connection the untrusted network had been blocking -- the user still had to wait for Keep-Alive's next pass or open the pushdown notification. All three now go through `KeepADBReceiver.trustBssidAndAttemptConnect()`, the same entry point `MainActivity`'s per-access-point trust button already uses (#470), so trusting a network from any of these three places in Settings immediately attempts the connection and cancels/clears any showing trust-prompt notification for that BSSID (issue #475).

## [1.8.2] - Unreleased

### Fixed
- `KeepADBNetworkTrustPrompt.clearPromptState()` cleared the entire BSSID prompt history instead of only the just-trusted access point's entry. Trusting a historical AP A from `MainActivity`'s Wi-Fi & Access Points card (or from `SettingsActivity`'s blocked-networks "Allow" dialog) while a different untrusted AP B had a pending trust-prompt notification silently cancelled B's notification and cleared its prompt state too, so B would never be re-prompted even though it had never actually been trusted. Introduced a BSSID-scoped `clearPromptState(Context, String)` and switched both call sites (`KeepADBReceiver.trustBssidAndAttemptConnect()` and `SettingsActivity`'s Allow button) to it; the untargeted `clearPromptState(Context)` has no remaining production caller. `SettingsActivity`'s Allow button still uses its existing manual (non-immediate) connect flow -- consolidating it onto `trustBssidAndAttemptConnect()` remains out of scope here and is tracked as issue #475 (issue #474).

### Changed
- The 18 non-English `values-*/strings.xml` locales were re-condensed to match the density #469 already applied to the eight shortened English onboarding/status strings (`setup_body`, `notification_permission_panel_body`, `battery_optimization_body`, `location_permission_panel_body`, `location_permission_panel_fallback_body`, `settings_hide_notification_subtext`, `settings_hide_notification_subtext_keepalive`, `advice_banner_text`). Each locale got an independent, language-native condensation rather than a literal translation of the new English text, and no safety- or action-relevant fact (VPN/Tailscale hint, unexpected-pairing warning, no-auto-reenable-without-location-permission, Keep-Alive's forced-notification behavior) was dropped in the process (issue #473).
- Patch version bump (1.8.1 -> 1.8.2, versionCode 99): both changes are bugfix/refinement work on existing surfaces, so a Patch bump applies. #473 and #474 were developed independently and are released together as one integrated build.

## [1.8.1] - Unreleased

### Changed
- The main screen's "Wi-Fi & Access Points" card no longer hard-caps the list at 6 entries (`KeepADBAccessPointOverview.MAX_ITEMS`). `buildItems` now returns the complete observation list; `MainActivity` shows at most 5 "other" access points by default and reveals the rest behind a "show N more"/"show less" toggle that only appears when there is actually something to hide, so short lists and the empty state stay interaction-free. The current connection keeps its own row above the collapsible list, so its connection/trust state never moves or disappears while expanding or collapsing (issue #468).
- Densified the main screen: `bg_panel` padding 20dp -> 16dp, inter-panel and internal margins 16dp/12dp -> 12dp/8-10dp across the advice banner, status, webhook and Wi-Fi & Access Points panels. The onboarding/status panel descriptions (setup, battery optimization, notification/location permission, advice banner, persistent-notification subtext) were trimmed to what the respective action needs. Status, error and action strings are unchanged (issue #469).
- Trusting an access point from `MainActivity`'s Wi-Fi & Access Points card now reuses `KeepADBReceiver.trustBssidAndAttemptConnect()` -- the same path the trust notification's "allow" action already takes -- so it immediately attempts the connection the untrusted network was blocking and cancels any showing prompt, instead of requiring the user to open the pushdown notification first. The Settings screen was reordered accordingly: the trusted-network allowlist panel now sits right after Language/Webhook and before the USB-ADB block, with the permanent-notification panel directly beneath it (issue #470).
- Every Settings card now starts collapsed behind a clickable +/- header and expands independently of the others. The expand state lives only in the live View tree (no `SharedPreferences`, no `onSaveInstanceState`), so reopening Settings always starts collapsed. The permission-warning banner stays excluded -- it is a conditional safety notice, not an option card (issue #471).
- Patch version bump (1.8.0 -> 1.8.1, versionCode 98): all four changes refine existing surfaces rather than introducing new ones, so a Patch bump applies. `versionCode` skips nothing; #468, #469, #470 and #471 were developed independently and are released together as one integrated build.

## [1.8.0] - Unreleased

### Fixed
- MainActivity's status card no longer shows the generic "Keep-Alive is waiting for the network" text while Wi-Fi is actually connected but Keep-Alive's automatic re-enable is blocked by the trusted-network allowlist or an unreadable network identity (missing Location permission) — it now shows two distinct messages for those cases instead of looking like KeepADB failed to notice a live connection. Added `MainActivity.resolveKeepAliveWaitingDetail(Context)` and the `MainActivity.KeepAliveWaitingDetail` enum, which reuse the existing `KeepADBService.isWifiConnected` and `KeepADBTrustedNetwork.getBlockReason` checks the automatic re-enable path already gates on, so the status text can never disagree with why Keep-Alive itself didn't re-enable (issue #458).

### Added
- MainActivity now surfaces a dedicated onboarding panel when the trusted-network allowlist (#260 default) is active but `ACCESS_FINE_LOCATION` is missing -- previously the permission was only ever requested from `SettingsActivity`'s manual toggle, so a fresh install/first run could get stuck in `identity_unavailable` forever with no way to notice or fix it from the main screen. The panel explains the requirement, lets the user grant the permission directly (system dialog via the existing `requestPermissions`/`onRequestPermissionsResult` pattern already used elsewhere in this activity and in `SettingsActivity`), and -- once a request has been made and the permission is still missing (denied, including "don't ask again") -- offers a one-tap fallback to switch to "trust all Wi-Fi networks" instead of leaving Keep-Alive stuck (issue #459).
- The untrusted-network prompt (#446, #450) now also fires while Wireless Debugging is already active: roaming onto a new, untrusted access point used to raise nothing until the connection eventually dropped and the silent auto re-enable block kicked in. `KeepADBService`'s network callback now checks trust on every Wi-Fi network-available/roam event regardless of the current on/off state, reusing the exact same throttled, BSSID-keyed prompt (issue #460).
- When the current network's identity can't be read at all (`KeepADBNetworkIdentity#isKnown()` false -- typically a missing/revoked `ACCESS_FINE_LOCATION` grant or disabled location service), the block is no longer swallowed silently (`outcome=skipped detail=identity_unavailable`). It now raises its own notification naming the problem, throttled the same way as the trust prompt under a fixed sentinel key, with a content intent that opens the app's permission page or the system location toggle directly depending on the likely cause (issue #460).
- Added a "Wi-Fi & Access Points" card to the main screen showing the currently connected access point (SSID, BSSID, trust status) highlighted on top, and recently observed access points from the existing `KeepADBBssidHistory` mesh observation log listed below. Access points sharing an SSID (e.g. FRITZ!Mesh repeaters) are labeled "AP x of y" so their distinct BSSIDs are visible instead of looking like duplicates. Each entry has a quick trust/untrust action wired directly to `KeepADBTrustedNetwork`, the same allowlist entry point Settings already uses -- no new source of truth was introduced, and `KeepADB` itself still never references the allowlist (#245 contract unaffected). Presentation logic (ordering, mesh grouping, trust matching, the fixed display cap) is factored into a new `KeepADBAccessPointOverview` class, unit-tested without Robolectric. `KeepADBBssidHistory` gained a read-only `getRecentObservations()` accessor for this; its stored data format is unchanged (issue #461).

### Changed
- Minor version bump (1.7.4 → 1.8.0, versionCode 97): new user-facing surfaces, not only bugfixes to existing behavior, so a Minor bump over Patch is warranted. `versionCode` skips 96 because four independently developed changes (#458, #459, #460, #461) are released together as one integrated build.

## [1.7.4] - Unreleased

### Changed
- Inverted the "Hide persistent notification" toggle's framing to "Persistent notification" (positive framing: switch ON = notification visible, switch OFF = hidden) in both the main screen and Settings. The previous negative "hide" framing read as broken/useless in the most common case (Keep-Alive active), where Android forces the foreground-service notification regardless of the setting. Only the UI binding and labels changed; the underlying preference key, its accessor names, and the Keep-Alive notification override are unchanged (issue #456).

## [1.7.3] - Unreleased

### Fixed
- Fixed a test (`KeepADBNotificationRobolectricTest.notificationShowsDisabledWaitingWhenWirelessDebuggingDropsMidDiscovery`) that claimed to cover the mid-discovery drop from #448 but never actually exercised that code path; it now drives a real discovery attempt (via the existing `KeepADBFakeNsdProbe`/`KeepADBFakeScheduler` seams plus a new `KeepADBNotification.setEndpointForTesting()` test hook) and flips Wireless Debugging off while it is still in flight. No production behavior changed (issue #453).

## [1.7.2] - Unreleased

### Fixed
- Fixed notification showing placeholder "searching" text instead of "disabled, waiting" when Wireless Debugging turns off mid-discovery while Keep-Alive is active (issue #448).

## [1.7.1] - Unreleased

### Fixed
- Fixed network trust prompt repeatedly re-firing when roaming or flapping between multiple untrusted Wi-Fi access points. The prompt suppression now maintains a bounded history of recently prompted networks across all untrusted access points rather than only remembering the single most recent one (issue #450).

## [1.7.0] - Unreleased

### Highlights
- Keep-Alive now explains why an untrusted Wi-Fi access point blocked automatic re-enabling and
  offers a one-tap way to trust it; recently blocked networks remain reviewable in Settings.

### Added
- Added a prompt notification when Keep-Alive is blocked from automatically re-enabling Wireless
  Debugging because the current Wi-Fi network or access point isn't on the trusted allowlist yet.
  The notification names the network and offers "Allow" (adds the access point to the allowlist
  and re-enables Wireless Debugging immediately, if still on that network) and "Block" (dismisses
  the prompt; nothing is trusted or blocklisted). The prompt is throttled per access point for 6
  hours to avoid repeat alerts on every heartbeat, in all 19 supported languages (issue #446).
- Added a "Recently blocked networks" view under Trusted Networks in Settings, listing access
  points that recently blocked automatic re-enable, newest first, with a per-entry "Allow" action
  to trust them retroactively (issue #446).

## [1.6.5] - 2026-09-14

### Fixed
- Fixed the endpoint notification freezing on its last "Wifi-ADB ENABLED" content when Wireless
  Debugging turns off on its own (roam, inactivity timeout, or AP loss) while Keep-Alive keeps the
  foreground service running and waiting for it to come back. Android silently ignores a cancel on
  an active foreground-service notification, so it now shows the true "Wifi-ADB DISABLED,
  Keep-Alive waiting" state instead, in all 19 supported languages. The same stale-content gap
  also affected the "Hide persistent notification" toggle and is fixed there too (issue #445).

## [1.6.4] - 2026-09-13

### Fixed
- Fixed notification freezing on placeholder "KeepADB: Endpoint searching..." when "Hide persistent notification" is active together with Keep-Alive. When Keep-Alive is active, Android enforces a foreground service notification; the notification now properly updates with the active endpoint once discovery finishes, rather than attempting a cancel that Android ignores. If Keep-Alive is inactive, the notification is cancelled and hidden as before (issue #443).

### Added
- Added "Hide persistent notification" toggle directly on MainActivity under the Keep-Alive section, making the connection between the background service and notification behavior immediately visible. Added dynamic subtext clarifying that Android enforces foreground service notifications during Keep-Alive and complete hiding is only possible via system channel settings across all 19 supported languages (issue #443).

## [1.6.3] - 2026-09-13

### Changed
- Cleaned up `settings_webhook_subtext` across all 19 localizations to remove the obsolete
  mention of USB endpoint reuse on DELETE requests, reflecting pure retry behavior on failure (issue #440).
- Cleaned up README and Fastlane descriptions to remove obsolete references to USB-ADB reachability
  reporting via webhook (issue #441).

## [1.6.2] - 2026-09-13

### Removed
- USB-ADB reachability reporting to `phone-register-server`. The app no longer reports
  USB connections/disconnections to the webhook endpoint, eliminating cross-protocol
  cleanup coordination and unused USB webhook preferences and status fields. The USB
  notification and USB->WLAN handover features remain fully intact (issue #438).

## [1.6.1] - 2026-09-13

### Changed
- The periodic re-verification of an already-cached WLAN-ADB endpoint in
  `KeepADBNotification` (#394) now uses the plain-connect
  `KeepADBEndpoint.isPortReachable()` instead of the TLS-sniff `probeAdbTlsPort()` -
  consistent with the #412 decision already applied to the mDNS discovery path. #404
  showed the TLS-sniff never actually matches genuine adbd on real devices, so it could
  never positively confirm a cached endpoint - only ever return "not reachable" and force
  a fresh rediscovery even when adbd was still there and reachable. Falling back to
  plain-connect restores the cache's actual purpose: a real, previously-working endpoint
  that answers on a periodic heartbeat tick is now confirmed and kept, avoiding an
  unnecessary mDNS rediscovery cycle. As with the mDNS path, this accepts any TCP
  responder on the cached host:port, not only genuine adbd - the same documented
  trade-off as #412/#424 (issue #435).

### Removed
- `KeepADBEndpoint.probeAdbTlsPort()`, `looksLikeAdbTlsResponse()`, and
  `PROBE_CLIENT_HELLO` - dead code once the cached-endpoint re-verification above (their
  last caller) stopped using them (issue #435).

## [1.6.0] - 2026-09-13

This is a minor release rather than a patch: the USB register-webhook contract with
`phone-register-server` changes in a way that makes a pre-1.6.0 app unable to speak correctly
with an already-updated server (see below), on top of a larger-than-usual batch of independent
fixes accumulated across 1.5.64/1.5.65.

### Fixed
- USB-ADB webhook reports carried no register-contract envelope, so `phone-register-server`
  rejected every endpoint-free USB registration and every USB deactivation with HTTP 426
  (`Endpoint-free USB events require register contract version 2`). The USB half of the
  hybrid register model was therefore inert against a contract-v2 register. `buildUsbPayload()`
  now declares `contract_version: 2` and supplies the ordering/idempotency envelope
  (`event_id`, `observed_at`) the register requires for the endpoint-free USB slot. The event id
  is derived from the reported state instead of being random, so a repeated identical report
  stays a duplicate the register can drop, while a real state change - including the transition
  from active to inactive - produces a new event the register accepts. The "nothing changed, do
  not resend" check now compares those state-derived event ids rather than whole payloads,
  because the payload additionally carries a volatile `observed_at` (issue #416).

### Removed
- The local loopback port-range "quick probe" (#314/#363/#366) that used to run alongside mDNS
  discovery as a best-effort shortcut. Three independent real-device measurements (#404) showed
  its TLS-sniff confirmation (`probeAdbTlsPort()`) never gets a matching reply from genuine
  adbd, so it never delivered an early confirmation -- only up to ~1.2s of worst-case added
  latency per connection attempt (two connect()/read() timeouts of up to 150ms each, times up to
  8 candidate ports). mDNS discovery is unaffected and is now the sole discovery path, as it
  already was in practice after the #412 repairs. `probeAdbTlsPort()` itself stays: it is still
  used by `KeepADBNotification`'s periodic re-verification of an already-cached endpoint (#394),
  which is unaffected by this removal (issue #424).

## [1.5.65] - 2026-09-13

### Fixed
- `KeepADBUsbHandover.isAutoHandoverStillPermitted()` re-checked only the trusted-network
  allowlist and the active Wi-Fi transport before the debounced automatic USB handover write --
  not the USB-WLAN handover mode itself. Switching the mode from AUTOMATIC to OFF while a
  delayed enable was still pending in the `TOGGLE_COOLDOWN_MS` window let that enable go through
  once on an otherwise still-trusted network. The guard now also re-reads the current mode at
  write time, matching the re-check the other automatic enable paths already apply to their own
  conditions (issue #383).
- `KeepADBNetwork.get(Context)`'s process-wide test singleton kept returning its first instance
  to every later caller for the rest of the JVM's lifetime, silently ignoring the `Context`
  argument. `KeepADBService.isWifiConnected()` and two `KeepADBEndpoint` methods call
  `KeepADBNetwork.get(context)` internally, so any Robolectric test exercising those methods
  instantiated the singleton incidentally, without the test author ever mentioning
  `KeepADBNetwork`. A later, unrelated test in the same JVM could then receive an instance bound
  to a foreign `Context`/`ConnectivityManager` shadow, causing intermittent, test-order-dependent
  failures (observed in ~2 of 3 full `./bin/verify` runs; issue #401). Added a shared
  `KeepADBNetworkResetRule` JUnit rule that all 20 Robolectric test classes now apply, resetting
  `KeepADBNetwork`'s singleton both before and after every test regardless of whether that test
  touches `KeepADBNetwork` directly, closing the gap structurally instead of relying on each test
  author to remember. No production code behavior changed.
- Corrected outdated security/behavior claims in `SECURITY.md`, `README.md`, and the webhook
  help text (all languages): the trusted-network allowlist has defaulted to `MODE_ALLOWLIST`
  since 1.5.5, so docs describing it as "opt-in"/"off by default" were stale — they now describe
  the allowlist as on by default with the old "all Wi-Fi networks" behavior as an explicit
  opt-out. The claim that automatic re-enable "never overrides an explicit manual OFF" is now
  phrased as a property of the current intent-tracking implementation (tested as of issue #309)
  rather than an unqualified absolute guarantee. The webhook help text no longer promises an
  unconditional `DELETE` on every shutoff — it now says the app attempts one, retrying later on
  failure, and skipping it while USB still uses the same endpoint. README's endpoint-discovery
  timing ("within 1-2 seconds") is now phrased as a typical, non-guaranteed figure rather than a
  fixed bound (issue #320).

### Removed
- Removed unused order-key constant definitions `KEY_WEBHOOK_PENDING_CLEANUP_ORDER` and
  `KEY_USB_WEBHOOK_PENDING_CLEANUP_ORDER` that were never read and risked silent drift if
  `orderKeyFor()` was refactored (issue #413).

### Added
- Added a source-based contract test pinning the statement order in
  `KeepADBTrustedNetwork.setVerifiedTrustObserverActive()` (deactivate, forget verified trust,
  then apply the requested state), following the same pattern already used elsewhere in
  `KeepADBTrustedNetworkContractTest`. The ordering is what keeps the method race-free per #354's
  own reasoning, but nothing previously pinned it, so a later reorder would have passed all unit
  tests while silently reintroducing the race (issue #375).

## [1.5.64] - 2026-09-13

### Fixed
- Clarified that the quick probe's per-candidate timeout constant is applied twice in
  `probeAdbTlsPort()` (connect and TLS-sniff read), so the actual worst-case budget per
  candidate is up to ~2x the configured value, not the value itself (issue #411).
- Investigated whether the mDNS-resolved candidate address check should adopt the same TLS-sniff
  verification issue #363 and #394 already use elsewhere, instead of only confirming that the
  host:port accepted a TCP connection. Real-device measurement for issue #404 showed the TLS
  sniff never gets a TLS-shaped response from genuine adbd, so switching the mDNS check to it
  would have removed the only working endpoint-discovery path without closing any real gap; the
  mDNS check therefore intentionally keeps its plain TCP-connect check for now (tracked as an
  open architecture question in issue #424). The three related timeout budgets for this class of
  reachability probe (quick probe, mDNS candidate verification, cached-endpoint re-verification)
  are documented together at their declaration and kept intentionally separate, since each guards
  a different call site with its own latency tolerance (issue #412).
- The byte-match fallback in `KeepADBNetwork.resolveScopeInterface()` (added for issue #403)
  handed back the first interface whose address list contained the searched-for link-local
  address, with no regard for whether that interface has anything to do with the tracked Wi-Fi
  network. A MAC-derived `fe80` link-local address can legitimately be bound to two interfaces
  at once, so "first wins" could stamp the wrong one and cause a legitimate candidate to be
  rejected. The scan is now restricted to interfaces that are up and not loopback, and an
  ambiguous match (more than one such interface claiming the identical address) is now treated
  as unresolvable instead of guessed (issue #410).
- `KeepADBNetwork.getWifiIpv4Address()` had no synchronous fallback at all, so a call made in the
  same process-startup window #390 identified (the Wi-Fi network callback is registered but its
  first delivery is still pending) always returned no address even on a connected Wi-Fi network.
  It now falls back to the same synchronous `WifiInfo` snapshot already used elsewhere, exactly
  while the tracker's callback view is not yet authoritative (issue #396).

### Added
- A test now covers the #368 FIFO backlog's migration path: legacy `StringSet` entries written by
  an app version predating #368, with the ordered shadow key absent, seeded directly into
  preferences. It proves reading them loses no entry and that a subsequent overflow still evicts
  the correct (reconstructed) oldest entry instead of growing past the bound or corrupting the
  backlog (issue #414).

### Changed
- No functional change: measured the #310 `EnableGuard` re-check (the `WifiManager`
  connection-info lookup now run inside the same `synchronized (KeepADB.class)` block as the
  `Settings.Global` write) on real hardware. Seven on-device samples of the guard call itself
  came back at 32-42 microseconds, negligible next to the existing multi-hundred-millisecond
  lock hold time of the recovery pulse it already shares the lock with. No caching or lock-scope
  reduction applied; see issue #341 for the full measurement writeup.
- Real-device measurement (Galaxy S20 FE, Android 13) of the quick probe's TLS sniff (#363)
  against genuine adbd on the actual `_adb-tls-connect` port found it never returns a TLS-shaped
  response to an unpaired client's ClientHello -- neither this app's own minimal hello nor a full
  standards-compliant one built by OpenSSL. adbd either holds the connection open until the probe
  times out or closes it with an empty reply, so the sniff currently never positively confirms a
  real endpoint; it still safely returns "no match" either way, so no incorrect endpoint has ever
  been accepted. The measured outcome (timeout / empty close / TLS-shaped match) is now logged per
  attempt so this can be observed live instead of only inferred (issue #404).

## [1.5.63] - 2026-09-13

### Fixed
- A link-local IPv6 scope mismatch could silently fall back to a scope-blind address comparison
  when the device's own network interface could not be resolved by name, quietly reopening the
  spoofing gap issue #364 had closed. The fallback is now visible as a diagnostics event, the
  device's own interface is now also resolved by scanning for the matching address when the name
  lookup fails, and the intentionally permanent scope-blind fallback on the candidate side is
  documented as such (issue #403).
- Stopping an active quick-probe scan could leave the scan thread running for up to roughly
  600ms per already-in-flight candidate before it noticed the stop request, because the previous
  connect and TLS-sniff timeouts were generous. Both are now tighter, so a stop request is
  honored noticeably sooner (issue #366).
- The pending webhook cleanup backlog evicted the newest entry on overflow instead of the oldest
  one, discarding the endpoint most likely to still be a live orphan registration while keeping
  the one most likely to be long gone. Eviction now removes the oldest entry, and pending
  cleanups are persisted in their original order so this survives an app restart (issue #368).
- The periodic re-verification of an already-registered wireless-debugging endpoint only checked
  whether the host:port still accepted a TCP connection, not whether the answering service still
  looked like adbd. A different service later taking over the same host:port would have kept
  being treated as the valid endpoint. Re-verification now uses the same TLS-sniff check already
  used when a new endpoint is first registered (issue #394).

## [1.5.62] - 2026-09-13

### Fixed
- Right after the app starts, the Wi-Fi connection check could report "no Wi-Fi" on a perfectly
  connected network: the network tracker's callback registers immediately, but the system delivers
  its first update a moment later, and that short window was already treated as a reliable "no".
  Since the check gates automatic re-enabling, the USB handover and the tile/notification state,
  the first check after a start could wrongly do nothing. The synchronous fallback is now used
  until the callback has actually reported once (review repair on issues #352/#390).
- A verification worker that could not be started at all (e.g. when the system refuses new
  threads) left endpoint verification permanently switched off for the rest of the app's run,
  because the "already running" marker was never cleared again (review repair on issue #351).

## [1.5.61] - 2026-09-13

### Fixed
- The synchronous best-effort Wi-Fi address fallback used right after app start (before the
  network callback has fired for the first time) or when the callback failed to register at all
  now also covers an IPv6-only Wi-Fi network. It used to only ever check for an IPv4 address
  (`WifiInfo` has never exposed IPv6), so a wireless-debugging endpoint on an IPv6-only network
  could be briefly rejected during that race window even though a valid address was reachable. A
  dual-stack network is unaffected -- IPv4 is still preferred exactly as before (issue #365).

## [1.5.60] - 2026-09-13

### Fixed
- The Wi-Fi endpoint address check now also compares the resolved network interface for a
  link-local (`fe80::/10`) IPv6 candidate, not just its numeric bytes. A device reachable on a
  different real interface (cellular, USB tethering, or an attacker-controlled VPN endpoint) that
  numerically collides with this device's own Wi-Fi link-local address is now rejected instead of
  being indistinguishable from the real endpoint. Our own link-local endpoint stays accepted
  whenever either side's resolved scope is unavailable, which is still routinely the case for
  addresses handed back by mDNS discovery (issue #364).

## [1.5.59] - 2026-09-13

### Fixed
- Repeated cached-endpoint re-verification triggers (the 60s heartbeat and a Wi-Fi roam callback
  can both land within a short window) no longer each start their own background worker thread
  and socket check. A verification already in flight now coalesces further triggers instead of
  running concurrently alongside it; the #315 verification token still guarantees a superseded
  check cannot mutate the cached endpoint after a newer event has already changed it (issue #351).

## [1.5.58] - 2026-09-13

### Fixed
- The endpoint address check no longer accepts a stale Wi-Fi address: once the network callback
  is registered and has actually reported the current state, a just-dropped address still handed
  out by the system's synchronous Wi-Fi snapshot is rejected as a wireless-debugging endpoint
  instead of being accepted additively. The snapshot keeps covering the two cases where the
  tracker genuinely knows nothing yet -- right after app start, before the first callback fires,
  and when callback registration failed altogether (issue #390).

## [1.5.57] - 2026-09-12

### Fixed
- The quick probe's Wi-Fi-address verification (#314) now also requires a TLS-shaped response
  before accepting a candidate port, closing a residual gap where a foreign, unrelated service
  bound to `0.0.0.0` (all interfaces) could answer on the Wi-Fi address too and be mistaken for
  adbd's listener; a plain successful TCP connect no longer suffices (issue #363).

## [1.5.56] - 2026-09-12

### Fixed
- `discover()` now releases the Wi-Fi multicast lock it holds during mDNS discovery if an
  exception is thrown after acquiring it but before the overall-timeout watchdog is armed
  (e.g. a failed background-probe thread start); previously such an exception left the lock
  held indefinitely, since the timeout that would otherwise release it never got scheduled
  (issue #359).

## [1.5.55] - 2026-09-12

### Fixed
- `setMode()` now invalidates the in-process verified-trust cache, matching what `remove()`
  already did: switching the trusted-network policy (e.g. `ALLOWLIST` -> `ALL_WIFI` ->
  `ALLOWLIST`) used to leave a stale "trusted" cache entry in place, which could let a
  masked-BSSID background reading fail open onto a rogue access point impersonating a
  previously trusted SSID instead of forcing a fresh BSSID verification (issue #353).

## [1.5.54] - 2026-09-12

### Fixed
- The Wi-Fi connectivity check no longer lets a stale, synchronous `WifiInfo` snapshot override
  the live network tracker's authoritative "not connected" answer; the snapshot is now only
  trusted as a fallback when the tracker's callback truly failed to register (issue #352).
- Resolving the currently active Wi-Fi address, when more than one eligible Wi-Fi network is
  tracked at once, is now deterministic instead of depending on unspecified map iteration order
  (issue #352).

## [1.5.53] - 2026-09-12

### Fixed
- Automatic re-enable (Keep-Alive service and USB WLAN handover) now requires an actually
  connected Wi-Fi transport in addition to the trusted-network policy; `MODE_ALL_WIFI` alone
  no longer auto-enables while Wi-Fi is disconnected in the background (issue #348).

## [1.5.52] - 2026-09-12

### Fixed
- Recovery pulses now abort on interrupted waits, rejected disable writes, or changed
  network/trust context before restoring wireless debugging, and diagnostics distinguish
  changed preconditions from newer user intent (issue #347).

## [1.5.51] - 2026-09-12

### Fixed
- USB webhook cleanup now loads the persisted WLAN registration snapshot before applying the
  cross-protocol guard after a process restart (issue #370).

## [1.5.50] - 2026-09-12

### Fixed
- Control-bearing legacy webhook URLs are rejected before they can reach the HTTP transport;
  redacted UI and log output continues to escape such input (issue #379).

## [1.5.49] - 2026-09-12

### Fixed
- Wi-Fi handover cleanup no longer treats a late loss of the old network as a loss of the still
  active new network (issue #349).
- Cross-protocol cleanup now fails closed for partially persisted peer snapshots, and successful
  registration removes equivalent legacy cleanup entries from both protocol queues (issues #369,
  #370, #377).
- Legacy pending URLs with an '@' in userinfo are sanitized completely before an outgoing retry
  (issue #377).

## [1.5.48] - 2026-09-12

### Fixed
- Pending webhook cleanup retry state is now persisted reliably, so backoff, expiry and the
  attempt limit prevent repeated network requests (issue #369).

## [1.5.47] - 2026-09-12

### Fixed
- Control characters in redacted webhook display and log output are now escaped (issue #379).

## [1.5.46] - 2026-09-12

### Fixed
- IPv4 webhook host masking now covers decimal, hexadecimal and trailing-dot notation while
  keeping DNS names visible (issue #378).

## [1.5.45] - 2026-09-12

### Fixed
- Legacy pending webhook cleanups no longer send stored URL userinfo when retried (issue #377).

## [1.5.44] - 2026-09-12

### Fixed
- USB-ADB webhook failures are now shown in the main-screen webhook status alongside the existing
  WLAN-ADB status (issue #371).

## [1.5.43] - 2026-09-12

### Fixed
- WLAN and USB webhook cleanups no longer clear a live registration from the other protocol when
  both use the same register URL (issue #370).

## [1.5.42] - 2026-09-12

### Fixed
- USB webhook deactivation with an empty URL now clears stale local and persisted registration
  state, records an explicit inactive status and notifies listeners; a genuine no-op remains a
  no-op (issue #372).

## [1.5.41] - 2026-09-12

### Fixed
- Pending register cleanups now expire, back off between attempts and stop after a bounded
  retry budget, so an unreachable old host cannot delay later register transactions indefinitely
  (issue #369).

## [1.5.40] - 2026-09-12

### Fixed
- Wi-Fi network loss now invalidates the local endpoint before queueing remote WLAN register
  cleanup; failed cleanup remains retryable and is surfaced to register-state listeners (issue #349).
## [1.5.39] - 2026-09-10

### Fixed
- Accessibility, multi-state and resource contract suites check Activity/Dialog views after
  lifecycle and layout, refresh/settings/profile action effects, widget PendingIntent clicks
  in all five states, and visible vector rendering through Robolectric (issue #325).
- Resource contracts validate compiled values and format arguments in all 18 locale buckets;
  an explicit guard covers Robolectric 4.13's Indonesian fallback and AAPT2 dump decoding.
  The local suite contains 421 tests (420 on the initial PR head, not 413). App behavior and
  version 1.5.39 / code 58 are unchanged by this test repair.

## [1.5.38] - 2026-09-10

### Fixed
- A rejected wireless-debugging setting write no longer leaves the app's remembered intent
  claiming that the requested state was applied (issue #333).
- Rapid manual OFF/ON toggles now receive only a short technical teardown gap, preserving the
  immediate manual behavior without restoring the former 1500 ms debounce (issue #335).

> `1.5.37` / version code `56` is retained as a documented gap: no matching release tag or
> Fastlane changelog is present in this repository. No release entry is inferred.

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
- Unified CI and release workflows: restricted documentation triggers, added `check-i18n` gate parity, unified release build to JDK 17, and added tag and changelog consistency checks (issue #327).

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
- Historical release note: release and signing documentation distinguished the published `v1.4.3`
  from the then-current `1.4.4` development snapshot; `v1.4.4` was subsequently published.

## [1.4.3] - 2026-08-30

### Added
- Optional GitHub issue reporting from Settings with a prefilled, editable issue template and placeholders for additional user notes.
- An explicit opt-in for including redacted diagnostic data; the complete draft is shown to the user before the external GitHub page is opened.
- Localized issue-reporting texts for all 19 supported languages without hardcoded visible literals.

### Changed
- The report dialog now keeps the editable draft in a compact, scrollable preview with visible actions and readable section breaks.
- Opening GitHub remains an explicit user action; no issue or diagnostic data is uploaded automatically by the app.

## [1.4.2] - Retrospective (not published)

### Added
- Resource contract coverage for locale key parity, format arguments, and visible UI literals.

## [1.4.1] - Retrospective (not published)

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
- These entries preserve historical issue-version decisions. The intermediate bumps below were not
  published as separate tags or releases and must not be retroactively renumbered to normalize the
  old classification, because later version codes and metadata refer to this history.

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
