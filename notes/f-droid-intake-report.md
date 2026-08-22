# F-Droid Intake Report — KeepADB

- **Prüfdatum:** 2026-08-22
- **Geprüfter Commit:** `cca6deeb7c92be6c193cade5011bdf78af455219`
- **Branch:** `master` (synchron mit `origin/master`)
- **Repository:** `https://github.com/m00sfett/KeepADB.git`
- **Paket-ID:** `de.hohnepeople.keepadb`

---

## Abschnitt A: Veröffentlichungsbestand

### 1. Git-Status, Branch und Remotes
- **Branch:** `master`
- **Remote:** `origin` -> `https://github.com/m00sfett/KeepADB.git` (Fetch & Push)
- **Arbeitsverzeichnis:** Vollständig sauber (`working tree clean`, 0 untracked files).
- **Ignorierte Dateien (`.gitignore`):**
  - `.gradle/`, `build/`, `app/build/`, `local.properties`, `*.iml`, `.idea/`, `captures/`, `.DS_Store`
  - Lokale Agenten-Steuerdateien: `AGENTS.md`, `CLAUDE.md`, `GEMINI.md` werden ordnungsgemäß ignoriert und nicht getrackt.

### 2. Zu veröffentlichender Dateibestand
Alle getrackten Dateien stellen den vollständigen Quellcode der Android-App unter AGPLv3 dar:
- Quellcode & Ressourcen: `app/src/main/...`, `app/src/test/...`
- Build-Deskriptoren & Wrapper: `build.gradle`, `app/build.gradle`, `settings.gradle`, `gradle.properties`, `gradle/wrapper/`
- Dokumentation & Metadaten: `README.md`, `CHANGELOG.md`, `LICENSE`, `F-DROID-PREPARATION.md`
- Hilfswerkzeuge & Workflows: `bin/verify`, `.github/workflows/ci.yml`, `.github/workflows/release.yml`
- Projektinterne Notizen: `notes/` (`issue-orchestrator-plan.md`, `runs.md`, `reviews/`)

### 3. Secrets, Tokens, private Pfade und historische Artefakte
- **Dateiprüfung:** Vollständige Durchsuchung nach Passwörtern, Tokens, privaten Schlüsseln, Keystores, internen Pfaden und IP-Adressen durchgeführt.
- **Befund:**
  - Keine privaten Schlüssel, Keystores oder Secrets im Quellcode vorhanden.
  - Mock-Strings in Unit-Tests (`KeepADBRegisterClientTest`) nutzen generische Test-URLs (`https://user:password@example.com:8443/...`, `http://192.168.1.10:8080/...`).
  - Git-Historie enthält keine jemals committeten `.key`, `.jks`, `.keystore`, `.pem` oder `local.properties`-Dateien.

### 4. Workflow-Dateien, .gitignore und Dokumentation
- **`.gitignore`:** Schließt alle lokalen IDE- und Build-Artefakte sowie Agent-Instruktionen aus.
- **`.github/workflows/`:**
  - `ci.yml`: Nutzt `workflow_dispatch`, baut via `./gradlew testDebugUnitTest lintDebug assembleDebug`.
  - `release.yml`: Triggert auf Tags `v*`, baut `assembleRelease`, erzeugt Release-APK und SHA-256 Checksumme, hängt sie an das GitHub-Release an.
- **`README.md` & `CHANGELOG.md`:** Dokumentieren Version 1.0 / 1.0.0, Berechtigungsanforderungen, Lizenz (AGPL-3.0-or-later) und Zero-Dependency-Architektur.

### 5. Build aus frischem Checkout
- **Test:** Frischer Klon nach `/tmp/test-keepadb-checkout` ohne private Dateien oder lokale Caches.
- **Ergebnis:** `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease` lief mit 89 Tasks fehlerfrei durch (alle 25 Unit-Tests bestanden, 0 Lint-Fehler, Debug- und Release-APKs gebaut).

---

## Abschnitt B: Recht und F-Droid-Kompatibilität

### 1. Lizenzstatus
- **Hauptlizenz:** GNU Affero General Public License v3.0, or (at your option) any later version (`AGPL-3.0-or-later`).
- **Lizenzdatei:** `LICENSE` enthält den offiziellen FSF-Lizenztext der AGPLv3.
- **Angaben im Projekt:** Einheitlich in `README.md`, `CHANGELOG.md` und `LICENSE` als AGPL-3.0-or-later deklariert.

### 2. Assets und Grafiken
- **Launcher-Icons (`ic_launcher*`):** Eigene Vektorgrafiken und Renderings (Terminal Prompt `>_` + Wi-Fi Broadcast-Bögen) unter AGPL-3.0-or-later.
- **Vektor-Icons (`ic_keepadb.xml`):** Eigene Vektorgrafik unter AGPL-3.0-or-later.
- **Standard-Icons (`ic_arrow_back.xml`, `ic_overflow_menu.xml`):** AOSP / Material Icons (Apache 2.0).
- **Schriftarten:** Keine eingebetteten Schriftdateien; es werden ausschließlich systemeigene Android-Schriftarten (Roboto / sans-serif) verwendet.

### 3. Bibliotheken und Abhängigkeiten
- **Runtime-Dependencies:** `0` (Zero Dependencies). Die App nutzt ausschließlich native AOSP Android Framework APIs.
- **Test-Dependencies:** `junit:junit:4.13.2` (`EPL-1.0`, nur im `testImplementation`-Scope für Plain-JVM-Tests).
- **Build-Plugins & Gradle:**
  - Android Gradle Plugin `8.7.2` (`Apache-2.0`, Quelle: Google Maven)
  - Gradle `8.9` (`Apache-2.0`)
  - Java `17` (OpenJDK, `GPLv2 with Classpath Exception`)

### 4. Netzwerkzugriffe, Telemetrie und Datenschutz
- **Telemetrie / Analytics / Crash-Reporting:** Keine vorhanden (0 Tracker, 0 Analytics-SDKs).
- **Werbung:** Keine vorhanden.
- **Netzwerkkommunikation:**
  - Lokale mDNS-Diensterkennung (`_adb-tls-connect._tcp`) über `NsdManager` und lokale Socket-Verbindungen (Loopback / LAN) zur adbd-Port-Erkennung.
  - Optionaler, vom Nutzer manuell konfigurierbarer Webhook-Endpunkt (`KeepADBRegisterClient`) zur Übermittlung des lokalen Endpunkts an einen eigenen Server. Standardmäßig deaktiviert.

### 5. Manifest-Berechtigungen und Begründungen
| Berechtigung | Zweck / Begründung |
|---|---|
| `android.permission.WRITE_SECURE_SETTINGS` | Ermöglicht das Schalten von `Settings.Global.adb_wifi_enabled` (0/1) ohne Root (einmalig per ADB erteilt). |
| `android.permission.POST_NOTIFICATIONS` | Ermöglicht ab Android 13 (API 33) das Anzeigen der Notification mit aktuellem Port und IP. |
| `android.permission.INTERNET` | Lokale Socket-Prüfung zur Starterkennung des adbd-Dienstes sowie für den optionalen Webhook. |
| `android.permission.CHANGE_WIFI_MULTICAST_STATE` | Empfang von mDNS-Multicast-Paketen zur Erkennung des TLS-Pairing-Ports. |
| `android.permission.ACCESS_NETWORK_STATE` | Überwachung des Verbindungsstatus über `ConnectivityManager.NetworkCallback` (z. B. Wi-Fi Reconnect). |
| `android.permission.RECEIVE_BOOT_COMPLETED` | Automatischer Re-Enable von Wireless Debugging nach Geräteneustart (nur wenn Keep-Alive aktiv). |
| `android.permission.FOREGROUND_SERVICE` | Ausführung des Hintergrundwächters `KeepADBService`. |
| `android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE` | Spezifischer Foreground-Service-Typ für Android 14+ (API 34+) bei Interaktion mit verbundenen Gerätediensten. |

### 6. F-Droid Anti-Features
- **Ergebnis:** Keine Anti-Features zutreffend (kein `Ads`, kein `Tracking`, kein `NonFreeNet`, kein `NonFreeAdd`, kein `NonFreeDep`).

---

## Abschnitt C: Release und Build

### 1. Versionsdaten
- **Paket-ID:** `de.hohnepeople.keepadb`
- **minSdk:** `30` (Android 11)
- **targetSdk:** `35` (Android 15)
- **compileSdk:** `35`
- **versionCode:** `1`
- **versionName:** `'1.0'` (in `app/build.gradle`) bzw. `1.0.0` (in `CHANGELOG.md`)

### 2. Lokaler Release-Build & Artefakt-Nachweis
- **Build-Befehl:** `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleRelease`
- **Toolchain:** OpenJDK 17.0.14, Gradle 8.9, Android Gradle Plugin 8.7.2, Build-Tools 35.0.0
- **Artefakt:** `app/build/outputs/apk/release/app-release.apk`
- **Dateigröße:** 320.776 Bytes (~313 KB)
- **SHA-256:** `573cd5a983dcc18ef063eeba6cac8268936c53e9fb85fec53dc7d126a615c78f`

### 3. F-Droid Build-Reproduzierbarkeit & Signierung
- **Reproduzierbarkeit:** F-Droid baut die App direkt aus den Git-Quellen in einer isolierten Docker/VM-Umgebung mit OpenJDK 17 und Android SDK 35. Da keine NDK-/C++-Komponenten oder proprietären Binaries existieren und Gradle 8.9 Standard ist, ist der Build für F-Droid vollständig nachvollziehbar.
- **Signierung:** F-Droid signiert APKs standardmäßig mit dem eigenen F-Droid-Release-Schlüssel. Nutzer, die über F-Droid installieren, erhalten Updates direkt über den F-Droid-Client.

---

## Abschnitt D: F-Droid-Metadaten-Vorschlag

Vorschlag für die Metadatendatei `metadata/de.hohnepeople.keepadb.yml` im `fdroiddata`-Repository:

```yaml
Categories:
  - Development
  - System
License: AGPL-3.0-or-later
AuthorName: m00sfett
WebSite: https://github.com/m00sfett/KeepADB
SourceCode: https://github.com/m00sfett/KeepADB
IssueTracker: https://github.com/m00sfett/KeepADB/issues
Changelog: https://github.com/m00sfett/KeepADB/blob/master/CHANGELOG.md

Name: KeepADB
Summary: Persistent Wireless Debugging companion with quick tile, widget & webhook sync
Description: |-
  KeepADB is a lightweight, zero-dependency utility to keep Android's Wireless
  Debugging persistently active and toggle it with a single tap.

  Since Android 11, Wireless Debugging uses dynamic ports and TLS pairing, but
  automatically disables itself on device reboot or Wi-Fi network reconnects.
  KeepADB solves this by monitoring connection state and re-enabling it automatically.

  Features:
  * 1-tap toggling via Quick Settings Tile or Home Screen Widget
  * Persistent Keep-Alive watchdog across reboots and network changes
  * Fast dynamic port and IP discovery displayed in notification shade
  * Automated Webhook integration (POST/DELETE) to notify local dev machines and CI
  * 100% native AOSP framework without external runtime dependencies or trackers
  * Multi-language support (19 languages) and Material You adaptive icon

  Note: Requires a one-time setup command via USB to grant WRITE_SECURE_SETTINGS permission:
  adb shell pm grant de.hohnepeople.keepadb android.permission.WRITE_SECURE_SETTINGS

RepoType: git
Repo: https://github.com/m00sfett/KeepADB.git

Builds:
  - versionName: 1.0.0
    versionCode: 1
    commit: v1.0.0
    subdir: app
    gradle:
      - yes

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 1.0.0
CurrentVersionCode: 1
```

---

## Konkrete Blocker & vorgeschlagene Aktionen

### Blocker für die Einreichung bei F-Droid
1. **Repository-Sichtbarkeit:** Das GitHub-Repository `m00sfett/KeepADB` ist aktuell privat. F-Droid erfordert ein öffentlich klonbares Git-Repository.
2. **Git-Release-Tag:** Im Repository existiert noch kein Git-Tag `v1.0.0` für den Release-Commit.

### Vorgeschlagene Produktänderungen (lokal im Repo)
1. **VersionName-Harmonisierung:** `versionName` in `app/build.gradle` von `'1.0'` auf `'1.0.0'` anpassen, um mit `CHANGELOG.md` (`[1.0.0]`) und dem Tag `v1.0.0` konsistent zu sein.
2. **Fastlane-Metadaten (optional):** Optionaler `metadata/` bzw. `fastlane/metadata/android/`-Ordner im Repository für Store-Beschreibungen und Screenshots.

### Vorgeschlagene externe Aktionen (erfordern separate Nutzerfreigabe)
1. **Git-Tag erstellen & pushen:** `git tag -a v1.0.0 -m "Release v1.0.0" && git push origin v1.0.0`
2. **Repository öffentlich schalten:** Sichtbarkeit von `m00sfett/KeepADB` auf GitHub auf *Public* umstellen.
3. **Merge Request bei `fdroiddata` einreichen:** Die Metadatendatei `metadata/de.hohnepeople.keepadb.yml` via GitLab MR an das offizielle F-Droid-Repository übermitteln.
