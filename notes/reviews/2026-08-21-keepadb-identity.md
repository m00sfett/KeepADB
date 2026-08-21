# KeepADB-Identitätsreview — 2026-08-21

## Urteil

- **Code-Review:** `approved`. Im beauftragten Rename-Scope wurde kein reparaturpflichtiger
  Produktbefund gefunden.
- **Gesamtabnahme/Merge-Gate:** `not approved` bis der Android-Smoke für die neue
  Ersteinrichtung nachgeholt ist. Der Plan definiert `approved` ausdrücklich als lokale Gates,
  freigegebenen Android-Smoke, unabhängigen Review und erforderliche PR-Checks; der aktuelle
  Auftrag verbietet Geräte-/UI-Aktionen. Der fehlende Smoke ist daher ein offenes Gate, kein
  statischer Codefehler.

## Scope und Serverstand

- Branch: `refactor/keepadb-identity`
- Review-Head: `cb27344ad347e0fb17aaa000b4a30d761bf6c2aa`
- Vergleichsbasis: `master` (`d906a8a7c5c230585ada6e84efef26f0392e1513`)
- PR: #73, Head stimmt mit dem Review-Head überein, `mergeStateStatus=CLEAN`.
- CI: Run `32469637963` für exakt diesen Head, `Lint & Build Debug APK`, `success`.
- Repository: `m00sfett/KeepADB`, GitHub-Readback `private=true`; lokaler Ordnername wurde
  nicht verändert und war nicht Teil des Scopes.
- Branch Protection konnte für das private Repository nicht gelesen werden (GitHub HTTP 403
  im aktuellen Tarif); der grüne PR-Check ist separat nachgewiesen.

## Findings

### F-001 — Android-Smoke der neuen First-run-Strecke fehlt (Gate, merge-blockierend)

Die statische Strecke ist vorhanden: `MainActivity.refresh()` blendet das Setup-Panel bei
fehlendem `WRITE_SECURE_SETTINGS` ein und deaktiviert beide Funktionsschalter
(`MainActivity.java:99-113`); `activity_main.xml:26-68` enthält die Einrichtung mit
USB-Hinweis, Befehl und Prüfschaltfläche. Der aktuelle APK-/Ressourcen-Readback bestätigt die
Artefakte, beweist aber nicht das Verhalten einer installierten App ohne Grant, nach Grant und
nach dem Zurückkehren in den normalen Pfad.

Der Smoke wurde wegen des ausdrücklichen Auftrags „keine Geräte-/UI-Aktionen“ nicht ausgeführt.
Offen bleibt deshalb die Messung: deutsch und englisch vor Grant, beide Schalter deaktiviert;
danach der exakt angezeigte Grant und ein Refresh, bei dem Panel verschwindet und beide Schalter
aktiv werden. Keine Reparatur vorgenommen.

### F-002 — `testDebugUnitTest` enthält keine Tests (Nachweisgrenze, nicht neu)

Der vom Implementierer dokumentierte lokale Lauf ist grün, meldet für `testDebugUnitTest` aber
`NO-SOURCE`. Das ist für diesen reinen Rename nicht selbst ein Code-Finding; die First-run-
Akzeptanz wird dadurch jedoch nicht regressionsgeschützt. Der fehlende UI-Smoke bleibt der
entscheidende offene Nachweis.

### F-003 — Optionaler Webhook-Methodenwert nicht Ende-zu-Ende verifiziert (Risiko, kein Muss-Fix)

`KeepADBRegisterClient` sendet jetzt den Produktwert `"method": "keepadb"`; der historische
Register-Readback im Plan dokumentiert noch `"wlan-adb"` (Plan `:323`). Das ist als Teil des
gewünschten ausgelieferten KeepADB-Identifiers plausibel, aber die optionale externe
Webhook-Kompatibilität wurde im Rename-Auftrag weder als Gate noch per Geräte-/Servertest
abgenommen. Kein Fix im Rename-Review: Eine Änderung des externen Vertrags wäre Scope-/Risiko-
Erweiterung.

## Geprüfte Behauptungen

| Behauptung | Nachweis | Ergebnis |
|---|---|---|
| Produkt-/Projektname und ausgelieferte Bezeichner sind KeepADB | `README.md`, `CHANGELOG.md`, `settings.gradle:14`, Release-Workflow `:36-42`, Ressourcen, DEX-Readback | bestanden |
| Application ID und Namespace sind konsistent | `app/build.gradle:6,10`; `apkanalyzer manifest application-id` | `de.hohnepeople.keepadb`, bestanden |
| Activity, Service, Tile, Widget, Receiver und Toggle-Action zeigen auf den neuen Namespace | `AndroidManifest.xml:22-67`; `apkanalyzer manifest print`; Klassenpfad-Existenzcheck | bestanden |
| Grant-Befehl ist lokalisiert und exakt passend | `values/strings.xml:5-8` und `values-de/strings.xml:5-8`; beide ergeben exakt `adb shell pm grant de.hohnepeople.keepadb android.permission.WRITE_SECURE_SETTINGS` | statisch bestanden |
| Vor Grant sind Funktionsschalter gesperrt, danach normaler Pfad | `MainActivity.java:99-113`, `activity_main.xml:26-68,97-120` | statisch bestanden; Smoke offen (F-001) |
| Neue App-ID ist eine separate Installation, ohne Migration/Löschung | neue `applicationId`; keine Installations-/Uninstall-/Migrationslogik im Scope; README-Kompatibilitätshinweis | bestanden |
| Release-Artefakte heißen KeepADB | `.github/workflows/release.yml:36-42` | bestanden |
| Plattformverträge bleiben unverändert | `adb_wifi_enabled` und `_adb-tls-connect._tcp` in Java/Manifest-Diff; keine geänderte Vertragsliteral-Zeile | bestanden |
| Legacy-Scan ist sauber | case-sensitive/case-insensitive Scan außerhalb historischer Plan-/Changelog-Einträge; zusätzlich `apkanalyzer dex packages` | keine alten Projekt-, Paket-, Klassen- oder Ressourcenbezeichner im ausgelieferten Scope |

## Reparaturen und Validierung

- Keine Produktreparatur erforderlich; deshalb kein Code-Fix-Commit.
- Reviewbericht ist die einzige neue Datei: `notes/reviews/2026-08-21-keepadb-identity.md`.
- Frisch read-only ausgeführt: Git-/PR-/CI-Statusabfrage, Repository-Privatheitsabfrage,
  `apkanalyzer`-Manifest-/DEX-Readback, Ressourcen-/Grant-Abgleich, Manifest-
  Klassenpfadprüfung und Legacy-Scans.
- Nicht erneut ausgeführt: Gradle-, Unit-, Lint- oder Geräte-/UI-Gates. Die vorhandenen grünen
  lokalen Nachweise und CI-Run wurden dem aktuellen Head zugeordnet; der Auftrag verbietet die
  Geräte-/UI-Aktion.

## Handoff

- Reviewstatus: `approved` für den statischen Codeumfang, `not approved` für die vollständige
  Merge-Abnahme wegen F-001.
- Serverstatus: PR offen, Head aktuell, CI grün; kein CI-Retrigger und keine Geräteaktion.
- Erforderlicher nächster Nachweis: freigegebener Android-Smoke der deutschen/englischen
  First-run-Permission-Strecke; danach Merge-Entscheidung neu prüfen.
