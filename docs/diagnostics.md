# KeepADB diagnostics

KeepADB writes structured diagnostic events to Logcat with the tag `KeepADBDiag`. Each event
contains an ISO-8601 wall-clock timestamp, elapsed-clock value, process ID, Android SDK, event
name, trigger source, outcome, and a short detail field.

The same lines are held in a bounded local ring buffer of at most 128 events in the app's private
`SharedPreferences`. Settings > Diagnostics > Export diagnostics opens Android's share sheet with
the plain-text export: one `key=value` event per line after the `KeepADB diagnostics v1` header.
The buffer is overwritten oldest-first and excluded from cloud backup and device transfer. KeepADB
does not upload it automatically; data leaves the app only when the user chooses a share target.

Pairing codes, tokens, passwords, authorization values, and URLs are redacted before Logcat and
the export buffer. Endpoint IP/port values may remain because they are the subject of the WLAN-ADB
diagnosis. Logcat retention is controlled by Android; the local buffer is limited to 128 events.

The event sequence is intended to be read as `user_action`/`toggle_attempt` -> `state_observed` ->
`recovery_attempt`/`recovery_or_stop` -> `service_*`. `intentId` correlates scheduled and completed
toggle or recovery writes. A service restart records the elapsed gap since the last persisted
heartbeat when one exists; its `pid` can be compared with surrounding events.
