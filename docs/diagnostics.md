# KeepADB diagnostics

KeepADB logs structured events with the Logcat tag `KeepADBDiag`. Events contain a timestamp,
elapsed time, process ID, Android SDK level, event type, trigger, result, and a short detail field.

## View and share

Under **Settings → Diagnostics & Maintenance → Export diagnostics**, Android's share sheet opens
a text export. KeepADB does not upload diagnostics automatically. An export leaves the app only
after you select a destination. For a temporary live excerpt, use Android Logcat:

~~~sh
adb logcat -s KeepADBDiag
~~~

Release builds keep at most 128 events in a private ring buffer; older entries are overwritten.
Debug builds use a journal instead, with a 50-hour age limit, additional size limits, and periodic
state snapshots. Android cloud backup and device transfer are disabled.

## Redaction and included data

Pairing codes, passwords, tokens, authorization values, and URLs are redacted before storage and
export. Diagnostics can still include the Wifi-ADB endpoint, including its host and port, because
that is the subject of the diagnosis.

In an export, a valid BSSID shows only its first three-octet block (the OUI, or manufacturer
identifier); the remaining three blocks are masked. Invalid BSSID values and SSIDs are masked
entirely. **Settings → Diagnostics & Maintenance → Report a problem or translation** opens an editable
report draft. If diagnostics are inserted there, the draft also masks the host and port and fully
masks BSSID and SSID values. While the app privacy switch is on (#904; the Settings screen has no
“Show” for it), **Export diagnostics** applies the same full redaction of host, port,
BSSID and SSID to the exported text.

Logcat has Android's own retention period. A diagnostics export may still contain private network
addresses. Review the preview and remove sensitive details before sharing the text with others.

## Read the event sequence

Typical sequences show a user action or toggle attempt, an observed state, a recovery attempt or
stop, and then service events. `intentId` links scheduled and completed toggle or recovery writes.
The process ID and elapsed time help identify a service restart or a gap between events.

Time limits (#1000): `timer` events record a timer's start, extension, end and expiry (see
[Wifi-ADB timer](timed-wifi-adb.md#diagnostics)); `force_mode` events now also record `extended`
(with the minutes left) and an end with the reason `manual_off`. A blocked "+1 h" on a locked
device appears as `user_action ... blocked timer_extend device_locked` (or `force_extend`).
