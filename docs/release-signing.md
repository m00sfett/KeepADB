# KeepADB-Release-Signatur

Stand: 2026-09-04

KeepADB verwendet für GitHub-Releases und die reproduzierbare Veröffentlichung über F-Droid
eine dauerhafte Upstream-Signieridentität. Sie darf nicht pro Release neu erzeugt oder ohne
einen ausdrücklich geplanten, mit Android und den Stores kompatiblen Migrationsweg ersetzt
werden.

## Aktueller Veröffentlichungsstand

Der aktuell veröffentlichte Stand ist [KeepADB v1.4.4](https://github.com/m00sfett/KeepADB/releases/tag/v1.4.4)
auf dem Quell-Commit `a1410ae0d6779618d159bf0d3e42001ebf683a7a`. Die zugehörigen GitHub-Release-
Artefakte heißen `KeepADB-v1.4.4.apk` und `KeepADB-v1.4.4.apk.sha256`.

Der aktuelle Entwicklungsstand ist `1.4.5` / VersionCode `17` und für den Release vorbereitet.
Ein neuer Release darf erst nach dem dafür vorgesehenen Freigabe- und Release-Gate als
veröffentlicht dokumentiert werden.

Der Release-Build verwendet den Gradle-Wrapper mit Gradle 8.9, Android Gradle Plugin 8.7.2,
JDK 21, compileSdk 35 und Android Build-Tools 34.0.0. Der maßgebliche Ablauf ist in
`.github/workflows/release.yml` festgehalten.

## Lokaler Build + Signing: `bin/build-signed-release.sh`

Der einzige unterstützte Weg, lokal ein signiertes Release-APK zu erzeugen, ist
`./bin/build-signed-release.sh` (keine Argumente). Es spiegelt `.github/workflows/release.yml`
Schritt für Schritt, damit ein lokaler Lauf und der CI-Lauf vergleichbare Ergebnisse liefern:

1. Keystore aus Vaultwarden (`android/keepadb-signing`) wiederherstellen.
2. **Fail-closed:** Keystore-SHA-256 gegen den unten dokumentierten Fingerprint prüfen — bei
   Abweichung sofort abbrechen, bevor irgendetwas signiert wird.
3. `./gradlew testDebugUnitTest lintDebug assembleRelease` mit JDK 21 — identisch zu
   `release.yml`, Schritt „Build unsigned release APK".
4. Mit `apksigner sign` aus Build-Tools **34.0.0** signieren, exakt mit den in `release.yml`
   gesetzten Flags: `--v1-signing-enabled false --v2-signing-enabled true
   --v3-signing-enabled true --v4-signing-enabled false`.
5. **Fail-closed:** Zertifikats-SHA-256 des signierten APKs gegen den dokumentierten
   Fingerprint prüfen — bei Abweichung das Artefakt löschen und abbrechen.
6. `sha256sum` als `.sha256`-Datei neben das APK schreiben.

Keystore und die aus Vaultwarden gelesene Notiz liegen nur in einem `mktemp -d`-Arbeitsbereich
mit Modus `700`/`600` und werden über einen `trap ... EXIT` in jedem Fall — auch bei Fehlern —
mit `shred -u` entfernt. Passwörter verlassen nie Shell-Variablen und werden nie ausgegeben.

Ergebnis: `app/build/outputs/apk/release/KeepADB-v<versionName>.apk` (+ `.sha256`), Version wird
automatisch aus `app/build.gradle` gelesen.

### Warum nicht `jarsigner` und nicht separates `zipalign`

Zwei Fallstricke, die hier bewusst vermieden werden und beim nächsten Mal nicht neu entdeckt
werden müssen:

- **`jarsigner` reicht nicht.** `targetSdk 35` verlangt mindestens APK Signature Scheme v2.
  `jarsigner` erzeugt nur eine v1-(JAR-)Signatur; `apksigner verify` lehnt ein so signiertes
  APK mit `DOES NOT VERIFY … requires a minimum of signature scheme v2` ab. Immer
  `apksigner sign` verwenden, nie `jarsigner`.
- **Kein separates `zipalign` vor `apksigner sign` nötig.** Der von
  `./gradlew assembleRelease` erzeugte `app-release-unsigned.apk` ist von AGP bereits
  zipaligned. Ein nachträgliches `zipalign` nach `jarsigner` (falls doch versehentlich
  `jarsigner` verwendet wurde) erzeugt ein technisch gültiges, aber von der CI abweichendes
  Artefakt — deshalb spiegelt das Skript exakt die CI-Reihenfolge: Gradle-Output direkt an
  `apksigner sign` übergeben, kein Zwischenschritt.

## Vor der Geräteinstallation: Downgrade- und Signaturkonflikt prüfen

Vor jeder Installation eines neu gebauten Release-APKs auf einem physischen Gerät:

1. **Versionsvergleich:** `dumpsys package de.hohnepeople.keepadb | grep versionCode` auf dem
   Zielgerät gegen `versionCode` im neuen APK vergleichen. Ist die installierte Version höher
   als die neue, ist das kein normaler Fortschritt — entweder ist auf dem Gerät ein Testbuild
   installiert, der nicht aus diesem Repository stammt, oder das lokale Repository ist nicht
   aktuell. In jedem Fall vor der Installation klären, nicht stillschweigend downgraden.
2. **Zertifikatsvergleich:** installiertes APK vom Gerät ziehen (`pm path` + `adb pull`) und mit
   `apksigner verify --print-certs` gegen das neue, signierte APK vergleichen. Weicht der
   `SHA-256 digest` ab, ist die installierte App mit einem anderen Schlüssel signiert
   (typischerweise ein lokaler Debug-Build) — `adb install -r` schlägt dann mit
   `INSTALL_FAILED_UPDATE_INCOMPATIBLE` fehl oder verlangt eine Deinstallation. Nie
   deinstallieren oder `pm clear` ohne eine ausdrückliche, auf den Datenverlust hinweisende
   Freigabe.

## Öffentliche Identität

- Paket-ID: `de.hohnepeople.keepadb`
- Alias: `keepadb-release`
- Keystore-Typ: PKCS#12
- Zertifikat SHA-256:
  `C5:2B:CD:17:1B:5C:CE:E8:87:F3:C1:6C:C3:A4:74:B3:8B:D9:CC:D7:71:CA:8C:D9:92:F8:2E:4D:17:75:3C:04`
- Keystore SHA-256:
  `a63a803f2f8bc4508127cc3f5d6e67b95ef500c0eb4184d77819bd6061f6fd4f`
- Zertifikatsinhaber: `C=DE, O=HohnePeople, CN=Tobias`
- Gültigkeit: 2026-08-23 12:51:31 UTC bis 2054-01-08 12:51:31 UTC

Diese Werte identifizieren den Schlüssel, enthalten aber kein privates Schlüsselmaterial.

## Sichere Ablage

Vaultwarden ist die kanonische Wiederherstellungsquelle. Der Eintrag
`android/keepadb-signing` enthält:

- den vollständigen PKCS#12-Keystore;
- Store- und Key-Passwort;
- Alias und Keystore-Typ;
- Paket-ID, Einsatzzweck und Gültigkeit;
- die öffentlichen Zertifikats- und Keystore-Fingerprints;
- Wiederherstellungsstatus und Rotationshinweis.

GitHub Actions besitzt lediglich abgeleitete Deployment-Kopien in den Secrets
`KEEPADB_SIGNING_KEYSTORE_BASE64`, `KEEPADB_SIGNING_STORE_PASSWORD`,
`KEEPADB_SIGNING_KEY_ALIAS` und `KEEPADB_SIGNING_KEY_PASSWORD`. GitHub ist nicht die
kanonische Sicherung, weil Secret-Werte dort nicht wieder ausgelesen werden können.

Private Schlüssel, Passwörter und der Base64-Inhalt werden niemals in diesem Repository,
Protokollen, Build-Logs oder Ausgaben dokumentiert.

## F-Droid-Modell

KeepADB nutzt reproduzierbare, upstream-signierte APKs. F-Droid baut die App aus dem
festgeschriebenen Quellstand neu und übernimmt das Upstream-APK nur bei erfolgreicher
Übereinstimmungsprüfung. `Binaries` verweist dafür auf das versionierte GitHub-Release;
`AllowedAPKSigningKeys` muss dem oben genannten Zertifikat entsprechen.

Ein gewöhnlicher privater F-Droid-Repository-Schlüssel wäre ausschließlich durch F-Droid
verwaltet und könnte deshalb nicht im Projekt-Vaultwarden gespeichert werden. Er ist für das
gewählte Upstream-Signing-Modell nicht die Update-Identität von KeepADB.

## Prüfung vor jedem Release

Vor Tag und Veröffentlichung sind nach gesonderter Freigabe mindestens diese Punkte zu
prüfen:

1. Der Vaultwarden-Keystore lässt sich in einer geschützten temporären Umgebung
   wiederherstellen. — automatisiert durch `bin/build-signed-release.sh`, Schritt 1.
2. Sein SHA-256-Wert entspricht dem dokumentierten Keystore-Fingerprint. — automatisiert,
   fail-closed, Schritt 2.
3. Alias, Zertifikatsfingerprint und Gültigkeit entsprechen diesem Dokument.
4. Das signierte APK trägt dieselbe Zertifikatsidentität. — automatisiert, fail-closed,
   Schritt 5.
5. Paket-ID, `versionName`, `versionCode`, Tag und Quell-Commit stimmen überein.
6. Bei reproduzierbarer F-Droid-Veröffentlichung stimmen F-Droid-Neubau und Upstream-APK
   außerhalb der Signatur überein.
7. Vor jeder Geräteinstallation zusätzlich Abschnitt „Vor der Geräteinstallation" oben
   durchgehen (Downgrade- und Signaturkonflikt).

Temporär wiederhergestellte Schlüsseldateien müssen mit Modus `0600` angelegt und nach der
Prüfung sicher entfernt werden. Fehlt der Vaultwarden-Eintrag, scheitert die Wiederherstellung
oder weicht ein Fingerprint ab, ist das Release blockiert. Es darf dann kein Ersatzschlüssel
erzeugt werden.

Letzter Wiederherstellungsnachweis: 2026-09-03, via `bin/build-signed-release.sh`. Der aus
Vaultwarden wiederhergestellte Keystore stimmte mit dem dokumentierten Keystore-Fingerprint
überein; das damit erzeugte APK (`versionName=1.4.5`, `versionCode=17`) trug das dokumentierte
Zertifikat (`SHA-256 c52bcd17…753c04`), verifiziert v3-signiert über
`apksigner verify --print-certs`.

Vorheriger Nachweis: 2026-08-29 (manuell, bytegleich zur geschützten lokalen Referenz, Zertifikat
stimmte mit dem veröffentlichten APK `v1.4.3` überein).
