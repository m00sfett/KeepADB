# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

- **Android developers and testers** who connect their computer to their own phone with `adb`
  and do not want to re-enable Wireless debugging after every reboot or Wi-Fi change.
- **Home-lab and automation users** whose scripts, CI jobs or test farms need the current
  Wifi-ADB endpoint (via the webhook), often without anyone looking at the phone.
- **Power users without root** who want Wifi-ADB for tools such as scrcpy without root or
  Shizuku.
- **Occasional users** who need ADB briefly for one tool and want it off again afterwards.

The phone is frequently locked while it is being used this way; the computer side is where the
attention is.

## Product Purpose

KeepADB switches Android's "Wireless debugging" (`Settings.Global.adb_wifi_enabled`, called
**Wifi-ADB** in the app) from the app, a Quick Settings tile, widgets, launcher shortcuts and its
notification, and can keep it available across reboots and network changes (Keep-Alive), limited
by a network rule. Success means: Wifi-ADB is on exactly when and where the user wants it, the
endpoint is at hand, and it is off everywhere else.

## Positioning

- Needs only the one-time `WRITE_SECURE_SETTINGS` grant (on the phone via self-pairing, over
  WebUSB, or with `adb`); no root, no Shizuku.
- Keep-Alive recovery is bound to network protection (trusted access points by default), with
  switch-off on untrusted and open Wi-Fi, and an explicit, time-limited Force mode as the only way
  to override it.
- Endpoint delivery to the user's own systems through an opt-in webhook.

## Operating Context

- Surfaces: main screen (Wifi-ADB switch, endpoint, Keep-Alive), Protection page and Wi-Fi
  networks list, Settings, setup assistant, foreground-service notification, Quick Settings tile,
  two home-screen widgets, launcher shortcuts.
- Typical scene: the user sits at a computer, the phone lies locked nearby; actions happen through
  the notification or tile, or the app is opened briefly to read the endpoint.
- Android itself may ask for approval to enable Wifi-ADB on a network; KeepADB cannot grant it.

## Capabilities and Constraints

- Pure AOSP framework, Java 17, minSdk 30, target/compileSdk 35; no runtime dependencies,
  no ads, no analytics, no tracking.
- Package `de.hohnepeople.keepadb`; distributed through GitHub Releases and F-Droid.
- Every surface reads the Wifi-ADB state live; persistent state is limited to explicit user
  settings and the bookkeeping that safety requires.
- Terminology is fixed: **Wifi-ADB**, **Keep-Alive**, **Force mode**, **endpoint**,
  **protection level**, **trusted / blocked** access points.

## Brand Commitments

- Name KeepADB; licence AGPL-3.0-or-later; website keepadb.roteson.de.
- English is the source language of all UI text; 19 interface languages are maintained.

## Evidence on Hand

- Design review of 1.12.8 with screenshots:
  `../../.impeccable/critique/2026-11-15T04-32-11Z__code-v1-app-src-main-res-layout-activity-main-xml.md`
  (metadata root, not in this repository).
- No user studies, usage numbers or testimonials exist; none may be invented.

## Product Principles

1. **Safety before convenience.** Safe defaults; nothing is loosened silently; dangerous modes
   are loud and time-limited.
2. **State at a glance.** On/off, the endpoint and the reason for the current state are visible
   at once.
3. **Honest, not reassuring.** Limits (Android approvals, timing tolerances, residual risks) are
   named plainly.
4. **Lean.** AOSP only, no libraries, no tracking; keep it simple rather than piling up features.

## Accessibility & Inclusion

- Every new text ships in all 19 locales; layouts must work right-to-left and with long words
  (German).
- Usable while the phone is locked: notifications and the tile must work without opening the app.
