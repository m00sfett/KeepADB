# New-installation defaults (defaults audit, #765)

This document records the starting values of a **newly installed** KeepADB app, why they are
used, and what happens to existing installations. It is the result of the audit in #765 (part of
#758) and applies from version 1.9.30.

## Principles

- **Only a new installation gets the safe defaults.** An existing installation keeps every saved
  value: nothing is silently tightened or relaxed.
- **“New” means that nothing has been saved.** “Reset” in Settings clears all app data
  (`clearApplicationUserData`) and therefore returns to exactly these values.
- A default is read, not written back. The only exception is the network-protection mode (#760),
  which is persisted on its first read so a later default change cannot shift an installation.

## Audit results

Every setting with a security impact already has its safest value on a new installation. The
network default was the only exception (“In all Wi-Fi networks”, `all_wifi`); #760 changed it to
“Only trusted access points.” The finding in issue #765 that the current default is
`MODE_ALL_WIFI` describes the state before #760 and is no longer accurate. No default therefore
changes here; #765 documents them and protects them against unnoticed changes (see “Evidence”).

**Exception since #904:** connection details in notifications were hidden by default (#592) and
are shown by default now, because the three privacy switches are uniformly opt-in (user decision of
2026-11-07). That is the one deliberate step away from the safest value; see “Privacy switches”
below for what existing installations keep.

### Settings with a security impact

| Setting | Key | Default | Effect | Assessment |
|---|---|---|---|---|
| Keep-Alive | `keep_alive_enabled` | off | The service does not re-enable Wireless debugging on its own; boot and update receivers do not start anything | Safe; decision (F2 from #758): also preselected as “Off” in the assistant (#761) |
| Network rule | `trusted_network_mode` | `allowlist` | Automatic enabling is allowed only on trusted access points (BSSID); recovery pauses when identity cannot be read | Safe (#760); only the exact value `all_wifi` means the previous “In all Wi-Fi networks” setting; every other value means `allowlist` |
| Also trust by Wi-Fi name | `trust_by_name` | off | An access point with a matching name is not considered trusted | Safe (convenience switch, available only in “Balanced”) |
| Name matching (legacy) | `trusted_network_ssid_matching` | off | The legacy SSID list has no effect | Safe; available only through the previous network-card switch |
| USB handover | `usb_wlan_handover_mode` | `off` | Plugging in does not enable anything or offer an action; an unknown saved value is treated as `off` | Safe |
| Check Wi-Fi security of trusted access points | `trusted_network_security_check` | off | Trusted access points are matched by address only, as before | Off by user decision (#860): an opt-in hardening for Android 12 and later; existing trust entries are never tightened silently |
| Turn off on untrusted Wi-Fi | `untrusted_shutdown_enabled` | off | Wireless debugging that is already on stays on in an untrusted network; KeepADB only notifies, as before | Off by user decision (#864): an opt-in hardening that must not change any installation silently; turning it on adds protection |
| Webhook | `register_webhook_enabled`, `register_webhook_url` | off, no URL | No outgoing traffic; there is no built-in destination URL (issue #64) | Safe |
| Wi-Fi and access-point observation | `wifi_aps_feature_enabled` | off | No recording; since #769 there is no UI or new recording, and since #788 no code reads or writes the key. An earlier saved value remains unchanged. The old observation history (`bssid_history_*`) is discarded once during update since #778 (marker `observation_history_discarded`, bookkeeping only, not a setting) | Safe (#507, #769) |

Lists that inform a decision start empty: there are no trusted access points, name matches, blocks,
or USB profiles.

### Display and convenience

These values do not control automatic enabling or data transfer. They remain unchanged, with the
reason for each documented here.

| Setting | Key | Default | Reason |
|---|---|---|---|
| Privacy: in the app | `privacy_mode_enabled` | off | User decision (#758, #904): the former single privacy mode, key and meaning unchanged. Masks Wi-Fi names, BSSIDs, IP addresses and the webhook host on every screen and dialog; a “Show” lifts it for one screen until it is left. It hides display text only and does not protect an action |
| Privacy: in notifications | `notification_privacy_enabled` | off | User decision (#904, 2026-11-07): replaces the opt-in “Notification details” (`notification_details_enabled`, #592) with the opposite polarity. Off means port, IP address, USB profile, Wi-Fi name and BSSID appear in notifications, and the notice about a new Wi-Fi has its two buttons. This is a deliberate weakening of #592 for new installations; the safe choice is one switch away, also on the first page of the setup assistant. Existing installations keep their behavior (see “Privacy switches” below) |
| Privacy: hide on the lock screen | `lockscreen_privacy_enabled` | off | User decision (#920, 2026-11-08): a sub-option of the notification switch. On marks every KeepADB notification `VISIBILITY_SECRET`, so none appears on the lock screen, whatever Android's “sensitive content” setting is; unlocked, the shade shows them as the notification switch decides. A new key, so existing installations keep what they had. A notification channel the user set to hide on the lock screen stays hidden either way. Android applies it only with a secure screen lock (PIN, pattern or password). Since #941 it is in the eye's long-press menu with the other switches, and all of them live in Settings → Privacy |
| Privacy: in widgets and the tile | `widget_privacy_enabled` | off | User decision (#904): the widget and the Quick Settings tile show the state only, without the port or the host. Off means they show them. It takes precedence over any per-widget “hide details” option |
| Widget: main action, order, hide details | none in `keepadb_prefs` | main action Wireless Debugging, order Debugging, Keep-Alive, Auto-sync, details visible | #912: stored per widget id in its own file `keepadb_widget_config`, never in the app preferences; a widget without an entry (every widget placed before) reads these defaults. Removed with the widget. The overview widget (#913) uses the same store for its only option, hide details (default visible). The widget privacy switch above takes precedence over the per-widget hide option |
| Hide notification | `hide_notification_enabled` | off | The visible ongoing notification shows that the service is running; hiding it is a deliberate choice |
| Keep display on | `keep_display_on_enabled` | off | Convenience with battery cost; turning it on adds no protection |
| USB notification | `usb_notification_enabled` | off | Opt-in; the following switch has no effect while this is off |
| Profile in USB notification | `usb_profile_notification_enabled` | on | Controls only the USB notification layout (profile row and actions); its contents are hidden by `notification_privacy_enabled` when that is on (“Host profile selected — details in the app”). Changing this to “off” for installations that have never saved the key would silently change what their USB notification shows without protecting anything |
| App language | `app_language` | empty (system language) | No security impact |
| Webhook retry timing | `register_webhook_retry_profile` | standard (5, 15, 30, 60 s) | Only used while webhook sync is on and a report failed; bounded, no polling (#866). After a given-up series, one single re-attempt at most every 15 minutes while the failure lasts (#881) |
| Webhook retries | `register_webhook_retry_count` | 4 | The hard maximum per series; fewer retries are a user choice (#866) |
| Home-screen advice card | `advice_banner_visible` | visible | “Do not show again” flag; display only. The flags for the other cards (`notification_permission_panel_visible`, `battery_optimization_panel_visible`, `network_onboarding_panel_visible`, `background_location_panel_visible`) were removed in #764 and are cleared on first start |

### Not a setting

| Key | Default | Reason |
|---|---|---|
| `last_desired_on` | on | Stores the last explicit on/off intent; it is not a switch. On a new installation it does not trigger anything on its own: the service and boot receivers first require `keep_alive_enabled`, USB handover first requires its mode (default `off`), and enabling Keep-Alive writes the “on” intent. An “off” default would instead affect existing installations that enabled Keep-Alive before this key existed and would silently disable their recovery |

`trusted_network_<id>_security` (#860) is absent until the access point of that trust entry is trusted while the phone is connected to it on Android 12 or later; it records the security tier seen then, is replaced when the access point is trusted again and removed with the entry. Bookkeeping, not a setting.

`untrusted_shutdown_manual_bssid` and `untrusted_shutdown_manual_at` (#864) are absent until Wireless debugging is switched on manually in KeepADB on a readable access point; they record that access point and the time of the manual session so the optional untrusted-Wi-Fi shutdown leaves it alone there, and are cleared when Wi-Fi is lost, Wireless debugging goes off, another access point is seen or the Keep-Alive service restarts. Bookkeeping, not settings.

The webhook warning has no wording switch any more (#893): it always names when the disruption
started and when a report last got through. A stored `register_webhook_freshness_warning` from an
older version is ignored. The report history (#892) lives in its own app-private file
`keepadb_report_history` (key `entries`): the last 24 hours, at most 300 entries, never the webhook
URL. It is record data, not a setting, and is empty on a new installation.

### Privacy switches (#904)

The single privacy mode is three independent opt-in switches. A new installation has all three
off. Two of them are derived once for installations that already exist, so nothing is loosened:

| Stored before the update | App (`privacy_mode_enabled`) | Notifications (`notification_privacy_enabled`) | Widgets (`widget_privacy_enabled`) |
|---|---|---|---|
| Nothing (new installation) | off | off | off |
| Existing, `notification_details_enabled` absent or `false` (the old default: details hidden) | unchanged | **on** | on if the old privacy mode was on, else off |
| Existing, `notification_details_enabled` = `true` | unchanged | off | on if the old privacy mode was on, else off |

The derivation follows the pattern of #760: `KeepADBApplication` calls
`KeepADBPreferences.migratePrivacySplit` before any component of the process writes a preference
(that is the only moment “nothing stored” identifies a new installation), persists the result and
the marker `privacy_split_initialized` once, and never touches the keys again. The marker is
bookkeeping, not a setting. On a new installation only the marker is written, no setting key. The
retired key `notification_details_enabled` stays in place, unread after the migration, so an older
app that is installed again still finds it. The three switches write at once when changed, in the
Settings and on the first page of the setup assistant; they are not part of “Next” or “Later”.

Force mode (#763) is off on a new installation. `force_state` is not saved without explicit
confirmation in the Force dialog; it contains the duration and expiry for that decision.
`force_expired_notice_pending` is `false` when there is no expiry and tracks only a pending
expiry notification. `force_expired_reason` is absent unless Force mode ended early for safety; it
holds only the reason (`safety`, #773) until the notice is delivered. All three keys are runtime
data, not alternate defaults.

The setup assistant (#761, #828) creates three keys, all bookkeeping rather than settings (the
privacy marker of #904 is excluded from the check that decides “existing installation”):
`onboarding_completed_version` is absent until the assistant is closed with “Later” or “Done”
(then it contains the assistant version); `onboarding_existing_install` records once whether
settings were already saved at first launch (only then does the intro show “New security
settings”). `upgrade_notice_1_10_shown` is absent on new installations and is set once when the
upgrade notice is shown to an existing installation without a completed assistant. The notice
explains the documented changes from #827/#848; it does not appear again after a reboot or another
update and changes no settings. Assistant steps write a value only if the user changes it;
pressing “Next” without input leaves every key unchanged, so a new installation keeps the same safe
defaults. This also applies to the “Permissions” and “Trusted Wi-Fi” steps (#767): their actions
(requesting a permission, trusting or blocking a network) take effect immediately and are explicit
actions; “Next” and “Skip” write nothing. Like the buttons on the home screen and Networks list,
the marker that records whether a permission was requested before—used to distinguish “denied”
from “permanently denied”—is stored in Activity preferences, not `keepadb_prefs`.

### Permissions and system state

Android permissions are not app settings and are all ungranted on a new installation: precise
location, “Allow all the time,” notifications, and exemption from battery optimization.
`WRITE_SECURE_SETTINGS` is granted only through the one-time `adb shell pm grant`. `allowBackup`
is off and `data_extraction_rules.xml` excludes every domain, so neither defaults nor existing
data are transferred through backup or when changing devices.

## New installation and existing data

| State | Behavior |
|---|---|
| Nothing saved (new installation or reset) | Defaults above |
| A value is saved and is more permissive than the default (for example, Keep-Alive on, `all_wifi`, webhook on) | Preserved; nothing is tightened |
| A value is saved and is stricter than a convenience default (for example, card hidden, profile notification off) | Preserved; nothing is relaxed |
| A value explicitly saved as the default | Remains saved |
| Corrupt value (`usb_wlan_handover_mode`, `trusted_network_mode`) | Read as a safe value (`off`, `allowlist`) |

Changing a default later can therefore never be just a replacement of the fallback constant in a
getter: that would also shift every installation that has never saved the key. A new default must
be persisted once, following the pattern in #760 (`KeepADBTrustedNetwork.ensureModeInitialized`),
and recorded in the changelog.

## Evidence

`KeepADBDefaultsAuditTest` is the executable version of this table:

- Each setting reads its default when no value is saved; reading writes nothing except the
  one-time network-mode flag (and the one-time privacy marker of #904, which the application
  writes at its first start).
- The table is complete: adding a preference key in the source that appears neither here nor in
  the test's data-key list fails the build, as does a table key removed from the source. Every
  setting key must appear in this document.
- The relevant paths behave safely with defaults (network decisions, boot and update receivers,
  USB handover), each with a counter-check using saved opt-in.
- Saved values remain in both directions (more permissive and stricter than the default), alone
  and in combination; a default value saved explicitly also remains. The test writes literal key
  strings rather than using setters, so a renamed key—which would reset every installation to its
  default—is detected.

The network rule is also covered in `KeepADBTrustMigrationTest` (the decision matches version
1.9.28 for every saved state) and `KeepADBTrustPrecedenceTest`; see
[Trusted Wi-Fi networks](trusted-networks.md).
