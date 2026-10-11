# KeepADB documentation

## Current product and setup information

- [README](../README.md): installation, the one-time permission grant, use, and brief privacy
  guidance.
- [Trusted Wi-Fi networks](trusted-networks.md): current BSSID/SSID rules, permissions,
  background starts, boot behavior, and fail-closed decisions.
- [New-installation defaults](defaults.md): audit of settings, their safe defaults, and behavior
  for existing installations.
- [Webhook contract](webhook-register.md): what the Android sender actually transmits and which
  receiver-side claims this repository does not establish.
- [Diagnostics export](diagnostics.md): local event buffer, debug journal, redaction, and manual
  sharing.
- [Application ID](package-id.md): why `de.hohnepeople.keepadb` remains after the website move.
- [Security policy](../SECURITY.md): threat model and private vulnerability reporting.

## Code and source-file guide

- [Permissions and components](../app/src/main/AndroidManifest.xml): location access,
  foreground-service types, boot receiver, and disabled backups.
- [Network identity](../app/src/main/java/de/hohnepeople/keepadb/KeepADBNetworkIdentity.java)
  and [trust rules](../app/src/main/java/de/hohnepeople/keepadb/KeepADBTrustedNetwork.java):
  reading and comparing BSSID/SSID values and fail-closed decisions.
- [Keep-Alive service](../app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java) and
  [boot receiver](../app/src/main/java/de/hohnepeople/keepadb/BootReceiver.java): runtime type,
  background fallback, and permitted boot starts.
- [Webhook payload](../app/src/main/java/de/hohnepeople/keepadb/KeepADBRegisterPayload.java)
  and [HTTP sender](../app/src/main/java/de/hohnepeople/keepadb/KeepADBRegisterClient.java):
  payload and outgoing app request.
- [Diagnostics export](../app/src/main/java/de/hohnepeople/keepadb/KeepADBDiagnostics.java):
  local buffer, journal, and export redaction.
- [Settings screen](../app/src/main/java/de/hohnepeople/keepadb/SettingsActivity.java) and
  [saved preferences](../app/src/main/java/de/hohnepeople/keepadb/KeepADBPreferences.java):
  user actions and app-local options. `SettingsActivity` owns the screen and Android lifecycle,
  and delegates bounded areas to controllers:
- [KeepADBNetworkCard](../app/src/main/java/de/hohnepeople/keepadb/KeepADBNetworkCard.java):
  owns the network-card view (current network, protection level, convenience toggle, Force mode,
  the Networks entry point, and background access), its actions, and dialogs. `SettingsActivity`
  delegates restore, start/stop, refresh, state saving, destruction, and permission-result handling.
- [KeepADBDiagnosticsController](../app/src/main/java/de/hohnepeople/keepadb/KeepADBDiagnosticsController.java):
  owns diagnostics export and the issue-report dialog and draft. The activity delegates restore,
  state saving, and destruction; the app-reset dialog remains in the activity. The controller opens
  the feedback page through the shared `openWebLink` method.
- [Build configuration](../app/build.gradle), [local gate](../bin/verify),
  [CI](../.github/workflows/ci.yml), and
  [release workflow](../.github/workflows/release.yml): versions, tools, and verification paths.

## Development and tests

- [Contributing](../CONTRIBUTING.md): JDK/SDK requirements, full local verification, test
  dependencies, CI, and the separate signing path.

## Measurement reports and historical material

These dated source records are kept in their original language; they are historical evidence, not
current instructions. The technical `CHANGELOG.md` is maintained in English.

- [Trusted-networks measurement summary](trusted-networks-measurement.md): specific results and
  remaining measurement limits.
- [Measurement archive](archive/trusted-networks-measurements-2026-09.md): earlier notes and
  follow-ups, preserved in the original language. Historical findings are not additional product
  rules.
- [Fresh Wi-Fi on the P60 hotspot](fresh-wifi-device-test.md): one historical device test, not a
  general guarantee for other manufacturers; preserved in the original language.
- [Design note on multiple webhook transports](design/issue-539-multi-transport-register-contract.md):
  historical contract draft, preserved in the original language; the webhook contract linked above
  is authoritative for the current app sender.
- [Language and hard-code check dated 2026-09-20](release/issue-523-language-hardcode-gate-2026-09-20.md):
  dated audit report in the original language, not current release instructions.
