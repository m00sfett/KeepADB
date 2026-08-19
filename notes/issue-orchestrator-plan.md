# Issue-Orchestrator-Plan

## Neuer Nutzerauftrag: WLAN-ADB-Endpoint

- Issues: [#3](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/3) Notification mit
  aktuellem Port/IP; [#4](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/4) zentrales
  Register auf `moosgames2020`, ausschließlich über Tailscale.
- Ziel: Den lokal aktiven WLAN-ADB-Endpoint sichtbar machen und anschließend für private Tools
  zentral lesbar hinterlegen.
- Paketgrenze: getrennte Issues wegen unterschiedlichem Risiko und Rückrollpfad; #3 ist lokal
  direkt umsetzbar, #4 enthält Architektur-, Netzwerk- und Betriebsentscheidungen.
- Nicht-Ziele: keine öffentliche Discovery, kein Internet-Endpoint, keine Portfreigabe, keine
  dauerhafte Historie und keine stillschweigende Kopplung der App an einen Server.
- Architektur-Optionen für #4: SSH/Dateiablage, kleiner HTTP-Registry-Dienst mit atomischer
  JSON-Datei, oder Tailscale Serve vor einem lokalen Dienst. Der Issue-Text empfiehlt als
  Arbeitshypothese den kleinen Registry-Dienst; die Entscheidung bleibt ein Akzeptanzkriterium.
- Abhängigkeit: #4 kann Datenmodell und Dienst unabhängig vorbereiten, die App-Kopplung darf
  erst nach expliziter Festlegung von Transport und Authentisierung erfolgen.
- Freigabe: Issues anlegen und Plan aktualisieren; keine Implementierung, kein Build, kein
  Gerätezugriff und keine Betriebsänderung freigegeben.
- Validierung: GitHub-Issue-Liste und beide URLs im selben Lauf abgefragt; keine Workflows im
  Repository konfiguriert. Arbeitsbaum vorbestehend verändert (`README.md`, `FRONTMATTER.md`)
  und unverändert zu bewahren.
- Status: `complete` für diesen Planungs-/Issue-Anlegeauftrag; Serverstatus #3/#4 offen,
  Commitstatus unverändert, Review nicht anwendbar, Implementierungsscope offen.

## Aktuelles Paket

- Issue: #1 — CI-Designsystem für WiFi-ADB
- Ziel: Die App, das Widget und die Quick-Settings-Kachel erhalten ein konsistentes natives
  Rot-Gelb-Dunkel-Design.
- Zusammenhang: gemeinsamer UI-Ressourcen- und Layoutpfad; die bestehende ADB-WLAN-Logik bleibt
  unverändert.
- Nicht-Ziele: kein Compose-/Material3-Umbau, keine externen Runtime-Dependencies, keine Änderung
  an Berechtigungen oder Reboot-/Toggle-Semantik, kein physisches Gerät.

## Stufe, Freigaben und Gates

- Umsetzung: S2, direkt durch den Hauptagenten; keine Delegation.
- Freigegeben: Implementierung, lokaler Debug-Build und Emulator-Smoke-Test.
- Muss-Akzeptanzfälle: kein heller Start-Flash; App AN/AUS und fehlende Berechtigung; Widget-Zustand
  und Umschalten; Tile-Zustand und Umschalten.
- `approved`: Debug-Build erfolgreich und alle genannten Fälle anhand von Screenshot/Logs oder
  dem freigegebenen S20-Fallback nachvollziehbar bestanden.
- Maximale Reparaturrunden: zwei lokale Reparaturschleifen; kein Architektur- oder Scope-Wechsel.

## Status

- Ausgang: `master` entsprach `origin/master`; Issue #1 ist offen.
- Vorhandene fremde Änderungen: `README.md` geändert, `FRONTMATTER.md` untracked; unverändert zu
  bewahren.
- Validierung: Build bestanden; Emulator-Smoke `blocked`.
- Nachweis Build: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug` — erfolgreich in
  25 s.
- Emulator-Nachweis: Registerabfrage ergab keinen `emulator`-Eintrag; der Resolver fand den Emulator
  doppelt auf ADB 5037/5038. Nach sauberem Stop scheiterte der vorgesehene Neustart an fehlendem
  `DISPLAY`/Qt-`xcb` (`Fatal: This application failed to start because no Qt platform plugin could
  be initialized`). Kein Headless-Fallback ausgeführt.
- S20-Fallback am 2026-08-20: registrierter WLAN-ADB-Transport `192.168.178.24:36861` erfolgreich
  fingerprint-validiert; Build, Installation, Activity-Start, UI-Dump und Screenshot bestanden.
  Der Activity-Ausschaltpfad wurde ausgelöst und trennte erwartungsgemäß den WLAN-ADB-Transport.
  Der anschließende Pflichtscan `phone-register scan-wlan s20` fand keinen offenen Port; ON-/
  Widget-/Tile-Abnahme ist daher transport-blockiert, bis WLAN-ADB am Telefon reaktiviert wird.
- Nach Reaktivierung auf Port `39945` wurde die Fingerprint-Prüfung wiederholt und der veraltete
  mDNS-/Offline-Transport gezielt entfernt. Der Activity-Start blieb erreichbar. Der Tile-Test
  lieferte jedoch keinen belegten Zustandswechsel (`adb_wifi_enabled` blieb vor dem erneuten
  Transportabbruch `1`); ein weiterer Scan fand keinen offenen Port. Die Tile- und Widget-Gates
  bleiben ungeprüft.
- Nach erneuter Reaktivierung wurde Port `35731` erfolgreich verbunden und fingerprint-validiert.
  Der programmatische Tile-Klick änderte den belegten Zustand nicht (`1` blieb `1`). Beim Öffnen
  des Quick-Settings-Panels brach der Transport erneut ab; der Port ist geschlossen. Tile- und
  Widget-Gates bleiben ungeprüft.
- Nach manueller Nachprüfung wurde Port `33465` erfolgreich verbunden und fingerprint-validiert;
  `adb_wifi_enabled=1`. Der Widget-Provider ist auf User 0 registriert. Der Nutzer bestätigt,
  dass App, Tile und Widget jeweils AUS/AN funktionieren; damit ist die S20-Fallback-Abnahme
  vollständig bestanden.
- Offene Risiken: UI-Akzeptanz auf dem Emulator; Fontdateien werden aus der lokal installierten,
  SIL-OFL-lizenzierten Fira-Sans-Installation übernommen.

## Übergabe und Retrospektive

- Checkpoint: nach Ressourcen-/Layoutänderung, nach Build und nach Emulator-Smoke.
- Retrospektive: Gate-Reihenfolge, tatsächlicher Defektfund, Nutzen der direkten Umsetzung und eine
  konkrete Verbesserung für die nächste Issue-Runde werden nach der Validierung ergänzt.

## Aufwandsprotokoll

- Geplant: 1 Issue / 1 Paket.
- Tatsächlich: UI-Ressourcen und Layouts umgesetzt; S20-Fallback-Abnahme bestanden.
- Fehlversuch: 1 Emulator-Start wegen Infrastruktur, keine Reparaturschleife am Code.
- Beobachtete Token-/Abrechnungswerte: unbekannt.

## Retrospektive

- Die Reihenfolge Build vor Geräteprüfung war sinnvoll: Der APK-Bestand ist kompilierbar, während
  der Geräteblocker klar als Infrastrukturfehler isoliert wurde.
- Kein Defekt wurde durch den Emulator gefunden; ein früherer statischer Check hätte den fehlenden
  GUI-Transport nicht beweisen können.
- Direkte Umsetzung war für das klar begrenzte S2-UI-Paket günstiger als Delegation.
- Verbesserung für die nächste Runde: Vor dem Smoke-Test den Emulator-Display-/ADB-Transport mit
  einem kurzen, read-only Wrapper-Check validieren und den Test bei fehlendem GUI-Zugang früh als
  Infrastruktur-Blocker markieren.
- Geräteprüfung: Das Umschalten von WLAN-ADB beendet den Prüftransport selbst. Für solche Tests
  künftig den manuellen Nutzerbeleg als eigenen Abnahmetyp vorsehen oder USB-ADB als unabhängigen
  Kontrollkanal verwenden.

## Abschlussstatus

`complete` für das Paket — Commit `6357d34` ist auf `origin/issue-1-ci-designsystem` vorhanden.
Die S20-Fallback-Abnahme ist bestanden. PR #2 ist weiterhin offen als Draft gegen `master`, Issue
#1 ist serverseitig offen; es wurden keine Merge-/Issue-Schreibaktionen vorgenommen.

## Übergabe-Checkpoint und nächste Auswahlrunde — 2026-08-20

- Roadmap-Abgleich: Die Aussage „Den lokal aktiven WLAN-ADB-Endpoint sichtbar machen und
  anschließend für private Tools zentral lesbar hinterlegen“ wird durch Issue #3 direkt erfüllt;
  Issue #4 folgt erst nach der lokalen Endpoint-Anzeige.
- Nächstes Paket: Issue #3 — Notification: WLAN-ADB-Port und IP anzeigen.
- Ziel: Bei aktivem WLAN-ADB den tatsächlich erreichbaren Port und die geeignete Geräte-IP live in
  einer laufenden Notification anzeigen und beim Deaktivieren entfernen.
- Zusammenhang: #3 ist der lokale Endpoint- und Anzeigepfad; #4 ist ein separater Tailscale-only
  Betriebs-/Datenpfad mit eigener Architektur-, Netzwerk- und Rollback-Grenze. Kein gemeinsames
  Paket.
- Nicht-Ziele: kein zentrales Register, kein Tailscale-/Server-Transport, keine Änderung der
  Toggle-Semantik oder des Berechtigungsmodells.
- Umfang: Endpoint-Adapter, Notification-Channel/-Berechtigung, Updates aus App/Widget/Tile,
  fehlende oder stale Endpoint-Daten sowie IPv4-/IPv6-/Multi-Interface-Verhalten gemäß Issue #3.
- Einstufung: S2, direkt umsetzbar; keine Delegation vorgesehen.
- Freigaben: Auswahl und Planaktualisierung erteilt. Implementierung, Build/Test und Geräte- oder
  externe Betriebsaktionen sind in diesem Lauf nicht freigegeben.
- Übergabe: Issue #3 und #4 sind serverseitig offen; PR #2 ist Draft ohne Checks. `README.md` und
  `FRONTMATTER.md` sind fremde Arbeitsbaumänderungen und unverändert zu bewahren. Kein Codepfad
  von #3 wurde geändert.
- Ungeprüfte Akzeptanzkriterien für #3: alle; insbesondere tatsächlicher Wireless-Debugging-Port,
  geeignete IP, exakte Notification-Texte/Fettdruck, App-/Widget-/Tile-Updates, Reboot-Frische,
  Channel/Berechtigung, fehlender Endpoint und Geräte-/Emulator-Nachweis.
- Review: `not applicable` für dieses Auswahl-/Planungspaket; keine Delegation.
- Laufstatus: `not approved` für die Umsetzung. Die nächste Aufgabe muss die typisierte Freigabe
  für Implementierung und die dafür vorgesehenen lokalen sowie Geräte-Gates enthalten.
