# Changelog

All notable changes to **KeepAdb** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2026-08-21

### Added
- **Triple Interface**:
  - Clean, dedicated Activity UI with real-time status and keep-alive toggle.
  - Quick Settings Tile (`AdbWifiTileService`) for instant 1-tap toggling directly from the notification shade.
  - Home Screen Widget (`AdbWifiWidget`) with live status feedback.
- **Keep-Alive Foreground Service**:
  - Persistent background watchdog (`AdbWifiService`) that automatically restores Wireless ADB on network drops, Wi-Fi reconnects, AP roaming, and device boot (`BootReceiver`).
- **Live Endpoint & Port Discovery**:
  - Real-time IP address and dynamic port discovery via mDNS Network Service Discovery (`NsdManager`) and an ultra-fast local loopback Fast-Probe scanner.
  - Ongoing notification with quick status display and connection string (`Port <port> @ <ip>`).
  - Full compatibility with local VPNs and overlay networks (e.g. Tailscale).
- **Internationalization (i18n)**:
  - English as standard language across all components and documentation.
  - German localization (`values-de`) for German system locales.
- **Open Source & Release Infrastructure**:
  - GNU General Public License v3.0 (`LICENSE`).
  - Automated GitHub Actions CI workflow for build validation and lint checks.
  - Automated GitHub Actions release workflow for publishing APK artifacts on version tags (`v*`).
- **Privacy & Security**:
  - 100% native AOSP framework, zero 3rd-party runtime dependencies, zero analytics or telemetry.
  - Optional custom register/webhook sync endpoint (disabled by default).
