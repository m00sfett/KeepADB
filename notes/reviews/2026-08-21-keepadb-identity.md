# KeepADB-Identitätsreview — 2026-08-21

## Urteil

- **Code-Review:** `approved` für den Folge-Head `5b862dd`. Der einzige technische
  Vertragsbefund wurde minimal behoben; Produkt-/Paketidentität und Transport-Enum sind jetzt
  getrennt korrekt.
- **Gesamtabnahme/Merge-Gate:** `not approved` bis der Android-Smoke für die neue
  Ersteinrichtung nachgeholt ist. Der Plan definiert `approved` ausdrücklich als lokale Gates,
  freigegebenen Android-Smoke, unabhängigen Review und erforderliche PR-Checks. F-001 stammt
  aus dem vorherigen Review und wurde in diesem Follow-up weder geprüft noch ausgeführt;
  Geräte-/UI-Aktionen bleiben ausgeschlossen.

## Scope und Serverstand

- Branch: `refactor/keepadb-identity`
- Review-Head: `5b862dd750540dec37ccab5d4cd8a251f505e723`
- Vergleichsbasis: `master` (`d906a8a7c5c230585ada6e84efef26f0392e1513`)
- PR: #73, Head stimmt mit dem Review-Head überein; beim Follow-up war
  `mergeStateStatus=UNSTABLE`, weil der automatische PR-CI-Run noch lief.
- CI: Der vorherige Run `32469637963` für den ursprünglichen Review-Head war grün. Für den
  Folge-Head war beim Nachtrag ein automatischer PR-Run aktiv; er wurde weder retriggert noch
  für dieses Review abgewartet.
- Repository: `m00sfett/KeepADB`, GitHub-Readback `private=true`; lokaler Ordnername wurde
  nicht verändert und war nicht Teil des Scopes.
- Branch Protection konnte für das private Repository nicht gelesen werden (GitHub HTTP 403
  im aktuellen Tarif); der grüne PR-Check ist separat nachgewiesen.

## Findings

### F-001 — Android-Smoke der neuen First-run-Strecke fehlt (übernommenes Gate,
merge-blockierend)

Dieser Befund stammt aus dem Review des vorherigen Heads `cb27344…`. Die statische Strecke ist
vorhanden: `MainActivity.refresh()` blendet das Setup-Panel bei
fehlendem `WRITE_SECURE_SETTINGS` ein und deaktiviert beide Funktionsschalter
(`MainActivity.java:99-113`); `activity_main.xml:26-68` enthält die Einrichtung mit
USB-Hinweis, Befehl und Prüfschaltfläche. Der damalige APK-/Ressourcen-Readback bestätigte die
Artefakte, bewies aber nicht das Verhalten einer installierten App ohne Grant, nach Grant und
nach dem Zurückkehren in den normalen Pfad.

Im Folge-Review wurde dieser Smoke weder ausgeführt noch erneut geprüft; Geräte-/UI-Aktionen
sind ausdrücklich ausgeschlossen. Offen bleibt deshalb unverändert die Messung: deutsch und
englisch vor Grant, beide Schalter deaktiviert; danach der exakt angezeigte Grant und ein
Refresh, bei dem Panel verschwindet und beide Schalter aktiv werden.

### F-002 — `testDebugUnitTest` enthält keine Tests (Nachweisgrenze, nicht neu)

Der vom Implementierer dokumentierte lokale Lauf ist grün, meldet für `testDebugUnitTest` aber
`NO-SOURCE`. Das ist für diesen reinen Rename nicht selbst ein Code-Finding; die First-run-
Akzeptanz wird dadurch jedoch nicht regressionsgeschützt. Der fehlende UI-Smoke bleibt der
entscheidende offene Nachweis.

### F-003 — Behoben: Register-Methodenwert ist ein technischer Transport-Enum

Der vorherige Head sendete `"method": "keepadb"` und verletzte damit den bestehenden
Serververtrag: `/home/tobias/agent/bin/phone-register-server:26` definiert
`VALID_METHODS = {"wlan-adb", "ssh-termux", "usb-adb", "usb-ssh-tunnel"}` und weist ungültige
Werte in den normalen POST-Pfad (`:293-295`) mit HTTP 400 zurück. Commit `5b862dd` setzt in
`KeepADBRegisterClient.java:92` minimal wieder `"method": "wlan-adb"`.

Das ist technisch korrekt und mit dem Rename vereinbar: `KeepADB` bleibt Produktname,
Application ID, Namespace, Klassen-, Ressourcen- und Release-Identität; `wlan-adb` ist der
serverseitige Transport-Enum. Der Wert stimmt außerdem mit dem historischen Register-Readback
im Plan (`:323`) überein. F-003 ist damit behoben; kein weiterer Fix erforderlich.

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
| Register-Transportvertrag bleibt gültig | `KeepADBRegisterClient.java:92`; `phone-register-server:26,293-295` akzeptieren `wlan-adb` und lehnen `keepadb` ab | nach `5b862dd` bestanden |
| Plattformverträge bleiben unverändert | `adb_wifi_enabled` und `_adb-tls-connect._tcp` in Java/Manifest-Diff; keine geänderte Vertragsliteral-Zeile | bestanden |
| Legacy-Scan ist sauber | case-sensitive/case-insensitive Scan außerhalb historischer Plan-/Changelog-Einträge; zusätzlich `apkanalyzer dex packages` | keine alten Projekt-, Paket-, Klassen- oder Ressourcenbezeichner im ausgelieferten Scope |

## Reparaturen und Validierung

- Der Hauptagent reparierte den Vertragswert minimal in `5b862dd`; dieser Folge-Review bestätigt
  nur die eine Zeile gegen den echten Serververtrag. Keine weitere Produktreparatur erforderlich.
- Reviewbericht ist die einzige von diesem Folge-Review zu ändernde Datei:
  `notes/reviews/2026-08-21-keepadb-identity.md`.
- Frisch read-only ausgeführt: Branch-/Commit-/Diff-Status und Auswertung von
  `/home/tobias/agent/bin/phone-register-server` gegen `KeepADBRegisterClient.java:92`.
- Nicht erneut ausgeführt: Gradle-, Unit-, Lint- oder Geräte-/UI-Gates. Der Hauptagent meldet
  `git diff --check` sowie `lintDebug assembleDebug testDebugUnitTest` grün (`NO-SOURCE` bei
  Unit-Tests); der aktive PR-CI-Run wurde nicht retriggert oder abgewartet.

## Handoff

- Reviewstatus: `approved` für den Code-Head `5b862dd` (F-003 behoben), `not approved` für die
  vollständige Merge-Abnahme wegen des aus dem Erstreview übernommenen F-001-Gerätegates.
- Serverstatus: PR offen, Head `5b862dd`; beim Nachtrag lief der automatische PR-CI-Run bereits,
  ohne dass er retriggert oder ausgewertet wurde. Keine Geräteaktion.
- Erforderlicher nächster Nachweis: freigegebener Android-Smoke der deutschen/englischen
  First-run-Permission-Strecke; danach Merge-Entscheidung neu prüfen.
