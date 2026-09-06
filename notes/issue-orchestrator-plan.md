# Issue-Orchestrator-Plan

Verlaufshistorie abgeschlossener Pakete (bis 2026-09-05) archiviert unter
`notes/archive/issue-orchestrator-plan-2026-09-06.md` — nur aktueller Stand hier.

## Aktueller Stand

- Offene GitHub-Issues (m00sfett/KeepADB): **keine**.
- HEAD `master`: `8e19d46`, versionCode 19 / versionName 1.5.0.
- Board (`Project #8`) und `github-drift` synchron; einziger verbliebener Drift-Fund
  (`migration/code-v1`-Restbranch) gehört nicht zu diesem Strang, unangetastet gelassen.

## Abschluss #257 — 2026-09-06

Retrospektiver Changelog-Eintrag für den 9-Issue-Batch aus PR #254 (#245–#253) plus
Versionsbump. Nutzerentscheidung: Minor-Bump (1.4.6 → 1.5.0), begründet durch neue Permissions
und ein neues, sichtbares Opt-in-Feature (Trusted-Network-Allowlist). Reine Doku-/
Versionsmetadaten, kein Codepfad, S1 ohne separaten Review (Selbstabnahme gegen
Akzeptanzkriterien). PR #258 gemergt, #257 automatisch geschlossen.

**Strangzähler:** #257 war ein isolierter Nebenfund aus dem #248-Strang, jetzt erledigt. Der
KeepADB-Backlog ist damit leer.

## Nächster Schritt

Keine offenen Issues. Nächster sinnvoller Schritt liegt außerhalb des Issue-Backlogs — z. B.
`$release-fdroid --dry` zur Prüfung des aktuellen Kandidaten (v1.5.0/19), falls eine
Release-Übergabe ansteht, oder Warten auf neue Issues/Nutzeraufträge.

- **issue_snapshot_at:** 2026-09-06T21:16Z (0 offene Issues)
- **plan_updated_at:** 2026-09-06
