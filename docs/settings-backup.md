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
| Wifi-ADB timer | `timer_default_duration` (the preselected duration, #1000) |
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
- **Run-time state**: Force mode, a running Wifi-ADB timer, the last on/off intent, manual exemptions, prompt and report
  histories, diagnostics, webhook bookkeeping and the setup assistant's progress.
- **The automation interface** (#998): neither its on/off switch nor the access token. An import
  must never open the entry point for other apps, and the token never leaves the device; switch it
  on again on the new device to get a new token.
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
  blocked addresses and names must be in the blocklist's canonical form (trimmed, lower-case
  addresses; no empty or unknown-network name), and strings must be
  non-empty and at most 1024 characters. Network names may contain any character the app itself
  accepted; JSON escapes them.
- The trusted and blocked entries must be consistent: every listed entry has its address or name, no field
  belongs to an unlisted entry, and the next free ID is above every listed one.

Any violation rejects the file and nothing changes. After a confirmation that shows the number of
trusted access points and blocked networks, the file replaces every managed key in one
`SharedPreferences` commit: keys the file does not name return to their defaults. Two exceptions:

- Without a webhook URL in the file, the stored URL and its on/off state stay as they are.
- A webhook URL that differs from the stored one replaces it only while nothing may be
  registered at the stored URL: synchronization is off and no withdrawal is outstanding
  (`KeepADBRegisterClient.hasOutstandingWithdrawal`). Synchronization then stays off until the user
  turns it on, and the URL field on the Settings screen shows the imported URL. Otherwise the
  file's URL is left out, the stored webhook stays as it is, and the import says so; changing the
  URL in the webhook settings keeps the existing withdrawal and retry rules in charge. After a
  reinstall nothing is stored, so the file's URL applies.

If the preference file cannot be written to storage, Android has still applied the values in
memory; KeepADB reconciles everything with them as below and then reports that they may not
survive, instead of reporting success.

The import also marks the protection level and the privacy switches as initialized, so the
one-time migrations do not rewrite the imported values. Afterwards KeepADB refreshes the
notification, widgets and warnings, masks the Settings screen and takes back a posted "new Wi-Fi"
notice with network details if a notification or lock-screen privacy switch is now on, and
re-evaluates the shutdown rules, which can only turn Wifi-ADB off. A picked file whose read is
still running when the screen is recreated (rotation) is read again by the new screen; a new pick
supersedes an earlier one: its read shows nothing and its confirmation closes and can no longer
apply. A changed refresh interval replaces a pending refresh alarm, as the interval picker does. The diagnostics journal records export and import outcomes without file contents.

Code evidence: `KeepADBSettingsBackupTest` (round trip, secrets absent, webhook handling,
replace-not-merge, unknown keys, foreign/old/malformed/inconsistent/oversized files, nothing
written on rejection).
