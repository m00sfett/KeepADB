# Runs

## 2026-08-22 — F-Droid preparation handoff

- **Purpose:** Record the project-specific preparation for the official F-Droid catalog as
  a clear handoff for the product agent.
- **Changes:** Added `F-DROID-PREPARATION.md`. The handoff covers publication inventory,
  license/dependency/data-flow findings, release/build evidence, and a proposed F-Droid
  metadata file.
- **Not performed:** no builds, tests, repository visibility change, uploads, or merge
  requests.
- **Next step:** Create the findings report at `notes/f-droid-intake-report.md`.

## 2026-08-21 — License change

- Replaced the repository license with the official GNU Affero General Public License v3.0, or (at your option) any later version.
- Updated the README badge and license section.
- Updated the changelog license entry.
- Verification: `cmp` against `/usr/share/licenses/spdx/AGPL-3.0-or-later.txt` and `git diff --check` passed.

## 2026-08-21 — Issue orchestrator selection

- Read-only inventory of the current GitHub issues, PRs, runs, workflows, branch-protection
  response, and drift status completed.
- Selected issue #130 as the next private-hardening package after verifying its actual
  `BootReceiver`/manifest call path.
- No implementation, build, test, device, workflow, or GitHub write action performed.
- Persistent plan updated with `not approved` status and typed gates.

## 2026-08-21 — Issue #130 implementation

- Full user approval received for implementation, local gates, and device validation.
- Restricted `BootReceiver` to system boot, removed `QUICKBOOT_POWERON`, and made foreground
  service start failures explicit and non-successful.
- Added `KeepADBBootReceiverContractTest`; validation and independent S4 review remain open.

## 2026-08-21 — Issue #130 review repair

- Independent S4 review 1 returned `NOT APPROVED` because asynchronous FGS promotion could
  still be followed by boot re-enable and because the static contract test had false positives.
- Repaired the ordering with a post-promotion re-enable path and a pre-foreground callback guard;
  tightened the contract test.
- Repeated `git diff --check`, unit tests, lint, and debug build successfully. Review 2 and the
  subsequent device gate remain open.

## 2026-08-22 — Issue #130 circuit breaker

- Independent S4 review 2 returned `NOT APPROVED`: pre-foreground heartbeat/state mutations and
  an unguarded network-loss callback remain; the static test does not prove ordering.
- Circuit breaker activated after two consecutive independent review failures. No third repair,
  review, or device gate started.
- Plan records three scoped options; status is `closed-pending-decision`.

## 2026-08-22 — Issue #130 option 1 repair

- User selected option 1: foreground promotion before recovery initialization.
- Moved heartbeat, user-intent consumption, observers, network callback, and ticker setup after
  successful `startForeground`; guarded late network-loss callbacks and strengthened ordering
  assertions in the static contract test.
- Local gates and a fresh independent S4 review remain required before device validation.

## 2026-08-22 — Issue #130 review 3 circuit breaker

- Independent S4 review 3 returned `NOT APPROVED`: failed FGS promotion on service re-entry is
  not fail-closed and the static test does not guard cleanup/order.
- No fourth review, automatic repair, or device gate started. Plan records three new options;
  status is `closed-pending-decision`.

## 2026-08-22 — Issue #130 fail-closed repair

- User selected fail-closed startup cleanup.
- Added cleanup for failed FGS promotion and service re-entry, including callback/ticker
  teardown, notification removal, and evaluated `stopSelfResult`.
- Repeated local diff, unit, lint, and debug-build gates successfully. Independent S4 review 4
  remains required before device validation.

## 2026-08-22 — Issue #130 review 4 scope circuit breaker

- Independent S4 review 4 returned `NOT APPROVED` with a high cross-component lifecycle finding:
  endpoint, notification, and webhook callbacks can outlive FGS re-entry cleanup.
- No fifth review, automatic repair, or device gate started. Plan records three new scope/
  architecture options; status is `closed-pending-decision`.

## 2026-08-22 — Issue #130 scope split

- User selected option 3. Created and read-back verified GitHub issue #135 for the
  cross-component FGS/endpoint/notification/webhook lifecycle.
- Kept #130 limited to receiver boundary, QUICKBOOT removal, foreign-broadcast protection,
  initial FGS start/promotion, and explicit startup failure handling.
- Continued the already authorized #130 device gate; no implementation work started for #135.

## 2026-08-22 — Issue #130 narrowed device blocker

- Installed the narrowed #130 APK and confirmed the explicit foreign BOOT_COMPLETED broadcast is
  rejected with `SecurityException`.
- After reboot, registered WLAN endpoint `192.168.178.24:34513` refused connections and the
  required scan found no valid WLAN-ADB port. USB fallback fingerprint validation succeeded.
- With `keep_alive_enabled=true`, `adb_wifi_enabled` remained `0`; no BootReceiver/FGS recovery
  evidence was obtained. No more reboot or install retries started. Status: `blocked`.

## 2026-08-22 — Issue #130 delayed transport recovery

- After allowing the device additional startup time, WLAN-ADB returned on
  `192.168.178.24:45099`; the endpoint, model, serial, SDK, and fingerprint were validated.
- Post-reboot evidence showed `keep_alive_enabled=true`, `adb_wifi_enabled=1`, and a current
  service heartbeat. The earlier blocker was superseded by this measured state.
- Updated and read back the narrowed #130 issue scope; #135 remains the open lifecycle follow-up.

## 2026-08-22 — Issues #145 & #146 (Vector icon for Notification and Tile)

- Generated and selected vector asset variant A (potrace contour tracing of monochrome launcher artwork).
- Updated `app/src/main/res/drawable/ic_keepadb.xml` with the new 24x24dp vector graphic.
- Added contract unit test in `KeepADBAccessibilityContractTest.java`.
- Local verification (`bin/verify`) passed cleanly.

## 2026-08-22 — Issues #148 & #149 (Tile & Notification icon scaling and label standardisation)

- Scaled vector icon glyph in `ic_keepadb.xml` to 20x20dp (~31% scale) for crisp appearance in status bar and tile.
- Standardized `tile_label` across all 19 locale string files to `@string/app_name` ("KeepADB").
- Added contract tests in `KeepADBAccessibilityContractTest.java`.
- PR #151 created, verified with `./bin/verify`, merged into `master`.

## 2026-08-22 — Issue #150 (Remove notification and FGS on wireless debugging disabled)

- Updated `KeepADBService`, `KeepADBNotification`, and toggle call sites to stop foreground service and cancel notification when wireless debugging is disabled.
- Added contract tests in `KeepADBBootReceiverContractTest.java`.
- PR #152 created, verified with `./bin/verify`, merged into `master`.

## 2026-08-22 — PR #153 (Allow cleartext HTTP traffic in network security config)

- Allowed cleartext HTTP traffic in `network_security_config.xml` for custom LAN webhooks.
- Added contract test in `KeepADBRegisterClientTest.java`.
- Verified with `./bin/verify`, merged into `master`.

## 2026-08-22 — F-Droid preparation and intake report

- Executed F-Droid preparation checklist from `F-DROID-PREPARATION.md` (Sections A through D).
- Verified clean-checkout reproducibility, Zero-Dependencies, AGPL-3.0-or-later licensing, permissions rationale, zero trackers/anti-features, and local release APK build.
- Generated `notes/f-droid-intake-report.md` with complete metadata YAML proposal for `fdroiddata`.

## 2026-08-22 — Release v1.0.0 and Public Publication

- Harmonized `versionName` in `app/build.gradle` to `1.0.0` (matching `CHANGELOG.md` and release tag).
- Executed `./bin/verify` (all 25 unit tests green, lint clean, release APK built).
- Created annotated Git tag `v1.0.0` on commit `a17e7e4` and pushed to `origin`.
- Changed GitHub repository visibility of `m00sfett/KeepADB` to **PUBLIC**.
- Created GitHub Release `v1.0.0` with signed APK and SHA-256 checksums.

## 2026-08-22 — F-Droid Official Catalog Merge Request

- Stored GitLab PAT securely in Vaultwarden (`git/gitlab-pat`) and deleted temporary auth file.
- Forked `fdroid/fdroiddata` on GitLab under `m00sfett/fdroiddata`.
- Created branch `add-de.hohnepeople.keepadb` and committed `metadata/de.hohnepeople.keepadb.yml`.
- Opened official F-Droid Merge Request: https://gitlab.com/fdroid/fdroiddata/-/merge_requests/46500

## 2026-08-22 — Issue #154 (Notification title status and deduplication)

- Updated notification title to `KeepADB: <STATUS>` (e.g. `KeepADB: Drahtloses Debugging ist AN` / `KeepADB: Wireless Debugging is ON`, `KeepADB: Endpoint wird gesucht …`, `KeepADB: Berechtigung fehlt`).
- Removed redundant connection endpoint info (`Port @ IP`) from notification title; second line continues to display formatted endpoint string (`Port <port> @ <ip>`).
- Updated all 19 locale string resources consistently.
- Created PR #155, verified via `./bin/verify`, merged into `master`, built and deployed to S20.

## 2026-08-22 — Issue #156 (Notification title refinement to Wifi-ADB status)

- Refined notification title to `KeepADB: Wifi-ADB AKTIVIERT` / `KeepADB: Wifi-ADB ENABLED` across all 19 locales.
- Added `notification_title_disabled` (`KeepADB: Wifi-ADB DEAKTIVIERT` / `KeepADB: Wifi-ADB DISABLED`) for completeness.
- Verified 100% key completeness and consistency across all 19 locale files (63 keys each).
- Created PR #157, verified via `./bin/verify`, merged into `master`, built and deployed to S20.

## 2026-08-22 — Issue #159 (Security risk warnings & network advice)

- Added *Security Considerations & Best Practices for Wireless Debugging* section to `README.md` (trusted networks, public hotspot precautions, pairing prompt checks).
- Added `settings_security_panel` with `@string/settings_section_security` and `@string/settings_security_body` to `activity_settings.xml`.
- Added localized security advice strings across all 19 supported languages (`values*/strings.xml`).
- Added static contract test `settingsActivityIncludesSecurityAdvicePanel` and expanded locale contract tests in `KeepADBAccessibilityContractTest.java`.
- Executed `./bin/verify` (all 25 unit tests passed, 0 lint warnings/errors, debug and release APKs built).

## 2026-08-22 — Issue #158 (Option to hide persistent notification)

- Added `hide_notification_enabled` preference to `KeepADBPreferences`.
- Updated `KeepADBNotification` to cancel and suppress status notifications when the hide preference is enabled.
- Added `settings_notification_panel` with toggle switch and explanatory subtext in `activity_settings.xml` and wired in `SettingsActivity.java`.
- Added localized strings for notification settings across all 19 supported languages (`values*/strings.xml`).
- Added contract tests in `KeepADBAccessibilityContractTest.java` and `KeepADBBootReceiverContractTest.java`.
- Executed `./bin/verify` (all 26 unit tests passed, 0 lint warnings/errors, debug and release APKs built).

## 2026-08-23 — Reproducible v1.1.0 release preparation

- Created a durable upstream PKCS12 release identity (`keepadb-release`) with certificate fingerprint `C5:2B:CD:17:1B:5C:CE:E8:87:F3:C1:6C:C3:A4:74:B3:8B:D9:CC:D7:71:CA:8C:D9:92:F8:2E:4D:17:75:3C:04`; the private key is outside the repository, backed up in Vaultwarden, and exposed to GitHub Actions only through write-only secrets.
- Prepared PR [#162](https://github.com/m00sfett/KeepADB/pull/162) for version `1.1.0` / version code `2`, upstream Fastlane metadata, pinned Android build-tools `34.0.0`, and unsigned Gradle builds signed externally with `apksigner`.
- Local `./bin/verify` passed. Two clean unsigned release builds were byte-identical. `apksigcopier compare --unsigned` passed for the signed APK, whose package/version and certificate were verified.
- Installed the signed `1.1.0` APK on the physical SM-G780G (`RF8T307S88H`) after backing up the previous APK and Preferences. The pulled installed APK was byte-identical to the signed candidate.
- On the unlocked S20, the Quick Settings tile toggled `adb_wifi_enabled` `1→0→1`; the active notification disappeared on OFF and returned on ON. The Settings switch hid and restored the notification. Added an English Fastlane screenshot captured with Wireless Debugging OFF, containing no endpoint address.
- No GitLab files, branches, commits, MR descriptions, labels, comments, or issue-bot actions were changed.
