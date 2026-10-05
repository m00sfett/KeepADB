# Paketname (`applicationId`) bleibt `de.hohnepeople.keepadb`

Entscheidung vom 2026-10-05 (Nutzer, im Zuge des Website-Umzugs nach `keepadb.roteson.de`).

## Entscheidung

`applicationId` und `namespace` bleiben `de.hohnepeople.keepadb`. Der Paketname ist eine
historische Kennung und muss nicht zu einer Domain passen. Er erscheint für Nutzer nirgends.
Der Website-Umzug ändert nur URLs (Einstellungen, Feedback-Link, Store-Metadaten), nicht die
App-Identität.

## Begründung

- Android behandelt eine neue `applicationId` als eine andere App: kein Update, keine
  Übernahme von Einstellungen und vertrauenswürdigen Netzen, auch nicht mit demselben
  Signaturschlüssel. Nutzer müssten neu einrichten und hätten zeitweise zwei Apps.
- Eine neue Versionsnummer (auch 2.0.0) ändert daran nichts; die Identität hängt allein an der ID.
- F-Droid führt den Eintrag pro Paketname. Eine neue ID bräuchte einen neuen Inclusion-MR samt
  Review; der alte Eintrag ließe sich nicht umleiten. Update-Historie ginge verloren.

## Was stattdessen gilt

- Neue Links (`https://keepadb.roteson.de`, Feedback unter `/feedback`) kommen mit dem nächsten
  regulären Release über den `release-fdroid`-Zyklus (Issue #754).
- Beim Release `WebSite:` in `fdroiddata/metadata/de.hohnepeople.keepadb.yml` von
  `https://hohnepeople.de/keepadb` auf `https://keepadb.roteson.de` ändern (Stand 2026-10-05
  zeigt der Eintrag noch auf hohnepeople.de).
- Ältere installierte Versionen (bis 1.8.38) öffnen weiter hohnepeople.de; die Seite leitet
  dort auf die neue Website weiter. Hinweis zur TLS-Kette: Issue #749.

## Wann neu bewerten

Nur bei einem bewusst geplanten harten Neustart der App (neue Identität, Datenbruch akzeptiert).
Dafür liegt aktuell kein Anlass vor.
