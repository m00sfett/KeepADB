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
