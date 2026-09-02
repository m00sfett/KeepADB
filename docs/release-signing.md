# KeepADB-Release-Signatur

Stand: 2026-08-31

KeepADB verwendet für GitHub-Releases und die reproduzierbare Veröffentlichung über F-Droid
eine dauerhafte Upstream-Signieridentität. Sie darf nicht pro Release neu erzeugt oder ohne
einen ausdrücklich geplanten, mit Android und den Stores kompatiblen Migrationsweg ersetzt
werden.

## Aktueller Veröffentlichungsstand

Der aktuell veröffentlichte Stand ist [KeepADB v1.4.3](https://github.com/m00sfett/KeepADB/releases/tag/v1.4.3)
auf dem Quell-Commit `51175de193b3be20be14848b072060717d0ae2db`. Die zugehörigen GitHub-Release-
Artefakte heißen `KeepADB-v1.4.3.apk` und `KeepADB-v1.4.3.apk.sha256`.

Der aktuelle Entwicklungsstand ist `1.4.4` / VersionCode `16` und für den Release vorbereitet.
Ein neuer Release darf erst nach dem dafür vorgesehenen Freigabe- und Release-Gate als
veröffentlicht dokumentiert werden.

Der Release-Build verwendet den Gradle-Wrapper mit Gradle 8.9, Android Gradle Plugin 8.7.2,
JDK 21, compileSdk 35 und Android Build-Tools 34.0.0. Der maßgebliche Ablauf ist in
`.github/workflows/release.yml` festgehalten; lokal ist der unsigned Build mit
`JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew assembleRelease` reproduzierbar zu prüfen.

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
   wiederherstellen.
2. Sein SHA-256-Wert entspricht dem dokumentierten Keystore-Fingerprint.
3. Alias, Zertifikatsfingerprint und Gültigkeit entsprechen diesem Dokument.
4. Das signierte APK trägt dieselbe Zertifikatsidentität.
5. Paket-ID, `versionName`, `versionCode`, Tag und Quell-Commit stimmen überein.
6. Bei reproduzierbarer F-Droid-Veröffentlichung stimmen F-Droid-Neubau und Upstream-APK
   außerhalb der Signatur überein.

Temporär wiederhergestellte Schlüsseldateien müssen mit Modus `0600` angelegt und nach der
Prüfung sicher entfernt werden. Fehlt der Vaultwarden-Eintrag, scheitert die Wiederherstellung
oder weicht ein Fingerprint ab, ist das Release blockiert. Es darf dann kein Ersatzschlüssel
erzeugt werden.

Letzter Wiederherstellungsnachweis: 2026-08-29. Der aus Vaultwarden wiederhergestellte
Keystore war bytegleich zur geschützten lokalen Referenz; sein Zertifikat stimmte mit dem
veröffentlichten APK `v1.4.3` überein.
