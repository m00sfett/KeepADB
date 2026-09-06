# Issue-Orchestrator-Plan

Verlaufshistorie abgeschlossener Pakete (bis 2026-09-05) archiviert unter
`notes/archive/issue-orchestrator-plan-2026-09-06.md` — nur aktueller Stand hier.

## Aktueller Stand

- Offene GitHub-Issues (m00sfett/KeepADB): nur **#248**.
- Letzter `--cleanup`-Lauf (2026-09-04): `cleanup-ready`. Seitdem keine neuen Commits in
  diesem Repo außer Notizen/Planungsdateien.
- TLS-Finding zu `hohnepeople.de` bereits als [hohnepeople-de#35](https://github.com/m00sfett/hohnepeople-de/issues/35)
  registriert, gehört nicht zu diesem Projekt.

## Paket: #248 — State/Side-Effect-Trennung in `KeepADB.java`

- **Issue:** [#248](https://github.com/m00sfett/KeepADB/issues/248) „Separate KeepADB state
  management from Android side effects".
- **Ziel:** `KeepADB.java` (305 Zeilen, aktuell: Debouncing, Intent-Tokens, Persistenz,
  `Settings.Global`-Schreibzugriffe, Service-Sync, Notification-/Widget-Refresh, statischer
  mutabler State) in klar getrennte Komponenten zerlegen: plattformunabhängiges
  State/Intent-Modell, `Settings.Global`-Schreibkomponente, Preferences-Komponente,
  Orchestrierungs-/Service-Koordinator, Notification-/Widget-Refresh als Output-Effekt statt
  Modellverantwortung.
- **Nicht-Ziele:** keine Verhaltensänderung an User-Intent-/Recovery-Semantik, keine neue
  Laufzeit-Dependency, keine UI-/API-Änderung.
- **Akzeptanzkriterien (aus Issue-Text):** State-Transitions ohne Android-Framework-Seiteneffekte
  testbar; Settings-Writes/UI-Refresh hinter expliziten Grenzen isoliert; bestehende
  User-Intent-/Recovery-Semantik unverändert; statischer mutabler State reduziert oder
  Lebenszyklus explizit begründet; bestehende Tests bleiben grün, neue Tests decken
  extrahiertes State-Verhalten ab; keine neue Dependency.
- **Zerlegung:** Einzelpaket ausreichend — die drei Komponenten (State-Modell,
  Settings-Writer, Koordinator) sind an denselben Codepfad und dieselbe Testbasis gekoppelt;
  eine Aufteilung in separate Issues würde Zwischenzustände mit gebrochenen Invarianten
  erzeugen (Rollback-Grenze ist die ganze Refaktorierung, nicht ein Teilschritt).
- **Stufe:** `agent-stage:s3` — Multi-File-Refactor mit Regressionsrisiko (bestehende
  Debounce-/Token-/Recovery-Semantik darf sich nicht ändern), aber kein Datenmodell-Wechsel,
  keine Migration, keine externe Schnittstelle, kein Security-kritischer Pfad → kein S4.
- **Review:** ab S2 verbindlich vorgeschrieben (Abschnitt 2) → eigener unabhängiger Review
  nach Implementierung, vor Merge.
- **Freigabe:** noch keine Implementierungsfreigabe erteilt (`--plan`-Lauf, reine Einordnung).

## Nächster Schritt

Reguläre Auswahl (ohne `--plan`) kann #248 direkt als einziges offenes Paket aufgreifen,
sobald der Nutzer die Umsetzung freigibt.

- **issue_snapshot_at:** 2026-09-06T13:06:37Z (Stand `gh issue list`, ein offenes Issue)
- **plan_updated_at:** 2026-09-06
