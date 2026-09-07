# Issue-Orchestrator-Plan

Verlaufshistorie abgeschlossener Pakete archiviert unter `notes/archive/` (bis 2026-09-06 in
`issue-orchestrator-plan-2026-09-06.md`, bis 2026-09-07 in
`issue-orchestrator-plan-2026-09-07.md`) — nur aktueller Stand hier.

## Aktueller Stand

- Repository-Layout: Der eigentliche App-Code liegt unter `code/v1/` als eigenständiges
  Git-Repo mit `origin` = `https://github.com/m00sfett/KeepADB.git`. Das Haupt-Repo
  (`/home/tobias/agent/projects/keepadb`) trägt Notizen/Instruktionen und hatte im
  Worktree dieses Laufs zwischenzeitlich keinen `origin`-Remote konfiguriert (Sandbox-
  Absicherung, kein Blocker für `gh`-basiertes Arbeiten) — mit Nutzerfreigabe wieder
  gesetzt.
- HEAD `code/v1`@`master`: `fd34726`, versionCode 22 / versionName 1.5.3 (noch **nicht**
  als GitHub-Release getaggt/veröffentlicht — letzter veröffentlichter Tag bleibt `v1.4.5`).
- Offene GitHub-Issues (m00sfett/KeepADB), alle bewertet und einsortiert:
  **#259** (Lizenz-/Website-Zeile in Settings, S1, direkt startbar),
  **#260** (Trusted-Networks-Default auf aktiv, S1, Migrationsentscheidung offen),
  **#263** (Verwalten-Button-Wording, S1, Formulierungsentscheidung offen),
  **#268** (Gesamtzahl beobachteter SSIDs in `KeepADBBssidHistory` unbegrenzt, S1, direkt
  startbar), **#269** (Platzhalter-SSID `UNKNOWN_SSID`, S0, sehr geringe Priorität, direkt
  startbar), **#270** (Trusted-Network-Check robust gegen Hintergrund-BSSID-Maskierung,
  S2, eigene Risikoklasse, direkt startbar),
  **#273** (CI: doppelter Actions-Run pro Push auf offenen PR-Branch, S1, braucht laut
  Policy explizite Freigabe für die Workflow-Änderung selbst, nicht automatisch mit dem
  übrigen Backlog umsetzen).
- Board (`Project #8`) und `github-drift` synchron bis auf den bereits bekannten, nicht zu
  diesem Strang gehörenden `migration/code-v1`-Restbranch (unangetastet gelassen).

## Übergabe-Checkpoint — PR #271 und PR #272 gemergt (2026-09-07)

- **PR #271** (`code/v1`, Branch `feature/262-264-trusted-network-button-bssid`, mit
  Nutzerfreigabe gepusht) schließt **#262, #264, #266** — CI grün, squash-gemergt,
  Branch gelöscht.
- **#267** (Tile zeigt fälschlich „Nicht verbunden") wurde für die drei Tile-bezogenen
  Akzeptanzkriterien (Kriterium 4 bleibt bei #270, Kriterium 5 war bereits erledigt) von
  einem `s2-worker` (sonnet·medium) auf Branch `fix/267-tile-connected-state` umgesetzt und
  von einem zweiten, unabhängigen `s2-worker` (sonnet·medium, `review and repair`) geprüft:
  **approved**, ein Muss-Fix gefunden und selbst repariert (Klick im Unterfall „WLAN-ADB
  tatsächlich aus, Keep-Alive wartet" löste zuvor keinen Reconnect aus). Branch war nach dem
  Merge von PR #271 divergiert (Versionskonflikt 1.5.1/20 vs. 1.5.2/21 in `CHANGELOG.md`/
  `app/build.gradle`) — vom Hauptagenten auf `origin/master` rebast, Versionsnummern auf
  22/1.5.3 neu abgestimmt, alle Gates danach erneut grün. **PR #272** CI grün, gemergt,
  schließt **#267**.
- **Neuer Fund während CI-Beobachtung, registriert:** **#273** — Workflow triggert auf
  `push:` (ohne Filter) UND `pull_request:`, dadurch doppelter Actions-Run pro Commit auf
  einem PR-Branch (beobachtet bei #271 und #272). Nicht selbst behoben — Workflow-Änderung
  braucht eigene Freigabe.
- **issue_snapshot_at:** 2026-09-07T09:20Z (sechs offene Issues: #259, #260, #263, #268,
  #269, #270, plus neu #273)
- **plan_updated_at:** 2026-09-07T09:20Z

## Nächster Schritt

Kein offener Branch mehr, kein offener PR. Direkt startbare Backlog-Optionen:

- **#269** (S0, sehr geringe Priorität) — kleinster mechanischer Fix.
- **#268** (S1) — Gesamtzahl beobachteter SSIDs in `KeepADBBssidHistory` unbegrenzt.
- **#270** (S2, eigene Risikoklasse) — Trusted-Network-Check robust gegen Hintergrund-
  BSSID-Maskierung.
- **#273** (S1, aber Workflow-Änderung — eigene Freigabe nötig, nicht automatisch)

Nicht direkt startbar ohne Nutzerentscheidung: **#260** (Migrationsentscheidung offen),
**#263** (Formulierungsentscheidung offen). **#259** ist S1 und direkt startbar, unabhängig.

Kein Strang-Übergewicht erkennbar: #262/#264/#266/#267 waren technisch verwandt (Trusted
Networks/Tile), aber jedes einzeln nutzersichtbar und abgeschlossen — kein reiner
Infrastruktur-/Nebenstrang ohne Produktfortschritt.
