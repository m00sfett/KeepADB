# KeepADB — Vorbereitung für den offiziellen F-Droid-Katalog

Stand: 2026-08-29
Zweck: projektspezifischer Prüf- und Übergabeauftrag für den F-Droid-Release-Track.
Charakter: vorbereitend; externe Veröffentlichungsaktionen sind nicht freigegeben.

Der zentrale, appübergreifende Ablauf steht in
`/home/tobias/agent/projects/appstores/docs/f-droid-official-catalog-preparation.md`.
Dieses Dokument enthält nur KeepADB-spezifische Aufgaben und Fragen.

## Zielzustand

KeepADB soll in den offiziellen F-Droid-Katalog eingereicht werden. Dafür muss der
Quellcode öffentlich zugänglich, rechtlich nachvollziehbar und aus einem konkreten
Release-Commit mit F-Droid baubar sein.

Bekannte Identität:

- App: KeepADB
- Paket-ID: `de.hohnepeople.keepadb`
- Produktpfad: dieses Repository
- veröffentlichte Version: `1.1.0` / VersionCode `2`
- Ziel: offizieller F-Droid-Katalog

## Signaturstatus

Die dauerhafte Upstream-Signieridentität, ihre öffentliche Zertifikatskennung, die
Vaultwarden-Ablage und der verpflichtende Wiederherstellungscheck sind in
[`docs/release-signing.md`](docs/release-signing.md) dokumentiert. Private Schlüsselwerte
gehören weder in diese Datei noch in andere Projektartefakte.

## Auftrag an den KeepADB-Produktagenten

Arbeite die folgenden Punkte read-only bzw. als klar getrennte Produktänderungen durch.
Ändere keine Repository-Sichtbarkeit und eröffne keinen Merge Request ohne eine neue,
ausdrückliche Freigabe des Nutzers.

### A. Veröffentlichungsbestand prüfen

- [ ] vollständigen Git-Status, Branch, Remote und aktuelle Commit-ID erfassen
- [ ] feststellen, welche Dateien tatsächlich öffentlich werden sollen
- [ ] Secrets, Tokens, private Pfade, lokale Artefakte, Debug-Ausgaben und unnötige
      historische Inhalte suchen
- [ ] `.gitignore`, Workflow-Dateien, README und Release-Dokumentation auf öffentliche
      Konsistenz prüfen
- [ ] prüfen, ob der aktuelle Stand ohne private Dateien aus einem frischen Checkout
      gebaut werden kann

### B. Recht und F-Droid-Kompatibilität

- [ ] Lizenzdatei und Lizenzangaben im Projekt abgleichen
- [ ] Lizenz von Icons, Fonts, Bildern und sonstigen Assets feststellen
- [ ] alle Gradle-/Android-Abhängigkeiten mit Quelle und Lizenz erfassen
- [ ] Netzwerkzugriffe, Telemetrie, Analytics, Werbung und externe Dienste bewerten
- [ ] Manifest-Berechtigungen jeweils mit ihrer konkreten Funktion begründen
- [ ] mögliche F-Droid-Anti-Features benennen oder als nicht vorhanden belegen

### C. Release und Build

- [ ] `versionName`, `versionCode`, Paket-ID und Release-Tag abgleichen
- [ ] geeigneten unveränderlichen Release-Commit vorschlagen
- [ ] sauberen lokalen Release-Build ausführen, sofern dafür die Test-/Buildfreigabe vorliegt
- [ ] erzeugtes Artefakt, Build-Befehl, Toolchain und SHA-256 dokumentieren
- [ ] prüfen, ob F-Droid den Build ohne proprietäre oder nicht verfügbare Komponenten
      reproduzieren kann
- [x] APK-Signatur- und Update-Kompatibilität erklären; keine Schlüsselwerte dokumentieren

### D. F-Droid-Metadaten vorbereiten

- [ ] Vorschlag für die F-Droid-YAML-Datei erstellen
- [ ] `Name`, `Summary`, `Description`, Kategorie und Links aus dem Produktbestand ableiten
- [ ] `RepoType`, `Repo`, `UpdateCheckMode` und vollständigen Release-Commit bestimmen
- [ ] Build-Block mit VersionName und VersionCode formulieren
- [ ] offene Annahmen und F-Droid-spezifische Ausnahmen ausdrücklich markieren

## Rückmeldung

Nach Abschluss dieses Auftrags bitte den Befund hier ablegen:

`notes/f-droid-intake-report.md`

Der Bericht muss enthalten:

- Prüfdatum und geprüften Commit
- Ergebnis je Abschnitt A–D
- konkrete Blocker
- vorgeschlagene Produktänderungen als getrennte Punkte
- vorgeschlagene externe Aktionen, jeweils mit benötigter Freigabe
- verwendete Befehle und relevante Ausgaben, aber keine Secrets

Zusätzlich den Abschluss im kanonischen `notes/runs.md` protokollieren. Der zentrale
Appstores-Track wird anschließend gegen diesen Bericht aktualisiert.

## Harte Grenzen

- Repository nicht öffentlich stellen.
- Nichts zu F-Droid hochladen und keinen Merge Request eröffnen.
- Keine Secrets, Keystores oder vollständigen Zugangsdaten in Berichte schreiben.
- Keine Änderungen an diesem zentralen Appstores-Projekt aus dem Produktagenten heraus,
  außer der Nutzer beauftragt das ausdrücklich.
