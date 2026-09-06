# Issue-Orchestrator-Plan

Verlaufshistorie abgeschlossener Pakete (bis 2026-09-05) archiviert unter
`notes/archive/issue-orchestrator-plan-2026-09-06.md` — nur aktueller Stand hier.

## Aktueller Stand

- Offene GitHub-Issues (m00sfett/KeepADB): **#259** (neu, unbewertet — Lizenz-/Website-Zeile
  in Settings, vom Nutzer/einer anderen Session direkt angelegt, außerhalb dieses Laufs).
- HEAD `master`: `879bb59`, versionCode 19 / versionName 1.5.0 (noch **nicht** als GitHub-Release
  getaggt/veröffentlicht — letzter veröffentlichter Tag bleibt `v1.4.5`).
- Board (`Project #8`) und `github-drift` synchron; einziger verbliebener Drift-Fund
  (`migration/code-v1`-Restbranch) gehört nicht zu diesem Strang, unangetastet gelassen.

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

## Nächster Schritt

Bei Interesse an einer echten Veröffentlichung: `$release-fdroid --dry` auf dem aktuellen
Kandidaten. Für den Issue-Backlog: `$issue-orchestrator-eco --plan` für #259.

- **issue_snapshot_at:** 2026-09-07T00:15Z (ein offenes Issue, #259)
- **plan_updated_at:** 2026-09-07
