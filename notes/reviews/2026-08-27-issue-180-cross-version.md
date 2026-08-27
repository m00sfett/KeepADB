# Issue #180 – Cross-Version-Abnahme

Datum: 2026-08-27  
Status: `not approved`

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
- API 32 blieb ein unvollständiger Installer-Rest ohne `source.properties`.
- API 31 blieb trotz zweier Einzelversuche im SDK-Manager beim Download/„Preparing“-Schritt
  ohne Dateifortschritt und wurde nach dem Timeout beendet.
- AVDs `KeepADB_API30`, `KeepADB_API34` und `KeepADB_API35` wurden angelegt. Der kanonische
  Resolver akzeptiert jedoch ausschließlich den konfigurierten AVD
  `Dev_Galaxy_S20_API_36_1_Play`; ein direkter ADB-Bypass wurde nicht verwendet.

Die SDK-Manager-Aufrufe meldeten die bekannte XML-Kompatibilitätswarnung. Das Problem bei API
31 ist ein Toolchain-/Download-Blocker, kein KeepADB-Befund.

## API-33-S20-Nachweis

Registerpfad: `wlan-adb`, Endpunkt `192.168.178.24:40435`; nach erfolgreicher Prüfung wurden
Modell `SM-G780G`, Serial `RF8T307S88H`, Android 13 und API 33 bestätigt. Die vorhandene
Installation Version 1.1.0 wurde geprüft, die Shared Preferences nach
`/home/tobias/agent/backup/2026-08-27/keepadb-s20-shared-prefs.tar` gesichert und die Debug-
APK mit `install -r` erfolgreich installiert.

Ausgangswerte:

- `adb_wifi_enabled=1`
- `de.hohnepeople.keepadb` war bereits in der User-Doze-Whitelist.

Die Activity ließ sich starten. Der UI-Dump zeigte jedoch den Sperrbildschirm; auch nach
Wake-/Swipe-/Statusbar-Versuchen blieb der Fokus auf `NotificationShade`. Die UI-Texte,
der Akku-Dialog, der Resume-Refresh und der Whitelist-Rückbau konnten deshalb auf dem S20 in
diesem Lauf nicht abgenommen werden. `adb_wifi_enabled` wurde nicht verändert.

## Bewertung

Erfüllt bzw. nachgewiesen: lokaler Build-/Teststand, API-30-Image und -AVD provisioniert,
API-34-Image und -AVD provisioniert, S20-Transport/Fingerprint, APK-Installation und
Prefs-Backup.

Offen: Laufzeitnachweise API 30–32 und 34–35, API-33-UI-Abnahme wegen Sperrbildschirm,
`adb_wifi_enabled`-Vorher/Nachher je Version, Whitelist-Rückbau je Version und die vollständige
versionierte Matrix. #180 ist nicht vollständig approved.
