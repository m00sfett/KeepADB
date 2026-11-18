# Settings backup (#1001)

KeepADB keeps its settings in private app storage and excludes them from Android cloud backup and
device transfer (`allowBackup="false"`, `data_extraction_rules.xml`). A reinstall therefore loses
every setting. **Settings → Settings backup** lets the user export the settings to a file and
import them again. Both directions use the Android document picker (Storage Access Framework):
the user chooses the file, KeepADB needs no storage permission and never uploads anything.

## What the file contains

| Area | Keys |
|---|---|
| Protection level | `trusted_network_mode` |
| Trusted access points | `trusted_network_ids`, `trusted_network_next_id`, `trusted_network_<id>_label`, `_bssid`, `_name`, `_ssid`, `_security` |
| Legacy trusted Wi-Fi names | `trusted_ssid_ids`, `trusted_ssid_next_id`, `trusted_ssid_<id>_ssid`, `trusted_network_ssid_matching` |
| Blocked networks | `blocked_bssids`, `blocked_ssids`, `blocked_bssid_ssid_<bssid>`, `blocked_bssid_name_<bssid>` |
| Network rules | `trust_by_name`, `trusted_network_security_check`, `untrusted_shutdown_enabled`, `open_network_shutdown_enabled` |
| Privacy | `privacy_mode_enabled`, `notification_privacy_enabled`, `widget_privacy_enabled`, `lockscreen_privacy_enabled` |
| Notification and display | `hide_notification_enabled`, `keep_display_on_enabled` |
| Webhook options | `register_webhook_periodic_enabled`, `register_webhook_periodic_interval` |
| Webhook URL (optional) | `register_webhook_url`, left out when the user picks "Without webhook URL" |

`KeepADBSettingsBackup.RULES` is the single list of these keys. The export reads only keys that
match it, never the whole preference file, so a secret such as an automation token can never end
up in the file.

## What is deliberately not included

- **The system permission** (`WRITE_SECURE_SETTINGS`): it must be granted again with
  `adb shell pm grant`; no file can grant it.
- **The Keep-Alive switch** and the **webhook on/off state**: an import must not lead to Wifi-ADB
  being turned on or to reports being sent. The user switches both on again.
- **Run-time state**: Force mode, the last on/off intent, manual exemptions, prompt and report
  histories, diagnostics, webhook bookkeeping and the setup assistant's progress.
- **The app language**: Android keeps it per app.
- **Widget settings**: they are keyed by widget ID, and a reinstall gives every widget a new ID,
  so they cannot be matched to the new widgets.

## File format

```json
{
  "format": "keepadb-settings",
  "version": 1,
  "exportedAt": 1795000000000,
  "appVersion": "1.15.1",
  "settings": {
    "trusted_network_mode": "allowlist",
    "blocked_ssids": ["Airport"],
    "untrusted_shutdown_enabled": true
  }
}
```

Values keep their stored type: booleans, non-negative integers, strings, and string sets as JSON
arrays. `exportedAt` and `appVersion` are informational. Before the file is handed to the picker,
the export validates it with the import's own parser, so KeepADB never writes a file it would
reject. Name fields that belong to no listed entry (leftovers an older version could leave after
lifting a block) are dropped from the export.

## Import

The import reads at most 256 KiB and validates the whole file before it writes anything:

- `format` must be `keepadb-settings` and `version` must be `1`; another version is reported as
  coming from a newer KeepADB.
- Unknown keys are ignored, so a file from a newer version with extra settings still imports.
- Every known key must have the right type and an allowed value: the protection level and the
  refresh interval must be one of their fixed values, the webhook URL must be a valid `http(s)` URL,
  strings must be non-empty, at most 1024 characters and free of control characters.
- The trusted and blocked entries must be consistent: every listed entry has its address or name, no field
  belongs to an unlisted entry, and the next free ID is above every listed one.

Any violation rejects the file and nothing changes. After a confirmation that shows the number of
trusted access points and blocked networks, the file replaces every managed key in one
`SharedPreferences` commit: keys the file does not name return to their defaults. Two exceptions:

- Without a webhook URL in the file, the stored URL and its on/off state stay as they are.
- With a webhook URL that differs from the stored one, synchronization is switched off. If it was
  on, the registration at the old URL is withdrawn first (`KeepADBWebhookSync.disable`); the user
  switches it on again for the new URL.

The import also marks the protection level and the privacy switches as initialized, so the
one-time migrations do not rewrite the imported values. Afterwards KeepADB refreshes the
notification, widgets and warnings and re-evaluates the shutdown rules, which can only turn
Wifi-ADB off. The diagnostics journal records export and import outcomes without file contents.

Code evidence: `KeepADBSettingsBackupTest` (round trip, secrets absent, webhook handling,
replace-not-merge, unknown keys, foreign/old/malformed/inconsistent/oversized files, nothing
written on rejection).
