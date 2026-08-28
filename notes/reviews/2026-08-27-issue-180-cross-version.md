# Issue #180 – Cross-Version-Abnahme

Datum: 2026-08-27  
Status: `approved` für die lokale API-30–35-Abnahme

## Freigabe und Ausgangslage

Der Nutzer gab die SDK-/AVD-Provisionierung, Emulatorstarts, APK-Installationen,
UI-/ADB-Abnahmen und die S20-Prüfung frei. Produktionscode, App-Datenlöschung und
GitHub-Actions waren nicht freigegeben und wurden nicht ausgeführt.

Lokaler Verify-Lauf:

```text
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./bin/verify
```

Ergebnis: erfolgreich; Unit-Tests, Lint sowie Debug- und Release-Build bestanden.

## Toolchain- und Zielinventur

- Vorhanden: Plattformen 35, 36, 36.1; System-Images API 29, 35 und 36.1.
- Vor der Provisionierung fehlend: API 30, 31, 32 und 34.
- API 30 System-Image und Plattform wurden installiert.
- API 34 System-Image wurde installiert.
- API 31 und API 32 wurden nach erneuten Einzelaufrufen vollständig installiert, jeweils mit
  System-Image und Plattform.
- AVDs `KeepADB_API30`, `KeepADB_API31`, `KeepADB_API32`, `KeepADB_API34` und
  `KeepADB_API35` wurden angelegt. Der kanonische
  Resolver akzeptiert jedoch ausschließlich den konfigurierten AVD
  `Dev_Galaxy_S20_API_36_1_Play`; ein direkter ADB-Bypass wurde nicht verwendet.

Die SDK-Manager-Aufrufe meldeten die bekannte XML-Kompatibilitätswarnung.

## API-33-S20-Nachweis

Registerpfad: `wlan-adb`, Endpunkt `192.168.178.24:38545`; nach erfolgreicher Prüfung wurden
Modell `SM-G780G`, Serial `RF8T307S88H`, Android 13 und API 33 bestätigt. Die vorhandene
Installation Version 1.1.0 wurde geprüft, die Shared Preferences nach
`/home/tobias/agent/backup/2026-08-27/keepadb-s20-shared-prefs.tar` gesichert und die Debug-
APK mit `install -r` erfolgreich installiert.

Ausgangswerte:

- `adb_wifi_enabled=1`
- `de.hohnepeople.keepadb` war bereits in der User-Doze-Whitelist.

Die Activity ließ sich nach erneuter freigegebener Gerätebedienung starten. Ohne Doze-Ausnahme
waren Hinweis, Erklärung und Button sichtbar. Der Button öffnete den zielgenauen Dialog
„Optimierung des Akkuverbrauchs beenden?“ mit „Ablehnen“ und „Zulassen“. Nach „Zulassen“ und
erneutem Resume war der Hinweis verborgen. Nach kontrolliertem Entfernen der Ausnahme erschien
er wieder. Die ursprüngliche Whitelist wurde danach wiederhergestellt; `adb_wifi_enabled` blieb
bei `1`.

Versionierte UI-Dumps und Screenshots liegen unter
`assets/2026-08-27-issue-180/`.

## Bewertung

## API-30–35-Matrix

Die fünf Matrix-AVDs wurden einzeln über den reparierten Resolver auf ADB 5038 geprüft. Für jede
Version wurden APK-Installation, Hinweis ohne Ausnahme, zielgenauer Akku-Dialog, Ausblenden nach
„Allow“ und Resume, Wiederanzeige nach Whitelist-Entzug sowie Whitelist-Bereinigung geprüft.

| API | Android | `adb_wifi_enabled` vorher/nachher | Ergebnis |
|---:|---|---|---|
| 30 | 11 | 0 / 0 | bestanden |
| 31 | 12 | 0 / 0 | bestanden |
| 32 | 12 | 0 / 0 | bestanden |
| 33 | 13, S20 | 1 / 1 | bestanden |
| 34 | 14 | 0 / 0 | bestanden |
| 35 | 15 | 0 / 0 | bestanden |

Die API-34- und API-35-Erststarts verlangten zusätzlich die einmalige Notification-Freigabe;
danach lief der Akku-Zyklus wie spezifiziert. Nach jedem Emulatorlauf war
`de.hohnepeople.keepadb` nicht in der Doze-Whitelist; beim S20 wurde der ursprüngliche
Whitelist-Zustand wiederhergestellt.

Erfüllt bzw. nachgewiesen: lokaler Build-/Teststand, API-30/31/32/34-Images und -Plattformen,
AVDs für API 30/31/32/34/35, S20-Transport/Fingerprint, APK-Installation, Prefs-Backup und
der vollständige API-30–35-UI-/Whitelist-Zyklus.

Die Live-Abnahme von #180 ist damit bestanden. Die PNG-Kopien wurden aus der korrekten
`adbui`-Ausgabedatei neu erzeugt und sind sämtlich gültige 1080×1920-PNGs. Es wurden keine
GitHub Actions gestartet und kein Issue oder PR automatisch geschlossen.
