# KeepADB diagnostics

KeepADB logs structured events with the Logcat tag `KeepADBDiag`. Events contain a timestamp,
elapsed time, process ID, Android SDK level, event type, trigger, result, and a short detail field.

## View and share

Under **Settings → Diagnostics & maintenance → Export diagnostics**, Android's share sheet opens
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
export. Diagnostics can still include the Wi-Fi ADB endpoint, including its host and port, because
that is the subject of the diagnosis.

In an export, a valid BSSID shows only its first three-octet block (the OUI, or manufacturer
identifier); the remaining three blocks are masked. Invalid BSSID values and SSIDs are masked
entirely. **Settings → Diagnostics & maintenance → Report a bug or translation** opens an editable
report draft. If diagnostics are inserted there, the draft also masks the host and port and fully
masks BSSID and SSID values.

Logcat has Android's own retention period. A diagnostics export may still contain private network
addresses. Review the preview and remove sensitive details before sharing the text with others.

## Read the event sequence

Typical sequences show a user action or toggle attempt, an observed state, a recovery attempt or
stop, and then service events. `intentId` links scheduled and completed toggle or recovery writes.
The process ID and elapsed time help identify a service restart or a gap between events.
