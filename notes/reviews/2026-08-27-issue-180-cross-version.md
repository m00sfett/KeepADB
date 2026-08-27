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
- API 31 und API 32 wurden nach erneuten Einzelaufrufen vollständig installiert, jeweils mit
  System-Image und Plattform.
- AVDs `KeepADB_API30`, `KeepADB_API31`, `KeepADB_API32`, `KeepADB_API34` und
  `KeepADB_API35` wurden angelegt. Der kanonische
  Resolver akzeptiert jedoch ausschließlich den konfigurierten AVD
  `Dev_Galaxy_S20_API_36_1_Play`; ein direkter ADB-Bypass wurde nicht verwendet.

Die SDK-Manager-Aufrufe meldeten die bekannte XML-Kompatibilitätswarnung. Das Problem bei API
31 ist ein Toolchain-/Download-Blocker, kein KeepADB-Befund.

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

Erfüllt bzw. nachgewiesen: lokaler Build-/Teststand, API-30/31/32/34-Images und -Plattformen,
AVDs für API 30/31/32/34/35, S20-Transport/Fingerprint, APK-Installation, Prefs-Backup und
der API-33-UI-/Whitelist-Zyklus.

Offen: Laufzeitnachweise API 30–32 und 34–35 sowie `adb_wifi_enabled`-Vorher/Nachher und
Whitelist-Rückbau je dieser Versionen. #180 ist nicht vollständig approved.
