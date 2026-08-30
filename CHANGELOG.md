# Changelog

All notable changes to **KeepADB** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.4.2] - Unreleased

### Added
- Resource contract coverage for locale key parity, format arguments, and visible UI literals.

## [1.4.1] - Unreleased

### Added
- Distinct app, widget, and Quick Settings states for off, missing permission, disconnected, and connected conditions.
- Fresh-process endpoint discovery from the normal Quick Settings Tile without Keep-Alive.
- Version and version code display in Settings, read from installed package metadata.

### Fixed
- Activity, widget, and Tile now refresh after asynchronous endpoint discovery.
- Tile-owned discovery is cancelled or invalidated at the end of the Tile lifecycle, preventing stale notification, register, and recovery side effects.
- Keep-Alive survives reboot, Wi-Fi AP changes, and temporary connection drops more reliably.

### Changed
- Settings sections now follow the product order from language and security through version information.

### Versioning note
- Each implemented issue increments the next patch or minor version. The intermediate bumps below are recorded retrospectively; they were not published as separate tags or releases.

## Retrospective issue version history

- `1.2.1` — #192: Keep-Alive remains active across reboot, Wi-Fi AP changes, and temporary connection drops.
- `1.3.0` — #193: App, widget, and Quick Settings Tile distinguish all operational states.
- `1.3.1` — #196: Fresh-process Tile discovery starts without Keep-Alive.
- `1.3.2` — #197: All UI surfaces refresh after asynchronous endpoint discovery.
- `1.3.3` — #198: Tile discovery uses the normal Tile lifecycle in fresh processes.
- `1.3.4` — #200: Tile-owned discovery cannot publish stale effects after lifecycle end.
- `1.4.0` — #203: Settings displays the installed app version and version code.
- `1.4.1` — #204: Settings sections follow the defined product order.
- `1.4.2` — #205: Localization resource and hard-coded literal audit contracts.

## [1.2.0] - 2026-08-29

### Added
- Optional USB-ADB notification with named, editable host profiles.
- Manual or automatic USB-to-WLAN-ADB handover, disabled by default.
- Structured, redacted recovery diagnostics that can be exported from Settings.
- Battery-optimization guidance when Android may restrict Keep-Alive.
- Direct notification action for turning off WLAN-ADB.

### Changed
- USB host-profile registration now works alongside WLAN-ADB registration and survives process restarts.
- USB notifications can be shown independently of host-profile details.
- USB-to-WLAN handover now preserves a deliberate manual OFF state and handles reconnect edge cases more safely.
- Webhook diagnostics now redact URL paths, credentials, and query parameters consistently.
- Webhook documentation now explains the optional USB device and profile data precisely.

## [1.1.0] - 2026-08-23

### Added
- Optional suppression of the persistent status notification.
- In-app and README security guidance for using Wireless Debugging safely.
- Upstream Fastlane metadata for F-Droid and other compatible catalog tools.

### Changed
- Notification titles now communicate the Wifi-ADB state more clearly without duplicating endpoint details.
- Release APKs now use a stable upstream signing identity and an unsigned Gradle build prepared for reproducibility verification.

## [1.0.0] - 2026-08-21

### Added
- **Triple Interface**:
  - Clean, dedicated Activity UI with real-time status and keep-alive toggle.
  - Quick Settings Tile (`KeepADBTileService`) for instant 1-tap toggling directly from the notification shade.
  - Home Screen Widget (`KeepADBWidget`) with live status feedback.
- **First-Time Setup Assistance**:
  - In-app guided setup instructions displaying the exact `adb shell pm grant` command required for `WRITE_SECURE_SETTINGS`.
- **Keep-Alive Foreground Service**:
  - Persistent background watchdog (`KeepADBService`) that automatically restores Wireless Debugging on network drops, Wi-Fi reconnects, AP roaming, and device boot (`BootReceiver`).
- **High-Speed Endpoint & Port Discovery**:
  - Batched non-blocking NIO loopback scanner resolving active `adbd` ports in under 200 milliseconds.
  - mDNS Network Service Discovery (`NsdManager`) fallback.
  - Ongoing notification with quick status display and connection string (`Port <port> @ <ip>`).
  - Full compatibility with local VPNs and overlay networks (e.g. Tailscale).
- **Central Settings Screen**:
  - Dedicated settings screen for language selection and optional custom webhook sync configuration.
- **Multi-Language Support (19 Languages)**:
  - Full localization for English (default), German, Spanish, French, Portuguese, Italian, Dutch, Polish, Ukrainian, Russian, Turkish, Arabic, Hindi, Simplified Chinese, Traditional Chinese, Japanese, Korean, Indonesian, and Vietnamese.
  - In-app language picker and native Android 13+ Per-App Language Preferences (`locales_config.xml`).
  - Native Right-to-Left (RTL) layout support.
- **Adaptive Icon & CI Design System**:
  - Native adaptive icon (Terminal Prompt + Wi-Fi Broadcast) with Android 13+ Material You monochrome support.
  - Cohesive Dark/Red/Yellow design system with native system typography and distinct touch-feedback states.
- **Open Source & Release Infrastructure**:
  - GNU Affero General Public License v3.0, or (at your option) any later version (`LICENSE`).
  - Automated GitHub Actions CI workflow for build validation and lint checks.
  - Automated GitHub Actions release workflow for publishing APK artifacts on version tags (`v*`).
- **Privacy & Security**:
  - 100% native AOSP framework, zero 3rd-party runtime dependencies, zero analytics or telemetry (< 350 KB APK).
  - Optional custom register/webhook sync endpoint (disabled by default).
