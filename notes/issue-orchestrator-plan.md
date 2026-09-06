# Issue-Orchestrator-Plan

Verlaufshistorie abgeschlossener Pakete (bis 2026-09-05) archiviert unter
`notes/archive/issue-orchestrator-plan-2026-09-06.md` — nur aktueller Stand hier.

## Aktueller Stand

- Offene GitHub-Issues (m00sfett/KeepADB): nur **#257** (Versionsbump/Changelog-Nachtrag für
  PR #254, unbewertet — reine Doku-/Versionskorrektur, kein Codepfad).
- HEAD `master`: `a32769f`, versionCode 18 / versionName 1.4.6.
- Board (`Project #8`) und `github-drift` synchron; einziger verbliebener Drift-Fund
  (`migration/code-v1`-Restbranch) gehört nicht zu diesem Strang, unangetastet gelassen.

## Abschluss #248 — 2026-09-06

**Besonderheit:** Während der Umsetzung wurde festgestellt, dass `master` durch eine parallele,
unabhängige Session (PR #254, 9-Issue-Batch) bereits eine eigene, bewusst partielle Umsetzung
von #248 gemergt hatte (`KeepADBSettingsGateway`/`KeepADBScheduler`-Interfaces). Mein eigener
Branch (PR #255) wurde dadurch nicht mehr konfliktfrei mergebar — echter Architekturkonflikt,
kein mechanischer Rebase-Fehler.

**Auflösung:** Nutzerentscheidung: unabhängiger s4-worker (Opus 5 · low) sollte beide Designs
vergleichen und eine Synthese bauen, statt eine Seite blind zu bevorzugen. Ergebnis: PR #254s
Gateway/Scheduler-Grenzen beibehalten, den fehlenden State-Kern (`KeepADBToggleState`,
plattformunabhängig) und eine dritte, in keinem der beiden Vorentwürfe vorhandene Grenze
(`KeepADBSurfaceRefresher` für Notification/Widget/Service-Refresh) ergänzt — letzteres, weil
das Akzeptanzkriterium „UI refreshes … isolated behind explicit boundaries" sonst nur zur
Hälfte erfüllt gewesen wäre. Zweiter, unabhängiger s4-worker-Review: `approved`, insbesondere
Locking im Recovery-Pulse-Pfad geprüft (Nebenläufigkeitsgarantien strikt stärker als vorher,
keine neue Race). PR #255 als „superseded" geschlossen, PR #256 (reconciled) gemergt, #248
automatisch geschlossen. Versionsbump 1.4.6/18 samt Changelog im selben PR.

**Nebenfund:** PR #254 hatte für ihren 9-Issue-Batch weder Versionsbump noch Changelog-Eintrag
gesetzt, obwohl darunter sicherheitsrelevante Änderungen sind (neue Permissions,
Trusted-Network-Gating, Backup-Policy). Als Issue #257 registriert, nicht selbst behoben
(Minor-vs-Patch-Entscheidung braucht Nutzerfreigabe, außerhalb des #248-Scopes).

**Strangzähler:** #248 war ein isoliertes Architektur-Issue, kein Teil eines größeren Strangs.
Der Nebenfund #257 stammt aus einem fremden Strang (dem 9-Issue-Batch einer anderen Session)
und wird hier nicht automatisch weiterverfolgt.

## Paket: #257 — Versionsbump/Changelog-Nachtrag für PR #254

- **Ziel:** Retrospektive `CHANGELOG.md`-Einträge für #245–#253 ergänzen, `versionCode`/
  `versionName` entsprechend erhöhen, passende Fastlane-Changelog-Datei(en) ergänzen.
- **Nicht-Ziele:** keine Codeänderung, kein neuer Aufrufpfad, keine Rückwirkung auf #248/#256.
- **Zerlegung:** Einzelpaket ausreichend — reine Doku-/Versionsmetadaten, ein zusammenhängender
  Commit.
- **Stufe:** `agent-stage:s1` (gesetzt) — Dokumentation/Versionsmetadaten, kein Urteilsanteil
  über die reine Textformulierung hinaus.
- **Blocker vor Implementierung:** Die Wahl Minor- vs. Patch-Bump ist keine Stufenfrage, sondern
  eine Policy-Entscheidung nach `AGENTS.md` („Änderungen auf Minor- oder Major-Ebene benötigen
  vor der Umsetzung eine ausdrückliche Nutzerfreigabe, die begründet, warum die Änderung einen
  Minor- statt Patch-Bump rechtfertigt"). PR #254 fügte neue Permissions (`ACCESS_FINE_LOCATION`,
  `ACCESS_COARSE_LOCATION`, `ACCESS_WIFI_STATE`) und ein neues, standardmäßig deaktiviertes
  Trusted-Network-Gating-Feature hinzu — das spricht für Minor statt Patch, ist aber eine
  Nutzerentscheidung, keine Routineableitung.

## Nächster Schritt

#257 ist eingestuft (S1), aber die Minor-vs-Patch-Frage braucht vor der Umsetzung eine
ausdrückliche Nutzerentscheidung (siehe oben). Regulärer Lauf ohne `--plan` sollte diese Frage
zuerst stellen, bevor implementiert wird.

- **issue_snapshot_at:** 2026-09-06T20:58:24Z (ein offenes Issue, #257)
- **plan_updated_at:** 2026-09-06 (--plan-Lauf, Nachtrag)
