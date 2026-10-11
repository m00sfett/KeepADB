# Warning display state (#826)

The nine stable base IDs in `KeepADBWarningState.Reason` represent the existing warning
conditions. `KeepADBHomeWarnings.evaluate()` and `reasons()` remain pure evaluations. The latter
also records operational causes while their display is suppressed by a system permission or a
paused card. Suppression therefore does not end an episode.

`warnings_v1_state` stores schema 1, canonical observed and acknowledged signatures per card,
and explicitly muted reasons. All display-state changes go through the same storage monitor.
Confirming the X covers only the displayed snapshot, intersected with the causes that remain
active. When a cause goes away, its acknowledgement is removed without showing the other causes
again. A later return is unacknowledged. Mutes are independent and persist while inactive. Reset
removes only mutes and acknowledgements for those reasons. System and Force reasons are rejected at
both mute boundaries.

For each reason, an additive `reason_episode_<id>` token records its observed display episode.
When it goes away and returns, the token changes. X, Undo, and mute feedback compare the displayed
token with the current one; matching reason IDs alone is not enough. Feedback retains its tokens
through rotation. An update adds missing tokens without deleting valid saved acknowledgements or
mutes.

The system-warning cause reads only the permission and the existing guarded Settings value. It
does not query an endpoint monitor while holding the display monitor; endpoint callbacks that run
synchronously on the UI thread may render warnings in turn.

Enabling Force mode atomically saves a new `warnings_force_episode` token with `force_state`.
`warnings_force_fingerprint` contains its SHA-256 fingerprint. Rebinding the state keeps the
episode; an unknown change by an older app conservatively creates a new episode once. An active
session without warning keys is also shown once. Time bases are never used as episode IDs. The
monitor serializes Force writes with display snapshots. Actual Force transitions still update
surfaces only after releasing the monitor; expiry checks and protection rules remain in effect.

Unknown schemas, corrupt stored values, and unknown reason IDs are preserved and treated as an
unacknowledged, unmuted display state. They cannot hide critical warnings. Failed commits do not
report a successful close. The new keys are in the existing preferences excluded from backup.
Older apps ignore them. Actual protection and Force parameters are not deleted.

Preference setters, service sync, heartbeat and network paths, Force transitions, Activity resume,
and permission results observe causes even while the home screen is closed. A completely
unobserved external off/on change while the app is stopped cannot be reconstructed from an
identical snapshot; no additional polling infrastructure is added.

Verification remains the integrator's responsibility. It includes `./bin/verify`, sensitivity
counter-checks for signature intersection, critical mute IDs, and Force tokens, plus the authorized
tablet/Fire 7 matrix. The matrix covers portrait and landscape, 100% and 200% font scale, long
translations, night mode, and real keyboard/TalkBack order, including cards with duplicate IDs.
New unit tests use real setters, preferences, and Force drivers. Activity tests exercise X, Undo,
triangle, Check, mute reversal, recreation, and Settings focus.
