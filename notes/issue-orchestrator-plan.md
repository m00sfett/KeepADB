# Issue-Orchestrator-Plan

Verlaufshistorie abgeschlossener Pakete (bis 2026-09-05) archiviert unter
`notes/archive/issue-orchestrator-plan-2026-09-06.md` — nur aktueller Stand hier.

## Aktueller Stand

- Offene GitHub-Issues (m00sfett/KeepADB), alle bewertet und einsortiert (`--plan`-Lauf
  2026-09-06): **#259** (Lizenz-/Website-Zeile in Settings, S1), **#260** (Trusted-Networks-
  Default auf aktiv, S1, Migrationsentscheidung offen), **#262** (Button hinzufügen/entfernen,
  S1), **#263** (Verwalten-Button-Wording, S1, Formulierung braucht Nutzerentscheidung),
  **#264** (BSSID in Verwaltungsliste, S1), **#265** (Mesh-/AP-Wechsel-Verhalten, S2, reine
  Architekturentscheidung, kein Umsetzungsauftrag). #261 war ein Sammel-Issue mit vier fachlich
  unabhängigen Teilen (Bündelungsgrenze verletzt) und wurde in #262–#265 zerlegt, dann
  geschlossen.
- HEAD `master`: `879bb59`, versionCode 19 / versionName 1.5.0 (noch **nicht** als GitHub-Release
  getaggt/veröffentlicht — letzter veröffentlichter Tag bleibt `v1.4.5`).
- Board (`Project #8`) und `github-drift` synchron bis auf einen bereits bekannten Fund
  (`migration/code-v1`-Restbranch, gehört nicht zu diesem Strang, unangetastet gelassen).

## Abschluss `--cleanup`-Lauf — 2026-09-07

**Baseline:** letzter veröffentlichter GitHub-Release `v1.4.5` (2026-09-04). **Kandidat:**
`master`@`879bb59` (versionCode 19, versionName 1.5.0, unveröffentlicht).

**Ledger-Prüfung (Baseline → Kandidat, 19 Commits):**
- Git-Zustand: `git status --short` leer, kein offener PR, kein aktiver CI-Run auf einem
  fremden Head.
- Versions-/Doku-Ledger: `versionCode 19` / `1.5.0` konsistent in `app/build.gradle`,
  `CHANGELOG.md`, `fastlane/.../18.txt` und `19.txt`. `README.md`-Erwähnung von `v1.4.5` ist
  ein illustrativer Beispielbefehl, keine Statusaussage — kein Korrekturbedarf.
- `git grep TODO/FIXME/XXX`: kein echter Fund (ein Datumsformat-Muster als Fehlalarm).
- Externe Produktwahrheit: keine neuen releasekritischen Fremd-URLs seit Baseline (nur ein
  GitHub-eigener Security-Advisory-Link in `SECURITY.md`). Feedback-URL
  `hohnepeople.de/keepadb/feedback` unverändert seit Baseline; Live-Readback schlägt in dieser
  Sandbox weiterhin mit TLS-Kettenfehler fehl — bereits als
  [hohnepeople-de#35](https://github.com/m00sfett/hohnepeople-de/issues/35) registriert, dort
  weiterhin offen, kein KeepADB-Blocker.
- Issue-Ledger: PR #254 (9 Issues, #245–#253) hatte nur Selbstprüfung des Implementierers,
  kein unabhängiger Review, kein Gerätetest. Nachgeholt: unabhängiger s4-worker-Review des
  kumulierten Diffs `v1.4.5..HEAD` — `approved`, ein echter Fund (Fail-open-Lücke bei
  All-Null-BSSID `00:00:00:00:00:00` auf manchen Geräten/OEM-Builds) behoben in `879bb59`.
- Kandidatengenaue Abnahme: `bin/verify` grün (inkl. `--rerun-tasks`-Gegenprobe),
  `bin/check-i18n` sauber. Signierte Release-APK (v1.5.0/19) gebaut und gegen die dokumentierte
  Signieridentität verifiziert (`docs/release-signing.md`).
- Geräte-Smoke-Test auf s20 (SM-G780G): **Fund unterwegs, kein Blocker.** Die zuvor unter der
  Produktions-Paket-ID installierte „v1.4.5" war entgegen der Dokumentation ein
  **Debug-signierter Build** (`CN=Android Debug`, `DEBUGGABLE`-Flag) statt der echten
  Release-APK — daher `INSTALL_FAILED_UPDATE_INCOMPATIBLE` beim Versuch, die echte Release-APK
  aufzuspielen. Mit expliziter Nutzerfreigabe deinstalliert (App-Daten verloren, Neueinrichtung
  von Webhook/Settings nötig) und die echte, signierte v1.5.0/19 frisch installiert — jetzt
  korrekt ohne `DEBUGGABLE`/`ALLOW_BACKUP`. Nachgewiesen am Gerät: kein Crash beim App-Start
  (R8-Minifizierung intakt), Endpoint-Discovery findet den echten Verbindungsendpunkt korrekt,
  UI-Toggle schaltet Wireless Debugging nachweislich aus (Verbindungsabbruch exakt beim Tippen,
  da die Testverbindung selbst über Wireless-ADB lief). Tiefere Gerätetests der
  Trusted-Network-Allowlist (Location-Permission-Flow, BSSID-Matching live) auf Nutzerentscheid
  nicht durchgeführt — dafür bereits vorhandene Unit-/Contract-Testabdeckung plus der
  unabhängige Code-Review gelten als ausreichend.
  **Register-Nebenfund:** s20 lief zeitweise über einen hybriden USB/mDNS-TLS-Connect-Kanal,
  der beim Toggeln von Wireless Debugging selbst zusammenbrach (erwartetes Verhalten, kein
  Bug) — Register jetzt auf `wlan-adb 192.168.178.24:34555` aktualisiert; dieser Port ist nach
  dem letzten Toggle-Test aktuell nicht erreichbar, bis der Nutzer Wireless Debugging am Gerät
  wieder einschaltet.
- **Neuer Fund während des Laufs:** #259 (Lizenz-/Website-Zeile in Settings) wurde extern
  angelegt, nicht Teil dieses Kandidaten. Gehört zum nächsten `--plan`/regulären Lauf.

**Übergabe: `cleanup-ready`** — Produkt-, Versions-, Doku- und Repo-Zustand des Kandidaten
`879bb59` (v1.5.0/19) sind für einen separaten Release-Audit bereit; dies ist keine
Releasefreigabe. Einzige offene Randnotiz: s20 aktuell nicht erreichbar (Nutzeraktion nötig,
kein Kandidatenblocker), und #259 als neues, noch unbewertetes Backlog-Item.

## Übergabe-Checkpoint — #262 + #264 (2026-09-06)

- **Paket:** #262 (Button hinzufügen/entfernen dynamisch) + #264 (BSSID in Verwaltungsliste),
  S1, selbst umgesetzt (kein Subagent, kein separater Review nötig unter S2).
- **Branch:** `feature/262-264-trusted-network-button-bssid`, Commit `d8ae1b5`
  (`code/v1`-Repo). **Nicht gepusht** — Push/PR würde laut Projekt-CI (seit #246: läuft auf
  jedem Push/PR) einen neuen GitHub-Actions-Run auslösen, der eine eigene Freigabe braucht.
- **Erledigte Akzeptanzkriterien:** Button-Text wechselt dynamisch (`refresh()`), Tap auf
  "entfernen" entfernt BSSID-basiert, unterschiedliche Toasts (added/removed/failed), BSSID als
  Sekundärzeile im Verwaltungsdialog, keine neuen hardcodierten Strings.
- **Ausgeführte Gates:** `bin/check-i18n` sauber (alle 18 Sprachen inkl. der 2 neuen Strings
  `settings_trusted_network_remove_button`/`_removed_toast`), `bin/verify` grün (Unit-Tests
  inkl. neuem `findAndRemoveCurrentNetworkFailWithoutAKnownIdentity`, Lint, Debug- und
  Release-Build).
- **Versionsnachweis:** versionCode 19→20, versionName 1.5.0→1.5.1 (Patch, innerhalb bestehender
  Freigabe). `CHANGELOG.md` und `fastlane/.../changelogs/20.txt` nachgezogen.
- **Ungeprüft/nachgelagert:** Geräte-/UI-Smoke-Test des Button-Umschaltens und der
  BSSID-Anzeige (keine Testfreigabe in diesem Lauf erteilt).
- **Nutzerentscheidung 2026-09-06:** Vorerst nicht pushen/PR öffnen (b) — Branch bleibt lokal,
  kein CI-Run ausgelöst. #262/#264 bleiben auf GitHub offen, bis diese Entscheidung revidiert
  wird.

## Nächster Schritt

Branch `feature/262-264-trusted-network-button-bssid` (Commit `d8ae1b5`) bleibt lokal liegen,
bis der Nutzer Push/PR freigibt. Bis dahin im Backlog verfügbar: **#259** (unabhängig, direkt
startbar, S1), **#260** (Migrations-Entscheidung offen), **#263** (Wording-Entscheidung offen),
**#265** (reine Architekturfrage, kein Implementierungsauftrag).

- **issue_snapshot_at:** 2026-09-06T22:34Z (sechs offene Issues: #259, #260, #262, #263, #264, #265)
- **plan_updated_at:** 2026-09-06
