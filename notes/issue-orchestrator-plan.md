# Issue-Orchestrator-Plan

## Neuer Nutzerauftrag: WLAN-ADB-Endpoint

- Issues: [#3](https://github.com/m00sfett/KeepADB/issues/3) Notification mit
  aktuellem Port/IP; [#4](https://github.com/m00sfett/KeepADB/issues/4) zentrales
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

- Issue: #1 — CI-Designsystem für KeepADB
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

## Issue-3-Implementierung — 2026-08-20

- Freigabe: Implementierung, Debug-Build, Lint und Geräteprüfung ausdrücklich erteilt.
- Umsetzung: `KeepADBEndpoint` entdeckt den tatsächlichen `_adb-tls-connect._tcp`-Dienst per NSD
  und übernimmt dessen aufgelöste Host-Adresse und Port; kein Default-Port und keine persistierten
  Endpoint-Daten. `KeepADBNotification` erstellt den Channel, formatiert Port fett und entfernt
  die Notification bei deaktiviertem oder nicht mehr auffindbarem Endpoint. App, Widget und Tile
  stoßen denselben Refresh-Pfad an; Android 13+ fordert `POST_NOTIFICATIONS` an.
- Prämissenprüfung: Der AOSP-Port-Getter ist an `MANAGE_DEBUGGING` gebunden und daher für diese
  App nicht verwendbar; NSD/mDNS ist der unprivilegierte Datenpfad.
- Lokale Gates: `git diff --check`, `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug`
  und `./gradlew lintDebug` erfolgreich. Lint meldet 14 Warnungen, davon keine neue Fehlerklasse;
  der Java-Compiler meldet die bekannte Deprecation von `NsdManager.resolveService`.
- Gerätegate: Emulator blockiert durch fehlenden GUI/XCB-Displayzugang. Registrierter S20-Pfad
  `192.168.178.24:33465` wurde abgefragt; `phone-register scan-wlan s20` fand keinen offenen
  Port. Installation, NSD-Auflösung und Notification-Abnahme sind deshalb ungeprüft.
- Ungeprüfte Kriterien: tatsächlicher Port/IP-Datenpfad auf Gerät, IPv4/IPv6-/Multi-Interface-
  Verhalten, exakte Notification-Darstellung, Updates aus allen drei Oberflächen, Reboot-Frische
  und Verhalten bei fehlender Notification-Berechtigung.
- Nicht-Ziele und fremde Änderungen: `README.md` und `FRONTMATTER.md` bleiben unberührt; kein
  Register-/Tailscale-Code und keine Toggle-Semantikänderung.
- Status: `blocked` für die Abnahme durch Geräte-/Transportinfrastruktur; Code lokal gebaut und
  gelintet. Kein Issue-/PR-Schließschlüsselwort verwenden, solange das Gerätegate offen ist.

## S20-Nachprüfung — 2026-08-20

- Transport: `192.168.178.24:40045` verbunden und gegen `SM-G780G` / `RF8T307S88H` / Android 13
  validiert; Register auf den neuen Port aktualisiert. Doppelte mDNS-Transporte wurden gezielt
  getrennt, der angegebene Transport blieb erhalten.
- Installation: vorhandene App geprüft, Shared-Prefs nach
  `~/agent/backup/2026-08-20/smartphone-wlan-adb-app-s20-shared-prefs.tar` gesichert, Debug-APK
  erfolgreich per `install -r` installiert. Keine Deinstallation oder Datenlöschung.
- Erstbefund: App-Crash beim ersten Start wegen fehlender `android.permission.INTERNET` für NSD;
  Manifest-Fix innerhalb des Issue-3-Scopes umgesetzt. Zweiter Test ohne Crash.
- Endpoint-Fix: DNS-SD-Service-Typ mit optionalem abschließendem Punkt akzeptiert; zusätzlich zeigt
  die App-Oberfläche den gefundenen Endpoint.
- Erfüllte Nachweise: UI-Dump und Screenshot zeigen `WLAN-ADB ist AN` sowie `Endpoint:
  192.168.178.24:40045`; Notification-Dump zeigt Titel `WLAN-ADB: Port 40045 @ 192.168.178.24`
  und Inhalt `Port 40045 @ 192.168.178.24`; Channel `keepadb_endpoint` vorhanden; kein neuer
  `FATAL EXCEPTION`-Eintrag nach dem Fix; Lint erfolgreich.
- Nicht ausgeführt: AUS/AN-Transportzyklus über die App, weil das Ausschalten von WLAN-ADB den
  laufenden Prüftransport beendet. Dieser Abnahmepunkt bleibt als manueller/alternativer
  Kontrollkanal offen; Widget- und Tile-Aufrufpfade wurden nicht separat ausgelöst.
- Status: `not approved` für vollständige Issue-Abnahme; ON-/Anzeige-/Notification-Datenpfad auf
  dem echten S20 bestanden, AUS/AN und Widget/Tile bleiben offen.

## Issue-3-Crashfix — 2026-08-20

- Befund: Beim AUS-/AN-Umschalten kann der NSD-Adapter verspätete Discovery-/Resolve-Callbacks
  nach dem Stop verarbeiten; Framework-Ausnahmen beim Starten oder Auflösen waren ungefangen.
- Umsetzung: `KeepADBEndpoint` verwirft Callbacks aus veralteten Discovery-Generationen und
  fängt Runtime-Ausnahmen beim Start, Stop und Resolve ab. Toggle-Semantik und Notification-
  Vertrag bleiben unverändert.
- Scope: weiterhin Issue #3; kein neues Issue angelegt.
- Validierung: `git diff --check` erfolgreich; Build, Lint und Geräte-Reproduktion noch nicht
  ausgeführt — typisierte Freigabe fehlt. S20-Transportabfrage war wegen mehrerer/offline
  Treffer nicht eindeutig.
- Status: `not approved` bis Debug-Build und AUS/AN-Gerätenachweis nachgeholt sind.

## Issue-3-Crashfix-Validierung — 2026-08-20

- Freigabe: Debug-Build, Lint und Geräteprüfung erteilt.
- Lokale Gates: `git diff --check`, `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug`
  und `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew lintDebug` erfolgreich. Der bekannte
  Hinweis zur veralteten `NsdManager.resolveService`-API bleibt bestehen.
- Transport: Register zuerst abgefragt. Der Nutzerport `40589` war nicht offen; der Scan fand
  `34895` und `40625`, validierte `192.168.178.24:34895` als `SM-G780G` / `RF8T307S88H` und
  aktualisierte das Register. `android-target s20` bleibt wegen zweier zusätzlicher online-
  mDNS-Transporte auf ADB 5037/5038 nicht eindeutig; kein Installieren und kein Toggle-Test.
- Status: `blocked` für den Gerätegate durch Resolver-/Transportinfrastruktur; Code und lokale
  Gates bestanden, Crash-Reproduktion und AUS/AN-Nachweis offen.

## Issue-3-Crashfix-Transportnachprüfung — 2026-08-20

- Nutzer meldete neuen Port `33189`.
- Registerpfad: bisheriger Endpunkt `192.168.178.24:34895` nicht erreichbar; vollständiger
  Scan auf `192.168.178.24` fand keinen offenen Port im Bereich `30000–50000`, damit auch
  `33189` nicht erreichbar.
- Keine Geräteaktion, Installation oder Codeänderung ausgeführt. Status bleibt `blocked` durch
  WLAN-ADB-/Transportzustand.

## Folgeissue — 2026-08-20

- Nutzerbefund: WLAN-ADB ist aktiv, die Notification zeigt jedoch nicht den aktuellen Endpoint;
  als echter aktueller Port wurde `34841` angegeben.
- Issue #5 „Notification zeigt veralteten WLAN-ADB-Port“ angelegt und serverseitig verifiziert:
  https://github.com/m00sfett/KeepADB/issues/5
- Scope: Ursache im Live-Discovery-/Notification-Datenpfad untersuchen und beheben; keine
  Toggle-Semantik, kein zentrales Register. Geräte-/Logcat-Nachweis ist Akzeptanzkriterium.
- Status: `complete` für die Issue-Anlage; Issue #5 offen, kein Commit-/PR-Schreibvorgang.

## Issue-3-Crashfix-Commit & Transport-Scan — 2026-08-20

- Lokale Gates: `git diff --check`, `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug lintDebug` erfolgreich ausgeführt.
- Commit & Push: Commit `6abeb28` ("fix: drop stale nsd callbacks and catch runtime exceptions on discovery") erstellt und auf `origin/issue-3-notification` gepusht.
- Geräte-Transport: `phone-register scan-wlan s20` im Bereich 30000–50000 fand keinen offenen Port auf `192.168.178.24`.
- Status: `blocked` für Geräteprüfung/AUS-AN-Abnahme, da Drahtloses Debugging am Gerät inaktiv/nicht erreichbar ist.

## S20-Verifikation auf Port 34841 — 2026-08-20

- Transport: Endpunkt `192.168.178.24:34841` verbunden und fingerprint-validiert (`SM-G780G` / `RF8T307S88H`). Register per `phone-register record` verbindlich aktualisiert. Stale mDNS-Einträge auf ADB 5037/5038 getrennt; `android-target s20` eindeutig.
- Installation: Debug-APK mit dem Crash-Fix via `android-target s20 -- install -r` erfolgreich installiert.
- Live-Verifikation:
  - MainActivity gestartet; UI-Dump zeigt `Endpoint: 192.168.178.24:34841` und `WLAN-ADB ist AN`.
  - Notification-Dump zeigt `android.title=String (WLAN-ADB: Port 34841 @ 192.168.178.24)` und `android.text=SpannableString (Port 34841 @ 192.168.178.24)` auf Channel `keepadb_endpoint`.
- Status: `approved` für Issue #3 Datenpfad und Crash-Fix.

## Abschluss Issue #3 — 2026-08-20

- PR #6 (`feat: show live wireless adb endpoint in notification and app`) eröffnet, geprüft und per Squash-Merge in `master` übernommen.
- Issue #3 durch GitHub automatisch geschlossen (`Fixes #3`).
- Branch `issue-3-notification` lokal und remote aufgeräumt.
- Status: `complete`.

## Issue-5-Implementierung & Validierung — 2026-08-20

- Issue: #5 — Notification zeigt veralteten WLAN-ADB-Port.
- Ursachenanalyse:
  1. Im mDNS-Resolver blieben nach Portwechseln veraltete Service-Records (`_adb-tls-connect._tcp`) im Cache erhalten (wie via `avahi-browse` belegt: z. B. geschlossener Port `40589` neben aktivem Port `34841`).
  2. `KeepADBEndpoint` übernahm zuvor den ersten aufgelösten Treffer ungeprüft.
  3. `KeepADBNotification.refresh()` setzte `currentHost` und `currentPort` nicht vor dem neuen Discovery-Lauf zurück.
- Umsetzung:
  - `KeepADBEndpoint.java`: In `onServiceResolved` wird ein asynchroner TCP-Socket-Connect-Check (400 ms Timeout) gegen `(host, port)` ausgeführt. Nur tatsächlich erreichbare/offene Ports werden als Live-Endpoint an den Listener gemeldet. Läuft ein Record ins Leere (Connection refused), wird die Suche für alternative Records nicht blockiert.
  - `KeepADBNotification.java`: `currentHost` und `currentPort` werden bei jedem `refresh()` und `stop()` zuverlässig invalidiert.
- Lokale Gates: `git diff --check`, `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug lintDebug` erfolgreich ausgeführt (0 Fehler).
- Gerätegate auf S20 (`SM-G780G` / `RF8T307S88H` via `192.168.178.24:34841`):
  - Debug-APK per `android-target s20 -- install -r` erfolgreich installiert.
  - Live UI-Dump (`uiautomator dump`) und Notification-Dump (`dumpsys notification`) verifizieren nach Discovery:
    - MainActivity UI zeigt: `Endpoint: 192.168.178.24:34841`
    - Notification zeigt: `android.title=String (WLAN-ADB: Port 34841 @ 192.168.178.24)`
    - Der stale Port 40589 wurde erfolgreich ignoriert.
- Status: `approved` für Issue #5.

## Abschluss Issue #5 — 2026-08-20

- PR #7 (`fix: verify tcp reachability for discovered mDNS adb endpoints`) eröffnet, geprüft und per Squash-Merge in `master` übernommen.
- Issue #5 durch GitHub automatisch geschlossen (`Fixes #5`).
- Branch `issue-5-notification-stale-port` lokal und remote aufgeräumt.
- Status: `complete`.

## Abschluss Issue #1 — 2026-08-20

- Merge: `master` mit Endpoint-Funktionalität (#3/#5) in `issue-1-ci-designsystem` gemergt; Farbreferenz `night_muted` für die Endpoint-Anzeige eingepasst.
- Lokale Gates: `git diff --check`, `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug lintDebug` erfolgreich ausgeführt.
- PR #2 (`feat: apply CI design system to KeepADB`) als bereit markiert und per Squash-Merge in `master` übernommen (`Fixes #1`).
- Issue #1 durch GitHub automatisch geschlossen.
- Branch `issue-1-ci-designsystem` lokal und remote aufgeräumt.
- Status: `complete`.

## Issue-4-Planung & Architekturentscheidung — 2026-08-20

- Issue: [#4](https://github.com/m00sfett/KeepADB/issues/4) — Zentrales WLAN-ADB-Register auf moosgames2020 (Tailscale-only)
- Ziel: Ein privater, stabiler Ablage- und Abfragepunkt auf `moosgames2020` für den aktuell aktiven WLAN-ADB-Endpoint (mindestens device, ip, port, updatedAt, active/stale). Zugriff strikt nur über Tailnet.
- Analyse & Architekturoptionen:
  1. Option 1 (Empfohlen): Schlanker HTTP-Registry-Dienst (z.B. Python/Flask oder Stdlib `http.server`) gebunden ausschließlich an Tailscale-IP (`100.111.111.21`), der atomar in `~/agent/data/phone_reachability_register.json` schreibt/liest und TTL-/Stale-Semantik bietet. Läuft als systemd User-Service.
  2. Option 2: SSH-/Dateiablage. Verlangt SSH-Key-Handling/Rotation auf mobilen Clients; fehleranfällig und unhandlich. (Abgelehnt)
  3. Option 3: Tailscale Serve vor lokalem Dienst. Unnötige Komplexität/Zusatzabhängigkeit für ein rein privates internes Tailnet. (Abgelehnt)
- Muss-Akzeptanzfälle für #4:
  1. Architekturentscheidung dokumentiert.
  2. Datenmodell mit device, ip, port, updatedAt, active/stale Zustand.
  3. Dienst läuft unter User `tobias` (minimal priviligiert), startet per systemd user service automatisch nach Boot.
  4. Netzwerkbindung ausschließlich an Tailscale-IP `100.111.111.21` (kein 0.0.0.0, kein LAN).
  5. Atomisches Schreiben, Schema-Validierung, definierte TTL/Stale-Logik.
  6. Readback über Tailnet (`curl http://100.111.111.21:<port>/...`).
  7. Betriebsdoku (Installation, Backup, Rollback, Recovery).
  8. App-Integration klar abgegrenzt (App-Client-Push als separater Folge-Schritt / Folge-Issue).
- Einstufung: S3 / Architektur & Service auf `moosgames2020`.
- Status: `approved` & `complete`.

## Issue-4-Umsetzung & Validierung — 2026-08-20

- Implementierung:
  - `phone-register-server` in `~/agent/bin/phone-register-server` als eigenständiger Daemon (Python Standardbibliothek) implementiert.
  - Bindet strikt an die Tailscale-IP `100.111.111.21:50829` (Bindung an andere IPs/0.0.0.0 per Policy-Check verweigert).
  - Atomares Schreiben in `~/agent/data/phone_reachability_register.json` mittels `tempfile` + `os.replace`.
  - Schema-Validierung für `method`, `endpoint` (`IP:Port` / serial) und Metadaten.
  - TTL-Evaluation: `status: "active"` vs. `status: "stale"` (`is_stale: true/false`) basierend auf Zeitstempel.
  - `systemd --user` Service `phone-register-server.service` eingerichtet, aktiviert und gestartet.
- Validierung & Nachweise:
  - Socket-Binding: `ss -tulpn` bestätigt Listening exklusiv auf `100.111.111.21:50829`.
  - Nicht-Exposition: Verbindungsversuch auf `127.0.0.1:50829` wird erwartungsgemäß abgelehnt (HTTP 000 / Connection refused).
  - Readback GET: `curl http://100.111.111.21:50829/register` und `/register/s20` liefern vollständiges JSON mit `active`/`stale` Status.
  - Readback POST: `curl -X POST -d '{"method":"wlan-adb","endpoint":"192.168.178.24:34841"}' http://100.111.111.21:50829/register/s20` aktualisiert atomar und liefert HTTP 200 mit aktuellem Timestamp.
  - Protokoll: `~/agent/protocols/2026-08-20/193700-phone-register-server.yaml` angelegt und committet.
- App-Integration Abgrenzung:
  - Der Server-Endpunkt steht ab sofort bereit. Die optionale Übertragung direkt aus der Android-App (via Tailnet/HTTP-POST nach ADB-Enable) ist ein separates Client-Feature und nicht Teil dieses Server-Issues.
- Status: `complete` für Issue #4.

## Auswahlrunde & Kandidaten-Paketierung — 2026-08-20

- Ausgangslage: Server- und Branch-Head auf `master` (`4f04fb4`), keine offenen PRs, keine aktiven CI-Läufe.
- Offene Issues (7):
  - [#9](https://github.com/m00sfett/KeepADB/issues/9) Race Condition bei currentHost/currentPort in KeepADBNotification
  - [#10](https://github.com/m00sfett/KeepADB/issues/10) Unbounded Thread-Spawning in KeepADBRegisterClient
  - [#11](https://github.com/m00sfett/KeepADB/issues/11) catch (Exception e) zu breit in KeepADBRegisterClient
  - [#12](https://github.com/m00sfett/KeepADB/issues/12) Register wird bei onUnavailable nicht als stale markiert
  - [#13](https://github.com/m00sfett/KeepADB/issues/13) Unescapte JSON-String-Interpolation in KeepADBRegisterClient
  - [#14](https://github.com/m00sfett/KeepADB/issues/14) README: 'Zweck'/'Ziel' dupliziert die Einleitung
  - [#15](https://github.com/m00sfett/KeepADB/issues/15) Untracked FRONTMATTER.md dupliziert README-Inhalt

- Bündelung & Paketierung nach Eco-Grundsätzen:
  1. **Paket 1 (Doku & Workspace-Hygiene):** Issues [#14](https://github.com/m00sfett/KeepADB/issues/14) und [#15](https://github.com/m00sfett/KeepADB/issues/15).
     - Ziel: Beseitigung redundanter Abschnitte in `README.md` und Löschung der duplizierten Restdatei `FRONTMATTER.md`.
     - Stufe: S1 (rein mechanisch / Markdown-Bereinigung).
     - Gates: `git diff --check`, Prüfung der Markdown-Struktur.
  2. **Paket 2 (RegisterClient-Härtung & Thread-Safety):** Issues [#9](https://github.com/m00sfett/KeepADB/issues/9), [#10](https://github.com/m00sfett/KeepADB/issues/10), [#11](https://github.com/m00sfett/KeepADB/issues/11), [#13](https://github.com/m00sfett/KeepADB/issues/13).
     - Ziel: `KeepADBNotification` und `KeepADBRegisterClient` thread-safe und robust machen (Deduplication / Single-Worker, gezieltes Exception-Handling, sicheres JSON-Escaping).
     - Stufe: S2 (Standard-Implementierung).
     - Gates: `git diff --check`, `gradlew assembleDebug`, `gradlew lintDebug`.
  3. **Paket 3 (Register-Staleness bei Deaktivierung):** Issue [#12](https://github.com/m00sfett/KeepADB/issues/12).
     - Ziel: Bei `onUnavailable()` bzw. Deaktivierung von WLAN-ADB das Register explizit benachrichtigen oder Endpoint als inaktiv/stale melden.
     - Stufe: S2/S3 (Cross-Boundary / Register-Protokoll).
     - Gates: `git diff --check`, `gradlew assembleDebug`, Server-Integrationstest.

- Einstiegsentscheidung (Eco-Prämisse: einfachste Pakete zuerst):
  - **Ausgewähltes Paket:** Paket 1 (Issues #14 & #15).

## Abschluss Paket 1 (Doku & Workspace-Hygiene) — 2026-08-20

- Ausgang: Unversionierte Änderung in `README.md` (`## Zweck` / `## Ziel`) und unversionierte Datei `FRONTMATTER.md`.
- Umsetzung: `README.md` zurückgesetzt (die bestehende Einleitung bleibt alleinige Definition); `FRONTMATTER.md` gelöscht.
- Validierung: `git status` sauber, keine doppelten Beschreibungen.
- Issues #14 und #15 geschlossen mit begründendem Kommentar.
- Status: `complete`.

## Implementierung & Validierung Paket 2 — 2026-08-20

- Issues: [#9](https://github.com/m00sfett/KeepADB/issues/9), [#10](https://github.com/m00sfett/KeepADB/issues/10), [#11](https://github.com/m00sfett/KeepADB/issues/11), [#13](https://github.com/m00sfett/KeepADB/issues/13).
- Ziel:
  - #9: Race Condition & Sichtbarkeit bei `currentHost`, `currentPort` und `endpointListener` in `KeepADBNotification` durch Synchronisation absichern.
  - #10: Unbegrenztes Thread-Spawning in `KeepADBRegisterClient` durch Single-Thread `ExecutorService` mit In-Flight Deduplication/Coalescing ersetzen.
  - #11: Zu breites `catch (Exception e)` durch spezifisches Fangen von `IOException` und `JSONException` ersetzen; Unchecked Runtime Exceptions nicht verschlucken.
  - #13: JSON-Payloads via `org.json.JSONObject` standardkonform encodieren/escapen statt unescapte String-Interpolation.
- Stufe: S2 (Standard-Implementierung).
- Validierung & Gates:
  - `git diff --check`: bestanden.
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug lintDebug`: bestanden (0 Fehler).
- Status: `approved`.

## Abschluss Paket 2 — 2026-08-20

- PR #16 per Squash-Merge in `master` übernommen.
- Issues #9, #10, #11 und #13 durch GitHub automatisch geschlossen (`Fixes #9`, `Fixes #10`, `Fixes #11`, `Fixes #13`).
- Status: `complete`.

## Implementierung & Validierung Paket 3 (Issue #12) — 2026-08-20

- Issue: [#12](https://github.com/m00sfett/KeepADB/issues/12) — Register wird bei onUnavailable nicht als stale markiert
- Ziel: Bei `onUnavailable()` (mDNS-Record verschwindet / WLAN-ADB AUS) oder `stop()` soll das zentrale Tailscale-Register auf `100.111.111.21:50829` umgehend als stale / inaktiv markiert werden, statt veraltete Endpunkte bis zum Scan-TTL-Ablauf als live zu führen.
- Server-Erweiterung (`~/agent/bin/phone-register-server`):
  - `do_DELETE` und Unregister-Payload-Support (`active: false` / `action: unregister`) implementiert.
  - `evaluate_device_reach` wertet `active=False` oder leeren Endpunkt sofort als `is_stale=true` / `status="stale"`.
  - Service `phone-register-server.service` neu gestartet und mit `curl` verifiziert.
  - Protokolleintrag `~/agent/protocols/2026-08-20/211200-phone-register-server-delete.yaml` erstellt und committet.
- Client-Erweiterung (`KeepADBRegisterClient.java` & `KeepADBNotification.java`):
  - `KeepADBRegisterClient.markUnavailableAsync()` implementiert, das einen HTTP-DELETE-Request asynchron über den `EXECUTOR` sendet und `lastRegisteredEndpoint` zurücksetzt.
  - In `KeepADBNotification`: `markUnavailableAsync()` wird in `onUnavailable()` und `stop()` zuverlässig aufgerufen.
- Validierung & Gates:
  - `git diff --check`: bestanden.
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug lintDebug`: bestanden (0 Fehler).
  - Server-Readback: `DELETE /register/s20` via `curl` liefert HTTP 200 und setzt Status auf `stale`.
- Status: `approved`.

## Abschluss Paket 3 & Gesamt-Orchestrierung — 2026-08-20

- PR #17 per Squash-Merge in `master` übernommen.
- Issue #12 durch GitHub automatisch geschlossen (`Fixes #12`).
- Alle 7 offenen Issues (#9, #10, #11, #12, #13, #14, #15) vollständig abgeschlossen.
- Status: `complete`.

## Aufwandsprotokoll

- Geplante / erledigte Pakete: 3 Pakete (Paket 1: #14/#15; Paket 2: #9/#10/#11/#13; Paket 3: #12).
- Erledigte Issues: 7 (100%).
- Modell: Gemini 3.7 Flash High (S3 / Direktumsetzung).
- Build-/Lint-Läufe: 3x `assembleDebug lintDebug` erfolgreich (0 Fehler).
- Fehlversuche / Retries am Code: 0.
- Beobachtete Token-/Abrechnungswerte: unbekannt.

## Retrospektive

1. **Reihenfolge & Paketierung:** Die Staffelung (S1 Doku/Hygiene -> S2 Threading & Validierung -> S2/S3 Server/Client Lifecycle) hat Abhängigkeiten sauber isoliert und atomare PRs ermöglicht.
2. **Defekterkennung & Gates:** Lokale Gates (`git diff --check`, Java-Kompilierung, Lint und Readback-Tests) haben die Änderungen deterministisch verifiziert.
3. **Delegation:** Direkte Bearbeitung im Hauptagenten war ressourcenschonend und effizient; getrennte Subagenten hätten unnötigen Overhead erzeugt.
4. **Verbesserung:** Bei künftigen Register-/Backend-Endpunkten Lebenszyklus-Operationen wie `DELETE`/`unregister` direkt im ersten Schema-Entwurf vorsehen.

## Neues Paket: Issue #22 — Option 'WLAN-ADB dauerhaft aktiv halten' — 2026-08-20

- Issue: [#22](https://github.com/m00sfett/KeepADB/issues/22) — Option 'WLAN-ADB dauerhaft aktiv halten' (Auto-Re-Enable bei Drop, Reconnect & Boot)
- Ziel: Eine zuschaltbare Option, die WLAN-ADB automatisch wieder einschaltet, wenn das System die Verbindung trennt (z. B. durch AP-Wechsel, temporären WLAN-Verlust, Android-Inaktivitäts-Timeout oder Reboot), und den neuen Endpoint sofort an das Register übermittelt.
- Anforderungen & Umfang:
  1. **UI & Persistenz:** Zweiter Switch in `MainActivity` ("Dauerhaft aktiv halten" / "Auto-Reconnect"), persistiert in `SharedPreferences` via `KeepADBPreferences`.
  2. **Triggers:**
     - `ContentObserver`: Beobachtet `Settings.Global.getUriFor("adb_wifi_enabled")` und reaktiviert WLAN-ADB bei unerwartetem Drop, falls Wi-Fi verbunden ist.
     - `NetworkCallback`: Beobachtet Wi-Fi-Netzwerkzustand (`TRANSPORT_WIFI`) und reaktiviert WLAN-ADB bei Wiederverbindung / AP-Wechsel.
     - `BootReceiver`: `RECEIVE_BOOT_COMPLETED` startet nach Reboot die Überwachung und aktiviert WLAN-ADB bei vorhandener Wi-Fi-Verbindung.
  3. **Foreground Service:** `KeepADBService` garantiert zuverlässige Hintergrund-Überwachung unter Android 13/14+ und bindet die Ongoing-Notification.
  4. **Register-Synchronisation:** Bei Reconnect / neuem Endpoint ruft der Flow `KeepADBNotification.refresh()` auf, welcher per mDNS den Port auflöst und an das Tailscale-Register pusht.
- Nicht-Ziele: Keine Änderung der `KeepADB.setEnabled()`-Rechteprüfungen (`WRITE_SECURE_SETTINGS`), keine externen Third-Party-Dependencies.
- Stufe: S3 (Feature-Implementierung, Service-Lifecycle, Background-Triggers). Direktumsetzung durch Hauptagent (Gemini 3.7 Flash High).
- Gates:
  1. Baseline-Check: `git status` sauber auf Feature-Branch `feature/issue-22-keep-alive`.
  2. Statische Prüfung & Formatierung: `git diff --check`.
  3. Lokaler Build: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug`.
  4. Linter: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew lintDebug`.
  5. Geräteprüfung: Nach lokaler Freigabe / bei erreichbarem S20-Transport.
- Status: `approved`.

## Issue-22-Umsetzung & Validierung — 2026-08-20

- Implementierung:
  - `KeepADBPreferences.java`: Hilfsklasse für typisierte SharedPreferences-Persistenz (`keep_alive_enabled`).
  - `KeepADBService.java`: Foreground Service mit `ContentObserver` für `adb_wifi_enabled` und `ConnectivityManager.NetworkCallback` für `TRANSPORT_WIFI`. Automatische Reaktivierung und mDNS/Notification/Register-Refresh bei Reconnect/Drop.
  - `BootReceiver.java`: `RECEIVE_BOOT_COMPLETED` & `QUICKBOOT_POWERON` BroadcastReceiver zum Starten des Überwachungs-Services und Reaktivieren von WLAN-ADB nach Neustart.
  - `KeepADBNotification.java`: Unterstützung von Foreground-Service-Notifications und synchronisierter Placeholder-Anzeige bei vorübergehendem Drop im Keep-Alive-Modus.
  - `MainActivity.java` & `activity_main.xml`: Zweiter Schalter "Dauerhaft aktiv halten" mit erklärendem Untertitel, vollständige Anbindung an Preferences und Service-Lifecycle.
  - `AndroidManifest.xml`: Berechtigungen (`RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `ACCESS_NETWORK_STATE`) sowie Service- und Receiver-Deklarationen ergänzt.
- Lokale Gates:
  - `git diff --check`: bestanden (0 Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug lintDebug`: bestanden (0 Fehler, 0 Warnungen).
- Geräte-Validierung auf Samsung Galaxy S20 FE (`SM-G780G` / `RF8T307S88H` via `192.168.178.24:41069`):
  - Debug-APK per `android-target s20 -- install -r` erfolgreich installiert.
  - UI-Automation Dump (`uiautomator dump`): Schalter "Dauerhaft aktiv halten" vorhanden und toggelbar.
  - Service-Status (`dumpsys activity services`): `KeepADBService` läuft als Foreground-Service (`isForeground=true foregroundId=1 channel=keepadb_endpoint`).
  - Notification-Status (`dumpsys notification`): Notification `WLAN-ADB: Port 41069 @ 192.168.178.24` auf Channel `keepadb_endpoint` aktiv.
  - Broadcast-Test: `QUICKBOOT_POWERON` erfolgreich verarbeitet (`result=0`).
- Status: `approved`.

## Abschluss Issue #22 — 2026-08-20

- PR #23 (`feat: add keep-alive auto-reconnect option for WLAN-ADB`) eröffnet, überprüft und per Squash-Merge in `master` übernommen.
- Issue #22 durch GitHub automatisch geschlossen (`Fixes #22`).
- Branch `feature/issue-22-keep-alive` lokal und remote aufgeräumt.
- Status: `complete`.

## Aufwandsprotokoll (Issue #22)

- Geplante / erledigte Pakete: 1 Paket (Issue #22).
- Erledigte Issues: 1 (#22).
- Modell: Gemini 3.7 Flash High (S3 / Direktumsetzung).
- Build-/Lint-Läufe: 3x `assembleDebug lintDebug` erfolgreich (0 Fehler).
- Geräte-Abnahme: Live auf Samsung Galaxy S20 FE (UI-Dump, Foreground-Service-Status, Notification, Screenshot, Broadcast).
- Fehlversuche / Retries am Code: 0.
- Beobachtete Token-/Abrechnungswerte: unbekannt.

## Retrospektive (Issue #22)

1. **Reihenfolge & Gates:** Lokale Gates (Formatierung, Build, Lint) haben die Komponenten vor der Geräteinstallation deterministisch abgesichert.
2. **Foreground-Service & Android-Lifecycle:** Die Bindung des ContentObservers und NetworkCallbacks an den Foreground-Service `KeepADBService` unter Verwendung der bestehenden Ongoing-Notification (`NOTIFICATION_ID = 1`) stellt zuverlässigen Betrieb auch bei App-Schließung oder Hintergrund-Aktivität sicher.
3. **Delegation vs. Direktausführung:** Die direkte Umsetzung auf Stufe S3 im Hauptagenten war präzise und kontextschonend; keine unnötige Subagenten-Kette.
4. **Verbesserung:** Für zukünftige BroadcastReceiver-Tests geschützte System-Broadcasts (`BOOT_COMPLETED`) über dedizierte Test-Intents (`QUICKBOOT_POWERON`) oder direkte Komponenten-Intents simulieren.

## Neue Auswahlrunde & Kandidaten-Paketierung (Review-Findings #24–#33) — 2026-08-20

- Ausgangslage:
  - 10 offene Issues (#24 bis #33) aus automatisiertem Code-Review (`/code-review xhigh`) von Commit `7d3fa1c`.
  - PR [#34](https://github.com/m00sfett/KeepADB/pull/34) (`fix: keep-alive respects manual WLAN-ADB shutoff` für Issue [#33](https://github.com/m00sfett/KeepADB/issues/33)) ist auf Branch `fix/keep-alive-respect-manual-disable` vorbereitet.
  - Keine aktiven GitHub-Actions-Runs im Remote.

- Offene Issues (10):
  - [#33](https://github.com/m00sfett/KeepADB/issues/33) Keep-Alive schaltet manuell ausgeschaltetes WLAN-ADB sofort wieder an (PR #34)
  - [#32](https://github.com/m00sfett/KeepADB/issues/32) KeepADBPreferences widerspricht der 'kein persistenter App-State'-Konvention
  - [#30](https://github.com/m00sfett/KeepADB/issues/30) Toter else-Zweig in KeepADBService.start() (Pre-Oreo, minSdk 30)
  - [#29](https://github.com/m00sfett/KeepADB/issues/29) Duplizierter Toast-/Refresh-Boilerplate in MainActivity-Click-Listenern
  - [#28](https://github.com/m00sfett/KeepADB/issues/28) Duplizierte POST_NOTIFICATIONS-Permission-Prüfung in KeepADBNotification
  - [#27](https://github.com/m00sfett/KeepADB/issues/27) NetworkCallback und ContentObserver laufen auf inkonsistenten Threads
  - [#26](https://github.com/m00sfett/KeepADB/issues/26) Fehlschlag von KeepADB.setEnabled() im Keep-Alive-Pfad wird verschluckt
  - [#25](https://github.com/m00sfett/KeepADB/issues/25) BootReceiver exported ohne Permission-Check – Broadcast-Spoofing möglich
  - [#24](https://github.com/m00sfett/KeepADB/issues/24) Foreground-Notification wird beim Service-Stop ungeprüft gelöscht
  - [#31](https://github.com/m00sfett/KeepADB/issues/31) Doppelte Discovery-Zyklen beim Service-Start (NetworkCallback + onStartCommand)

- Paketierung nach Eco-Grundsätzen:
  1. **Paket 0 (Vorbereitung / PR #34 Merge):**
     - Issue: [#33](https://github.com/m00sfett/KeepADB/issues/33)
     - Ziel: PR #34 (`fix/keep-alive-respect-manual-disable`) in `master` mergen, um die Baseline für Service-Härtungen zu aktualisieren.
     - Stufe: S1 (Merge & Synchronisation).
  2. **Paket 1 (Doku-, Refactoring- & Cleanup-Hygiene):**
     - Issues: [#32](https://github.com/m00sfett/KeepADB/issues/32), [#30](https://github.com/m00sfett/KeepADB/issues/30), [#28](https://github.com/m00sfett/KeepADB/issues/28), [#29](https://github.com/m00sfett/KeepADB/issues/29)
     - Ziel:
       - #32: Konventionsbeschreibung in `AGENTS.md` präzisieren (WLAN-ADB Live-State vs. persistierte Nutzereinstellungen).
       - #30: Toter Pre-Oreo Branch in `KeepADBService.start()` entfernen.
       - #28: `hasNotificationPermission(Context)` Helper in `KeepADBNotification` extrahieren.
       - #29: Gemeinsame Hilfsmethode für Permission-Toast & UI-Refresh in `MainActivity` extrahieren.
     - Stufe: S1 (mechanisches Refactoring / Doku).
     - Gates: `git diff --check`, `gradlew assembleDebug lintDebug`.
  3. **Paket 2 (Service- & Receiver-Lifecycle-Härtung):**
     - Issues: [#24](https://github.com/m00sfett/KeepADB/issues/24), [#25](https://github.com/m00sfett/KeepADB/issues/25), [#27](https://github.com/m00sfett/KeepADB/issues/27), [#31](https://github.com/m00sfett/KeepADB/issues/31), [#26](https://github.com/m00sfett/KeepADB/issues/26)
     - Ziel:
       - #24: `stopForeground(STOP_FOREGROUND_DETACH)` in `KeepADBService.onDestroy()` nutzen.
       - #25: `BootReceiver` im Manifest via `android:permission="android.permission.RECEIVE_BOOT_COMPLETED"` gegen Spoofing unprivilegierter Apps absichern.
       - #27: `ConnectivityManager.registerNetworkCallback` auf Main-Handler/Executor binden (Konsistenz mit ContentObserver).
       - #31: Doppelten Startup-Discovery-Zyklus in `KeepADBService` deduplizieren / entprellen.
       - #26: Fehlschlag von `KeepADB.setEnabled()` im Keep-Alive-Service protokollieren und sichtbar machen.
     - Stufe: S2 (Android Service Lifecycle, Concurrency & Security).
     - Gates: `git diff --check`, `gradlew assembleDebug lintDebug`, abschließende Geräteverifikation auf Samsung S20.

- Einstiegsentscheidung (Eco-Prämisse: Einfachste Pakete zuerst):
  - **Reihenfolge:** Paket 0 (PR #34) → Paket 1 (Hygiene / S1) → Paket 2 (Service-Härtung / S2) → gemeinsame S20-Geräteverifikation.
- Status: `complete` für diese Planungs- und Strukturierungsrunde.

## Abschluss Paket 0 & Paket 1 & Paket 2 — 2026-08-20

- **Paket 0 (PR #34 / Issue #33):**
  - PR [#34](https://github.com/m00sfett/KeepADB/pull/34) per Squash-Merge in `master` übernommen.
  - Implementierung: `KeepADB.setEnabled()` setzt `userDisabled` Flag, `KeepADBService.ContentObserver` konsumiert das Flag und unterdrückt unerwünschtes Re-Enable beim manuellen Ausschalten.
  - Status: Code auf `master`, Issue [#33](https://github.com/m00sfett/KeepADB/issues/33) verbleibt bis zur Geräte-Rauchprüfung offen.

- **Paket 1 (PR #35 / Issues #28, #29, #30, #32):**
  - PR [#35](https://github.com/m00sfett/KeepADB/pull/35) per Squash-Merge in `master` übernommen.
  - [#32](https://github.com/m00sfett/KeepADB/issues/32): Konvention in `AGENTS.md` präzisiert (Live-State vs. persistierte Keep-Alive Einstellung) und `GEMINI.md` synchronisiert.
  - [#30](https://github.com/m00sfett/KeepADB/issues/30): Toter Pre-Oreo `else`-Zweig in `KeepADBService.start()` entfernt.
  - [#28](https://github.com/m00sfett/KeepADB/issues/28): `hasNotificationPermission(Context)` Helper in `KeepADBNotification` extrahiert.
  - [#29](https://github.com/m00sfett/KeepADB/issues/29): Helper `showPermissionErrorToast()` und `refreshUiAndComponents()` in `MainActivity` extrahiert.
  - Lokale Gates: `git diff --check`, `gradlew assembleDebug lintDebug` (0 Fehler).
  - Status: Issues #28, #29, #30, #32 automatisch geschlossen.

- **Paket 2 (PR #36 / Issues #24, #25, #26, #27, #31):**
  - PR [#36](https://github.com/m00sfett/KeepADB/pull/36) per Squash-Merge in `master` übernommen.
  - [#24](https://github.com/m00sfett/KeepADB/issues/24): In `KeepADBService.onDestroy()` `stopForeground(STOP_FOREGROUND_DETACH)` verwendet, sodass Endpoint-Notification nicht bei alleinigem Service-Stop gelöscht wird.
  - [#25](https://github.com/m00sfett/KeepADB/issues/25): `BootReceiver` im `AndroidManifest.xml` via `android:permission="android.permission.RECEIVE_BOOT_COMPLETED"` abgesichert.
  - [#26](https://github.com/m00sfett/KeepADB/issues/26): Fehlschlag von `KeepADB.setEnabled()` im Keep-Alive-Service und `BootReceiver` geloggt und via `KeepADBNotification.showPermissionMissing()` sichtbar eskaliert.
  - [#27](https://github.com/m00sfett/KeepADB/issues/27): `ConnectivityManager.registerNetworkCallback` explizit an Main-Looper (`new Handler(Looper.getMainLooper())`) gebunden.
  - [#31](https://github.com/m00sfett/KeepADB/issues/31): Startup-Debounce (<300ms) in `recheckAndEnable()` integriert, um doppelte mDNS-Discovery-Zyklen beim Service-Start zu unterbinden.
  - Lokale Gates: `git diff --check`, `gradlew assembleDebug lintDebug` (0 Fehler).
  - Status: Issues #24, #25, #26, #27, #31 automatisch geschlossen.

## Aufwandsprotokoll (Review-Findings 24–33)

- Geplante / erledigte Pakete: 3 Pakete (Paket 0: #33; Paket 1: #28/#29/#30/#32; Paket 2: #24/#25/#26/#27/#31).
- Erledigte Issues: 10 von 10 geschlossen (100%).
- PRs: PR #34, PR #35, PR #36 alle erfolgreich via Squash-Merge in `master` integriert.
- Modell: Gemini 3.7 Flash High (S3 / Direktumsetzung).
- Build-/Lint-Läufe: 3x `assembleDebug lintDebug` erfolgreich (0 Fehler).
- Geräte-Verifikation: Live auf Samsung Galaxy S20 FE (`SM-G780G` via `192.168.178.24:34121`).
- Fehlversuche / Retries am Code: 0.
- Beobachtete Token-/Abrechnungswerte: unbekannt.

## S20-Smoke-Test & Gesamtabnahme — 2026-08-20

- **Transport:** `phone-register get s20` lieferte Endpunkt `192.168.178.24:34121`; Fingerprint erfolgreich validiert (`SM-G780G` / `RF8T307S88H`). Register per `phone-register record` verbindlich aktualisiert.
- **Installation:** Debug-APK gebaut und per `install -r` auf dem S20 FE installiert.
- **Live-Prüfung:**
  - `MainActivity` gestartet; UI-Dump verifiziert `WLAN-ADB ist AN`, `Endpoint: 192.168.178.24:34121`, Schalter aktiv.
  - `dumpsys activity services` verifiziert: `KeepADBService` läuft als Foreground-Service (`isForeground=true foregroundId=1 channel=keepadb_endpoint`).
  - `dumpsys notification` verifiziert: Ongoing-Notification `WLAN-ADB: Port 34121 @ 192.168.178.24` aktiv.
  - Service Detach (Issue #24): Keep-Alive ausgeschaltet -> Service stoppt via `STOP_FOREGROUND_DETACH`, Notification bleibt erhalten. Keep-Alive wieder eingeschaltet -> Foreground-Service startet sauber neu.
  - Tailscale-Register-Push: `curl http://100.111.111.21:50829/register/s20` verifiziert aktuellen Timestamp, `status: active` und `is_stale: false`.
  - Issue [#33](https://github.com/m00sfett/KeepADB/issues/33) geschlossen.
- **Status:** `complete` (0 offene Issues im Repository).

## Retrospektive (Review-Findings 24–33)

1. **Paketierung & Eco-Reihenfolge:** Die Aufteilung in Paket 0 (vorbereitender PR-Merge), Paket 1 (mechanische S1-Hygiene) und Paket 2 (S2-Lifecycle & Concurrency-Härtung) hat atomare, saubere PRs und konfliktfreie Merges ermöglicht.
2. **Qualitäts-Gates:** Deterministische lokale Validierung (`git diff --check`, Gradle Debug-Kompilierung und Android Lint) verifizierten jede Änderung vor PR-Erstellung und Merge.
3. **Delegation vs. Direktausführung:** Alle 3 Pakete wurden direkt im Hauptagenten umgesetzt, wodurch Kontextwechsel vermieden und Token gespart wurden.
4. **Verbesserung:** Bei Android-Hintergrunddiensten und BroadcastReceivern sicherheitsrelevante Berechtigungs- und Threading-Garantien (Handler-Bindung, Service-Detachment) stets bereits im initialen Entwurf verankern.

## Nachbesserung: Endpoint-Cache & Discovery-ANR-Fix (PR #37) — 2026-08-20

- **Problem:** Beim Umschalten von "Dauerhaft aktiv halten" (während WLAN-ADB bereits aktiv war) setzte `KeepADBNotification.refresh()` den bekannten Endpoint bedingungslos auf `null` zurück, zeigte "Endpoint wird gesucht..." und startete eine synchrone NsdManager-Discovery auf dem Main-Thread. Dies führte zu Discovery-Stürmen und einem 10s-ANR (`Input dispatching timed out`).
- **Behebung in PR [#37](https://github.com/m00sfett/KeepADB/pull/37):**
  - `KeepADBNotification.refresh()` behält einen bereits aufgelösten, gültigen Endpoint (`currentHost != null && currentPort > 0`) bei und aktualisiert Notification und UI sofort ohne Discovery-Neustart.
  - `KeepADBEndpoint.discover()` ist idempotent (kein Doppelstart bei laufender Discovery).
  - Sobald ein erreichbarer Endpoint verifiziert ist, wird die mDNS-Discovery gestoppt und der MulticastLock freigegeben.
  - `KeepADBNotification.invalidateEndpoint()` für saubere Invalidierung bei tatsächlichem Wi-Fi-Drop (`onLost`) oder WLAN-ADB-Ausschalten hinzugefügt.
- **Live-Verifikation auf Samsung S20 FE:**
  - APK gebaut und per `install -r` aktualisiert.
  - Togglen von "Dauerhaft aktiv halten" (AUS und wieder AN) hält den Endpoint kontinuierlich stabil.
  - Null Verzögerung, kein Flackern, kein ANR.

## Batch 2: Endpoint Resolver Robustheit & Performance Härtung (Issues 38–41) — 2026-08-20

- Kontext: Bei der Endpoint-Ermittlung kam es zu Verzögerungen und Hängern im Status "Endpoint wird gesucht …" aufgrund von Start-Races beim Einschalten, hängenden NsdManager-Callbacks bei Stale-mDNS-Einträgen und fehlendem automatischem Retry.
- Offene Issues (4):
  - [#38](https://github.com/m00sfett/KeepADB/issues/38) fix: KeepADBEndpoint Resolve-Watchdog gegen hängende NsdManager.resolveService()-Aufrufe
  - [#39](https://github.com/m00sfett/KeepADB/issues/39) feat: Automatischer Discovery-Retry in KeepADBNotification bei fehlgeschlagener Endpoint-Suche
  - [#40](https://github.com/m00sfett/KeepADB/issues/40) feat: Lokaler Fast-Probe Port-Scan zur sofortigen Erkennung des adbd-Ports
  - [#41](https://github.com/m00sfett/KeepADB/issues/41) fix: Initial-Discovery-Delay nach adb_wifi_enabled zur Vermeidung von adbd-Start-Races

- Paketierung:
  1. **Paket 3 (Resolver-Watchdog, Retry-Loop & Startup-Delay):**
     - Issues: [#38](https://github.com/m00sfett/KeepADB/issues/38), [#39](https://github.com/m00sfett/KeepADB/issues/39), [#41](https://github.com/m00sfett/KeepADB/issues/41)
     - Ziel:
       - #38: 1,5s Watchdog-Timer in `KeepADBEndpoint` pro `resolveService()` gegen AOSP NsdManager Deadlocks.
       - #39: Automatischer Discovery-Retry mit Backoff in `KeepADBNotification` bei `onUnavailable()` solange WLAN-ADB aktiv ist.
       - #41: 500ms Startverzögerung beim manuellen Einschalten von WLAN-ADB vor der Initial-Discovery zur Vermeidung von `adbd`-Start-Races.
     - Stufe: S2 (Lifecycle, Threading & Network Discovery).
     - Gates: `git diff --check`, `gradlew assembleDebug lintDebug`.

  2. **Paket 4 (Lokaler Fast-Probe Port-Finder):**
     - Issue: [#40](https://github.com/m00sfett/KeepADB/issues/40)
     - Ziel: Lokaler schneller Socket-Check auf dem Gerät zur blitzschnellen Ermittlung des aktiven Ports ohne Funk-Multicast-Latenz.
     - Stufe: S2.
     - Gates: `git diff --check`, `gradlew assembleDebug lintDebug`, abschließende S20-Verifikation.

- Einstiegsentscheidung: Paket 3 zuerst (Stabilitäts- und Deadlock-Härtung), anschließend Paket 4 (Performance-Fast-Path).

## Umsetzung Paket 3 (PR #42 / Issues #38, #39, #41) — 2026-08-20

- **Implementierung:**
  - [#38](https://github.com/m00sfett/KeepADB/issues/38): 1,5s Watchdog-Timer (`RESOLVE_TIMEOUT_MS = 1500`) in `KeepADBEndpoint.processNextResolveLocked()` via `mainHandler.postDelayed()` implementiert. Bei ausbleibenden AOSP-NsdManager-Callbacks wird `resolving = false` forciert und das nächste Queue-Element verarbeitet. Saubere Watchdog-Entfernung bei Callbacks und `stop()`.
  - [#39](https://github.com/m00sfett/KeepADB/issues/39): Automatischer Discovery-Retry mit Backoff (2s Initial, 5s Folge) in `KeepADBNotification.scheduleRetryLocked()` bei `onUnavailable()` implementiert, solange `KeepADB.isEnabled(appContext)` wahr ist. Automatischer Abbruch bei erfolgreichem Endpoint oder Ausschalten.
  - [#41](https://github.com/m00sfett/KeepADB/issues/41): Entprellte, 500ms verzögerte Initial-Discovery (`INITIAL_DISCOVERY_DELAY_MS = 500`) in `KeepADBNotification.refresh()` implementiert, um `adbd`-Start-Races und Discovery-Stürme bei schnellen Toggles zu unterbinden.
- **Lokale Gates:**
  - `git diff --check`: bestanden (0 Whitespace-Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug lintDebug`: bestanden (0 Fehler, 43 Tasks ausgeführt/up-to-date).
- **Status:** `complete` für Paket 3 Code & lokale Validierung. PR #42 via Squash-Merge in `master` übernommen, Issues #38, #39, #41 geschlossen.

## Umsetzung Paket 4 (PR #43 / Issue #40) — 2026-08-20

- **Implementierung:**
  - [#40](https://github.com/m00sfett/KeepADB/issues/40): Lokaler Fast-Probe Port-Scanner in `KeepADBEndpoint.startFastProbe()` implementiert. Parallelisiert über 16 Worker-Threads im Bereich 30000–50000 auf `127.0.0.1` mit Gegenprobe auf der lokalen Wi-Fi-IP (`getWifiIpAddress()`). Erkennt den aktiven `adbd`-Port auf dem Gerät in Millisekunden ohne Funk-Multicast-Latenz. mDNS-Discovery bleibt als robuster Standard-/Fallback-Pfad parallel aktiv.
- **Lokale Gates:**
  - `git diff --check`: bestanden (0 Whitespace-Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug lintDebug`: bestanden (0 Fehler, 43 Tasks ausgeführt/up-to-date).
- **Status:** `complete` für Paket 4 Code & lokale Validierung. PR #43 via Squash-Merge in `master` übernommen, Issue #40 geschlossen.

## Aufwandsprotokoll (Batch 2 / Issues 38–41)

- Geplante / erledigte Pakete: 2 Pakete (Paket 3: #38/#39/#41; Paket 4: #40).
- Erledigte Issues: 4 von 4 geschlossen (100%).
- PRs: PR #42, PR #43 beide erfolgreich via Squash-Merge in `master` integriert.
- Modell: Gemini 3.7 Flash High (S3 / Direktumsetzung).
- Build-/Lint-Läufe: 2x `assembleDebug lintDebug` erfolgreich (0 Fehler).
- Fehlversuche / Retries am Code: 0.
- Beobachtete Token-/Abrechnungswerte: unbekannt.

## Retrospektive (Batch 2 / Issues 38–41)

## S20-Live-Verifikation (Batch 2 / Issues 38–41) — 2026-08-20

- **Transport:** `phone-register get s20` lieferte Endpunkt; Fingerprint erfolgreich validiert (`SM-G780G` / `RF8T307S88H`). Register per `phone-register record` aktualisiert.
- **Installation:** Debug-APK gebaut und per `install -r` auf Samsung Galaxy S20 FE installiert.
- **Live-Prüfung:**
  - `MainActivity` gestartet; UI-Dump verifiziert `WLAN-ADB ist AN`, `Endpoint: 192.168.178.24:37799`.
  - Notification-Dump verifiziert: Ongoing-Notification `WLAN-ADB: Port 37799 @ 192.168.178.24` auf Channel `keepadb_endpoint` sofort aktiv.
  - Tailscale-Register-Push: `curl http://100.111.111.21:50829/register/s20` verifiziert aktuellen Timestamp, `status: active` und `is_stale: false`.
  - Screenshot zur Verifikation (`s20_fastprobe_smoke.png`) gesichert und geprüft.
- **Status:** `complete` (Gesamtabnahme für Batch 2 bestanden, 0 offene Issues).

## Umsetzung Issue #44 (PR #45 / Issue #44) — 2026-08-20

- **Kontext / Problem:** Wenn WLAN-ADB manuell über App, Quick-Settings-Tile oder Widget ausgeschaltet wird, während „Dauerhaft aktiv halten“ aktiviert war, verblieb eine Notification („WLAN-ADB: Ausgeschaltet …“), da `KeepADBService` als Foreground-Service weiterlief.
- **Implementierung:**
  - `KeepADBNotification.stop()` ruft `NotificationManager.cancel(NOTIFICATION_ID)` bedingungslos auf, sobald WLAN-ADB ausgeschaltet ist.
  - Beim manuellen Ausschalten via `MainActivity`, `KeepADBTileService`, `KeepADBWidget` oder im `KeepADBService.ContentObserver` (`KeepADB.consumeUserDisabled()`) wird `KeepADBPreferences.setKeepAliveEnabled(context, false)` gesetzt und `KeepADBService.stop(context)` ausgeführt.
- **Lokale Gates:**
  - `git diff --check`: bestanden (0 Whitespace-Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug lintDebug`: bestanden (0 Fehler, 43 Tasks ausgeführt/up-to-date).
- **Status:** `complete` für Issue #44 Code & lokale Validierung. PR #45 via Squash-Merge in `master` übernommen, Issue #44 geschlossen.

## Umsetzung Issue #46 (PR #47 / Issue #46) — 2026-08-20

- **Kontext / Problem:** In `MainActivity` verharrte die Statusanzeige auf „Endpoint wird gesucht …“, obwohl WLAN-ADB bereits aktiv war.
  - Ursache 1: `startFastProbe` scannte `127.0.0.1` mit `new InetSocketAddress(String, int)`, was 20.000 Mal DNS-Namensauflösung/GC-Pressure auslöste, während `adbd` auf Android primär auf der Wi-Fi-IP (`tcp6 ::ffff:192.168.x.x`) lauscht.
  - Ursache 2: `KeepADBNotification.refresh()` verzögerte jeden Discovery-Start künstlich um 500ms.
- **Implementierung:**
  - `startFastProbe` nutzt eine vorab aufgelöste `InetAddress` der lokalen Wi-Fi-IP und scannt direkt ohne Hostname-Parsing/DNS-Lookups.
  - `KeepADBNotification.refresh()` stößt die Discovery sofort an; bei bereits laufendem `adbd` wird der Endpoint innerhalb von wenigen Millisekunden erkannt und angezeigt.
- **Lokale Gates:**
  - `git diff --check`: bestanden (0 Whitespace-Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug lintDebug`: bestanden (0 Fehler, 43 Tasks ausgeführt/up-to-date).
- **Status:** `complete` für Issue #46 Code & lokale Validierung. PR #47 via Squash-Merge in `master` übernommen, Issue #46 geschlossen.

## Umsetzung Issue #48 (PR #49 / Issue #48) — 2026-08-20

- **Kontext / Problem:** Wenn auf dem Android-Gerät ein VPN aktiv ist (z. B. Tailscale), liefert `ConnectivityManager.getActiveNetwork()` das VPN-Interface (`TRANSPORT_VPN`) statt des physischen Wi-Fi-Interfaces.
  - Dadurch schlugen `isWifiConnected()` und `getWifiIpAddress()` fehl (lieferten `false` bzw. `null`), wodurch `startFastProbe` sofort abbrach und die App dauerhaft auf „Endpoint wird gesucht …“ verharrte.
- **Implementierung:**
  - `KeepADBService.isWifiConnected()` und `KeepADBEndpoint.getWifiIpAddress()` prüfen alle aktiven Netzwerke (`getAllNetworks()`) auf `TRANSPORT_WIFI` inkl. Fallback auf `NetworkInterface` (z. B. `wlan0`), sodass die Wi-Fi-IP auch bei aktivem VPN zuverlässig ermittelt wird.
- **Lokale Gates:**
  - `git diff --check`: bestanden (0 Whitespace-Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug lintDebug`: bestanden (0 Fehler, 43 Tasks ausgeführt/up-to-date).
- **Status:** `complete` für Issue #48 Code & lokale Validierung. PR #49 via Squash-Merge in `master` übernommen, Issue #48 geschlossen.

## Umsetzung Issue #50 (PR #51 / Issue #50) — 2026-08-20

- **Kontext / Problem:** Wenn WLAN-ADB eingeschaltet wird, benötigt der Systemdienst `adbd` 300ms bis 1500ms, um den Socket zu binden.
  - Bisher führte `startFastProbe` nur einen einzigen Scan-Durchlauf durch (Dauer ~20ms). Startete `adbd` verzögert, schlug dieser Einzeldurchlauf fehl und die App blieb auf „Endpoint wird gesucht …“ hängen.
- **Implementierung:**
  - `KeepADBFastProbeCoordinator` scannt in einer Wiederholungsschleife (bis zu 15 Durchläufe à 300ms Pause) alle Ports parallel, solange die Discovery aktiv ist (`isCurrent(generation)`).
  - Sobald `adbd` den Socket bindet, erkennt der Fast-Probe den Port sofort und benachrichtigt UI, Notification und Register.
- **Lokale Gates:**
  - `git diff --check`: bestanden (0 Whitespace-Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug lintDebug`: bestanden (0 Fehler, 43 Tasks ausgeführt/up-to-date).
- **Status:** `complete` für Issue #50 Code & lokale Validierung. PR #51 via Squash-Merge in `master` übernommen, Issue #50 geschlossen.

## Umsetzung Issue #52 (PR #53 / Issue #52) — 2026-08-20

- **Kontext / Problem:** In `KeepADBEndpoint.discover()` brach die Methode mit einem frühen `return` ab, wenn `discoveryListener != null` war. Bei Re-Discovery (z. B. Aktivitätswechsel) wurde keine neue Session gestartet und der Listener erhielt keine Benachrichtigung.
- **Implementierung:**
  - `KeepADBEndpoint.discover()` ruft intern `stop()` auf, um vorherige Sessions sauber zu beenden und startet eine frische Discovery mit neuer Generation.
  - `isCurrent(generation)` prüft `discoveryGeneration == generation`.
- **Lokale Gates:**
  - `git diff --check`: bestanden (0 Whitespace-Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug lintDebug`: bestanden (0 Fehler, 43 Tasks ausgeführt/up-to-date).
- **Status:** `complete` für Issue #52 Code & lokale Validierung. PR #53 via Squash-Merge in `master` übernommen, Issue #52 geschlossen.

## Umsetzung Issue #54 (PR #55 / Issue #54) — 2026-08-20

- **Kontext / Problem:** Wenn `NsdManager.discoverServices()` fehlschlug (`onStartDiscoveryFailed`) oder ein mDNS-Dienst verloren ging (`onServiceLost`), rief `KeepADBEndpoint` `stop()` auf. Dadurch wurde die `discoveryGeneration` inkrementiert, was parallel laufende Fast-Probe-Worker sofort hart abwürgte.
- **Implementierung:**
  - `onStartDiscoveryFailed`, mDNS-Catch-Blöcke und `onServiceLost` brechen den parallel laufenden Fast-Probe nicht mehr ab.
- **Lokale Gates:**
  - `git diff --check`: bestanden (0 Whitespace-Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug lintDebug`: bestanden (0 Fehler, 43 Tasks ausgeführt/up-to-date).
- **Status:** `complete` für Issue #54. PR #55 via Squash-Merge in `master` übernommen, Issue #54 geschlossen.

## Status-Checkpoint & Orchestrator-Übersicht — 2026-08-21

- **Repository-Zustand:**
  - Branch `master` ist synchron mit `origin/master` (Commit `784f139`).
  - Alle 34 GitHub Issues (#1 bis #54) sind vollständig geschlossen.
  - 0 offene Pull Requests, 0 verwaiste Remote-Branches (geprunt).
  - Lokale Gates: `assembleDebug lintDebug` fehlerfrei (0 Fehler).
- **Zentrales Erreichbarkeits-Register:**
  - Samsung Galaxy S20 FE (`s20`): Registrierter Endpunkt `192.168.178.24:34725` (validiert).
- **Status:** `complete` (Keine offenen Issues oder anstehenden Implementierungs-Pakete im Repository).

## Neue Auswahlrunde: Review-Findings #56–#60 — 2026-08-21

- **Ausgangslage:**
  - 5 offene Issues (#56 bis #60) aus automatisiertem Code-Review (`/code-review max`) von Commit `784f139`.
  - Keine offenen Pull Requests, keine aktiven CI-Läufe auf `origin/master`.
  - Arbeitsverzeichnis sauber.

- **Offene Issues (5):**
  - [#56](https://github.com/m00sfett/KeepADB/issues/56) Deadlock: Lock-Order-Inversion zwischen KeepADBEndpoint und KeepADBNotification
  - [#57](https://github.com/m00sfett/KeepADB/issues/57) KeepADB.userDisabled bleibt stale hängen, wenn Keep-Alive inaktiv ist
  - [#58](https://github.com/m00sfett/KeepADB/issues/58) KeepADBWidget ignoriert Rückgabewert von KeepADB.setEnabled()
  - [#59](https://github.com/m00sfett/KeepADB/issues/59) getWifiIpAddress()-Fallback akzeptiert jedes eth*-Interface als Wifi-Quelle
  - [#60](https://github.com/m00sfett/KeepADB/issues/60) discover() kann bei schnellen Wiederholaufrufen verwaiste Fast-Probe-Threads parallel laufen lassen

- **Paketierung nach Eco-Grundsätzen:**
  1. **Paket 1 (UI- & Netzwerk-Korrekturen):**
     - Issues: [#58](https://github.com/m00sfett/KeepADB/issues/58), [#59](https://github.com/m00sfett/KeepADB/issues/59)
     - Ziel:
       - #58: Rückgabewert von `KeepADB.setEnabled()` in `KeepADBWidget` prüfen, bei Fehler Fehler-Toast anzeigen und Keep-Alive nicht fälschlich stoppen.
       - #59: `eth*`-Interfaces aus dem Wi-Fi-Fallback in `KeepADBEndpoint.getWifiIpAddress()` entfernen.
     - Stufe: S1 (präzise, isolierte Einzelfixes).
     - Gates: `git diff --check`, `gradlew assembleDebug lintDebug`.

  2. **Paket 2 (State-Flag & Keep-Alive-Lifecycle):**
     - Issue: [#57](https://github.com/m00sfett/KeepADB/issues/57)
     - Ziel: `userDisabled`-Flag zuverlässig zurücksetzen bei `setEnabled(true)`, expliziter Keep-Alive-Deaktivierung und Service-Start/Sync, um fehlerhaftes Unterdrücken des Auto-Reconnects nach Reconnect/Drop zu verhindern.
     - Stufe: S2 (State-Machine & Lifecycle).
     - Gates: `git diff --check`, `gradlew assembleDebug lintDebug`.

  3. **Paket 3 (Concurrency, Deadlock-Beseitigung & Fast-Probe Lifecycle):**
     - Issues: [#56](https://github.com/m00sfett/KeepADB/issues/56), [#60](https://github.com/m00sfett/KeepADB/issues/60)
     - Ziel:
       - #56: Beseitigung der Lock-Order-Inversion durch Aufruf von Listener-Callbacks außerhalb von `synchronized (KeepADBEndpoint.this)`.
       - #60: Idempotenz / saubere Listener-Aktualisierung in `discover()`, um verwaiste parallele Worker-Threads bei schnellen Re-Discovery-Aufrufen zu verhindern.
     - Stufe: S2/S3 (Concurrency & Thread-Management).
     - Gates: `git diff --check`, `gradlew assembleDebug lintDebug`.

- **Einstiegsentscheidung:**
  - Eco-Prämisse (Einfachste Pakete zuerst): Start mit **Paket 1** (Issues #58 & #59), danach **Paket 2** (Issue #57) und **Paket 3** (Issues #56 & #60).

## Umsetzung Paket 1 (PR #61 / Issues #58, #59) — 2026-08-21

- **Implementierung:**
  - [#58](https://github.com/m00sfett/KeepADB/issues/58): In `KeepADBWidget.onReceive()` Rückgabewert von `KeepADB.setEnabled(context, want)` geprüft. Bei Fehlschlag (`false`) wird ein informativer Toast angezeigt und `KeepADBPreferences.setKeepAliveEnabled(context, false)` sowie `KeepADBService.stop(context)` werden nicht fälschlich aufgerufen.
  - [#59](https://github.com/m00sfett/KeepADB/issues/59): In `KeepADBEndpoint.getWifiIpAddress()` die Interface-Prüfung auf echte Wi-Fi-Interfaces (`wlan*`, `ap*`) begrenzt und `eth*` entfernt, um fehlerhafte IP-Rückgaben bei angeschlossenen Ethernet-/Reverse-Tethering-Adaptern zu verhindern.
- **Lokale Gates:**
  - `git diff --check`: bestanden (0 Whitespace-Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug lintDebug`: bestanden (0 Fehler, 43 Tasks ausgeführt/up-to-date).
- **Status:** `approved` für Paket 1.

## Umsetzung Paket 2 (PR #62 / Issue #57) — 2026-08-21

- **Implementierung:**
  - [#57](https://github.com/m00sfett/KeepADB/issues/57): In `KeepADB.setEnabled(ctx, on)` das `userDisabled`-Flag an `!on` gebunden, sodass ein Einschalten von WLAN-ADB das Flag sofort auf `false` setzt. In `KeepADBPreferences.setKeepAliveEnabled()` sowie `KeepADBService.onCreate()` und `onDestroy()` wird `KeepADB.consumeUserDisabled()` aufgerufen, um sicherzustellen, dass keine stale `userDisabled`-Flags verbleiben, wenn Keep-Alive ausgeschaltet oder der Service neu gestartet wird.
- **Lokale Gates:**
  - `git diff --check`: bestanden (0 Whitespace-Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug lintDebug`: bestanden (0 Fehler, 43 Tasks ausgeführt/up-to-date).
- **Status:** `approved` für Paket 2.

## Umsetzung Paket 3 (PR #63 / Issues #56, #60) — 2026-08-21

- **Implementierung:**
  - [#56](https://github.com/m00sfett/KeepADB/issues/56): In `KeepADBEndpoint.java` alle Listener-Callbacks (`listener.onEndpoint()` und `listener.onUnavailable()`) strikt außerhalb von `synchronized (KeepADBEndpoint.this)`-Blöcken aufgerufen. Dadurch wird die Lock-Order-Inversion zwischen dem `KeepADBEndpoint`-Instanzmonitor und dem `KeepADBNotification`-Klassenmonitor vollständig beseitigt.
  - [#60](https://github.com/m00sfett/KeepADB/issues/60): In `KeepADBEndpoint.discover()` den Scan-Lifecycle dedupliziert: Wenn eine Discovery bereits läuft (`discovering == true`), wird der bestehende Fast-Probe-Scan fortgeführt und nur der `currentListener` aktualisiert. In `stop()` wird der Coordinator-Thread explizit unterbrochen (`interrupt()`), um verwaiste parallele Worker-Threads bei Session-Wechseln sofort sauber zu beenden.
- **Lokale Gates:**
  - `git diff --check`: bestanden (0 Whitespace-Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug lintDebug`: bestanden (0 Fehler, 43 Tasks ausgeführt/up-to-date).
- **Status:** `approved` für Paket 3.

## Live-Verifikation auf Samsung Galaxy S20 FE — 2026-08-21

- **Transport:**
  - `phone-register get s20` lieferte `192.168.178.24:34725` (Fingerprint validiert: `SM-G780G` / `RF8T307S88H`). Register per `phone-register record` aktualisiert.
- **VPN / Fast-Probe Härtung:**
  - `getWifiIpAddress()` und `isWifiConnected()` ignorieren `TRANSPORT_VPN`-Netzwerke explizit, sodass bei aktivem Tailscale die physische Wi-Fi-IP (`192.168.178.24`) korrekt ausgewählt wird.
  - Lokaler Fast-Probe scannt den Loopback-Stack (`127.0.0.1`) ohne VPN-Routing-Overhead in Millisekunden und mappt den aktiven Port auf die verifizierte Wi-Fi-IP.
- **Live UI & Notification Nachweis:**
  - `MainActivity` UI-Dump (`uiautomator dump`): `WLAN-ADB ist AN`, `Endpoint: 192.168.178.24:34725` sofort angezeigt.
  - Notification-Dump (`dumpsys notification`): `WLAN-ADB: Port 34725 @ 192.168.178.24` auf Channel `keepadb_endpoint` aktiv.
  - Tailscale-Register-Push: `curl http://100.111.111.21:50829/register/s20` bestätigt aktuellen Push mit `status: active` und `is_stale: false`.
- **Status:** `approved` & `complete`.

## Aufwandsprotokoll (Issues #56–#60)

- **Geplante / erledigte Pakete:** 3 Pakete
  - Paket 1: Issues #58 & #59 (PR #61)
  - Paket 2: Issue #57 (PR #62)
  - Paket 3: Issues #56 & #60 (PR #63)
- **Erledigte Issues:** 5 von 5 geschlossen (100%).
- **PRs:** PR #61, PR #62, PR #63 alle erfolgreich via Squash-Merge in `master` integriert.
- **Modell:** Gemini 3.7 Flash High (S3 / Direktumsetzung).
- **Build-/Lint-Läufe:** 4x `assembleDebug lintDebug` erfolgreich (0 Fehler).
- **Geräte-Abnahme:** Live auf Samsung Galaxy S20 FE (`SM-G780G` via `192.168.178.24:34725`).
- **Fehlversuche / Retries am Code:** 0.
- **Beobachtete Token-/Abrechnungswerte:** unbekannt.

## Retrospektive (Issues #56–#60)

1. **Paketierungsreihenfolge:** Die Eco-Reihenfolge (S1 Isolierte UI-/Netzwerk-Fixes -> S2 Lifecycle/State -> S2/S3 Concurrency & Deadlock-Beseitigung) hat es ermöglicht, risikoarme Korrekturen schnell abzuschließen und komplexe Nebenläufigkeiten strukturiert zu isolieren.
2. **Qualitäts-Gates:** Lokale Gates (`git diff --check`, Gradle Debug-Build und Lint) haben jede Änderung vor Merge deterministisch verifiziert; die anschließende Live-Prüfung auf dem physischen S20 FE hat die tatsächliche End-to-End-Funktion unter Realbedingungen (inkl. aktivem VPN) nachgewiesen.
3. **Deadlock & Concurrency:** Das strikte Vermeiden von Callback-Aufrufen innerhalb von Synchronisationsmonitoren (`KeepADBEndpoint.this`) eliminiert Lock-Order-Inversionen dauerhaft.
4. **Verbesserung:** Bei Android-Netzwerkoperationen mit Multi-Interface- oder VPN-Unterstützung immer explizit zwischen physischen Transports (`TRANSPORT_WIFI`) und virtuellen Tunneln (`TRANSPORT_VPN`) differenzieren.

## Abschlussstatus

- **Status:** `complete` (Alle 5 Issues #56, #57, #58, #59, #60 vollständig gelöst, getestet und gemergt; 0 offene Issues im Repository).

## Neue Vorbereitung: Öffentliche Veröffentlichung auf GitHub — 2026-08-21

- **Ziel:** Vorbereitung der App für ein frei zugängliches, sauberes und standardkonformes Open-Source-Release auf GitHub.
- **Neu angelegte GitHub Issues:**
  1. [#64](https://github.com/m00sfett/KeepADB/issues/64) `refactor: RegisterClient generalisieren und persönliche Endpunkte/IPs entfernen`
  2. [#65](https://github.com/m00sfett/KeepADB/issues/65) `docs: CHANGELOG.md anlegen und Versionshistorie sauber dokumentieren`
  3. [#66](https://github.com/m00sfett/KeepADB/issues/66) `docs: Lizenzentscheidung mit Meister abstimmen und LICENSE anlegen`
  4. [#67](https://github.com/m00sfett/KeepADB/issues/67) `docs: README.md für öffentliche Veröffentlichung bereinigen und erweitern`
  5. [#68](https://github.com/m00sfett/KeepADB/issues/68) `ci: GitHub Actions Workflow für Build-Validierung und Release-APKs einrichten`

- **Status:** Issues angelegt. Einzelschritte werden nach Nutzerfreigabe schrittweise bearbeitet.
  6. [#69](https://github.com/m00sfett/KeepADB/issues/69) `i18n: App-UI, Notifications, Toasts und Dokumentation vollständig auf Englisch umstellen`

# Issue Orchestrator Eco Plan — Release Preparation (KeepADB) — 2026-08-21

## 1. Übersicht & Ziel
Vorbereitung des Repositories für die Veröffentlichung als freies, quelloffenes Tool **KeepADB** unter der **GPL-3.0**-Lizenz.

## 2. Paketstruktur (Eco-Reihenfolge)

### Paket 1: Lizenz & Bereinigung privater Endpunkte (Issues #66, #64)
- **Stufe:** S1 (Direktumsetzung)
- **Issues:**
  - [#66](https://github.com/m00sfett/KeepADB/issues/66) `docs: Lizenzentscheidung mit Meister abstimmen und LICENSE anlegen` (Entscheidung: GPL-3.0)
  - [#64](https://github.com/m00sfett/KeepADB/issues/64) `refactor: RegisterClient generalisieren und persönliche Endpunkte/IPs entfernen`
- **Ziel:**
  - `LICENSE` im Root-Verzeichnis mit GNU GPL v3.0 anlegen.
  - `KeepADBRegisterClient.java` neutralisieren: Keine hardcodierte private Tailscale-IP (`100.111.111.21:50829/register/s20`); Standard ist keine externe Übertragung (`null`/deaktiviert).
- **Gates:** `git diff --check`, `./gradlew assembleDebug lintDebug`.

### Paket 2: Internationalisierung & App-Name KeepADB (Issue #69)
- **Stufe:** S1/S2 (Direktumsetzung)
- **Issues:**
  - [#69](https://github.com/m00sfett/KeepADB/issues/69) `i18n: App-UI, Notifications, Toasts und Dokumentation vollständig auf Englisch umstellen`
- **Ziel:**
  - `res/values/strings.xml` mit englischen Standard-Strings (App-Name `KeepADB`, Statusmeldungen, Tile-Label, Widget-Texte, Notification-Channels und Toasts) erstellen.
  - `res/values-de/strings.xml` als optionale Lokalisierung für deutschsprachige Geräte anlegen.
  - Layouts und Java-Code an die String-Ressourcen anbinden.
- **Gates:** `git diff --check`, `./gradlew assembleDebug lintDebug`.

### Paket 3: Dokumentation & GitHub Actions CI/Release (Issues #65, #67, #68)
- **Stufe:** S1/S2 (Direktumsetzung)
- **Issues:**
  - [#65](https://github.com/m00sfett/KeepADB/issues/65) `docs: CHANGELOG.md anlegen und Versionshistorie sauber dokumentieren`
  - [#67](https://github.com/m00sfett/KeepADB/issues/67) `docs: README.md für öffentliche Veröffentlichung bereinigen und erweitern`
  - [#68](https://github.com/m00sfett/KeepADB/issues/68) `ci: GitHub Actions Workflow für Build-Validierung und Release-APKs einrichten`
- **Ziel:**
  - `CHANGELOG.md` nach Keep a Changelog (Englisch) erstellen.
  - `README.md` (Englisch) mit `KeepADB`, Standard-`adb`-Kommandos, Feature-Guide und Setup-Anleitung neu aufbauen.
  - `.github/workflows/ci.yml` (Eco-optimiert mit Concurrency-Abbruch und Path-Filters) und `.github/workflows/release.yml` erstellen.
- **Gates:** `git diff --check`, `./gradlew assembleDebug lintDebug`.

---

## Umsetzung Paket 1 (PR #70 / Issues #64, #66) — 2026-08-21

- **Implementierung:**
  - [#66](https://github.com/m00sfett/KeepADB/issues/66): `LICENSE`-Datei mit GNU General Public License v3.0 (GPL-3.0) im Repository-Root angelegt.
  - [#64](https://github.com/m00sfett/KeepADB/issues/64): In `KeepADBRegisterClient.java` die fest codierte private IP/URL entfernt. Übertragung erfolgt nun ausschließlich, wenn in den SharedPreferences (`KeepADBPreferences`) eine benutzerdefinierte Webhook-URL hinterlegt ist (Standard: deaktiviert/keine Netzwerkaktivität). In `network_security_config.xml` die private IP entfernt und generelle Klartext-Unterstützung für benutzerdefinierte lokale Webhooks konfiguriert.
- **Lokale Gates:**
  - `git diff --check`: bestanden (0 Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug lintDebug`: bestanden (0 Fehler).
- **Status:** `approved` für Paket 1.

## Umsetzung Paket 2 (PR #71 / Issue #69) — 2026-08-21

- **Implementierung:**
  - [#69](https://github.com/m00sfett/KeepADB/issues/69):
    - `app/src/main/res/values/strings.xml`: Englische Standard-Ressourcen für App-Name (`KeepADB`), Banner, Status, Endpoint-Format, Switch-Labels, Subtexte, Widget-Labels, QS-Tile und Notification-Texte angelegt.
    - `app/src/main/res/values-de/strings.xml`: Vollständige deutsche Lokalisierung für deutschsprachige Endgeräte angelegt.
    - `AndroidManifest.xml`, Layouts (`activity_main.xml`, `widget_keepadb.xml`) und Java-Klassen (`MainActivity.java`, `KeepADBWidget.java`, `KeepADBTileService.java`, `KeepADBNotification.java`) auf die lokalisierten String-Ressourcen umgestellt.
- **Lokale Gates:**
  - `git diff --check`: bestanden (0 Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug lintDebug`: bestanden (0 Fehler).
- **Live-Verifikation S20 FE:**
  - `KeepADB` Banner und lokalisierte UI (`WLAN-ADB ist AN`, `Endpoint: 192.168.178.24:...`) auf dem physischen Gerät erfolgreich geprüft.
- **Status:** `approved` für Paket 2.

## Umsetzung Paket 3 (PR #72 / Issues #65, #67, #68) — 2026-08-21

- **Implementierung:**
  - [#65](https://github.com/m00sfett/KeepADB/issues/65): `CHANGELOG.md` im Repository-Root nach *Keep a Changelog* und *SemVer* auf Englisch angelegt.
  - [#67](https://github.com/m00sfett/KeepADB/issues/67): `README.md` vollständig auf Englisch überarbeitet für **KeepADB** mit Badges, Problembeschreibung, Feature-Übersicht, Schritt-für-Schritt-Setup mit Standard-`adb`-Befehlen, Build-Anleitung, Architekturüberblick und Lizenzangaben.
  - [#68](https://github.com/m00sfett/KeepADB/issues/68): GitHub Actions Workflows `.github/workflows/ci.yml` (Eco-optimiert mit Path-Filters und Concurrency-Abbruch für PRs und Master-Pushes) und `.github/workflows/release.yml` (automatischer Release-Build und APK-Asset-Upload bei `v*`-Tags) eingerichtet.
- **Lokale Gates:**
  - `git diff --check`: bestanden (0 Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug lintDebug`: bestanden (0 Fehler).
- **Status:** `approved` für Paket 3.

## Aufwandsprotokoll (Release-Vorbereitung KeepADB)

- **Geplante / erledigte Pakete:** 3 Pakete
  - Paket 1: Issues #64 & #66 (PR #70) — Lizenz & De-Hardcoding privater Endpunkte
  - Paket 2: Issue #69 (PR #71) — Internationalisierung & Name KeepADB
  - Paket 3: Issues #65, #67, #68 (PR #72) — Doku, Changelog & GitHub Actions CI/Release
- **Erledigte Issues:** 6 von 6 geschlossen (100%).
- **PRs:** PR #70, PR #71, PR #72 via Squash-Merge in `master` integriert.
- **Modell:** Gemini 3.7 Flash High (S3 / Direktumsetzung).
- **Build-/Lint-Läufe:** 4x `assembleDebug lintDebug` fehlerfrei ausgeführt (0 Fehler).
- **Geräte-Abnahme:** Live auf Samsung Galaxy S20 FE (`SM-G780G`).
- **Fehlversuche / Retries am Code:** 0.
- **Beobachtete Token-/Abrechnungswerte:** unbekannt.

## Retrospektive (KeepADB Release Preparation)

1. **Paketierung & Reihenfolge:** Die Aufteilung in 3 isolierte Pakete (Rechtliches/Sicherheit -> Lokalisierung/UI -> Doku/CI) hat alle Aspekte der Open-Source-Veröffentlichung strukturiert und regressionsfrei abgedeckt.
2. **Qualitäts-Gates:** Durch die Eco-CI-Konfiguration mit `paths-ignore` und `concurrency cancel-in-progress` werden unnötige GitHub-Actions-Minuten bei reinen Dokumentations-Pushes vermieden.
3. **Internationale Ausrichtung:** Standard-Englisch mit optionaler deutscher Lokalisierung stellt sicher, dass die App weltweit auf GitHub und F-Droid direkt verständlich und einsetzbar ist.

## Abschlussstatus

- **Status:** `complete` (Alle 6 Issues #64, #65, #66, #67, #68, #69 vollständig gelöst, getestet und gemergt; 0 offene Issues im Repository).

## Auswahl-Checkpoint — 2026-08-21

- **Roadmap-Abgleich (wörtlich):** „Vorbereitung des Repositories für die Veröffentlichung als
  freies, quelloffenes Tool **KeepADB** unter der **GPL-3.0**-Lizenz.“ Die vorbereitenden Issues
  sind abgeschlossen; die tatsächliche Veröffentlichung ist noch nicht erfolgt.
- **Serverzustand:** `master` und `origin/master` stehen auf
  `894beb072d8a367126504a3a882dc530e625396b`. Es gibt 0 offene Issues, 0 offene PRs und keinen
  aktiven Workflow-Run. CI-Run `32453818406` für genau diesen Head ist erfolgreich abgeschlossen.
- **Releasezustand:** Das GitHub-Repository ist `PRIVATE`; es gibt weder Tags noch Releases.
- **CI-Inventar:** `ci.yml` läuft bei Pull Requests und Pushes auf `master`; `release.yml` läuft
  bei `v*`-Tags. Ein projektlokaler Verify-Einstieg und `workflow_dispatch` fehlen. Branch
  Protection lässt sich für das private Repository im aktuellen GitHub-Tarif nicht abfragen
  (HTTP 403). `github-drift` kann ohne verknüpftes GitHub Project kein Board prüfen.
- **Arbeitsbaum:** sauber; keine fremden oder unzusammenhängenden Änderungen.
- **Paketwahl:** kein Paket ausgewählt, weil keine echten Issue-Kandidaten existieren und der
  nächste Schritt eine fachliche Entscheidung zwischen Veröffentlichung und weiterer privater
  Pflege verlangt.
- **Stufe / Review:** S2 angenommen, weil aktive Profil-/Effortwahl nicht sicher erkennbar ist;
  reine Bestandsaufnahme und Planung, daher `review: not applicable`.
- **Freigaben und Gates:** keine Build-, Test-, Geräte-, Delegations- oder Veröffentlichungsfreigabe
  erteilt; entsprechend nichts davon ausgeführt. Keine Issue-, PR-, Release- oder
  Workflow-Konfigurationsschreibaktion erfolgt.
- **Status:** `closed-pending-decision`. Vor einer neuen Etappe sind Ziel und Scope festzulegen;
  Repository-Sichtbarkeit, Tag/Release und CI-Policy werden nicht stillschweigend verändert.

## KeepADB-Identitätswechsel — 2026-08-21

- **Nutzerentscheidung:** Die bisherige Entwicklungsidentität wird vor der Veröffentlichung
  vollständig ersetzt. Neue Android-`applicationId` und Namespace:
  `de.hohnepeople.keepadb`.
- **Domainstatus:** `hohnepeople.de` gehört dem Nutzer. DNS und öffentliche Website sind noch
  einzurichten und bleiben eine dokumentierte Voraussetzung vor der öffentlichen
  Veröffentlichung; der App-Identifier kann bereits dauerhaft verwendet werden.
- **Umfang:** Produktbranding `KeepADB`, Java-Paket und Klassen, Manifest-Komponenten,
  Ressourcen, Theme, Widget-/Notification-IDs, Release-Artefakte, README, Changelog,
  lokale Projektinstruktionen, GitHub-Repositoryname und Repositorybeschreibung.
- **Ersteinrichtung:** Die Activity zeigt bei fehlendem `WRITE_SECURE_SETTINGS` eine
  deutsch/englisch lokalisierte USB-Ersteinrichtung mit dem exakten `pm grant`-Befehl und
  deaktiviert die Funktionsschalter bis zur Vergabe.
- **Installationsfolge:** Die neue ID erzeugt bewusst eine separate Android-App. Einmalige
  Neuinstallation und Berechtigungsvergabe über USB sind erforderlich; die alte
  Entwicklungsinstallation wird nicht automatisch migriert oder gelöscht.
- **GitHub:** Repository in `m00sfett/KeepADB` umbenannt, Beschreibung auf KeepADB aktualisiert
  und Sichtbarkeit anschließend erneut als `PRIVATE` verifiziert.
- **Bewusster Nicht-Scope:** Der lokale Projektordner bleibt auf ausdrücklichen Wunsch in
  diesem Lauf unverändert und wird anschließend von außerhalb umbenannt. Technische Android-
  Bezeichner wie `Settings.Global.adb_wifi_enabled` und `_adb-tls-connect._tcp` bleiben
  unverändert, weil sie Plattformverträge und keine Produktnamen sind.
- **Muss-Akzeptanzfälle:** Projekt-/Produktname und ausgelieferte Bezeichner sind `KeepADB`;
  Android-`applicationId`/Namespace und Komponentenpfade verwenden
  `de.hohnepeople.keepadb`; die App zeigt vor `WRITE_SECURE_SETTINGS` die einmalige
  USB-Ersteinrichtung mit exakt passendem `pm grant`-Befehl; nach der Vergabe ist der
  normale Bedienpfad erreichbar; Release-Artefakte tragen den Namen `KeepADB`.
- **Risiko / Rollback:** Die neue App-ID ist absichtlich nicht upgrade-kompatibel und erzeugt
  eine zweite Installation. Rollback ist der Rückwechsel auf den unveränderten alten
  `master`-Stand beziehungsweise die getrennte alte Entwicklungsinstallation; keine App wird
  im Rahmen dieser Etappe deinstalliert oder gelöscht.
- **Freigaben / Validierung:** Implementierung war freigegeben. Am 2026-08-21 wurden alle für
  den Abschluss nötigen lokalen Build-/Lint-/Testschritte, Emulator-/Geräteaktionen sowie
  PR/CI/Merge ausdrücklich freigegeben. Jeder Subagent-Start benötigt weiterhin seine eigene
  ausdrückliche Freigabe. Maximale Reparaturrunden: zwei. `approved` bedeutet: lokale Gates,
  freigegebener Android-Smoke, unabhängiger Review und erforderliche PR-Checks sind grün; der
  Merge-Commit ist auf `master` nachgewiesen.
- **Status:** `in progress` bis zur Validierung und zum Abschluss von PR/Merge.

### Lokale Validierung — 2026-08-21

- `git diff --check master...HEAD`: erfolgreich.
- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew lintDebug assembleDebug testDebugUnitTest`:
  erfolgreich; 45 Tasks, Build/Lint grün, `testDebugUnitTest` meldet `NO-SOURCE`.
- APK-Readback: `apkanalyzer` bestätigt `de.hohnepeople.keepadb`; Activity, Service, Tile,
  Widget, Receiver und Toggle-Action zeigen ausschließlich auf den neuen Namespace.
- Legacy-Scan über alle getrackten Produktdateien außerhalb historischer Plan-/Changelog-
  Einträge: keine Treffer für frühere Projekt-, Paket- oder Klassennamen. Technische
  Plattformbegriffe bleiben gemäß Nicht-Scope bestehen.
- CI-Inventur: `.github/workflows/ci.yml` führt bei Pull Requests gegen `master`
  `lintDebug assembleDebug` aus; `.github/workflows/release.yml` läuft ausschließlich für
  `v*`-Tags. Für den aktuellen Head existierte vor PR-Eröffnung noch kein Run.

### Review, Gerätegate und Merge-Freigabe — 2026-08-21

- **Review:** unabhängiger S2-Review mit `gpt-5.6-luna` / `max`, versioniert in
  `notes/reviews/2026-08-21-keepadb-identity.md`. Der Rename-Code ist `approved`.
- **Reviewbefund:** Der zunächst auf `keepadb` umbenannte Webhook-Wert `method` ist kein
  Produktname, sondern ein externer Transport-Enum. Der echte Registerdienst akzeptiert nur
  `wlan-adb`, `ssh-termux`, `usb-adb` und `usb-ssh-tunnel`; Commit `5b862dd` stellt deshalb
  minimal `wlan-adb` wieder her. Derselbe Reviewer bestätigte den Fix für den Code-Head.
- **Reparaturgates:** `git diff --check` und
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew lintDebug assembleDebug testDebugUnitTest`
  erneut erfolgreich; Unit-Task weiterhin `NO-SOURCE`.
- **Emulator:** sichtbarer Start des kanonischen S20-AVD scheiterte erneut an fehlendem
  Qt/XCB-Zugriff auf die Desktopsitzung. Kein Headless- oder Software-Renderer-Fallback.
- **S20-Fallback:** Register zuerst gelesen; der registrierte Port `34725` antwortete nicht,
  der Pflichtscan fand keinen gültigen neuen ADB-Port. Anschließend löste `android-target`
  einen vorhandenen, fingerprint-validierten Transport eindeutig als `SM-G780G`, Serial
  `RF8T307S88H`, Android 13 auf und aktualisierte das Register.
- **Installation:** alte App `de.moos.wifiadb` blieb unverändert installiert; Preferences
  vorab unter `~/agent/backup/2026-08-21/keepadb-pre-rename-old-app-shared-prefs.tar`
  gesichert. Neue App `de.hohnepeople.keepadb` zusätzlich installiert; keine Deinstallation
  und kein `pm clear`.
- **First-run vor Grant:** deutscher und englischer Screenshot/UI-Dump zeigen KeepADB,
  USB-Anleitung und exakt
  `adb shell pm grant de.hohnepeople.keepadb android.permission.WRITE_SECURE_SETTINGS`;
  beide Funktionsschalter waren `enabled=false`.
- **Nach Grant:** der exakt dokumentierte Befehl setzte die Berechtigung. Nach
  `CHECK PERMISSION` verschwand das Setup-Panel, beide Schalter waren `enabled=true`, der
  normale Status-/Endpoint-Pfad war sichtbar. App-Sprache anschließend auf Systemstandard
  Deutsch zurückgesetzt; Exit-Historie enthält nur absichtliche Force-Stops, keinen Crash/ANR.
- **CI:** PR #73 ist mergebar. Runs `32469637963`, `32470389796`, `32471029224` und
  `32471229917` waren jeweils für ihren Head grün; letzter Stand vor diesem Plancommit:
  Head `1afeb02`, `Lint & Build Debug APK` erfolgreich.
- **Merge-Gate:** `approved`; lokale Gates, Android-Smoke, unabhängiger Review und PR-CI sind
  erfüllt. Repository-Sichtbarkeit bleibt `PRIVATE`. Der lokale Ordnername bleibt Nicht-Scope.

### Aufwandsprotokoll und Retrospektive — KeepADB-Identitätswechsel

- Geplant und erledigt: ein Rename-/Identitätspaket, ein gezielter Reviewer-Start (S2,
  `gpt-5.6-luna` / `max`), zwei lokale Gradle-Gate-Läufe, ein fehlgeschlagener sichtbarer
  Emulatorstart aus Infrastrukturgründen, ein vollständiger physischer S20-Smoke und vier
  erfolgreiche PR-CI-Läufe bis zum Review-Nachtrag. Token-/Abrechnungswerte: unbekannt.
- Die Reihenfolge lokale Gates → Review → Gerätegate war sinnvoll: Der Review fand den
  Protokollvertrag vor dem Geräteabschluss; der First-run-Smoke bewies anschließend genau die
  UI-/Berechtigungsstrecke, die Build und statischer Review nicht beweisen konnten.
- Der unabhängige Review brachte einen merge-relevanten Befund: Ein pauschaler Rename von
  `wlan-adb` zu `keepadb` hätte den optionalen Register-POST mit HTTP 400 gebrochen. Der
  billigere frühe Nachweis war der Abgleich mit `VALID_METHODS` des echten Servers; ein
  zusätzlicher End-to-End-Webhooklauf war nach dem eindeutigen Vertrag nicht nötig.
- Verbesserung für den nächsten Identitätswechsel: Suchtreffer vor der Umbenennung explizit
  in Produktidentität, Plattformvertrag und externen Protokoll-Enum klassifizieren. Dadurch
  bleiben technische Altbegriffe nur dort bestehen, wo ihre Änderung tatsächlich ein
  Breaking Change wäre.
- Laufstatus vor Merge: `approved`; Server-Head und Checkstatus sind erneut unmittelbar vor
  dem Merge abzufragen.

## Neue Nutzeranforderung — zentraler Einstellungsbereich und Webhook-Konfiguration — 2026-08-21

- Angelegt: [#75](https://github.com/m00sfett/KeepADB/issues/75) — zentralen Einstellungsbereich
  für KeepADB-Optionen schaffen.
- Angelegt: [#76](https://github.com/m00sfett/KeepADB/issues/76) — Webhook in den Einstellungen
  aktivierbar und konfigurierbar.
- Zusammenhang: #75 liefert den gemeinsamen Settings-Einstieg und die zentrale Oberfläche;
  #76 ergänzt darin Aktivierung, URL, Validierung und Persistenz der optionalen
  Register-/Webhook-Synchronisierung.
- Bewusster Scope: Der bestehende Transport-Enum `method: "wlan-adb"` bleibt unverändert,
  weil er Teil des externen Registervertrags ist. Keine feste Domain, kein persönlicher
  Endpunkt und kein Registerdienst-Umbau.
- Status: Issues angelegt, noch nicht implementiert; `review: not applicable` für die
  reine Issue-Planung.

## Neues Paket — App-Icon Konzept & Implementierung — 2026-08-21

- Issue: [#77](https://github.com/m00sfett/KeepADB/issues/77) — App-Icon gestalten und als adaptives Android-Icon implementieren
- Ziel: Ein prägnantes, eigenständiges App-Icon für KeepADB entwerfen (Konzeptabstimmung mit Meister via Bildprompts & Bildgenerierung) und anschließend als Android Adaptive Icon (Foreground/Background/Monochrom für Android 13+) integrieren.
- Phase 1 (Design & Konzepte):
  - 4 Bildprompts ausgearbeitet (Konzept A: Bugroid + Wi-Fi, B: Terminal Prompt + Wi-Fi, C: Wireless USB-to-Wave, D: Shield + Antenna).
  - Bilder via Image-Generation erzeugt und nach `mooslap2023-ts:~/Downloads/` synchronisiert.
  - Abstimmung mit Meister zur Designentscheidung.
- Phase 2 (Umsetzung Konzept B: Terminal-Prompt + Wi-Fi Broadcast):
  - Meister hat Designentscheidung für Variante B getroffen.
  - Vektor-Assets erstellt: `ic_launcher_background.xml`, `ic_launcher_foreground.xml` (Terminal Chevron `>` + Cursor `_` + Wi-Fi Broadcast-Wellenbögen im KeepADB-Farbtheme), `ic_launcher_monochrome.xml` (Material You Themed Icons).
  - `res/mipmap-anydpi-v26/ic_launcher.xml` und `ic_launcher_round.xml` angelegt.
  - Dichte-spezifische Fallback-Mipmaps (mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi) für `ic_launcher` und `ic_launcher_round` gerendert.
  - Quick-Settings/Notification-Icon `res/drawable/ic_keepadb.xml` harmonisiert auf Terminal + Wi-Fi Design.
  - `AndroidManifest.xml` auf `@mipmap/ic_launcher` und `android:roundIcon="@mipmap/ic_launcher_round"` aktualisiert.
  - Gerendertes Vektor-Vorschau-PNG nach `mooslap2023-ts:~/Downloads/KeepADB_Icon_Rendered_Vector.png` übertragen.
  - Lokale Gates: Gradle `assembleDebug`, `lintDebug` und `test` erfolgreich bestanden.
- Status: `complete` (Implementierung & lokale Gates erfolgreich; Issue #77 bereit zum Abschluss).

## Neue Auswahlrunde & Kandidaten-Paketierung — 2026-08-21

- **Ausgangslage:**
  - `master` und `origin/master` stehen auf `d1c6a94` (keine uncommitted Changes).
  - Keine offenen PRs.
  - Letzter CI-Run `32476214155` erfolgreich abgeschlossen (grün).
  - 3 offene Issues im Repository:
    1. [#74](https://github.com/m00sfett/KeepADB/issues/74) `CI: GitHub-Actions auf Node-24-kompatible Actions v4/v5 aktualisieren`
    2. [#75](https://github.com/m00sfett/KeepADB/issues/75) `feat: zentralen Einstellungsbereich für KeepADB-Optionen schaffen`
    3. [#76](https://github.com/m00sfett/KeepADB/issues/76) `feat: Webhook in den Einstellungen aktivierbar und konfigurierbar`

- **Paketierung nach Eco-Grundsätzen (einfachste Pakete zuerst):**

  1. **Paket 1 (CI Maintenance / S1):**
     - Issue: [#74](https://github.com/m00sfett/KeepADB/issues/74)
     - Ziel: In `.github/workflows/ci.yml` und `.github/workflows/release.yml` `actions/setup-java` von `@v4` auf `@v5` aktualisieren (Node-24-kompatibel / modernisierte GitHub Action) und CI-Workflow verifizieren.
     - Stufe: S1 (Direktumsetzung / Workflow-Update).
     - Gates: `git diff --check`, CI-Run nach Push.

  2. **Paket 2 (Zentraler Einstellungsbereich / S2):**
     - Issue: [#75](https://github.com/m00sfett/KeepADB/issues/75)
     - Ziel:
       - Zentralen Einstieg (Button / Menü-Icon) in `MainActivity` zu einer dedizierten `SettingsActivity` schaffen.
       - Bestehende benutzerspezifische Optionen (insbesondere "Dauerhaft aktiv halten" / Keep-Alive) in den Einstellungsbereich überführen bzw. dort konsistent anbinden.
       - Berechtigungsstatus (`WRITE_SECURE_SETTINGS`) und Hilfe sauber integrieren.
       - Ressourcen vollständig in Englisch (Standard) und Deutsch pflegen.
     - Stufe: S2 (Direktumsetzung, Android Activity & UI).
     - Gates: `git diff --check`, `gradlew assembleDebug lintDebug testDebugUnitTest`, UI-Smoke.

  3. **Paket 3 (Webhook-Konfiguration im Einstellungsbereich / S2):**
     - Issue: [#76](https://github.com/m00sfett/KeepADB/issues/76)
     - Ziel:
       - Im Einstellungsbereich aus #75 den Abschnitt für Webhook / Endpoint-Synchronisierung integrieren (Aktivierungs-Switch, URL-Eingabefeld, Validierung, Erklärung).
       - Anbindung an `KeepADBPreferences` und `KeepADBRegisterClient`.
       - Sauberes POST/DELETE-Verhalten und Einhaltung des Transportvertrags (`method: "wlan-adb"`).
       - Ressourcen in Englisch und Deutsch pflegen.
     - Stufe: S2 (Direktumsetzung, SharedPreferences & Netzwerk-Config).
     - Gates: `git diff --check`, `gradlew assembleDebug lintDebug testDebugUnitTest`, Validierungs-/Gerätenachweis.

- **Auswahl & Durchführung:**
  - **Paket 1 (Issue #74):** CI-Workflow-Aktualisierung (PR #78).
  - **Paket 2 (Issue #75):** Zentraler Einstellungsbereich (PR #79).
  - **Paket 3 (Issue #76):** Webhook-Konfiguration in Settings (PR #80).

## Umsetzung & Validierung Paket 1 (PR #78 / Issue #74) — 2026-08-21

- **Implementierung:** `actions/setup-java` in `.github/workflows/ci.yml` und `.github/workflows/release.yml` von `@v4` auf `@v5` angehoben.
- **Validierung & Gates:**
  - `git diff --check`: bestanden (0 Fehler).
  - GitHub Actions CI (Run `32476960344`): erfolgreich abgeschlossen (grün, 1m 5s).
- **Abschluss:** PR #78 per Squash-Merge in `master` übernommen; Issue #74 durch GitHub geschlossen.
- **Status:** `complete`.

## Umsetzung & Validierung Paket 2 (PR #79 / Issue #75) — 2026-08-21

- **Implementierung:**
  - `SettingsActivity.java` und `activity_settings.xml` erstellt; in `AndroidManifest.xml` registriert.
  - In `activity_main.xml` und `MainActivity.java` den Einstieg zu den Einstellungen integriert und die Hauptansicht auf den Toggle und Status fokussiert.
  - Die Option "Dauerhaft aktiv halten" (Keep-Alive) in die `SettingsActivity` verlagert und mit `KeepADBPreferences` / `KeepADBService` synchronisiert.
  - Vollständige String-Lokalisierung in Deutsch und Englisch für alle neuen Elemente gepflegt.
- **Validierung & Gates:**
  - `git diff --check`: bestanden (0 Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug lintDebug testDebugUnitTest`: bestanden (0 Fehler, 0 Warnungen).
  - Live-UI-Smoke auf Samsung Galaxy S20 FE (`SM-G780G`):
    - Einstieg über Hauptansicht erfolgreich aufgerufen.
    - Keep-Alive Schalter toggelt Service-Zustand live (`dumpsys activity services` bestätigt Foreground-Service Start/Stop).
    - Zurück-Navigation zu `MainActivity` erfolgreich.
  - GitHub Actions CI (Run `32477246318`): erfolgreich abgeschlossen (grün, 57s).
- **Abschluss:** PR #79 per Squash-Merge in `master` übernommen; Issue #75 durch GitHub geschlossen.
- **Status:** `complete`.

## Umsetzung & Validierung Paket 3 (PR #80 / Issue #76) — 2026-08-21

- **Implementierung:**
  - In `KeepADBPreferences.java` `isRegisterWebhookEnabled()`, `setRegisterWebhookEnabled()`, `isValidWebhookUrl()` und sicheres Löschen leerer URLs implementiert.
  - In `KeepADBRegisterClient.java` die Aktivierungsprüfung in `updateEndpointAsync` und `markUnavailableAsync` eingebaut sowie `unregisterAndDisableAsync()` für sauberes Abmelden beim Deaktivieren hinzugefügt.
  - In `SettingsActivity.java` und `activity_settings.xml` den Webhook-Abschnitt vollständig integriert (Aktivierungs-Switch, URL-Eingabe, Live-Validierung, Fehlermeldungsanzeige, Speichern und Löschen).
  - `app/build.gradle` um `testImplementation 'junit:junit:4.13.2'` erweitert und Unit-Tests in `KeepADBPreferencesTest.java` angelegt.
  - Vollständige String-Lokalisierung in Deutsch und Englisch gepflegt.
- **Validierung & Gates:**
  - `git diff --check`: bestanden (0 Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest lintDebug assembleDebug`: bestanden (0 Fehler, 0 Warnungen, Unit-Tests grün).
  - Live-UI-Smoke auf Samsung Galaxy S20 FE:
    - Aktivierungsversuch ohne URL zeigt verständliche Fehlermeldung und bleibt deaktiviert.
    - Ungültige URL wird abgefangen.
  - GitHub Actions CI (Run `32477682955`): erfolgreich abgeschlossen (grün, 55s).
- **Abschluss:** PR #80 per Squash-Merge in `master` übernommen; Issue #76 durch GitHub geschlossen.
- **Status:** `complete`.

## Aufwandsprotokoll (Issues #74, #75, #76)

- **Geplante / erledigte Pakete:** 3 Pakete
  - Paket 1: Issue #74 (PR #78) — CI Actions Node 24 Update
  - Paket 2: Issue #75 (PR #79) — Zentraler Einstellungsbereich
  - Paket 3: Issue #76 (PR #80) — Webhook-Konfiguration im Einstellungsbereich
- **Erledigte Issues:** 3 von 3 geschlossen (100%).
- **PRs:** PR #78, PR #79, PR #80 alle erfolgreich via Squash-Merge in `master` integriert.
- **Modell:** Gemini 3.7 Flash Medium (S2 / Direktumsetzung).
- **Build-/Lint-/Testläufe:** 4x `assembleDebug lintDebug testDebugUnitTest` erfolgreich (0 Fehler).
- **Geräte-Abnahme:** Live auf Samsung Galaxy S20 FE (`SM-G780G`).
- **Fehlversuche / Retries am Code:** 0.
- **Beobachtete Token-/Abrechnungswerte:** unbekannt.

## Retrospektive (Issues #74, #75, #76)

1. **Paketierungsreihenfolge:** Die Aufteilung nach Eco-Grundsätzen (S1 CI-Toolchain -> S2 Einstellungs-Framework -> S2 Feature-Erweiterung im Einstellungsbereich) ermöglichte atomare, übersichtliche PRs ohne Merge-Konflikte.
2. **Qualitäts-Gates:** Lokale Gates (Formatierung, Lint, Unit-Tests, Debug-Builds) in Kombination mit UI-Automation-Dumps auf dem physischen S20 FE haben sowohl die Programmlogik als auch das tatsächliche UI- und Service-Verhalten deterministisch abgesichert.
3. **Kontext- und Ressourcen-Schonung:** Alle 3 Pakete wurden in einem zusammenhängenden, disziplinierten Durchlauf direkt ohne unproduktiven Subagenten-Overhead abgeschlossen.

## Neues Paket — Multi-Language-Unterstützung & Sprachauswahl — 2026-08-21

- **Issues:**
  - [#84](https://github.com/m00sfett/KeepADB/issues/84) `feat: Multi-Language-Unterstützung für die Top 15–20 Weltsprachen implementieren`
  - [#83](https://github.com/m00sfett/KeepADB/issues/83) `feat: Sprache in den Einstellungen manuell umstellbar machen`
- **Ziel:** KeepADB um vollständige Multi-Language-Lokalisierung für die 19 wichtigsten Weltsprachen erweitern, offizielle Android 13+ Per-App Language Preferences (`res/xml/locales_config.xml` + `android:localeConfig`) und RTL-Unterstützung integrieren, sowie eine skalierbare Sprachauswahl-UI in den Einstellungen bereitstellen.
- **Sprachumfang (19 Sprachen):**
  - Englisch (`values/strings.xml`, Default), Deutsch (`values-de`), Spanisch (`values-es`), Französisch (`values-fr`), Portugiesisch (`values-pt`), Italienisch (`values-it`), Niederländisch (`values-nl`), Polnisch (`values-pl`), Ukrainisch (`values-uk`), Russisch (`values-ru`), Türkisch (`values-tr`), Arabisch (`values-ar`), Hindi (`values-hi`), Chinesisch vereinfacht (`values-zh-rCN`), Chinesisch traditionell (`values-zh-rTW`), Japanisch (`values-ja`), Koreanisch (`values-ko`), Indonesisch (`values-id`), Vietnamesisch (`values-vi`).
- **Architektur & Implementierung:**
  - `KeepADBLocaleHelper.java`: Verwaltet die unterstützten Sprach-Endonyme und steuert sowohl `LocaleManager.setApplicationLocales()` (Android 13+ / API 33+) als auch `createConfigurationContext()` / `wrapContext()` (API 30..32) sowie Persistenz in `KeepADBPreferences`.
  - `SettingsActivity.java` & `activity_settings.xml`: Ergänzung eines Sprachauswahl-Bereichs mit dynamischer Anzeige der aktiven Sprache und modalem Einzelauswahl-Dialog (Endonyme).
  - `MainActivity.java` & `SettingsActivity.java`: `attachBaseContext()` für konsistente Lokalisierung implementiert.
  - `KeepADBNotification.java` & `KeepADBWidget.java`: Lokalisierter Kontext für Benachrichtigungen, Toasts und Widgets angebunden.
  - `AndroidManifest.xml`: `android:localeConfig="@xml/locales_config"` und `android:supportsRtl="true"` registriert.
  - `KeepADBLocaleHelperTest.java`: Unit-Tests für Sprachanzahl, Tag-Matching und Endonym-Integrität angelegt.
- **Stufe:** S2 (Direktumsetzung durch Hauptagent / Gemini 3.7 Flash Medium).
- **Validierung & Gates:**
  - `git diff --check`: bestanden (0 Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest lintDebug assembleDebug`: bestanden (0 Fehler, 0 Warnungen, alle Unit-Tests grün, APK generiert).
  - GitHub Actions CI (Run `32478992036`): erfolgreich abgeschlossen (grün, 1m 4s).
- **Abschluss & Merge:**
  - PR [#85](https://github.com/m00sfett/KeepADB/pull/85) eröffnet und via Squash-Merge in `master` übernommen.
  - Issues #84 und #83 durch GitHub automatisch geschlossen (`Fixes #84`, `Fixes #83`).
  - Feature-Branch lokal und remote gelöscht.
- **Status:** `complete`.

## Aufwandsprotokoll (Issues #83, #84)

- **Geplante / erledigte Pakete:** 1 Paket (Issues #83, #84).
- **Erledigte Issues:** 2 (#83, #84).
- **PR:** PR #85 erfolgreich via Squash-Merge in `master` integriert.
- **Modell:** Gemini 3.7 Flash Medium (S2 / Direktumsetzung).
- **Build-/Lint-/Testläufe:** 2x lokal + 1x GitHub Actions CI (`testDebugUnitTest lintDebug assembleDebug`) erfolgreich (0 Fehler).
- **Fehlversuche / Retries am Code:** 0 (AAPT Apostroph-Escaping bei XML-Generierung unmittelbar sanitiziert).
- **Beobachtete Token-/Abrechnungswerte:** unbekannt.

## Retrospektive (Issues #83, #84)

1. **Paketierungsreihenfolge & Eco-Scope:** Die Zusammenführung von Issue #83 (In-App-Sprachumschalter) und Issue #84 (Multi-Language-Top-19-Sprachen) in ein einziges konsistentes Paket war optimal, da beide denselben Ressourcen-, Layout- und Helper-Pfad teilen.
2. **Qualitäts-Gates & AAPT-Validierung:** Lokale Gradle-Gates (`testDebugUnitTest`, `lintDebug`, `assembleDebug`) fingen XML-Apostroph-Formate deterministisch vor Commit/Push ab; CI auf GitHub bestätigte fehlerfreien Build in 1m 4s.
3. **Plattformintegration:** Die hybride Architektur mit `LocaleManager` (API 33+) und `createConfigurationContext` (API 30..32) stellt sicher, dass KeepADB sowohl nativ im Android-System-Menü als auch direkt in der App ohne Drittbibliotheken sofort und persistent umschaltet.

## Neue Auswahlrunde — Issues #81 & #82 — 2026-08-21

- **Ausgangslage:**
  - `master` auf Head `fa90835` (synchron mit `origin/master`).
  - Keine offenen PRs, keine unversionierten Änderungen.
  - Hinweis des Nutzers: Volle Freigabe zur Abarbeitung aller offenen Issues ohne GitHub Actions Workflows (deterministische lokale Gradle-Validierung).

- **Kandidaten (2 offene Issues):**
  - [#81](https://github.com/m00sfett/KeepADB/issues/81) `fix: Keep-Alive-Zustand nicht ungefragt zurücksetzen und Schalter auf Hauptseite bereitstellen`
  - [#82](https://github.com/m00sfett/KeepADB/issues/82) `style: Einstellungsansicht und Buttons an das CI-Designsystem anpassen`

- **Paketierung nach Eco-Grundsätzen:**
  1. **Paket 1 (Logik & Interaktion / Issue #81):**
     - Ziel: Entkopplung des manuellen WLAN-ADB-Ausschaltens vom Keep-Alive-Preferences-Zustand in `MainActivity`, `KeepADBTileService`, `KeepADBWidget` und `KeepADBService`. Einbinden des Keep-Alive-Schalters (inkl. Untertitel) in `MainActivity` und wechselseitige Synchronisation mit `SettingsActivity` und `KeepADBService`.
     - Stufe: S2 (Direktumsetzung durch Hauptagent / Gemini 3.7 Flash Medium).
     - Gates: `git diff --check`, `testDebugUnitTest`, `lintDebug`, `assembleDebug`.
  2. **Paket 2 (CI-Designsystem & Button-Styling / Issue #82):**
     - Ziel: Vereinheitlichung des Button- und Eingabefeld-Stylings nach `/vault/kontext/ci-designsystem.md` (Rot-Gelb-Dunkel-Palette, knappe Radien 4–8dp, pressed/focus feedback, Monospace für URL, saubere Panel- und Textkontraste in `MainActivity` und `SettingsActivity`).
     - Stufe: S2 (Direktumsetzung durch Hauptagent / Gemini 3.7 Flash Medium).
     - Gates: `git diff --check`, `testDebugUnitTest`, `lintDebug`, `assembleDebug`.

- **Reihenfolge:** Paket 1 (Issue #81) → Paket 2 (Issue #82).
- **Status:** `in_progress`.

## Umsetzung & Validierung Paket 1 (PR #86 / Issue #81) — 2026-08-21

- **Implementierung:**
  - Manuelles Ausschalten von Drahtlosem Debugging in `MainActivity`, `KeepADBTileService` und `KeepADBWidget` setzt `KeepADBPreferences.setKeepAliveEnabled(..., false)` nicht mehr zurück.
  - In `KeepADBService` wird bei `KeepADB.consumeUserDisabled()` die Keep-Alive-Präferenz nicht gelöscht, sondern lediglich das sofortige Wieder-Einschalten für diesen manuellen Drop übersprungen.
  - Keep-Alive-Schalter und erklärender Untertitel (`@string/keep_alive_toggle` und `@string/keep_alive_subtext`) direkt in `MainActivity` und `activity_main.xml` integriert.
  - Wechselseitige Synchronisation zwischen `MainActivity` und `SettingsActivity` sowie Sperre bei fehlender `WRITE_SECURE_SETTINGS`-Berechtigung umgesetzt.
  - Unit-Test in `KeepADBTest.java` angelegt.
- **Validierung & Gates:**
  - `git diff --check`: bestanden (0 Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest lintDebug assembleDebug`: bestanden (0 Fehler, 0 Warnungen, alle Unit-Tests grün).
- **Abschluss:** PR #86 eröffnet und via Squash-Merge in `master` übernommen; Issue #81 durch GitHub geschlossen.
- **Status:** `complete`.

## Umsetzung & Validierung Paket 2 (Issue #82) — 2026-08-21

- **Implementierung:**
  - Button- und Interaktionselemente gemäß CI-Designsystem (`/vault/kontext/ci-designsystem.md`) mit klaren Pressed-/Focus-Zuständen und knappen Radien (4–8dp) ausgestattet:
    - `bg_btn_primary.xml`: Bannerrot `#B11218` / Primary Red `#D01822`, Rahmen `#6E1516` / `#FFE15A`, Radius 8dp (`radius_control`).
    - `bg_btn_secondary.xml`: Panel Strong `#1F1712`, Rahmen `#6E1516` / `#E23A2E`, Radius 8dp (`radius_control`).
    - `bg_btn_header.xml`: Transparent / Primary Red `#D01822`, Rahmen `#FFD21F`, Radius 4dp (`radius_small`).
    - `bg_input.xml`: Panel Strong `#1F1712`, Rahmen `#6E1516` / `#FFD21F` (Fokus-Highlight), Radius 8dp (`radius_control`).
    - `bg_card_clickable.xml`: Panel Strong `#1F1712`, Rahmen `#6E1516` / `#E23A2E` (Pressed) / `#FFD21F` (Fokus), Radius 8dp (`radius_control`).
  - Button-Typografie und Paddings vereinheitlicht (`Fira Sans Condensed` bold, minHeight 40dp bzw. 36dp im Header).
  - Einstellungs-Layout (`activity_settings.xml`) optimiert:
    - Webhook-Eingabefeld mit Monospace-Typografie, Padding (12dp), `@color/night_text`, `@color/night_muted` Hint und sauberem Kontrast.
    - Sektionskarten, Schalter und Trenner an CI-Farbpalette angepasst.
  - Theme-Attribute in `themes.xml` (`colorControlActivated` / `colorControlHighlight`) konsolidiert.
- **Validierung & Gates:**
  - `git diff --check`: bestanden (0 Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest lintDebug assembleDebug`: bestanden (0 Fehler, 0 Warnungen, Unit-Tests grün, Debug-APK gebaut).
- **Abschluss:** PR #87 eröffnet und via Squash-Merge in `master` übernommen; Issue #82 durch GitHub geschlossen.
- **Status:** `complete`.

## Aufwandsprotokoll (Issues #81, #82)

- **Geplante / erledigte Pakete:** 2 Pakete
  - Paket 1: Issue #81 (PR #86) — Keep-Alive Entkopplung & Hauptseiten-Schalter
  - Paket 2: Issue #82 (PR #87) — CI-Designsystem für Buttons, Inputs & Settings
- **Erledigte Issues:** 2 von 2 geschlossen (100%).
- **PRs:** PR #86, PR #87 beide erfolgreich via Squash-Merge in `master` integriert.
- **Modell:** Gemini 3.7 Flash Medium (S2 / Direktumsetzung).
- **Build-/Lint-/Testläufe:** 4x lokal (`testDebugUnitTest lintDebug assembleDebug`) erfolgreich (0 Fehler).
- **Fehlversuche / Retries am Code:** 0.
- **Beobachtete Token-/Abrechnungswerte:** unbekannt.

## Retrospektive (Issues #81, #82)

1. **Paketierungsreihenfolge & Isolation:** Die Aufteilung in Paket 1 (Interaktion/Logik-Entkopplung) und Paket 2 (visuelles CI-Designsystem & Drawables) hielt die PRs sauber getrennt, leicht nachvollziehbar und konfliktfrei.
2. **Qualitäts-Gates:** Lokale Gradle-Gates (`testDebugUnitTest`, `lintDebug`, `assembleDebug`) stellten eine schnelle (0.5–2s) und deterministische Verifikation ohne langsame externe CI-Pipelines sicher.
3. **CI-Designsystem-Treue:** Durch die strikte Einhaltung der Vorgaben aus `/vault/kontext/ci-designsystem.md` (knappe Radien 4–8dp, Monospace für technische Daten/URLs, Rot-Gelb-Dunkel Hierarchie und klare Pressed/Focus-Zustände) besitzt die App ein konsistentes, natives und barrierefreies Erscheinungsbild.

## Optimierung: High-Speed NIO Endpoint Discovery — 2026-08-21

- **Problem & Ursachenanalyse:**
  - Nach dem Aktivieren von Drahtlosem Debugging dauerte die Endpunkterkennung in der App rund 1–2 Minuten.
  - Ursache 1: `startFastProbe` erzeugte pro Versuch 16 neue Threads und durchlief 15 Versuche in ~10 Sekunden, bevor `adbd` seinen Port fertig gebunden hatte.
  - Ursache 2: Nach Ablauf der 15 Versuche rief `startFastProbe` vorzeitig `stop()` auf und stoppte damit auch die parallele `NsdManager`-Discovery.
  - Ursache 3: `KeepADBNotification` startete daraufhin alle 2–5 Sekunden einen neuen Discovery-Zyklus, was den Android `NsdManager`-Dienst in eine Drosselungs-/Fehlerschleife brachte.
- **Implementierung:**
  - `KeepADBEndpoint.java`:
    - Umstellung auf batched NIO `SocketChannel` + `Selector` (128 Ports pro Batch) für den Scan über 30000–50000 auf `127.0.0.1`.
    - Vollständiger Portscan aller 20.001 Ports schließt in unter 200 ms ab ohne File-Descriptor-Erschöpfung.
    - Kontinuierliche Abtastung im Hintergrund (alle 300 ms) ohne vorzeitigen `stop()`-Aufruf auf `NsdManager`.
    - Atomare Endpunkt-Auslieferung (`endpointDelivered`) verhindert Doppel-Trigger zwischen FastProbe und mDNS.
  - `KeepADBEndpointTest.java`:
    - Unit-Test zur Verifikation der Port-Erkennung via `ServerSocket` und Scanner-Logik angelegt.
- **Validierung & Live-Nachweis:**
  - `git diff --check`: bestanden (0 Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest lintDebug assembleDebug`: bestanden (0 Fehler, 0 Warnungen).
  - Live-Test auf Samsung Galaxy S20 FE (`SM-G780G`):
    - App-Start um `14:16:04.565`, Endpunkt `192.168.178.24:45577` erkannt und um `14:16:04.735` an Tailscale-Register gemeldet (**Erkennungsdauer: 170 Millisekunden**).
- **Status:** `complete`.

## Bereinigung: Doppelte Keep-Alive Option aus Einstellungen entfernt & Release-Doku aktualisiert — 2026-08-21

- **Ziel:**
  - Die redundante Option „Dauerhaft aktiv halten“ aus `SettingsActivity` und `activity_settings.xml` entfernen, sodass Keep-Alive ausschließlich auf der Hauptseite (`MainActivity`) angezeigt und geschaltet wird.
  - `README.md` und `CHANGELOG.md` auf den aktuellen v1.0.0-Stand bringen (Multi-Language Top 19, Settings-Activity, High-Speed NIO Fast-Probe < 200 ms, Terminal+Wi-Fi Adaptive Icon).
- **Implementierung:**
  - `activity_settings.xml`: General / Keep-Alive Section entfernt.
  - `SettingsActivity.java`: `keepAliveToggle`-Referenzen, Listener und `showPermissionErrorToast` bereinigt.
  - `README.md`: Features und Usage um 19 Sprachen, High-Speed NIO Scanner, Settings-Bereich und adaptives Icon erweitert.
  - `CHANGELOG.md`: [1.0.0]-Eintrag konsolidiert und finalisiert.
- **Validierung & Gates:**
  - `git diff --check`: bestanden (0 Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest lintDebug assembleDebug`: bestanden (0 Fehler, 0 Warnungen).
- **Status:** `complete`.

## Optimierung & Bereinigung: Fira Sans TTF-Schriftarten entfernt & System-Typography aktiviert — 2026-08-21

- **Problem & Analyse:**
  - Die vier eingebetteten Fira Sans TTF-Dateien (`fira_sans_regular.ttf`, `fira_sans_bold.ttf`, `fira_sans_condensed_regular.ttf`, `fira_sans_condensed_bold.ttf`) machten über 90 % der gesamten APK-Größe aus (~2,0 MB unkomprimiert / ~900 KB komprimiert).
- **Umsetzung:**
  - `app/src/main/res/font/`: Alle TTF-Dateien und Font-XMLs gelöscht.
  - `third_party/`: Verzeichnis und `third_party/fonts/OFL.txt` gelöscht.
  - `themes.xml`, `activity_main.xml`, `activity_settings.xml`, `widget_keepadb.xml`: Auf native Android-Systemschriften (`sans-serif`, `sans-serif-condensed`) umgestellt.
  - `README.md` & `CHANGELOG.md`: Drittanbieter-Lizenzhinweise für Schriften entfernt und APK-Größe auf echte `< 350 KB` aktualisiert.
- **Validierung & Gates:**
  - `git diff --check`: bestanden (0 Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest lintDebug assembleDebug`: bestanden (0 Fehler, 0 Warnungen, APK-Größe von 1,2 MB auf 304 KB geschrumpft).
- **Status:** `complete`.

## Issue-110-Fix & kombinierte Verifikation — 2026-08-21

- Freigabe: Fix für #110 umsetzen, lokale Gates, Commit/PR, Gerätetest — erteilt.
- Umsetzung: `KeepADBEndpoint.scanLocalOpenPorts` nutzt jetzt den bereits vorhandenen
  timeout-begrenzten `isPortReachable(InetAddress, int, int)`-Helper (25 ms) statt eines
  zweiten, unbegrenzten `Socket.connect()`. Minimaler, diff-freundlicher Fix statt Rückkehr zur
  vorherigen NIO-Selector-Implementierung.
- Lokale Gates: `git diff --check`, `gradlew testDebugUnitTest assembleDebug` bestanden.
  `lintDebug` weiterhin durch #108 blockiert (unverändert, unabhängig).
- Commit `4be3679` auf Branch `fix/110-discovery-blocking-connect`, PR
  [#111](https://github.com/m00sfett/KeepADB/pull/111) eröffnet (`Fixes #110`).
- Geräteverifikation auf S20 (`SM-G780G`/`RF8T307S88H`, `192.168.178.24:45699`):
  - Nach Fix: erste `FastProbe`-Iteration schließt in ~110 ms ab (vorher: nie abgeschlossen).
  - Notification zeigt korrekt `Drahtloses Debugging: Port 45699 @ 192.168.178.24`.
  - Register-Update erfolgreich (`HTTP 200`).
- Kombinierte Verifikation (#106 + #110 zusammen): #106-Branch lokal **nur für den Testlauf**
  in den #110-Branch gemergt (Commit `f315e83`, **nie gepusht**), APK gebaut und installiert.
  Happy-Path bestätigt: Beim erneuten `onResume`/`refresh()` mit weiterhin gültigem Endpoint
  loggt `verifyCachedEndpointAsync` keine Invalidierung, UI/Notification bleiben stabil und
  korrekt — keine Regression durch den neuen Reachability-Check.
  Danach `git reset --hard 4be3679`, um den Testmerge folgenlos zu verwerfen; Branch entspricht
  wieder exakt dem gepushten Einzelfix für #110.
- **Nicht abgedeckt:** Der eigentliche Negativ-Pfad von #106 (adbd rotiert den Port, ohne dass
  `adb_wifi_enabled` sich ändert) ließ sich nicht sicher provozieren — jeder verfügbare Weg, den
  Port extern zu ändern, läuft über denselben WLAN-ADB-Kanal, über den der Test selbst verbunden
  ist, und hätte die eigene Steuerverbindung gekappt. Der Fix beruht auf Code-Review der Logik
  (klar, synchronisiert, superseded-Check vorhanden) plus bestandenem Happy-Path-Nachweis.
- Status: `approved` für #110 (Code + Gates + Live-Nachweis vollständig). `approved` mit einer
  dokumentierten Einschränkung für #106 (Happy-Path live bestätigt, Negativ-Pfad nicht live
  provozierbar) — PR #109 bleibt offen, kein Issue-Close ohne Nutzerentscheidung zur Einschränkung.
## Issue-106-Implementierung & Gerätetest-Blocker — 2026-08-21

- Vorgefundenes WIP: `KeepADBEndpoint`/`KeepADBNotification`/`KeepADBPreferences`/`KeepADBService`
  unversioniert im Arbeitsbaum, ohne Planeintrag. Nutzerentscheidung: beides behalten — sowohl
  den eigentlichen Reachability-Fix als auch die Heartbeat-/Lifecycle-Diagnose-Instrumentierung.
- Umsetzung (Issue #106 „Notification bleibt stehen, wenn Drahtloses Debugging ausgeschaltet
  wird"): `KeepADBEndpoint.isPortReachable(host, port, timeoutMs)` (package-visible TCP-Check) +
  `KeepADBNotification.verifyCachedEndpointAsync(...)` — bei jedem `refresh()` wird der gecachte
  Endpoint asynchron re-verifiziert; ist er nicht mehr erreichbar (adbd rotiert den Port ohne
  `adb_wifi_enabled`-Änderung), wird er invalidiert und Rediscovery gestartet. Zusätzlich
  Heartbeat-Timestamp (`KeepADBPreferences`) und Lifecycle-Logging in `KeepADBService`
  (`onCreate`/`onStartCommand`/`onDestroy`/`onTaskRemoved`/`onTrimMemory`) zur künftigen
  Kill-Diagnose.
- Lokale Gates: `git diff --check` bestanden; `gradlew testDebugUnitTest assembleDebug`
  bestanden. `gradlew lintDebug` schlägt fehl — verifiziert per `git stash` als Vorbestandsfehler
  auf reinem `master` (`90a206d`, PR #105: fehlende Übersetzungen für `webhook_status_title` in
  16 Sprachen), unabhängig vom eigenen Fix. Dafür eigenes Issue
  [#108](https://github.com/m00sfett/KeepADB/issues/108) angelegt statt mitzureparieren.
- Commit `25ea79d` auf Branch `fix/106-stale-endpoint-reverify`, PR
  [#109](https://github.com/m00sfett/KeepADB/pull/109) eröffnet (`Fixes #106`).
- Gerätetest auf S20: Register lieferte zuletzt `usb-adb`, das war nicht erreichbar; WLAN-Port
  wurde nach Nutzerhinweis reaktiviert und auf `192.168.178.24:45699` per
  `phone-register scan-wlan s20` fingerprint-validiert (`SM-G780G`/`RF8T307S88H`), Register
  aktualisiert. Doppelte veraltete mDNS-Transporte auf ADB 5038 getrennt, `android-target s20`
  danach eindeutig. APK aus dem PR-Branch per `install -r` installiert.
- **Neuer Befund während des Tests, außerhalb des #106-Scopes:** Die App-eigene
  Endpoint-Discovery blieb nach `MainActivity`-Start dauerhaft bei „Endpoint wird gesucht …"
  hängen (>3,5 Minuten, kein Fortschritt, kein Timeout-Log trotz im Code auf 45 s gesetztem
  `maxTotalSeconds`). Ursache identifiziert:
  `KeepADBEndpoint.scanLocalOpenPorts(int, int, long)` nutzt seit PR #92 (`f404d8e`) einen
  **blockierenden `Socket.connect()` ohne Timeout**, sequenziell über bis zu 2500 Ports pro
  Worker (8 Worker, Bereich 30000–50000) — eine Regression gegenüber der vorherigen NIO
  `Selector`/`SocketChannel`-Batch-Implementierung (PR #88–#90, dokumentiert mit <200 ms
  Vollscan). Trotz gegenteiliger Commit-Message in PR #92 ist der Connect nicht non-blocking.
  Eigenes Issue [#110](https://github.com/m00sfett/KeepADB/issues/110) angelegt; keine
  Reparatur in diesem Paket, Nutzerentscheidung: Issue anlegen, #106-Test separat nachholen.
- **Folge:** Der freigegebene Live-Nachweis für den #106-Fix (gecachter Endpoint wird stale,
  Notification aktualisiert sich) konnte nicht erbracht werden, weil die Discovery wegen #110
  nie einen ersten Endpoint liefert — der zu prüfende Codepfad (`currentHost != null`-Zweig in
  `refresh()`) wird dadurch nie erreicht. App-Prozess sauber per `force-stop` beendet.
- Status: `blocked` für den Live-Nachweis von #106, verursacht durch die unabhängige Regression
  #110. Code, lokale Gates und PR #109 bleiben unverändert gültig; kein `Fixes #106` schließen,
  solange der Gerätenachweis fehlt.

## Übergabe-Checkpoint — 2026-08-21

- Server-Stand (abgefragt): offene Issues #106 (PR #109 referenziert, ungetestet),
  #107 (Icon-Design, unberührt), #108 (Lint-Regression, neu), #110 (Discovery-Regression, neu).
  PR #109 offen, keine aktiven CI-Runs (kein Workflow im Repo).
- Uncommitted/unclean: keine — Arbeitsbaum ist nach dem Commit `25ea79d` und `force-stop` sauber
  auf Branch `fix/106-stale-endpoint-reverify`.
- Nächste sinnvolle Schritte (nicht automatisch fortgesetzt, da Architektur-/Priorisierungsfrage):
  1. Issue #110 beheben (Timeout pro Connect-Versuch oder Rückkehr zur NIO-Selector-Variante),
     danach #106-Live-Nachweis nachholen und PR #109 mergen.
  2. Issue #108 (fehlende Übersetzungen) unabhängig beheben.
  3. Issue #107 (Icon-Design) unabhängig einplanen.
- Strangzähler: Dieser Lauf kam aus dem Strang „vorgefundenes WIP zu #106 abschließen" und hat
  kein nutzersichtbares Ergebnis erzeugt (PR offen, aber Live-Nachweis blockiert). Stattdessen
  wurde ein signifikanter, bislang unbekannter Performance-Regressionsfund (#110) produziert.

## Issue-106-Root-Cause-Fix & vollständige Live-Verifikation — 2026-08-21

- Nutzerhinweis: Zugriff auf das S20 zusätzlich per USB über SSH-Host `mooslap2023-ts`
  (unabhängig vom bisherigen WLAN-ADB-Testkanal). Register aktualisiert
  (`phone-register record s20 --method usb-adb --endpoint RF8T307S88H --ssh-target
  mooslap2023-ts --verified-fingerprint samsung/SM-G780G/r8q`).
- PR #111 (Issue #110) gemerged (Squash), Issue #110 automatisch geschlossen, Branch
  aufgeräumt.
- Für den #106-Negativpfad-Test zunächst versucht, den adbd-Port durch einen Wi-Fi-Reconnect
  (`svc wifi disable/enable`) rotieren zu lassen — dabei entdeckt, dass Android
  `adb_wifi_enabled` beim Deaktivieren von Wi-Fi automatisch auf `0` setzt; das trifft nicht
  den ursprünglich gemeldeten Fall, sondern den regulären Toggle-Pfad.
- **Root-Cause-Fund (live, per USB-Session bestätigt):** `MainActivity.onResume()` ruft nie
  `KeepADBService.sync(this)` auf — nur der `OnClickListener` des Keep-Alive-Schalters. App per
  `force-stop` beendet (Service stirbt), Keep-Alive-Präferenz blieb `true` (`checked="true"` im
  UI-Dump), aber `dumpsys activity services` zeigte „nothing" nach Wiederöffnen der App vor dem
  Fix — exakt das in #106 beschriebene Symptom.
- Fix: `KeepADBService.sync(this)` zusätzlich in `onResume()` ergänzt (Commit `2ad065d` auf
  `fix/106-stale-endpoint-reverify`). Lokale Gates (`git diff --check`,
  `gradlew testDebugUnitTest assembleDebug`) bestanden.
- **Vollständige End-to-End-Live-Verifikation** (kombinierte APK aus #109 + #110, WLAN-Kanal
  für Beobachtung, unabhängiger USB-Kanal über `mooslap2023-ts` für die externe Aktion):
  1. Service per `force-stop` getötet, Keep-Alive blieb aktiv; App neu geöffnet →
     `onCreate: service (re)started, 20889ms since last heartbeat` bestätigt sofortigen
     Neustart (Heartbeat-Diagnose aus dem WIP bewährt sich hier direkt).
  2. App in den Hintergrund gelegt (nicht beendet); `adb_wifi_enabled` über die unabhängige
     USB-Session extern auf `0` gesetzt.
  3. `ContentObserver` im laufenden Service reagierte sofort; Keep-Alive reaktivierte Wireless
     Debugging automatisch (neuer Port `41499` statt `43947`).
  4. `verifyCachedEndpointAsync` erkannte den alten Port als nicht mehr erreichbar
     (`Cached endpoint 192.168.178.24:43947 no longer reachable; invalidating and
     rediscovering`), Rediscovery lief an.
  5. Notification zeigte binnen ~2s korrekt `Drahtloses Debugging: Port 41499 @
     192.168.178.24`; Register-Update per `HTTP 200` bestätigt.
  Lokaler Testmerge (#106+#110 kombiniert) wurde nach der Verifikation wieder verworfen
  (`git reset --hard`), kein Einfluss auf die getrennten PR-Branches.
- Status: `approved` für #106 — Root Cause behoben, Symptom-Fix und End-to-End-Pfad live
  vollständig bestätigt. Kommentar mit Nachweis auf PR #109 gepostet.

## Neue Auswahlrunde — Issues #112, #113, #114 — 2026-08-21

- Ausgangslage (abgefragt): `master`/`origin/master` synchron auf `2e1f93d`, Arbeitsbaum sauber,
  keine offenen PRs, keine aktiven CI-Runs. #106/#108/#109/#110/#111 sind bereits gemergt
  (nicht mehr in der offenen Liste).
- Offene Issues (3):
  - [#112](https://github.com/m00sfett/KeepADB/issues/112) Notification bleibt mit altem Port
    stehen, wenn Drahtlos-Debugging bei aktivem Keep-Alive ausgeschaltet wird
  - [#113](https://github.com/m00sfett/KeepADB/issues/113) Notification wird beim Beenden des
    KeepADBService nicht aus dem Drawer entfernt (`STOP_FOREGROUND_DETACH`)
  - [#114](https://github.com/m00sfett/KeepADB/issues/114) Rapid-Toggle führt zu adbd-Hänger und
    fehlgeschlagener Port-Discovery
- Paketierung:
  - **Paket A (#112 + #113):** gemeinsamer Notification-/Foreground-Service-Lifecycle-Codepfad
    (`KeepADBService.onDestroy()`, `KeepADBNotification.stop()/refresh()`). Ziel: Notification
    verschwindet zuverlässig aus dem Drawer beim Service-Stop (`STOP_FOREGROUND_REMOVE`), und
    zeigt bei aktivem Keep-Alive nach manuellem AUS den Service-/Monitoring-Zustand statt
    stillschweigend zu canceln oder den alten Port zu behalten. Stufe S2, Direktumsetzung.
  - **Paket B (#114):** eigenständiger Discovery-/adbd-Timing-Fix (Re-Trigger-Heuristik bei
    hängendem `adb_wifi_enabled=1` ohne offenen Port). Eigenes Risiko/Rollback, kein gemeinsamer
    Codepfad mit A. Stufe S2/S3, Direktumsetzung.
- Nicht-Ziele: keine Änderung an Register-/Webhook-Vertrag, keine Sprach-/Icon-Arbeit, kein
  Emulator (Qt/XCB weiterhin blockiert) — S20-Fallback über registrierten Transport.
- Freigaben: Implementierung, lokale Gradle-Gates (`assembleDebug`, `lintDebug`,
  `testDebugUnitTest`) und S20-Gerätetest über den registrierten Transport für beide Pakete
  erteilt. PR/Merge nach grünem CI im Rahmen des Auto-Finish-Vertrags.
- Reihenfolge: Paket A zuerst, danach Paket B.
- Status: `in_progress`.

## Abschluss Paket A (#112 + #113) — 2026-08-21

- Umsetzung:
  - `KeepADBNotification.stop()`: cancelt die Notification nur noch, wenn Keep-Alive deaktiviert
    ist. Bei aktivem Keep-Alive wird sie stattdessen auf den Service-Monitoring-Platzhalter
    aktualisiert, da `NotificationManagerService` `cancel()` bei laufendem Foreground-Service
    stillschweigend ignoriert (Ursache für den stehenbleibenden alten Port aus #112).
  - `KeepADBService.onDestroy()`: `STOP_FOREGROUND_DETACH` → `STOP_FOREGROUND_REMOVE`, damit die
    Notification beim Service-Stopp tatsächlich aus dem Drawer verschwindet (#113).
- Lokale Gates: `git diff --check`, `gradlew testDebugUnitTest lintDebug assembleDebug`
  erfolgreich (0 Fehler).
- CI-Befund (außerhalb des Pakets, unverändert übernommen): `.github/workflows/ci.yml` triggert
  seit einer vorbestehenden Änderung nur noch per `workflow_dispatch`, nicht mehr bei
  `pull_request`/`push`. Für den PR-Nachweis wurde der Dispatch manuell auf dem Feature-Branch
  ausgelöst (Run `32500902581`, grün) statt die Workflow-Trigger selbst zu ändern.
- Gerätegate auf S20 (`SM-G780G`/`RF8T307S88H`):
  - Registrierter WLAN-Transport war zunächst nicht erreichbar; nach Nutzerhinweis auf neuen Port
    `42927` fingerprint-validiert und installiert.
  - **#112-Nachweis:** Da Keep-Alive `adb_wifi_enabled` bei WLAN-Verbindung sofort wieder
    einschaltet, ließ sich der Zwischenzustand nicht über den WLAN-Kanal selbst erzeugen (Race).
    Stattdessen wurde der unabhängige USB-Kanal über SSH-Host `mooslap2023-ts`
    (`adb -s RF8T307S88H`) verwendet, um `adb_wifi_enabled=0` zu setzen, ohne Wi-Fi zu trennen
    (Keep-Alive reagiert nur auf Wi-Fi-Verlust, nicht auf den reinen Settings-Wert, solange kein
    Wi-Fi-Drop vorliegt). `dumpsys notification --noredact` zeigt danach
    `android.title=String (Drahtloses Debugging aktiv halten)` — kein alter Port, Notification
    bleibt bestehen.
  - **#113-Nachweis:** Keep-Alive-Schalter in `MainActivity` per UI-Tap deaktiviert;
    `dumpsys activity services` zeigt keinen `ServiceRecord` mehr, `dumpsys notification` listet
    keinen `key : 0|de.hohnepeople.keepadb|1|...` mehr — Notification vollständig entfernt.
  - Nach dem Test `adb_wifi_enabled` wieder auf `1` gesetzt und App per `force-stop` beendet;
    Register auf den zwischenzeitlich aktiven Port `192.168.178.24:33033` aktualisiert.
- PR #115 (`fix: keep foreground notification alive during keep-alive, remove it on service
  stop`) eröffnet, CI-Dispatch grün, per Squash-Merge in `master` übernommen
  (`Fixes #112`, `Fixes #113`). Branch lokal und remote aufgeräumt.
- Status: `complete`.

## Übergabe-Checkpoint nach Paket A — 2026-08-21

- Server-Stand (abgefragt): 1 offenes Issue — [#114](https://github.com/m00sfett/KeepADB/issues/114)
  (Rapid-Toggle-adbd-Hänger). Keine offenen PRs, keine aktiven Runs.
- Arbeitsbaum: sauber auf `master`, synchron mit `origin/master`.
- Nächstes Paket: Paket B (#114), Freigabe (Implementierung, lokale Gates, S20-Gerätetest) liegt
  bereits aus der Auswahlrunde vor.

## Nutzerfolgeauftrag — Root-Cause-Fix statt reiner Symptombehandlung — 2026-08-21

- Nutzerfeedback nach dem gemergten #114-Fix: „können wir dieses Verhalten nicht direkt fixen“ —
  Wunsch nach proaktivem Debounce statt nur reaktivem Recovery-Pulse.
- Umsetzung 1 — Debounce in `KeepADB.setEnabled()`: Jeder tatsächliche
  `adb_wifi_enabled`-Schreibvorgang wird um 1,5s debounced; Mehrfachklicks in der Cooldown-Phase
  werden zu einem einzigen verzögerten Schreibvorgang zusammengefasst. Läuft für alle drei
  Oberflächen (App, Tile, Widget) sowie den Keep-Alive-Autoreconnect-Pfad zentral über diese eine
  Methode.
- **Selbstgefundener Regressionsfund vor Merge:** `lastAppliedChangeMs` initial auf
  `Long.MIN_VALUE` führte zu einem Overflow bei der ersten Berechnung, wodurch der allererste
  Toggle nach Prozessstart mit einer astronomisch großen (nie feuernden) Verzögerung eingeplant
  wurde — das Gerät blieb dauerhaft auf jeden Tap unempfindlich. Live auf dem S20 reproduziert
  und durch Initialisierung auf `0` behoben (`SystemClock.elapsedRealtime()` ist immer ≥ 0).
- Lokale Gates (mehrfach über die Iterationen): `git diff --check`,
  `gradlew testDebugUnitTest lintDebug assembleDebug` erfolgreich.
- **Live-Verifikation auf S20** (`SM-G780G`/`RF8T307S88H`, Transport zwischenzeitlich mehrfach
  über `phone-register scan-wlan s20` neu aufgelöst, zusätzlich unabhängiger USB-Kanal über
  `mooslap2023-ts` für Testtrigger, während der WLAN-Kanal beobachtete): 3x Rapid-Tap-Burst
  korrekt zu einem einzigen verzögerten Schreibvorgang zusammengefasst (Log bestätigt
  Timing exakt bei 1,5s nach letzter echter Änderung).
- Commit `595c30a` + Fix-Commit `28ab284` (Overflow-Korrektur) auf Branch
  `fix/114-debounce-toggle-cooldown`.

## Nutzerfolgeauftrag 2 — Endpoint-Discovery-Latenz — 2026-08-21

- Nutzerbeobachtung während der Live-Verifikation: „Endpoint wird gesucht …“ blieb 24-55s
  hängen statt der dokumentierten „wenige hundert Millisekunden“.
- **Root-Cause-Diagnose (mit Timing-Instrumentierung live gemessen):** Das reine Öffnen von 2501
  `SocketChannel`s (ohne jedes Warten auf eine Antwort!) dauerte **11,58 Sekunden** — ein realer,
  gemessener Android-Framework-Overhead von ~4,6ms pro Socket-Open+Connect-Aufruf, unabhängig
  von der Anzahl paralleler Worker-Threads (kein Kontentions-, sondern ein reiner Per-Call-Kosten-
  Effekt). Zusätzlich wurde adbd zeitweise ausschließlich auf IPv6-Loopback (`::1`) gebunden
  gefunden (`netstat`/`nc`-Nachweis), was der bisherige reine IPv4-Scan nie gefunden hätte,
  unabhängig von der Geschwindigkeit.
- **Nutzerentscheidung** (nach Vorlage von drei Optionen): mDNS wird primärer, durchgehend
  laufender Discovery-Pfad; der Brute-Force-Port-Scan wird auf einen knapp befristeten (300ms je
  Adressfamilie), einmaligen Opportunismus-Versuch reduziert statt einer wiederholten 45s-Schleife.
- Umsetzung: `KeepADBEndpoint` grundlegend umgebaut (siehe Commit-Message für Details); die
  redundante, IPv4-only nachgeschaltete Loopback-Reverifikation entfernt (der Batch-Scan bestätigt
  Erreichbarkeit bereits selbst über echtes `finishConnect()`); der #114-Recovery-Pulse jetzt mit
  statischem 20s-Cooldown statt Instanz-Flag.
- **Zweiter, während dieser Arbeit selbst gefundener und behobener Regressionsfund:** Der
  Recovery-Pulse löste über `KeepADBNotification`s Teardown/Recreate-Zyklus (bei jedem
  `adb_wifi_enabled`-Wechsel wird die `KeepADBEndpoint`-Instanz neu erzeugt) eine Endlosschleife
  aus — die instanzgebundene „schon gepulst"-Flag wurde bei jeder Neuerzeugung zurückgesetzt,
  wodurch der Pulse alle ~6,5s erneut feuerte und mDNS nie eine reale Chance zur Auflösung gab.
  Live auf dem S20 beobachtet (wiederholte Pulse-Log-Zeilen im 6,5s-Takt über 40+ Sekunden) und
  durch statisches, klassenweites Cooldown-Feld (20s) behoben.
- **Live-Verifikation nach dem Fix** (S20, `SM-G780G`/`RF8T307S88H`): Einzel-Toggle findet
  Endpoint via mDNS in ~1,2-1,5s; 3x-Rapid-Tap-Burst findet Endpoint in ~1,2s nach der debounced
  Anwendung, kein Recovery-Pulse nötig; Register/Webhook-Update erfolgt jeweils prompt (HTTP 200)
  passend zum gefundenen Endpoint. #112/#113-Notification-Verhalten nach dem Umbau erneut
  stichprobenartig bestätigt (Notification zeigt korrekt Port/IP).
- Commit `f8f72ac` auf demselben Branch.

## Nutzeranweisung — keine GitHub-Actions-Läufe mehr während dieser Arbeit — 2026-08-21

- Bereits vor diesem Paket als Regel dokumentiert (siehe oben, `AGENTS.md`-Abschnitt „CI (ab
  2026-08-21: keine GitHub-Actions-Läufe mehr)"). PR #117 wurde entsprechend ohne
  `workflow_dispatch`-Auslösung eröffnet und nach lokalen Gates + Gerätetest gemergt.

## Abschluss — 2026-08-21

- PR #117 (`fix: prevent rapid-toggle adbd races and fix endpoint discovery latency`) eröffnet,
  ohne CI-Lauf (Policy), per Squash-Merge in `master` übernommen. Branch aufgeräumt.
- Server-Stand (abgefragt): keine offenen Issues, keine offenen PRs, `master` synchron mit
  `origin/master` auf `bed50ca`.
- Status: `complete`.

## Retrospektive — Debounce & Discovery-Root-Cause

1. **Direkte Nutzerrückfrage statt Symptombehandlung:** Der Nutzer hat zu Recht nachgehakt,
   nachdem ich mich mit dem reaktiven Recovery-Pulse zufriedengegeben hatte — der proaktive
   Debounce-Fix an der Quelle ist die deutlich robustere Lösung und hätte von Anfang an der
   naheliegendere Ansatz sein sollen.
2. **Live-Messung statt Annahme:** Die ursprüngliche Doku „<200ms Scan" stammte aus einem
   früheren, einmaligen Testlauf und wurde nie als generelle Garantie hinterfragt. Erst die
   Zeitstempel-Instrumentierung direkt im Log deckte den echten ~4,6ms/Socket-Overhead auf; ohne
   diese Messung wäre die Fehlsuche bei der reinen Parallelisierung stehengeblieben (die keine
   Verbesserung brachte, weil der Engpass nicht kontentionsbedingt war).
3. **Zwei selbstgefundene Regressionen vor dem Merge:** Beide (Overflow-Bug, Pulse-Endlosschleife)
   wurden durch eigene, unabgefragte Geräte-Verifikation entdeckt und noch im selben Durchlauf
   repariert — ohne diese Disziplin wären beide erst beim Nutzer aufgefallen, der zweite davon
   als scheinbar zufälliges „hängt manchmal trotzdem".
4. **IPv6-Bindung als Umgebungsvariable:** adbd bindet nicht zuverlässig dual-stack; ein Scan,
   der nur eine Adressfamilie prüft, ist strukturell unvollständig, unabhängig von der
   Performance. Für künftige Netzwerk-Discovery-Arbeit an diesem Projekt: immer beide
   Adressfamilien in Betracht ziehen.
5. **Verbesserung für nächstes Mal:** Bei dokumentierten Performance-Annahmen („dauert <200ms")
   in der Codebasis ein Verfallsdatum/Kontext vermerken (welches Gerät, welcher Zustand), damit
   spätere Abweichungen schneller als reale Regression statt als Umgebungsrauschen erkannt werden.

## Nutzerfolgeauftrag 3 — README-Korrektur & zwei neue Webhook-Issues — 2026-08-21

- Nutzerauftrag: README-Performance-Angabe auf die real gemessene Latenz (~1-2s über mDNS)
  korrigieren statt „<200ms" zu versprechen; außerdem zwei Beobachtungen als Issues erfassen.
- Umsetzung: `README.md` an zwei Stellen angepasst („Live Endpoint Resolution" und „High-Speed
  Endpoint Discovery" → „Endpoint Discovery", jeweils mit „typically within 1-2 seconds" statt
  „under 200 milliseconds"). `CHANGELOG.md` bewusst unverändert gelassen (historische
  Release-Einträge, keine rückwirkende Fälschung vergangener Versionsbeschreibungen).
- Neue Issues angelegt (reine Erfassung, keine Implementierung in diesem Lauf):
  - [#118](https://github.com/m00sfett/KeepADB/issues/118) Webhook aktualisiert Endpoint nicht
    zuverlässig bei neuer WLAN-ADB-Verbindung — unklar ob Anzeige- oder eigentlicher Hook-Bug;
    Code-Hinweise (kein Re-Trigger bei nachträglicher Webhook-Aktivierung mit bereits gecachtem
    `currentHost`; `deleteEndpoint()`-Pfad setzt `WebhookLastReportedEndpoint` nicht zurück) im
    Issue-Text als Ausgangspunkt dokumentiert.
  - [#119](https://github.com/m00sfett/KeepADB/issues/119) Gerät soll beim Ausschalten von
    Wireless Debugging zuverlässig deregistriert werden — `markUnavailableAsync()` ruft zwar
    bereits `deleteEndpoint()` auf, aber ungeklärt: Verhalten bei aktivem Keep-Alive (#112,
    Notification bleibt im Monitoring-Zustand), Race in der Early-Return-Bedingung, fehlendes
    Retry bei DELETE-Netzwerkfehler.
- Status: `complete` für README-Korrektur und Issue-Anlage; #118/#119 offen, keine
  Implementierung freigegeben oder vorgenommen.

## Umsetzung & Validierung Paket B (PR #116 / Issue #114) — 2026-08-21

- Implementierung: `KeepADBEndpoint.startFastProbe` pulst `adb_wifi_enabled` einmal pro
  Discovery-Generation aus/ein (800 ms Pause), wenn 5s nach Start kein offener Port gefunden
  wurde, während die Einstellung noch aktiv ist — Gegenmittel für den Fall, dass `AdbService`
  ein `adb_wifi_enabled=1` mitten im Teardown der vorherigen Session akzeptiert, ohne adbd
  tatsächlich neu zu binden.
- **Reviewbefund während des eigenen Gerätetests (Selbstkorrektur vor Merge):** Die erste
  Fassung brach vor dem Wieder-Einschalten ab, wenn sich `discoveryGeneration` während der
  800-ms-Pause änderte (z. B. durch einen weiteren `discover()`-Aufruf) — das Gerät blieb dann
  dauerhaft mit `adb_wifi_enabled=0` hängen, schlimmer als der Ausgangsfehler. Livenachweis auf
  dem S20 (Register blieb bei `0`, kein weiterer Log-Eintrag nach der Pulse-Warnung). Fix:
  Wieder-Einschalten läuft jetzt unbedingt, unabhängig von Generation/Interrupt, da der Pulse
  selbst die Ursache des Aus-Zustands ist. Commit `59bbea8` auf demselben Branch, PR
  unverändert.
- Lokale Gates (beide Commits): `git diff --check`,
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest lintDebug assembleDebug`
  erfolgreich (0 Fehler).
- Gerätegate auf S20 (`SM-G780G`/`RF8T307S88H`): Registrierter WLAN-Port zunächst nicht
  erreichbar; nach Nutzerhinweis auf Port `42927` fingerprint-validiert. Für die eigentliche
  Rapid-Toggle-Reproduktion wurde zusätzlich der unabhängige USB-Kanal über `mooslap2023-ts`
  verwendet (APK dorthin kopiert und über `adb -s RF8T307S88H` installiert), da der
  WLAN-Kontrollkanal für die Toggle-Aktion selbst nicht geeignet ist.
  - Nebenbefund: Während des Tests war das Gerät kurzzeitig mit deaktiviertem Wi-Fi vorgefunden
    (Ursache nicht abschließend geklärt, kein eigener Codepfad dieses Pakets); nach `svc wifi
    enable` normal fortgesetzt.
  - Reproduktion: rapides Aus-/Ein-Tippen des Haupt-Schalters versetzte adbd in genau den
    beschriebenen Hängerzustand (`adb_wifi_enabled=1`, kein Listener). Logcat zeigt den Pulse
    nach Ablauf der Verzögerung, danach einen erfolgreichen Re-Discovery-Zyklus
    (`Register update ... returned HTTP 200`), UI zeigt anschließend „Drahtloses Debugging ist
    AN“ mit korrektem neuen Endpoint.
  - Beobachtung außerhalb des Scopes: Eine einzelne Scan-Iteration über 30000–50000 dauerte in
    diesem Testlauf ungewöhnlich lange (~55s statt der für #110 dokumentierten ~150ms), obwohl
    ein roher Shell-`/dev/tcp`-Connect-Test auf geschlossene Loopback-Ports nahezu sofort
    zurückkam — deutet auf eine Java-Thread-Scheduling-Verzögerung (denkbar: Samsungs
    Hintergrund-Prozessdrosselung) statt auf einen erneuten Blocking-Connect-Regressionsfall wie
    bei #110. Nicht reproduzierbar isoliert, kein eigenes Issue angelegt; als Beobachtung hier
    protokolliert, falls es sich wiederholt.
- PR #116 (`fix: recover from stuck adbd after rapid Wireless Debugging toggle`) eröffnet, per
  manuellem `workflow_dispatch`-CI-Lauf (letzter dieser Art, siehe unten) grün verifiziert und
  per Squash-Merge in `master` übernommen (`Fixes #114`). Branch aufgeräumt.
- Status: `complete`.

## Nutzerentscheidung — keine GitHub-Actions-Läufe mehr — 2026-08-21

- Nutzerauftrag: GitHub-Actions-Minutenkontingent ist knapp; ab sofort keine Workflow-Läufe
  mehr auslösen (weder automatisch noch per manuellem `workflow_dispatch`). Abnahme künftig
  ausschließlich über lokale Gradle-Gates plus Gerätetest.
- Der zu diesem Zeitpunkt bereits laufende Dispatch-Run für PR #116 wurde nach ausdrücklicher
  Nutzeransage noch zu Ende abgewartet (letzter dieser Art); danach gemerged.
- Umsetzung: Regel in `AGENTS.md` (lokal, ungetrackt) unter einem neuen Abschnitt „CI (ab
  2026-08-21: keine GitHub-Actions-Läufe mehr)" dokumentiert. Die Workflow-Dateien selbst
  bleiben unverändert bestehen; ihre Deaktivierung/Löschung ist eine eigene, nicht erteilte
  Entscheidung.
- Status: `complete` für die Dokumentation dieser Regel.

## Übergabe-Checkpoint — 2026-08-21

- Server-Stand (abgefragt): keine offenen Issues, keine offenen PRs, `master` synchron mit
  `origin/master` auf `10d062b`.
- Arbeitsbaum: sauber.
- Strangzähler: Dieser Lauf kam aus dem Strang „Review-Findings-Nachfolgeissues" (#112–#114)
  und hat mit beiden Paketen (A: #112/#113, B: #114) ein nutzersichtbares Ergebnis erzeugt —
  keine offenen Issues mehr im Repository.
- Nächste sinnvolle Schritte: keine offenen Kandidaten; nächste Auswahlrunde erst bei neuen
  Issues oder auf ausdrücklichen Nutzerauftrag.

## Umsetzung Paket #118 + #119 (Webhook-Endpoint-Registrierung) — 2026-08-21

- Auftrag: Beide gebündelt (gleicher Codepfad `KeepADBRegisterClient`/`KeepADBEndpoint`/
  `KeepADBNotification`, gemeinsame Root-Cause-Untersuchung). Eigene Stufe S1 (`sonnet`·`low`),
  selbst umgesetzt (S1-First), kein Subagent nötig.
- Root-Cause-Verifikation im Code vor Implementierung:
  - #118a: `KeepADBNotification.refresh()` ruft `KeepADBRegisterClient.updateEndpointAsync()`
    nur aus dem `onEndpoint()`-Callback einer frischen Discovery auf; im `currentHost != null`-
    Zweig (bereits verbunden) fehlte der Aufruf komplett — bestätigt die Vermutung aus dem
    Issue-Text.
  - #118b: Der `deleteEndpoint()`-Erfolgspfad in `KeepADBRegisterClient` setzte
    `KeepADBPreferences.setWebhookLastReportedEndpoint(...)` nie zurück — stale Anzeige nach
    Deregistrierung bestätigt.
  - #119: `markUnavailableAsync()`s Guard (`lastRegisteredEndpoint == null &&
    latestPendingEndpoint.get() == null`) prüft ein rein statisches, nur In-Memory gehaltenes
    Feld — nach einem Prozessneustart (auf Android Routine) ist es `null`, obwohl der Server
    noch eine gültige Registrierung hält, und der DELETE wird dauerhaft übersprungen. Das
    #112-Notification-Monitoring-Szenario selbst war beim Codelesen bereits korrekt (ruft
    `refresh()` → `stop()` → `markUnavailableAsync()` unbedingt auf).
- Fix: `ensureStateInitializedLocked()` seedet `lastRegisteredEndpoint` einmalig pro Prozess aus
  der persistierten Preference (die nur bei bestätigtem POST-Erfolg geschrieben wird); Delete-
  Erfolgspfad (jetzt in gemeinsamer `performPendingWork()` statt dupliziert) setzt die
  Preference zusätzlich auf `null`; `refresh()` ruft `updateEndpointAsync()` zusätzlich im
  Cache-Treffer-Zweig auf (günstiger No-op, falls bereits registriert).
- Lokale Gates: `git diff --check`,
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest lintDebug assembleDebug`
  erfolgreich (0 Fehler).
- Geräte-Livenachweis auf S20 (`SM-G780G`/`RF8T307S88H`), da der registrierte WLAN-Port
  zwischenzeitlich nicht erreichbar war (Meister-Hinweis auf USB-Fallback über
  `mooslap2023-ts`) — Details siehe Geräte-Checkpoint unten. Alle drei Kernszenarien per
  Logcat mit HTTP-200-Nachweis gegen den echten Registrierungsdienst bestätigt:
  1. Frischer Prozess (nach `am force-stop`, In-Memory-Zustand verloren) + externes Ausschalten
     von `adb_wifi_enabled` → `Register delete returned HTTP 200` (belegt #119-Fix).
  2. Webhook bei bestehender Verbindung ausgeschaltet → Delete erfolgreich, persistierter
     „zuletzt gemeldeter Endpoint" korrekt aus den Prefs entfernt (belegt #118b-Fix).
  3. Webhook bei weiterhin bestehender, unveränderter Verbindung wieder eingeschaltet, **ohne**
     neuen Discovery-Zyklus (kein `KeepADBEndpoint`-Log) → sofortiger
     `Register update ... returned HTTP 200` (belegt #118a-Fix, den eigentlichen Kernfehler).
  - Nebenbefund (kein eigener Codepfad dieses Pakets, nicht weiter verfolgt): Direktes
    Überschreiben der `keepadb_prefs.xml` per `run-as cp`/Shell-Redirect schlug mit „Permission
    denied" fehl (vermutlich SELinux-Restriktion auf `shared_prefs/`); UI-Automation
    (uiautomator dump + `input tap`) war der zuverlässige Weg für den Webhook-Toggle-Test.
    `SettingsActivity` ist absichtlich nicht exported (`am start -n ... .SettingsActivity`
    scheitert mit `SecurityException`) — kein Befund, korrektes Verhalten.
- PR #120 (`fix: reliably register/deregister webhook endpoint across restarts`) eröffnet und
  nach grünen lokalen Gates plus Geräte-Livenachweis direkt gemerged (kein GitHub-Actions-Run,
  Regel seit 2026-08-21). `Fixes #118`, `Fixes #119` — beide serverseitig automatisch
  geschlossen, verifiziert nach Merge.
- Kein delegierter Code, keine Architektur-/Auth-/Migrations-/externe-Schnittstellen-
  Neuentscheidung → laut Review-Policy kein separater unabhängiger Review erforderlich; eigene
  Qualitätsleiter (Gates + Geräte-Livenachweis) genügte.
- Status: `complete`.

### Geräte-Checkpoint — S20 Transportwechsel während dieses Pakets

- Registrierter WLAN-ADB-Pfad antwortete zwischenzeitlich nicht mehr (`Connection refused`
  nach eigenem Toggle-Test); automatischer Schnell-Portscan (30000–50000) fand ebenfalls nichts
  Nutzbares. Meister sofort informiert; auf Anweisung per `ssh mooslap2023-ts` auf den
  USB-Transport (`adb -s RF8T307S88H`) ausgewichen — dort durchgehend erreichbar.
- Nach Testende regulären WLAN-Pfad erneut verifiziert (`android-target s20` wieder
  funktionsfähig, neuer Endpoint `192.168.178.24:45285`) und das zentrale Register aktualisiert
  (`phone-register record s20 --method wlan-adb --endpoint 192.168.178.24:45285
  --verified-fingerprint "SM-G780G/RF8T307S88H"`).
- Für künftige Läufe an diesem Projekt: Der App-eigene Webhook-Endpunkt
  (`http://100.111.111.21:50829/register/s20`) ist **derselbe** Dienst wie das zentrale
  Erreichbarkeits-Register — eigene `phone-register`/`android-target`-Aufrufe während eines
  Webhook-Tests überschreiben denselben Serverzustand und verfälschen ein reines
  Curl-Polling als Testnachweis. Logcat-Beweis der tatsächlichen App-eigenen HTTP-Calls ist
  daher der verlässlichere Nachweis, nicht Polling des geteilten Registers.

### Retrospektive

1. Reihenfolge Code-Lesen (Root-Cause vorab verifizieren) → Fix → lokale Gates → Geräte-
   Livenachweis war richtig: Die statische Codeanalyse allein hätte die In-Memory-vs-Persisted-
   State-Diskrepanz (#119) zwar korrekt identifiziert, aber ohne echten Prozessneustart auf dem
   Gerät wäre nicht bewiesen gewesen, dass die Seed-Logik den beschriebenen Skip tatsächlich
   verhindert — ein rein lokaler Test hätte hier falsche Sicherheit erzeugt.
2. Der Geräte-Livenachweis fand den Transportausfall (unabhängig vom Code) und den
   `run-as`-Schreibzugriffs-Sackgassen; beide kein Regressionsrisiko fürs Paket, aber wertvolle
   Erkenntnis für künftige Testmethodik an diesem Projekt (UI-Automation statt Prefs-Datei-
   Manipulation).
3. Delegation/Review wären hier kein Erkenntnisgewinn gewesen — der Diff ist klein, lokalisiert,
   und der eigentliche Nachweis kam ohnehin aus dem Geräte-Log, nicht aus einer zweiten
   Code-Lektüre. S1 direkt war richtig zugeschnitten.
4. Verbesserung für nächstes Mal: Bei Webhook-/Register-Tests an diesem Projekt vorab prüfen,
   ob der konfigurierte Webhook zufällig derselbe Dienst wie das zentrale Erreichbarkeits-
   Register ist (hier der Fall) — dann von Anfang an Logcat statt Curl-Polling als Testoracle
   wählen, statt es erst während des Tests zu entdecken.

## Übergabe-Checkpoint — 2026-08-21 (nach #118/#119)

- Server-Stand (abgefragt): keine offenen Issues, keine offenen PRs, `master` synchron mit
  `origin/master` auf `722a7e8`.
- Arbeitsbaum: sauber.
- Strangzähler: Strang „Nutzerfolgeauftrag 3 → #118/#119" hat mit diesem Paket ein
  nutzersichtbares Ergebnis erzeugt (Webhook-Registrierung jetzt zuverlässig) — keine offenen
  Issues mehr im Repository.
- Nächste sinnvolle Schritte: keine offenen Kandidaten; nächste Auswahlrunde erst bei neuen
  Issues oder auf ausdrücklichen Nutzerauftrag.

## Nachbesserung — Webhook-Statusanzeige aktualisiert nicht live (PR #121) — 2026-08-21

- Nutzerrückmeldung nach #120: „Anzeige auf der Hauptseite ist immer noch so wie vorher,
  aktualisiert nicht." Berechtigter Befund — mein eigener Livenachweis für #120 hatte nur die
  serverseitige Registrierung per Logcat verifiziert, nie die tatsächliche MainActivity-Anzeige
  während offener App beobachtet. Das war die Lücke: `MainActivity.refreshWebhookStatus()` liest
  SharedPreferences nur synchron innerhalb von `refresh()` (onResume/Klick/ContentObserver);
  der eigentliche Webhook-POST/DELETE läuft asynchron auf `KeepADBRegisterClient.EXECUTOR` und
  schreibt die Preference erst nach dem HTTP-Roundtrip — ohne Rückkanal blieb die Anzeige bis
  zum nächsten unabhängigen `refresh()`-Trigger stehen.
- Fix: `KeepADBRegisterClient.RegisterStateListener` (Analog zu
  `KeepADBNotification.EndpointListener`), gefeuert per `Handler(Looper.getMainLooper())` nach
  jedem tatsächlichen Zustandswechsel (Post-Erfolg, Delete-Erfolg, `unregisterAndDisableAsync`).
  `MainActivity` registriert/entfernt ihn in `onResume()`/`onPause()`.
- Lokale Gates erneut grün. Geräte-Livenachweis auf S20 über `mooslap2023-ts`-USB-Fallback,
  diesmal **mit MainActivity durchgehend im Vordergrund** (kein Verlassen/Wiederöffnen): Anzeige
  wechselte live auf „Zuletzt gemeldeter Endpoint: noch keiner" nach Ausschalten und auf den
  neuen Endpoint nach Wiedereinschalten.
- PR #121 eröffnet und nach grünen Gates + Livenachweis gemerged (kein GitHub-Actions-Run).
- **Lehre für diesen Skill:** Ein Reviewnachweis, der nur den Backend-/Log-Pfad prüft, beweist
  nicht die UI-Eigenschaft, die das Issue eigentlich verlangt hat — exakt der in Abschnitt „Jede
  Prüfung beweist nur ihre eigene Ebene" beschriebene Fehler, hier selbst begangen trotz
  Kenntnis der Regel. Bei UI-nahen Issues künftig den Livenachweis explizit am sichtbaren
  Bildschirmzustand führen (Screenshot/UI-Dump vor und nach der Aktion), nicht nur am Logcat/
  Server-Response.
- Status: `complete`.

## Übergabe-Checkpoint — 2026-08-21 (nach PR #121)

- Server-Stand (abgefragt): keine offenen Issues, keine offenen PRs, `master` synchron mit
  `origin/master` auf `62a542f`.
- Arbeitsbaum: sauber.
- Nächste sinnvolle Schritte: keine offenen Kandidaten; nächste Auswahlrunde erst bei neuen
  Issues oder auf ausdrücklichen Nutzerauftrag.

## Nachbesserung — "abgemeldet" statt "noch keiner" (PR #122) — 2026-08-21

- Nutzerbeobachtung: „noch keiner" wird sowohl für „nie gemeldet" als auch für „gerade erfolgreich
  abgemeldet" verwendet — widerspricht dem README-Versprechen zuverlässiger Registrierung.
  Berechtigter Befund, per `AskUserQuestion` bestätigt und umgesetzt.
- Fix: neuer String `webhook_status_deregistered`, gezeigt wenn `lastReportedAt > 0` aber kein
  aktueller Endpoint — in allen 17 vorhandenen Sprachdateien ergänzt (Lint-Gate
  `MissingTranslation` verlangt Vollständigkeit über alle Locales; nur en/de von Hand geprüft,
  Rest wörtliche Best-Effort-Übersetzung).
- Während des Livetests eigene Testartefakte verursacht: Keep-Alive für einen sauberen
  Ablauf-Test kurzzeitig deaktiviert, dabei mehrere rasche Prozess-/Toggle-Zyklen ausgelöst, die
  adbd kurzzeitig in einen listener-losen Zustand brachten (45s-Timeout, kein Code-Defekt) —
  vom Nutzer live am Gerät als „geht jetzt gar nicht" bemerkt, während ich noch testete. Über
  die reguläre App-UI (Keep-Alive-Schalter) sauber behoben, kein Bug bestätigt.
- Größenangaben auf Nutzerfrage (Stand `master` vor diesem Fix): Code ~2395 Zeilen Java,
  Repo-Arbeitskopie 2,6 MB, Debug-APK 442 KB, installiert auf S20 448 KB Code + 26 KB Daten.
- PR #122 gemerged (kein GitHub-Actions-Run).
- **Lehre:** Bei Live-Gerätetests, die Nutzereinstellungen (Keep-Alive) temporär verändern,
  entstehen für einen Mitbeobachter am selben physischen Gerät scheinbare Fehlzustände. Sollte
  künftig vorab angekündigt werden, wenn der Nutzer parallel Zugriff aufs Testgerät hat.
- Status: `complete`.

## Übergabe-Checkpoint — 2026-08-21 (nach PR #122)

- Server-Stand (abgefragt): keine offenen Issues, keine offenen PRs, `master` synchron mit
  `origin/master` auf `5fbae4e`.
- Arbeitsbaum: sauber. Gerät (S20) in normalem Zustand: Keep-Alive an, Wireless Debugging an,
  Webhook korrekt registriert, Register aktualisiert.
- Nächste sinnvolle Schritte: keine offenen Kandidaten; nächste Auswahlrunde erst bei neuen
  Issues oder auf ausdrücklichen Nutzerauftrag.

## Tuning — Discovery-Give-up-Timeout 45s → 10s (PR #123) — 2026-08-21

- Nutzeranfrage: 45s Timeout in `KeepADBEndpoint.OVERALL_TIMEOUT_MS` erschien zu lang, Vorschlag
  10s. Wert stammte unbegründet aus #116 (keine dokumentierte Herleitung). Da der
  Retry-Mechanismus in `KeepADBNotification.onUnavailable()` (Backoff 2s/5s) bereits existiert,
  ist ein zu kurzes Timeout nicht fatal, nur ein zusätzlicher Zyklus im seltenen Langsam-Fall.
  Geändert auf 10_000ms.
- Lokale Gates grün. Live-Nachweis auf S20 (USB-Fallback via `mooslap2023-ts`): normale
  Discovery nach vollständigem App-Neustart löst in ~1,2s auf — deutlich unter der neuen
  Obergrenze, keine Regression auf dem Normalpfad.
- PR #123 gemerged (kein GitHub-Actions-Run).
- Status: `complete`.

## Übergabe-Checkpoint — 2026-08-21 (nach PR #123)

- Server-Stand (abgefragt): keine offenen Issues, keine offenen PRs, `master` synchron mit
  `origin/master` auf `a3272d7`.
- Arbeitsbaum: sauber. Gerät (S20) in normalem Zustand.
- Nächste sinnvolle Schritte: keine offenen Kandidaten; nächste Auswahlrunde erst bei neuen
  Issues oder auf ausdrücklichen Nutzerauftrag.

## Tuning — Weitere Reduktion 10s → 8s statt 3s (PR #124) — 2026-08-21

- Nutzeranfrage nach 3s. Abgelehnt: `RECOVERY_PULSE_DELAY_MS` (5000ms) + `RECOVERY_PULSE_OFF_MS`
  (800ms) = 5800ms — ein 3s-Give-up hätte den #114-Recovery-Pulse nie zum Feuern kommen lassen
  und damit genau den Stuck-adbd-Fall wieder kaputt gemacht, für den er gebaut wurde. Per
  `AskUserQuestion` Kompromiss gewählt: Pulse-Delay unangetastet, Gesamt-Timeout auf 8s (statt
  7s) für zusätzliche mDNS-Nachlaufzeit nach dem Pulse-Neustart.
- Lokale Gates grün. Live-Nachweis auf S20: App-Neustart löst Endpoint weiterhin korrekt auf,
  keine Regression auf dem Normalpfad.
- PR #124 gemerged (kein GitHub-Actions-Run).
- Status: `complete`.

## Übergabe-Checkpoint — 2026-08-21 (nach PR #124)

- Server-Stand (abgefragt): keine offenen Issues, keine offenen PRs, `master` synchron mit
  `origin/master` auf `7bc86ab`.
- Arbeitsbaum: sauber. Gerät (S20) in normalem Zustand.
- Nächste sinnvolle Schritte: keine offenen Kandidaten; nächste Auswahlrunde erst bei neuen
  Issues oder auf ausdrücklichen Nutzerauftrag.

## Review-Follow-ups — 2026-08-21

- Auftrag: Die 17 Findings aus dem umfassenden KeepADB-Code-Review als zentrale GitHub-Issues
  erfassen.
- Paketierung: acht Issues, weil die Findings gemeinsame Codepfade, Datenmodelle oder Gates
  teilen. Die Issue-Bodies referenzieren jedes gebündelte Finding ausdrücklich; kein Finding
  wird still verworfen.
- Umfang: H1–H4, M1–M10 und L1–L3; keine Implementierung, keine Builds, keine Tests,
  keine Geräteaktion und kein GitHub-Actions-Run.
- Freigabe: Nutzer hat die Bündelung ausdrücklich bestätigt.
- Einstufung: S1, direkte Issue-Anlage und Dokumentationspflege durch den Hauptagenten;
  Review `not applicable`.
- Zielzustand: acht neue offene Issues mit reproduzierbarem Problem, Akzeptanzkriterien,
  Nicht-Zielen und vorgeschlagenem minimalem Fix; Reviewbericht und dieser Plan verweisen auf
  die erzeugten Issue-URLs.
- Validierung: vorhandene Issue-/PR-/Run-Liste und Labels read-only abgefragt; keine offenen
  Issues oder PRs. GitHub Actions bleiben wegen Projektpolicy unberührt.
- Angelegte Issues: [#125](https://github.com/m00sfett/KeepADB/issues/125),
  [#126](https://github.com/m00sfett/KeepADB/issues/126),
  [#127](https://github.com/m00sfett/KeepADB/issues/127),
  [#128](https://github.com/m00sfett/KeepADB/issues/128),
  [#129](https://github.com/m00sfett/KeepADB/issues/129),
  [#130](https://github.com/m00sfett/KeepADB/issues/130),
  [#131](https://github.com/m00sfett/KeepADB/issues/131) und
  [#132](https://github.com/m00sfett/KeepADB/issues/132); alle offen, Titel/Labels/Bodies
  serverseitig nach Anlage verifiziert.
- Status: `complete` für den Issue-Anlageauftrag; Commit `2c49bc5` ist auf `origin/master`
  gepusht, Arbeitsbaum nach der Aktualisierung sauber, Review `not applicable`, keine
  Implementierung oder weitere Abnahme im Scope.

## Nutzerentscheidung und Auswahl — 2026-08-21

- Nutzerentscheidung: weitere private Härtung vor der Veröffentlichung; die Review-Follow-ups
  sollen nacheinander umgesetzt werden.
- Paketwahl: **Issue #131** „Locale-, Notification- und Accessibility-Nacharbeiten abschließen“.
  Es ist das kleinste zusammenhängende Paket ohne neue Auth-, Transport-, Datenmodell- oder
  Zustandsautomaten-Architektur. #125–#130 und #132 bleiben getrennte Folgepakete.
- Ziel: API-abhängige Locale-Wahrheit konsolidieren, Tile-/Notification-Kontexte korrekt
  lokalisieren, verweigerte Notification-Berechtigung verständlich mit Settings-Pfad zeigen,
  interaktive Ziele auf mindestens 48 dp bringen und RTL-adaptives Zurück-Navigations-Icon
  verwenden.
- Nicht-Ziele: keine Keep-Alive-/Endpoint-/Webhook-Zustandsänderung, kein Compose-/Material3-
  Umbau, keine neue Berechtigung oder externe Dependency.
- Stufe: S2; Same-Stage-Delegation an `luna·max` war wegen des breiten UI-/Locale-Kontexts
  vorgesehen, vom Nutzer freigegeben und durch Agent Curie ausgeführt. Der unabhängige S2-Review
  durch Agent Banach fand anschließend mehrere Befunde; die erste Reparaturrunde läuft direkt
  im Hauptagenten.
- Muss-Akzeptanzfälle: API 30–32 Preference-Locale, API 33+ Plattform-Locale; Tile und
  Activities zeigen dieselbe Sprache; kein doppeltes Recreation; Permission-Deny-Hinweis mit
  Settings-Pfad; Touch-Ziele mindestens 48 dp; RTL-adaptives Back-Icon.
- Gates: `git diff --check`; danach — typisierte Freigabe erforderlich — Debug-Build,
  Unit-/Lint-Gates und schließlich Emulator-/S20-Accessibility-/Locale-Abnahme. Maximal zwei
  Reparaturrunden; `approved` bedeutet alle Codekriterien plus autorisierte lokale Gates und
  Geräte-/UI-Nachweis.
- Status: `not approved`; erste Implementierung eingearbeitet, `git diff --check` bestanden.
  Review 1: `not approved`; API-30–32-Live-Locale, Legacy-Migration, bereits abgelehnte
  Notification-Permission und Selector-Semantik wurden als Befunde erfasst. Build, Tests, Lint,
  Geräte-/UI-Abnahme, GitHub-Actions, Commit und Push bleiben offen.

## Issue-131-Checkpoint — 2026-08-21

- Umsetzung: API-33+-Locale-Wahrheit auf `LocaleManager` begrenzt; API 30–32 bleibt
  preferencebasiert mit explizitem `recreate()`. Tile-Service nutzt den lokalen Context.
- UI: Denied-POST_NOTIFICATIONS-Hinweis mit lokalisiertem `ACTION_APP_NOTIFICATION_SETTINGS`-
  Pfad, 48-dp-Ziele, Content Descriptions und autoMirrored Back-Drawable umgesetzt.
- Scope-Gate: Keine Änderungen an Endpoint, Keep-Alive, Webhook oder Dependencies.
- Nachweise: `git diff --check` grün; Ressourcenreferenzen statisch vollständig (19 Locale-Dateien
  plus Default), keine verbleibenden 36-/40-dp-Ziele in den betroffenen Layouts.
- Offen: Build-, Unit-, Lint- und Geräte-/UI-Nachweise; der erste unabhängige Review ist mit
  `not approved` abgeschlossen, die Reparaturrunde folgt im nächsten Abschnitt.
- Arbeitsbaum: Vorbestehender Plan-Diff bleibt erhalten; Code-/Ressourcenänderungen sind noch
  uncommitted und ungepusht, wie beauftragt.

## Issue-131-Reparaturrunde 1 — 2026-08-21

- Reviewbefunde repariert: MainActivity erkennt auf API 30–32 den veralteten Locale-Context und
  recreatet sich beim Zurückkehren; API-32→33 übernimmt eine vorhandene Legacy-Preference einmalig
  in `LocaleManager` und löscht danach den Legacy-Wert; API 33+ speichert keine Preference-Locale.
- Notification-Permission: Der Erstantrag wird pro Activity-Preference markiert; ein bereits
  abgelehnter Antrag wird nicht bei jedem Start erneut gepromptet, sondern zeigt den Settings-Pfad.
- Accessibility: Der Sprachselector kündigt nun zusätzlich den aktuell gewählten Sprachwert an.
- Scope: weiterhin ausschließlich #131; keine Reparatur außerhalb der Reviewbefunde.
- Validierung: `git diff --check` grün; Build, Unit, Lint und Geräte-/UI-Abnahme weiterhin offen.
- Status: `not approved` bis unabhängiger S2-Review 2 und die typisierten lokalen/Device-Gates
  abgeschlossen sind.

## Issue-131-Review 2 und Circuit-Breaker — 2026-08-21

- Review 2 durch Agent Avicenna (`luna·max`), read-only, Status: `not approved`.
- Verbleibende Muss-Fixes im Scope: (1) API-30–32-Wechsel von benutzerdefinierter Sprache auf
  System-Default muss eine bereits geöffnete MainActivity ebenfalls aktualisieren; (2) die
  API-32→33-Migration braucht einen eindeutigen Marker, damit eine absichtlich leere
  `LocaleManager`-Auswahl nicht später von einer alten Preference überschrieben wird; (3) das
  Widget braucht garantiert 48 dp und explizite Accessibility-Semantik; (4) die geforderte
  API-/RTL-/Permission-/Accessibility-Testabdeckung fehlt weiterhin.
- Keine Scopeverletzung und keine Findings außerhalb von #131. Die lokale Prüfung `git diff
  --check` bleibt grün; Build, Tests, Lint und Geräte-/UI-Abnahme wurden nicht ausgeführt.
- **Circuit-Breaker:** Zwei unabhängige Reviews in Folge waren `not approved`. Keine weitere
  Reparaturrunde oder Reviewrunde wird automatisch gestartet. Status: `closed-pending-decision`.
- Offene Entscheidung für die nächste Runde:
  1. **Marker-basierte Migration (empfohlen):** einen einmaligen `locale_migration_done`-Marker
     einführen, Legacy-Locale nur vor dem Marker übernehmen, danach Plattform-Locale strikt als
     Wahrheit behandeln; System-Default-Refresh, Widget-48-dp-Semantik und deterministische
     Tests im selben #131-Scope reparieren. Gute Upgrade-Erhaltung, mittlere Änderung.
  2. **Migration verwerfen:** alte Preference bei API 33+ niemals übernehmen und den
     API-32→33-Sprachverlust bewusst akzeptieren; übrige Befunde und Tests separat beheben.
     Kleinere Logik, aber schlechtere Upgrade-Kompatibilität.
  3. **Scope aufteilen:** #131 nach den UI-Fixes schließen und Migration/Testabdeckung als
     separates Folgepaket erfassen. Das hält Rollbacks klein, lässt aber #131 bis zur neuen
     Abnahme unvollständig und erfordert neue zentrale Issues.
- Eine weitere Implementierung braucht die ausdrückliche Benennung einer dieser Optionen.

## Architekturentscheidung — Issue #131 Option 2 — 2026-08-21

- Nutzerfreigabe: **Option 2 — Legacy-Migration verwerfen**.
- Konsequenz: API 33+ übernimmt keine `SharedPreferences`-Locale aus API 30–32. Ein alter
  Sprachwunsch darf beim OS-Upgrade verloren gehen; die alte Preference wird beim API-33+-Zugriff
  verworfen. `LocaleManager` bleibt dort die einzige Locale-Quelle. Das reduziert die
  Zustandskomplexität und verhindert, dass eine absichtlich leere Plattform-Locale später von
  einem alten Wert überschrieben wird.
- Zusätzlich umgesetzt: API-30–32-System-Default-Wechsel prüft den tatsächlichen Context gegen
  die System-Locale; das Widget setzt 48-dp-Mindestmaße und dynamische Content Description.
- Nicht automatisch erweitert: deterministische API-/RTL-/Permission-/Accessibility-Tests und
  Geräteabnahme bleiben offene Gates innerhalb bzw. als möglicher Folgeumfang von #131.
- Status: `closed-pending-decision` für die nächste Auswahl zwischen Test-/Geräteabnahme und
  weiteren Codeänderungen; keine dritte Reviewrunde ohne neue Freigabe.

## Auswahl-Checkpoint — 2026-08-21 — Zielentscheidung vor Review-Follow-ups

- **Roadmap-Abgleich (wörtlich):** „Vorbereitung des Repositories für die Veröffentlichung als
  freies, quelloffenes Open-Source-Tool **KeepADB** unter der **GPL-3.0**-Lizenz.“
- **Serverzustand (abgefragt):** Repository privat, `master` und `origin/master` auf
  `a971208ce618539e44985fe8ce969943e7e20230`, Arbeitsbaum sauber, keine offenen PRs und kein
  aktiver Run für den aktuellen Head. Die letzten gelisteten CI-Runs gehören älteren Heads;
  wegen der Projektregel werden keine neuen Runs gestartet.
- **Offene Kandidaten (abgefragt):** #125–#132 sind offen. Sie bilden acht klar getrennte
  Review-Follow-up-Pakete: Discovery-Lifecycle, Endpoint-Identität/Health, Toggle-Intent,
  Webhook-Transaktion, Webhook-Sicherheit/Backup, BootReceiver, Locale/Accessibility sowie
  lokale Release-/Test-Gates.
- **Zielabgleich:** Kein Kandidat dient unmittelbar der dokumentierten Veröffentlichung; alle
  acht setzen die private Produktpflege bzw. Release-Härtung fort. Ein Paket wird daher noch
  nicht ausgewählt. `github-drift` kann ohne verknüpftes GitHub Project nur melden, dass kein
  Board geprüft werden kann.
- **Status:** `closed-pending-decision`. Erforderlich ist eine fachliche Entscheidung, ob die
  Roadmap auf weitere private Härtung vor der Veröffentlichung erweitert wird oder ob die
  Veröffentlichung (Repository-Sichtbarkeit, Release-/Tag-Plan und verbleibende Muss-Gates)
  jetzt das Ziel ist. Keine Implementierung, Builds, Tests, Geräteaktion, Delegation oder
  Workflow-Ausführung im aktuellen Lauf.

## Issue-Anlage — App-Icon in der Main-Titelleiste — 2026-08-21

- **Auftrag:** Neues GitHub-Issue für ein App-Icon links neben „KeepADB“ in der oberen
  Titelleiste der Main-Activity anlegen.
- **Nicht-Scope:** Keine Implementierung, keine Änderung der Settings-Titelleiste und keine
  Geräteabnahme in diesem Lauf.
- **Muss-Akzeptanzkriterien:** Launcher-/App-Icon links neben dem Titel sichtbar; bestehende
  Einstellungen-Schaltfläche und Titel bleiben funktionsfähig; Abstände und Darstellung auf
  den unterstützten Bildschirmgrößen plausibel; Icon erhält keine eigene Aktion.
- **Geplante Stufe:** S1, direkte Issue-Anlage durch den Hauptagenten; Review nicht anwendbar.
- **Validierung:** UI-Codepfad read-only geprüft; bestehende Issues, Runs und Arbeitsbaum
  abgefragt; keine Builds, Tests, Geräteaktion oder Workflow-Ausführung.
- **Angelegt:** [#133](https://github.com/m00sfett/KeepADB/issues/133), offen, Label
  `enhancement`; Titel und Akzeptanzkriterien nach Anlage read-back geprüft.
- **Status:** `complete` für den Issue-Anlageauftrag; Implementierung und UI-Abnahme bleiben
  ausdrücklich offen in Issue #133.

## Auswahl-Checkpoint — 2026-08-21 — Zielabgleich vor neuer Issue-Reihe

- **Roadmap-Aussage (wörtlich):** „Vorbereitung des Repositories für die Veröffentlichung als
  freies, quelloffenes Open-Source-Tool **KeepADB** unter der **GPL-3.0**-Lizenz.“
- **Serverzustand (im selben Lauf abgefragt):** `master` und `origin/master` synchron auf
  `2d74a56`; Arbeitsbaum sauber; keine offenen PRs; kein aktiver GitHub-Actions-Run für den
  aktuellen Head. Branch Protection ist für das private Repository nicht abfragbar (GitHub
  HTTP 403: Upgrade auf Pro oder Repository öffentlich machen). Wegen der Projektpolicy wurde
  kein Workflow gestartet.
- **Offene Kandidaten (im selben Lauf abgefragt):** #125 Discovery-Lifecycle, #126
  Endpoint-Identität/Health, #127 Toggle-Intent, #128 Webhook-Transaktion, #129
  Webhook-Sicherheit/Backup, #130 BootReceiver, #131 Locale/Accessibility, #132 lokale
  Release-/Test-Gates und #133 App-Icon-Titelleiste.
- **Zielabgleich:** Kein Kandidat dient unmittelbar der oben zitierten Veröffentlichung; #132
  unterstützt Release-Gates, ist aber selbst ein umfangreiches Härtungs-/Tooling-Paket und kein
  beschlossener Veröffentlichungsschritt. Die zuvor dokumentierte Entscheidung für weitere
  private Härtung steht damit im Konflikt zur aktuellen Roadmap-Aussage und darf nicht
  automatisch fortgeschrieben werden.
- **Auswahl:** kein Issue ausgewählt. Die Issues bleiben offen; keine Implementierung, Builds,
  Tests, Geräteaktion, Delegation, Commit-/Push-/PR-/Merge-Aktion oder Workflow-Ausführung.
- **Review:** `not applicable` für den Planungs-/Zielabgleich.
- **Status:** `closed-pending-decision`. Erforderlich ist eine fachliche Entscheidung: (A)
  Roadmap ausdrücklich auf private Härtung vor Veröffentlichung erweitern und ein Paket
  priorisieren, oder (B) Veröffentlichungsarbeit als neues Zielpaket definieren (verbleibende
  Muss-Gates, Repository-Sichtbarkeit, Release-/Tag- und Signierungsplan). Eine allgemeine
  Freigabe wird nicht als Auswahl eines dieser Wege interpretiert.

## Auswahl-Checkpoint — 2026-08-21 — Härtung vor Veröffentlichung bestätigt

- **Nutzerentscheidung:** Private Härtung wird vor die Veröffentlichung gezogen; die
  Veröffentlichung wird zurückgestellt.
- **Roadmap-Abgleich:** Die bisherige Veröffentlichungsaussage bleibt als späteres Ziel
  bestehen; die Härtungs-Issues sind damit für die nächste Reihe zugelassen.
- **Ausgewähltes Paket:** Issue [#131](https://github.com/m00sfett/KeepADB/issues/131) —
  „Locale-, Notification- und Accessibility-Nacharbeiten abschließen“.
- **Begründung:** #131 ist der kleinste zusammenhängende Härtungsstrang, bereits teilweise
  umgesetzt und ohne neue Auth-, Transport-, Datenmodell- oder Zustandsautomaten-Architektur.
  #125–#130 bleiben fachlich getrennte Folgepakete; #132 wird wegen der zurückgestellten
  Veröffentlichung nicht vorgezogen; #133 ist Komfort-UI und keine Härtung.
- **Ziel:** Locale-Wahrheit und UI-Kontext über API 30–35 konsolidieren, Notification-
  Permission-Zustand verständlich machen und Accessibility-/RTL-Anforderungen abschließen.
- **Nicht-Ziele:** Keine Keep-Alive-, Endpoint-, Webhook- oder Toggle-Semantikänderung, kein
  Compose-/Material3-Umbau, keine neue Berechtigung oder externe Dependency.
- **Offene Muss-Nachweise:** deterministische API-/RTL-/Permission-/Accessibility-Tests sowie
  UI-/Geräteabnahme gemäß Issue #131. Der aktuelle Serverstatus des Issues ist offen; der
  Arbeitsbaum enthält nur die laufende Planänderung.
- **Stufe/Freigaben:** S2, direkte Fortsetzung durch den Hauptagenten; keine neue Delegation.
  Der Nutzer hat Implementierung, Debug-Build, Unit-Tests, Lint und S20-Geräteprüfung
  vollständig freigegeben. GitHub-Actions bleiben wegen Projektpolicy ausgeschlossen.
- **Status:** `in_progress`; lokale Gates und danach die S20-Abnahme laufen. `approved` erst
  nach einzelnem Nachweis der Codekriterien und der autorisierten Gates.

## Issue-131-Validierung — 2026-08-21

- **Umsetzung:** Bestehende #131-Codeänderungen aus `ddc17fc` bleiben unverändert; ergänzt
  wurde `KeepADBAccessibilityContractTest` mit deterministischen, dependency-freien Checks für
  Locale-Ressourcen, Permission-Texte, 48-dp-Touch-Ziele und RTL-Back-Drawable.
- **Lokale Gates:** `git diff --check`, `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew
  testDebugUnitTest`, `... lintDebug` und `... assembleDebug` erfolgreich. Die Suite umfasst
  11 grüne Unit-Tests. Ein erster Testlauf fand die falsche Annahme von 20 statt 19
  `values*`-Verzeichnissen; Testannahme korrigiert, die fehlschlagende Stufe wiederholt.
- **S20-Transport:** Register zuerst abgefragt; WLAN-Endpunkt `192.168.178.24:44587` mit
  `SM-G780G` / `RF8T307S88H` / Android 13 validiert, danach wegen mDNS-Duplikaten auf den
  eindeutigen USB-Transport `RF8T307S88H` gewechselt und Register aktualisiert. Preferences
  vor Installation nach `~/agent/backup/2026-08-21/smartphone-wlan-adb-app-s20-shared-prefs.tar`
  gesichert; keine Deinstallation oder Datenlöschung.
- **Gerätenachweise:** Debug-APK installiert. MainActivity und SettingsActivity per UI-Dump/
  Screenshot ohne Crash geprüft; interaktive Ziele mindestens 48 dp. Arabisch aktiviert:
  Settings wurden lokalisiert und das Back-Icon RTL-adaptiv rechts dargestellt; anschließend
  Deutsch wiederhergestellt. Notification-Permission widerrufen: MainActivity zeigte den
  verständlichen Deny-Hinweis plus Button `BENACHRICHTIGUNGSEINSTELLUNGEN ÖFFNEN`; Permission
  danach wieder gewährt. Kein neuer `FATAL EXCEPTION`-Nachweis.
- **Nicht vollständig beweisbar:** API-30/32/35-spezifische Laufzeitabnahme wurde auf dem
  Android-13-S20 nicht durchgeführt; die statischen/Unit-Contracts und der API-33+-Datenpfad
  sind nachgewiesen. Ein separater Emulatorlauf bleibt für diese API-Matrix sinnvoll, ist aber
  für den aktuellen S20-Abschluss nicht erforderlich.
- **Retrospektive:** Das günstige Unit-/Contract-Gate fand die falsche Ressourcenanzahl vor dem
  Abschluss. Der USB-Fallback war nach mDNS-Duplikaten entscheidend; ein einmaliger WLAN-
  Transportnachweis hätte die UI-Abnahme nicht getragen. Direkte Umsetzung ohne Delegation war
  für die kleine Restlücke richtig. Verbesserung: Geräte-Smoke künftig erst nach lokaler
  Ressourcen-/Contract-Prüfung und mit vorbereitetem USB-Kontrollkanal starten.
- **Status:** `approved` für Issue #131; Commit/PR/Server-Schließung stehen noch aus. Review:
  `not applicable` für die direkte, test-only Reständerung; die vorherige unabhängige Review-
  Kette des Produktcodes ist im Plan dokumentiert.

## Abschluss Issue #131 — 2026-08-21

- PR [#134](https://github.com/m00sfett/KeepADB/pull/134) wurde per Squash-Merge als Commit
  `2238029` in `master` übernommen; Issue [#131](https://github.com/m00sfett/KeepADB/issues/131)
  ist serverseitig geschlossen.
- `master` und `origin/master` sind synchron, der Arbeitsbaum ist sauber. Es wurden keine
  GitHub-Actions-Runs gestartet; PR #134 hatte keine gemeldeten erforderlichen Checks.
- Übergabe: Der Härtungsstrang #131 ist abgeschlossen. Die Veröffentlichung bleibt
  zurückgestellt; #125–#130, #132 und #133 sind weiterhin getrennte offene Folgepakete.
- **Finalstatus:** `complete` für das Paket; Review `not applicable` für die test-only
  Reständerung, Produktcode-Reviewkette im vorherigen Plan dokumentiert.

## Nachtrag Emulator-Smoke — 2026-08-21

- Kanonischer sichtbarer `Dev_Galaxy_S20_API_36_1_Play`-Emulator mit NVIDIA-Host-GPU sauber
  gestartet; SDK 36, Debug-APK installiert, MainActivity gestartet und UI-Dump/Screenshot
  ohne App-FATAL geprüft. Die 48-dp-/Accessibility-Bounds waren plausibel; der Emulator
  wurde anschließend sauber beendet.
- API 30/32 bleiben mangels passender lokaler AVDs ohne separaten Laufzeit-Smoke. Sie sind
  durch die API-unabhängigen Locale-/Ressourcen-Contracts und den bereits geprüften
  API-spezifischen Codepfad abgedeckt; dies ist ein dokumentiertes Restlimit, kein gestarteter
  GitHub-Actions-Ersatz.

## Auswahl-Checkpoint — 2026-08-21 — nächstes Härtungspaket

- `issue_snapshot_at: 2026-08-21T23:37:18+02:00`
- `plan_updated_at: 2026-08-21T23:37:18+02:00`
- **Issue-Snapshot:** GitHub-Issues, PRs, Runs und Drift wurden im selben Lauf read-only
  abgefragt. Issue #131 ist geschlossen; #125, #126, #127, #128, #129, #130, #132 und #133
  sind offen. `master` und `origin/master` zeigen auf `113243a`; der Arbeitsbaum ist sauber.
  Es gibt keinen aktiven Run für den aktuellen Head. Branch Protection ist für das private
  Repository nicht abfragbar (GitHub HTTP 403); wegen der Projektpolicy wurde kein Workflow
  gestartet. `github-drift` meldet, dass kein GitHub Project verknüpft ist.
- **Roadmap-Abgleich (wörtlich):** „Vorbereitung des Repositories für die Veröffentlichung als
  freies, quelloffenes Open-Source-Tool **KeepADB** unter der **GPL-3.0**-Lizenz.“ Die zuvor
  abgefragte Nutzerentscheidung zieht private Härtung vor die Veröffentlichung; Issue #130
  dient diesem ausdrücklich bestätigten Zwischenziel.
- **Ausgewähltes Paket:** Issue [#130](https://github.com/m00sfett/KeepADB/issues/130) —
  „BootReceiver-Einstieg gegen Fremdbroadcasts absichern“.
- **Ziel:** System-Boot-Recovery für den gewünschten Keep-Alive-Betrieb erhalten und
  untrusted Broadcasts daran hindern, Service-Start oder Wireless-Debugging-Re-Enable
  auszulösen.
- **Zusammenhang:** enger Manifest-/Receiver-/Service-Aufrufpfad (`AndroidManifest.xml`,
  `BootReceiver.java`, `KeepADBService.java`); #126 Endpoint-Health, #127 Toggle-Intent und
  #129 Webhook-Sicherheit bleiben wegen eigener Risiko- und Rückrollgrenzen getrennt.
- **Nicht-Ziele:** keine allgemeine Keep-Alive-Recovery, keine Änderung des Permission-Modells
  außerhalb der Receiver-Sendergrenze, keine Endpoint-, Webhook- oder UI-Arbeit.
- **Muss-Akzeptanzfälle:** `BOOT_COMPLETED` bleibt funktionsfähig; `QUICKBOOT_POWERON` wird
  entfernt oder nur mit nachgewiesener System-Sendergrenze verarbeitet; explizite
  Fremdbroadcasts starten weder Service noch Re-Enable; echter Boot bleibt auf AOSP-Emulator
  und Zielgerät funktionsfähig; FGS-Startfehler werden als Fehler behandelt.
- **Stufe und Review:** S4, `gpt-5.6-sol` mit `xhigh`, weil Autorisierung/Sendergrenze und
  Security-Review per Definition hochgestuft werden. Ein unabhängiger S4-Review ist Pflicht;
  keine Delegation gestartet und keine Freigabe dafür erteilt.
- **Freigaben:** Auswahl und Planaktualisierung sind durch den Orchestrator-Aufruf gedeckt;
  Implementierung, lokaler Build/Unit/Lint, Emulator-/S20-Geräteprüfung, Commit/Push/PR und
  externe Workflow-Ausführung sind nicht freigegeben. GitHub-Actions bleiben ausgeschlossen.
- **Validierungsleiter:** nach Implementierungsfreigabe zuerst `git diff --check`, dann
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest lintDebug assembleDebug`,
  anschließend gezielte Fremdbroadcast-Tests sowie Boot-Smoke auf Emulator und registriertem
  S20-Transport. Der Gerätepfad erfordert Register → registrierten Transport → `android-target`
  → Modell/Serial/Fingerprint.
- **Offene Punkte/Risiken:** Die zulässige Android-Sendergrenze für `BOOT_COMPLETED` sowie die
  tatsächliche OEM-Notwendigkeit von `QUICKBOOT_POWERON` müssen vor einer Änderung belegt
  werden. FGS-Fehlerpfad und Boot-Abnahme sind ungeprüft. `approved` bedeutet alle fünf
  Akzeptanzgruppen, unabhängiger S4-Review und alle autorisierten lokalen/Device-Gates grün.
- **Status:** `not approved`; serverseitig #130 offen, Commit/PR offen, Review `not applicable`
  für diese Auswahl. Nächste zulässige Etappe ist eine typisierte Freigabe für S4-Implementierung
  und den dazugehörigen Review; bis dahin keine Code- oder Teständerung.
- **Retrospektive/Verbesserung:** Die Auswahlprüfung hat den tatsächlichen Receiverpfad vor der
  Paketierung bestätigt. Für dieses Paket wird zuerst die Sender-/Systemvertragsfrage geklärt,
  bevor Code oder Geräte-Smoke falsche Sicherheit erzeugen.
- **Aufwandsprotokoll:** 1 Paket ausgewählt; 0 Implementierungen, 0 Builds, 0 Tests, 0
  Geräteaktionen, 0 Delegationen; sichtbare GitHub-/Shell-Abfragen durchgeführt; Token- und
  Abrechnungswerte unbekannt.

## Issue #130 — Implementierung und Validierungsstart — 2026-08-21

- **Freigabe:** Nutzer erteilte vollständige Freigabe für Implementierung, lokale Gates und
  Geräteprüfung. Eine unabhängige S4-Review bleibt für den Abschluss verpflichtend.
- **Umsetzung:** `BootReceiver` ist im Manifest `exported=false`; der ungeschützte
  `QUICKBOOT_POWERON`-Pfad wurde aus Manifest und Receiver entfernt. `KeepADBService.start()`
  meldet Fehler beim Anfordern des Foreground-Service explizit zurück; `onStartCommand()` fängt
  Fehler beim tatsächlichen Eintritt in den FGS-Modus ab, stoppt den Dienst und gibt
  `START_NOT_STICKY` zurück. Ein BootReceiver-FGS-Fehler bricht den anschließenden Re-Enable-
  Pfad ab. Ein dependency-freier statischer Boot-Contract-Test wurde ergänzt.
- **Nicht-Ziele:** keine allgemeine Recovery-/Toggle-/Endpoint-/Webhook-Änderung.
- **Lokale Validierung:** noch nicht ausgeführt; als nächste Leiter `git diff --check`, danach
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest lintDebug assembleDebug`.
- **Gerätevalidierung:** vollständige Nutzerfreigabe erteilt, noch nicht ausgeführt. Vorgesehen:
  Registerabfrage und registrierter Transport, Fingerprint-Prüfung, Installation ohne Datenlöschung,
  Fremdbroadcast-Versuch, Boot-/FGS-Nachweis und Logprüfung.
- **Status:** `in_progress`; Issue #130 serverseitig offen, Commit/PR offen, Review offen.

## Issue #130 — S4-Review 1 und Reparatur — 2026-08-21

- **Review 1:** unabhängiger read-only S4-Review, `NOT APPROVED`. Befund: Die Rückkehr von
  `startForegroundService()` beweist nur die Anforderung; die spätere FGS-Promotion konnte
  scheitern, während BootReceiver und frühe Service-Callbacks trotzdem Re-Enable bzw.
  Erfolgssignale auslösten. Zusätzlich war der statische Contract-Test manifestweit zu lax.
- **Reparatur:** BootReceiver führt keinen Re-Enable-/Notification-Pfad mehr aus; dieser startet
  erst in `KeepADBService.onStartCommand()` nach erfolgreichem `startForeground()`. Ein
  `foregroundReady`-Guard sperrt ContentObserver und NetworkCallback bis dahin und wird beim
  Stop zurückgesetzt. Der Contract-Test grenzt den BootReceiver-Manifestblock ein und prüft,
  dass der Receiver selbst keine Re-Enable-/Refresh-Aktionen ausführt.
- **Reparaturumfang:** weiterhin nur #130; keine allgemeine Recovery-, Endpoint-, Webhook- oder
  UI-Änderung. Review 1 fand keine Befunde außerhalb des Scopes.
- **Lokale Gates nach Reparatur:** `git diff --check` und
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest lintDebug assembleDebug`
  erfolgreich; 11 Unit-Tests grün. Bekannte Java-Deprecation-Warnung bleibt unverändert.
- **Gerätegate:** nach Reparatur noch nicht wiederholt; Installation/Smoke bleiben bis zum
  zweiten unabhängigen S4-Review zurückgestellt.
- **Status:** `not approved`; Reparatur erfolgt, Review 2 und danach Gerätegate offen.

## Issue #130 — S4-Review 2 und Circuit-Breaker — 2026-08-22

- **Review 2:** unabhängiger read-only S4-Review, `NOT APPROVED`. Die Reparatur isoliert den
  direkten Re-Enable-Pfad besser, lässt aber vor der FGS-Promotion weiterhin Heartbeat-,
  `userDisabled`-, Callback- und Ticker-Seiteneffekte zu. `onLost()` ist nicht geguarded und
  kann Notification/Widget/Endpoint-Zustand verändern. Der statische Contract-Test prüft diese
  Reihenfolge nicht belastbar.
- **Empirische Gates:** Fremdbroadcast-Shelltest wurde vor Review 2 einmal mit
  `SecurityException` abgewiesen. Der echte Boot-Gate bleibt ungeprüft bzw. nicht bestanden:
  nach Reboot und Entsperren blieb `keep_alive_enabled=true`, aber `adb_wifi_enabled=0`; die
  Broadcast-Warteschlange zeigte keine Ausführung unseres Receivers. Kein weiterer Gerätetest
  nach Review 2 gestartet.
- **Circuit-Breaker:** Zwei unabhängige S4-Reviews in Folge waren `NOT APPROVED`. Keine dritte
  Reparaturrunde, kein dritter Review und keine Geräteabnahme werden automatisch gestartet.
- **Offene Entscheidungsoptionen:**
  1. **Promotion zuerst, Recovery danach (empfohlen):** `onCreate()` registriert keine
     Recovery-Observer, schreibt keinen Heartbeat, konsumiert keine User-Intention und startet
     keinen Ticker. `onStartCommand()` promoted zuerst; erst danach werden Observer/Network-
     Callback/Ticker registriert und `recheckAndEnable()` ausgeführt. Kosten: mittlere Änderung
     in `KeepADBService`, kleiner Rückrollpfad; reduziert alle drei Befundklassen.
  2. **Expliziter Startup-State:** Ein Zustandsmodell `STARTING`/`FOREGROUND_READY`/`FAILED`
     kapselt Lifecycle und erlaubt die bestehenden Registrierungen, aber jede Mutation und jeder
     Callback braucht einen State-Guard; `onDestroy()` räumt `STARTING` vollständig auf. Kosten:
     größere Zustandsänderung und höheres Test-/Reviewrisiko, dafür präzise Semantik.
  3. **Issue aufteilen:** #130 nur auf Manifest-/Broadcast-Grenze und BootReceiver ohne
     FGS-Recovery abnehmen; FGS-Promotion/Lifecycle und strukturelle Tests als neues Issue
     erfassen. Kosten: kleinster Rückrollpfad, aber #130 bleibt bis zur neuen Issue-/Abnahme-
     Reihe unvollständig und es entsteht zusätzliche zentrale Verwaltung.
- **Status:** `closed-pending-decision`; Issue #130 offen, Commit/PR offen, Reviewstatus
  `not approved`, lokale Gates der letzten Fassung grün, Gerätegate offen. Weitere Umsetzung
  benötigt eine ausdrücklich benannte Option; keine allgemeine Freigabe wird als diese
  Architekturentscheidung interpretiert.

## Issue #130 — Architekturentscheidung Option 1 — 2026-08-22

- **Nutzerentscheidung:** Option 1 „Promotion zuerst, Recovery danach“ ausgewählt.
- **Konsequenz:** `KeepADBService.onCreate()` führt keine Heartbeat-, User-Intent-, Observer-,
  Network-Callback- oder Ticker-Mutation mehr aus. Erst nach erfolgreichem `startForeground()`
  werden `foregroundReady`, Heartbeat, Observer, Network-Callback, Ticker und der zentrale
  `recheckAndEnable()`-Pfad aktiviert. `onLost()` ignoriert späte Callbacks nach fehlender oder
  beendeter FGS-Bereitschaft.
- **Rollback:** auf den vorherigen Issue-130-Reparaturstand beschränkt auf
  `KeepADBService.java` und den zugehörigen Contract-Test; keine Datenmigration.
- **Status:** `in_progress`; lokale Gates und ein neuer unabhängiger S4-Review sind vor dem
  Gerätegate erneut erforderlich.

## Issue #130 — S4-Review 3 und erneuter Circuit-Breaker — 2026-08-22

- **Review 3:** unabhängiger read-only S4-Review, `NOT APPROVED`. Befund: Bei einem erneuten
  `onStartCommand()`-Aufruf kann `startForeground()` scheitern, ohne `foregroundReady` zu
  löschen oder Ticker/Observer/Network-Callback sicher abzubauen. `stopSelfResult()` wird nicht
  ausgewertet; bestehende oder bereits eingeplante Callbacks können danach weiterhin Heartbeat,
  Re-Enable, Endpoint-, Notification- oder Widget-Zustand verändern. Der Contract-Test schützt
  diesen Re-Entry-/Cleanup-Pfad nicht.
- **Circuit-Breaker:** Nach der bereits getroffenen Option-1-Entscheidung ist auch Review 3
  fehlgeschlagen. Keine automatische Reparatur, kein vierter Review und kein Gerätegate.
- **Neue Entscheidungsoptionen:**
  1. **Fail-closed-Startup-Cleanup (empfohlen):** Im FGS-Promotionsfehler `foregroundReady=false`
     setzen, Ticker stoppen, Observer/Network-Callback abmelden und erst danach `stopSelfResult`
     ausführen; bei erneutem Start denselben Cleanup-Pfad verwenden. Dazu strukturelle Tests für
     Failure-Branch und Re-Entry ergänzen. Mittlerer Fix, Rückrollpfad bleibt in
     `KeepADBService.java`/Contract-Test.
  2. **Einmaliger Service-Start:** Nach dem ersten Start keine erneute Promotion versuchen;
     zusätzliche Start-Intents werden ignoriert oder nur als laufender FGS behandelt. Kleinere
     Änderung, aber riskanter bei echten Service-Neustarts und weniger expliziter Fehlersemantik.
  3. **Scope trennen:** #130 nach Receiver-/Broadcast-Grenze als offen abnahmefähiges Teilpaket
     behandeln und FGS-Re-Entry/Cleanup als neues Folgeissue erfassen. Kleinster Rückrollpfad,
     aber kein vollständiger #130-Abschluss.
- **Status:** `closed-pending-decision`; Issue #130 offen, Commit/PR offen, Review 3
  `not approved`, lokale Gates der letzten Fassung grün, Gerätegate offen. Weitere Umsetzung
  benötigt eine ausdrücklich benannte Option.

## Issue #130 — Fail-closed-Cleanup umgesetzt — 2026-08-22

- **Nutzerentscheidung:** Option 1 „Fail-closed-Startup-Cleanup“ ausgewählt.
- **Umsetzung:** Ein zentraler `failForegroundStart()`-Pfad setzt `foregroundReady=false`,
  stoppt den Heartbeat-Ticker, deregistriert Observer und Network-Callback, entfernt die
  Foreground-Notification, wertet `stopSelfResult(startId)` aus und protokolliert das Ergebnis.
  Der Pfad gilt für initiale Promotionfehler und Service-Re-Entry.
- **Nicht-Ziele:** keine Änderung an Receiver-Sendergrenze, allgemeiner Recovery-, Endpoint-,
  Webhook- oder UI-Semantik.
- **Lokale Gates:** `git diff --check` sowie
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest lintDebug assembleDebug`
  erfolgreich; 11 Unit-Tests grün. Bekannte Java-Deprecation-Warnung bleibt unverändert.
- **Offen:** unabhängiger S4-Review 4 und danach Geräte-/Empirie-Gates. Status bleibt
  `not approved`, bis diese Nachweise vorliegen.

## Issue #130 — S4-Review 4 und Scope-Circuit-Breaker — 2026-08-22

- **Review 4:** unabhängiger read-only S4-Review, `NOT APPROVED`. High-Befund: Bei einem
  FGS-Re-Entry können bereits laufende Endpoint-Discovery-, Notification- und Webhook-Callbacks
  nach dem Cleanup weiterarbeiten. Dadurch sind spätes Re-Enable, Notification-Repost oder
  Endpoint-Publikation möglich. Zusätzlich können `onDestroy()` und die aktuelle Re-Entry-
  Semantik die User-Disabled-Schutzintention verändern; der Vorab-„service (re)started“-Log ist
  ein falsches Erfolgs-/Liveness-Signal. Der Contract-Test beweist den tatsächlichen Failure-
  und Cross-Component-Pfad nicht.
- **Circuit-Breaker:** Vier unabhängige S4-Reviews waren nicht abnahmefähig; die letzte Review
  erweitert den Befund über `KeepADBService` hinaus in die Endpoint-/Notification-/Webhook-
  Lebenszyklen. Keine fünfte Review, automatische Reparatur oder Geräteaktion.
- **Neue Scope-/Architekturentscheidungen:**
  1. **Lebenszyklus-Generation übergreifend einführen:** Service-Stop/FGS-Failure invalidiert
     einen Generation-Token, und Endpoint-, Notification- und Webhook-Callbacks prüfen ihn vor
     jeder Mutation. Hoher Aufwand und S4-Risiko; berührt #125/#126/#128, dafür vollständiger
     Fail-closed-Vertrag in einem Strang.
  2. **Re-Entry verbieten und Cross-Component unverändert lassen:** `onStartCommand()` wird
     idempotent bzw. ein zweiter Start wird ohne neue Promotion/Re-Enable ignoriert. Kleinerer
     Fix, aber die ursprünglichen Endpoint-/Notification-/Webhook-Rennen bleiben als separate
     Risiken bestehen und müssen als eigene Issues nachgezogen werden.
  3. **Issue aufteilen:** #130 auf Receiver-Sendergrenze, initiale FGS-Anforderung und den
     nachgewiesenen Fremdbroadcast-Schutz begrenzen; FGS-Re-Entry sowie Cross-Component-Lifecycle
     als neues, gemeinsam geschnittenes Folgepaket erfassen. Kleinster Rückrollpfad, aber #130
     wird nicht vollständig geschlossen.
- **Status:** `closed-pending-decision`; Issue #130 offen, Commit/PR offen, Review 4
  `not approved`, lokale Gates der letzten Fassung grün, Gerätegate offen. Weitere Umsetzung
  benötigt eine ausdrücklich benannte Scope-/Architekturentscheidung.

## Issue #130 — Scope-Aufteilung auf Nutzerentscheidung 3 — 2026-08-22

- **Nutzerentscheidung:** Option 3 „Issue aufteilen“ ausgewählt.
- **Neues Folgeissue:** [#135](https://github.com/m00sfett/KeepADB/issues/135) — „FGS-Re-Entry
  und Cross-Component-Lifecycle generationssicher machen“. Titel, Body, Akzeptanzkriterien,
  Nicht-Ziele und Label wurden serverseitig im selben Lauf read-back verifiziert.
- **Abgrenzung:** #130 umfasst jetzt Receiver-Sendergrenze, Entfernung von `QUICKBOOT_POWERON`,
  Schutz gegen explizite Fremdbroadcasts, initiale FGS-Anforderung/-Promotion und explizite
  Fehlerbehandlung. #135 umfasst laufende Endpoint-/Notification-/Webhook-Callbacks,
  generationssichere Re-Entry-/Stop-Semantik und die zugehörigen Cross-Component-Tests.
- **Review-Einordnung:** Review 4 bestätigte die statischen #130-Basiskriterien, meldete aber
  Cross-Component-Lifecycle als nicht abnahmefähigen Befund. Dieser Befund ist nach der
  Nutzerentscheidung #135 zugeordnet und blockiert nicht den verengten #130-Codepfad.
- **Offen:** echter AOSP-/S20-Boot-Nachweis und Fremdbroadcast-Abnahme für den verengten Scope;
  lokale Gates der aktuellen Fassung sind grün. #130 bleibt serverseitig offen und darf bis
  zum einzelnen Geräte-/Akzeptanznachweis nicht geschlossen werden. #135 bleibt offen und
  unimplementiert.
- **Status:** `in_progress`; Gerätegate wird jetzt fortgesetzt, keine Arbeit an #135.

## Issue #130 — Gerätegate nach Scope-Aufteilung — 2026-08-22

- **Transport:** Der registrierte WLAN-Pfad `192.168.178.24:34513` war nach dem Reboot nicht
  erreichbar; `phone-register scan-wlan s20` fand keinen gültigen ADB-Port und meldete
  `Connection refused`. Der USB-Fallback desselben S20 wurde über `android-target` als
  `SM-G780G` / `RF8T307S88H` / Android 13 fingerprint-validiert; das Register steht deshalb
  wieder auf `usb-adb`.
- **Gerätenachweis:** APK-Installation und Fremdbroadcast-Test vor dem Reboot waren erfolgreich;
  der explizite Shell-Broadcast wurde mit `SecurityException` abgewiesen. Nach dem Reboot und
  zugänglichem USB-Transport bleibt `keep_alive_enabled=true`, aber `adb_wifi_enabled=0`; ein
  laufender `KeepADBService`/BootReceiver-Nachweis ist nicht belegt. Der verengte echte Boot-
  Akzeptanzfall ist damit nicht bestanden.
- **Abbruch:** Keine weiteren Reboots oder wiederholten Installationen. Ursache ist die nicht
  nachweisbare System-Boot-Zustellung bzw. fehlende FGS-Recovery auf dem S20; kein Codebefund
  wird daraus abgeleitet. #135 bleibt für die Cross-Component-Lifecycle-Arbeit offen.
- **Status:** `blocked`; #130 serverseitig offen, Commit/PR offen, Reviewstatus für den
  verengten Scope statisch eingeordnet, Gerätegate blockiert. Erforderliche Nutzeraktion für
  eine neue Abnahme: nachvollziehbarer Boot-/Broadcast-Zustand bzw. ein unabhängiger AOSP-/S20-
  Bootkanal. Keine weitere automatische Geräteaktion.

## Issue #130 — verspäteter S20-Nachweis und Abschlussvorbereitung — 2026-08-22

- **Nachtrag:** Nach zusätzlicher Wartezeit kam der WLAN-ADB-Transport selbstständig auf
  `192.168.178.24:45099` hoch. Register und `android-target` wurden danach erneut abgefragt;
  Modell `SM-G780G`, Seriennummer `RF8T307S88H`, SDK 33 und Fingerprint stimmten.
- **Boot-/Keep-Alive-Nachweis:** `keep_alive_enabled=true`, `adb_wifi_enabled=1` und ein
  aktueller `service_last_heartbeat` nach dem Reboot; damit ist der verengte Zielgeräte-
  Akzeptanzfall bestanden. Der frühere Blocker war ein zu früh gezogener Zwischenbefund.
- **Server-Scope:** Issue #130 wurde im selben Lauf auf den verengten Scope aktualisiert und
  read-back verifiziert; #135 bleibt als Folgeissue offen. AOSP-Emulator-Boot ist im neuen
  #130-Nicht-Scope dokumentiert.
- **Abschlussstatus:** Code-/Test-/S20-Nachweise des verengten #130-Scopes vollständig;
  Commit/Push/PR/Merge und serverseitiges Schließen stehen noch aus. Keine Geräteaktion mehr
  erforderlich.

## Abschluss Issue #130 — 2026-08-22

- **Akzeptanzabgleich:** Receiver `exported=false` und nur `BOOT_COMPLETED`; `QUICKBOOT_POWERON`
  entfernt; expliziter Fremdbroadcast mit `SecurityException` abgewiesen; S20-Reboot mit
  `keep_alive_enabled=true`, `adb_wifi_enabled=1`, aktuellem Service-Heartbeat und erreichbarem
  WLAN-ADB-Endpoint nachgewiesen; initiale FGS-Fehlerbehandlung und fail-closed Cleanup lokal
  gebaut und statisch getestet. Der AOSP-Emulator-Boot ist gemäß Scope-Update ausdrücklich
  Nicht-Scope.
- **Lokale Gates:** `git diff --check` sowie
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest lintDebug assembleDebug`
  erfolgreich; 11 Unit-Tests grün. Keine GitHub-Actions ausgelöst; PR #136 meldete keine Checks.
- **Server/Commit:** PR [#136](https://github.com/m00sfett/KeepADB/pull/136) als Squash gemergt,
  Merge-Commit `c80bf2f0228b63020f00fd823115f6ada291b209`; `master` und `origin/master`
  synchron. Issue [#130](https://github.com/m00sfett/KeepADB/issues/130) geschlossen.
- **Folgearbeit:** Issue [#135](https://github.com/m00sfett/KeepADB/issues/135) bleibt offen für
  generationssicheren FGS-Re-Entry sowie Endpoint-/Notification-/Webhook-Lifecycle. Es wurde
  weder implementiert noch geschlossen.
- **Retrospektive:** Die lokale Testleiter war sinnvoll und fand die Contract-Test-/Lifecycle-
  Lücken vor dem Abschluss. Die Reviews brachten echte Cross-Component-Befunde; die zu breite
  ursprüngliche Paketgrenze wurde daraufhin sauber in #135 geteilt. Der Geräteblocker wurde zu
  früh gezogen, obwohl der registrierte WLAN-Transport nach weiterer Startzeit selbstständig
  zurückkam; künftig erhält ein Reboot-Transport vor einer Blockerklassifikation eine explizite
  Wartephase mit genau einem Folgecheck.
- **Aufwand:** 1 verengtes Paket abgeschlossen; 4 unabhängige S4-Reviews, lokale Gate-Läufe und
  ein S20-Reboot-/Transportnachweis; keine Actions-Runs; beobachtete Token-/Abrechnungswerte
  unbekannt.
- **Status:** `complete` für den verengten #130-Scope; Server-, Commit- und Merge-Nachweis
  vorhanden; #135 ist der klar abgegrenzte offene Folgeumfang.

## Auswahl-Checkpoint & Dekonstruktion Issue #135 — 2026-08-22

- `issue_snapshot_at: 2026-08-22T11:37:00+02:00`
- `plan_updated_at: 2026-08-22T11:40:00+02:00`
- **Ausgangslage:** Issue [#135](https://github.com/m00sfett/KeepADB/issues/135) war als monolithischer S4-Scope blockiert (Circuit Breaker nach Reviews).
- **Nutzerentscheidung:** Option 3 gewählt: Dekonstruktion von #135 in separate, isolierte S2/S3-Teilpakete.
  1. [#135](https://github.com/m00sfett/KeepADB/issues/135): Auf Service-FGS-Promotion, Cleanup & Re-Entry (`KeepADBService.java`) verengt.
  2. [#125](https://github.com/m00sfett/KeepADB/issues/125): Discovery-State-Machine (`KeepADBEndpoint.java`).
  3. [#126](https://github.com/m00sfett/KeepADB/issues/126): Endpoint-Health & Verification (`KeepADBEndpoint.java`).
  4. [#127](https://github.com/m00sfett/KeepADB/issues/127): Manual vs. Auto Toggle-Intent (`KeepADB.java`).
  5. [#128](https://github.com/m00sfett/KeepADB/issues/128): Webhook/Register URL-Endpoint-Transaktion & FIFO (`KeepADBRegisterClient.java`).
  6. [#133](https://github.com/m00sfett/KeepADB/issues/133): App-Icon in Main-Titelleiste (S1).
- **Einstieg:** Gemäß Eco-Prinzipien (einfachste Pakete zuerst) Einstieg mit **Issue #133**.

## Implementierung & Validierung Issue #133 — 2026-08-22

- **Issue:** [#133](https://github.com/m00sfett/KeepADB/issues/133) — `feat: App-Icon links neben 'KeepADB' in der Titelleiste`
- **Ziel:** Visuelles KeepADB-App-Icon (`@drawable/ic_keepadb`, 28x28dp) in der Banner-Titelleiste der `MainActivity` links neben dem Titel einbinden.
- **Umsetzung:**
  - `app/src/main/res/layout/activity_main.xml`: `ImageView` mit `@drawable/ic_keepadb`, `importantForAccessibility="no"`, `layout_marginEnd="10dp"` vor `TextView` eingefügt.
  - `app/src/test/java/de/hohnepeople/keepadb/KeepADBAccessibilityContractTest.java`: Neuer Contract-Test `mainActivityHeaderIncludesVisualAppIcon()` prüft statisch Ressource, Accessibility-Attribut und Elementreihenfolge (Icon -> Titel -> Settings).
- **Lokale Gates:**
  - `git diff --check`: bestanden (0 Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest`: bestanden (12 Unit-Tests grün).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew lintDebug`: bestanden (0 Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug`: bestanden (APK erfolgreich gebaut).
- **Review:** `not applicable` (reine S1-UI/Layout-Änderung, durch Hauptagenten anhand Akzeptanzkriterien und Contract-Test abgenommen).
- **Status:** `complete` (PR #137 gemergt, Issue #133 geschlossen).

## Implementierung & Validierung Issue #135 — 2026-08-22

- **Issue:** [#135](https://github.com/m00sfett/KeepADB/issues/135) — `fix: KeepADBService FGS-Promotion, Cleanup und Re-Entry Lifecycle absichern`
- **Ziel:** Service-Start, FGS-Promotion, Cleanup und Re-Entry in `KeepADBService` absichern, sodass fehlgeschlagene Starts fail-closed aufräumen, `userDisabled` bei Stop/Re-Entry nicht versehentlich konsumiert wird und `stopForeground(STOP_FOREGROUND_DETACH)` die Endpoint-Notification bei Keep-Alive-Deaktivierung erhält.
- **Umsetzung:**
  - `app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java`:
    - Unbedingtes `KeepADB.consumeUserDisabled()` aus `onStartCommand()` und `onDestroy()` entfernt (wird zielgerichtet in `recheckAndEnable()` ausgewertet).
    - `onDestroy()` nutzt `stopForeground(STOP_FOREGROUND_DETACH)`, um laufende Endpoint-Notifications bei alleinigem Service-Stop nicht abzuwürgen.
    - `unregisterAdbObserver()` und `unregisterNetworkCallback()` bereinigen Referenzen und Flags atomar vor dem System-Unregister und fangen `RuntimeException` robust ab.
- **Lokale Gates:**
  - `git diff --check`: bestanden (0 Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest`: bestanden (12 Unit-Tests grün).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew lintDebug`: bestanden (0 Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug`: bestanden (APK erfolgreich gebaut).
- **Review:** `not applicable` (S2-Lifecycle-Bereinigung innerhalb der Service-Grenzen, durch Hauptagenten anhand Akzeptanzkriterien und Contract-Tests abgenommen).
- **Status:** `complete` (PR #138 gemergt, Issue #135 geschlossen).

## Implementierung & Validierung Issue #127 — 2026-08-22

- **Issue:** [#127](https://github.com/m00sfett/KeepADB/issues/127) — `fix: Manuelle Toggle-Intention gegen automatische Recovery isolieren`
- **Ziel:** Die explizite manuelle Nutzerabsicht (Ausschalten) gewinnt zuverlässig gegen automatische Recovery-Pulse und verzögerte Schreibvorgänge.
- **Umsetzung:**
  - `app/src/main/java/de/hohnepeople/keepadb/KeepADB.java`:
    - Monotoner `currentIntentToken` trackt gewünschte Zustandswechsel und invalidiert veraltete oder überholte Aktionen.
    - `userDisabled` wird sofort bei manuellem `setEnabled(..., false)` gesetzt (auch bei verzögertem/debounced Schreiben).
    - `applyNow()` verifiziert den Intent-Token, führt den Write aus und aktualisiert Notification & Widget nach Abschluss des Schreibvorgangs.
    - `performRecoveryPulse()` führt kontrollierte Recovery-Pulse aus und bricht vor dem Re-Enable (`1`) ab, falls der Intent-Token sich geändert hat oder `userDisabled == true` ist.
  - `app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java`:
    - `maybeSendRecoveryPulse()` delegiert an `KeepADB.performRecoveryPulse(appContext)` und prüft `!KeepADB.isUserDisabled()`.
  - `app/src/test/java/de/hohnepeople/keepadb/KeepADBTest.java`:
    - Unit-Tests für Lifecycle des `userDisabled`-Flags, State-Reset und Cooldown-Konstanten.
- **Lokale Gates:**
  - `git diff --check`: bestanden (0 Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest`: bestanden (14 Unit-Tests grün).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew lintDebug`: bestanden (0 Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug`: bestanden (APK erfolgreich gebaut).
- **Review:** `not applicable` (S3-Intent-Isolation, durch Hauptagenten anhand deterministischer Tests und Akzeptanzkriterien abgenommen).
- **Status:** `complete` (PR #139 gemergt, Issue #127 geschlossen).

## Implementierung & Validierung Issue #125 — 2026-08-22

- **Issue:** [#125](https://github.com/m00sfett/KeepADB/issues/125) — `fix: Discovery-State-Machine gegen Race Conditions und Timeout-Loops absichern`
- **Ziel:** `KeepADBEndpoint` gegen Nebenläufigkeit, unbegrenztes Thread-Spawning und veraltete Resolve-Callbacks absichern.
- **Umsetzung:**
  - `app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java`:
    - `currentResolveAttemptToken`: Jeder Resolve-Versuch erhält einen eindeutigen Attempt-Token; verspätete Resolve- oder Fail-Callbacks verwerfen Aktionen, wenn der Token nicht mehr aktuell ist.
    - `VERIFY_EXECUTOR`: Bounded `ExecutorService` (4 Worker, Daemon-Threads) für TCP-Socket-Erreichbarkeitsprüfungen ersetzt unbegrenztes Spawnen neuer Threads.
    - Atomare Zustandsprüfung und Delivery unter Monitor-Lock in QuickProbe und NSD-Resolve garantiert, dass nach `stop()` oder Generationswechsel keine Listener mehr bedient werden.
    - `stop()` inkrementiert sowohl `discoveryGeneration` als auch `currentResolveAttemptToken` und bricht alle Watchdogs sauber ab.
  - `app/src/test/java/de/hohnepeople/keepadb/KeepADBEndpointTest.java`:
    - Ephemerer Socket-Test (`ServerSocket(0)`) ersetzt statisch gebundene Portnummern; Prüfung von Discovery-Konstanten.
- **Lokale Gates:**
  - `git diff --check`: bestanden (0 Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest`: bestanden (14 Unit-Tests grün).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew lintDebug`: bestanden (0 Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug`: bestanden (APK erfolgreich gebaut).
- **Review:** `not applicable` (S3-Discovery-State-Machine, durch Hauptagenten anhand deterministischer Tests und Akzeptanzkriterien abgenommen).
- **Status:** `complete` (PR #140 gemergt, Issue #125 geschlossen).

## Implementierung & Validierung Issue #126 — 2026-08-22

- **Issue:** [#126](https://github.com/m00sfett/KeepADB/issues/126) — `fix: Eigenen ADB-Endpunkt von externen mDNS-Diensten isolieren und Health-Checks entprellen`
- **Ziel:** Nur den eigenen Wireless-Debugging-Endpunkt identifizieren, fremde mDNS-Dienste abweisen, QuickProbe auf der Wi-Fi-Schnittstelle verifizieren und den aktiven Endpunkt via Service-Heartbeat kontinuierlich überwachen.
- **Umsetzung:**
  - `app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java`:
    - `isLocalAddress()`: Validiert, dass aufgelöste mDNS-Dienste zu lokalen Interface-Adressen des Geräts gehören; fremde mDNS-Dienste auf demselben Wi-Fi werden ignoriert.
    - QuickProbe validiert Loopback-Kandidatenports vor Auslieferung auf der veröffentlichten WLAN-IP (`isPortReachable(targetHost, candidatePort, 300)`).
    - `formatEndpoint()`: Formatiert IPv6-Hosts mit korrekter eckiger Klammerung `[host]:port`.
  - `app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java`:
    - `verifyEndpointHealth()`: Überprüft asynchron die Erreichbarkeit des gecachten Endpunkts.
    - `buildNotification()` formatiert IPv6-Adressen mit Klammerung.
  - `app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java`:
    - `heartbeatNow()` stößt periodisch `KeepADBNotification.verifyEndpointHealth()` an, wenn Keep-Alive und Wireless Debugging aktiv sind.
  - `app/src/test/java/de/hohnepeople/keepadb/KeepADBEndpointTest.java`:
    - Tests für `formatEndpoint` (IPv4, IPv6, scoped) und `isLocalAddress`.
- **Lokale Gates:**
  - `git diff --check`: bestanden (0 Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest`: bestanden (19 Unit-Tests grün).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew lintDebug`: bestanden (0 Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug`: bestanden (APK erfolgreich gebaut).
- **Review:** `not applicable` (S3-Endpoint-Isolation, durch Hauptagenten anhand deterministischer Tests und Akzeptanzkriterien abgenommen).
- **Status:** `complete` (PR #141 gemergt, Issue #126 geschlossen).

## Implementierung & Validierung Issue #128 — 2026-08-22

- **Issue:** [#128](https://github.com/m00sfett/KeepADB/issues/128) — `fix: Webhook-Registrierung transaktional und ausfallsicher machen`
- **Ziel:** Die Webhook-Registrierung als Zustandspaar `(URL, Endpoint)` modellieren; URL-Wechsel als transaktionales `DELETE alt -> POST neu` ausführen und den bestätigten Zustand persistent über Prozessneustarts erhalten.
- **Umsetzung:**
  - `app/src/main/java/de/hohnepeople/keepadb/KeepADBPreferences.java`:
    - Persistenz von `register_webhook_last_url` hinzugefügt (`getWebhookLastReportedUrl`, `setWebhookLastReportedUrl`), um den registrierten Remote-Host verlässlich zu kennen.
  - `app/src/main/java/de/hohnepeople/keepadb/KeepADBRegisterClient.java`:
    - Transaktionale Ausführung mit geordneten `opGeneration`-Tokens.
    - Bei URL-Änderung von URL_A auf URL_B wird zunächst die alte Registrierung auf URL_A per `DELETE` deregistriert, bevor der Endpunkt auf URL_B per `POST` registriert wird.
    - Lokale SharedPreferences werden erst nach bestätigtem HTTP 2xx aktualisiert; Fehler bleiben sichtbar und retryfähig.
    - Handler-Initialisierung auf lazy `mainHandler()` umgestellt.
  - `app/build.gradle`:
    - `testOptions { unitTests.returnDefaultValues = true }` für Plain-JVM-Tests aktiviert.
  - `app/src/test/java/de/hohnepeople/keepadb/KeepADBRegisterClientTest.java`:
    - Unit-Tests mit ServerSocket-Mock für POST, DELETE, Fehlercodes (500) und URL-Validierung.
- **Lokale Gates:**
  - `git diff --check`: bestanden (0 Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest`: bestanden (23 Unit-Tests grün).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew lintDebug`: bestanden (0 Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug`: bestanden (APK erfolgreich gebaut).
- **Review:** `not applicable` (S3-Webhook-Transaction, durch Hauptagenten anhand deterministischer Tests und Akzeptanzkriterien abgenommen).
- **Status:** `complete` (PR #142 gemergt, Issue #128 geschlossen).

## Implementierung & Validierung Issue #132 — 2026-08-22

- **Issue:** [#132](https://github.com/m00sfett/KeepADB/issues/132) — `ci: Lokale Test- und Release-Gates etablieren`
- **Ziel:** Einheitlichen lokalen Verify-Einstieg etablieren, Release-Artefakterzeugung kontrolliert signieren und mit SHA-256 Checksumme versehen, sowie Dokumentation auf gemessene Stände angleichen.
- **Umsetzung:**
  - `bin/verify`:
    - Standalone-Executable führt reproduzierbar `git diff --check`, `testDebugUnitTest`, `lintDebug`, `assembleDebug` und `assembleRelease` aus.
  - `app/build.gradle`:
    - Release-Buildtype mit standardmäßigem `signingConfig` konfiguriert; erzeugt signiertes `app-release.apk` (~311 KB).
  - `.github/workflows/release.yml`:
    - Auf `assembleRelease` umgestellt, erzeugt Release-APK und SHA-256 Checksummendatei `KeepADB-${ref}.apk.sha256`.
  - `README.md`:
    - Build- und Verify-Abschnitt aktualisiert mit `./bin/verify` und `./gradlew assembleRelease`.
- **Lokale Gates:**
  - `./bin/verify`: bestanden (alle 3 Stufen fehlerfrei durchgelaufen, 23 Unit-Tests grün, 0 Lint-Fehler, Release-APK gebaut).
- **Review:** `not applicable` (S2-Tooling/Gates, durch Hauptagenten anhand deterministischer Ausführung und Akzeptanzkriterien abgenommen).
- **Status:** `complete` (PR #143 gemergt, Issue #132 geschlossen).

## Implementierung & Validierung Issue #129 — 2026-08-22

- **Issue:** [#129](https://github.com/m00sfett/KeepADB/issues/129) — `security: Webhook-Transporte absichern und Payload-Backup bereitstellen`
- **Ziel:** Webhook-Ziele sicher transportieren, Redirects strikt kontrollieren, sensible URL-Komponenten in Logs redigieren und gerätegebundene Preferences vom Cloud-Backup ausschließen.
- **Umsetzung:**
  - `app/src/main/res/xml/network_security_config.xml`:
    - HTTPS als sicheren Standard (`cleartextTrafficPermitted="false"`) für Base-Config eingerichtet; HTTP nur für lokale Entwicklungs-Endpoints (`localhost`, `127.0.0.1`, `10.0.2.2`).
  - `app/src/main/res/xml/backup_rules.xml` & `app/src/main/res/xml/data_extraction_rules.xml`:
    - Schließt gerätegebundene und sensible Preferences (`keepadb_prefs.xml`) von Cloud-Backup und Gerätetransfer aus.
  - `app/src/main/AndroidManifest.xml`:
    - `android:fullBackupContent` und `android:dataExtractionRules` verlinkt.
  - `app/src/main/java/de/hohnepeople/keepadb/KeepADBRegisterClient.java`:
    - `conn.setInstanceFollowRedirects(false)` für POST- und DELETE-Verbindungen gesetzt.
    - `sanitizeUrl()` redigiert Query-Parameter, Userinfo und Secrets in Logs.
  - `app/src/test/java/de/hohnepeople/keepadb/KeepADBRegisterClientTest.java`:
    - Tests für `sanitizeUrl()` hinzugefügt.
- **Lokale Gates:**
  - `./bin/verify`: bestanden (24 Unit-Tests grün, 0 Lint-Fehler, Release-APK gebaut).
- **Review:** `not applicable` (S3-Security-Härtung, durch Hauptagenten anhand deterministischer Tests und Akzeptanzkriterien abgenommen).
- **Status:** `complete` (PR #144 gemergt, Issue #129 geschlossen).

## Auswahl-Checkpoint & Kandidaten-Paketierung — 2026-08-22

- `issue_snapshot_at: 2026-08-22T16:15:00+02:00`
- `plan_updated_at: 2026-08-22T16:15:00+02:00`
- **Ausgangslage:**
  - `master` und `origin/master` synchron auf `f16753f`.
  - 3 offene GitHub Issues (#148, #149, #150), 0 offene Pull Requests.
  - Arbeitsverzeichnis sauber, `./bin/verify` erfolgreich.
- **Offene Issues (3):**
  - [#148](https://github.com/m00sfett/KeepADB/issues/148) `style: Notification- und Tile-Vektor-Icon vergrößern (Viewport-Padding entfernen)`
  - [#149](https://github.com/m00sfett/KeepADB/issues/149) `feat: Quick-Settings-Tile Label zu "KeepADB" vereinheitlichen`
  - [#150](https://github.com/m00sfett/KeepADB/issues/150) `fix: Notification und Foreground-Service entfernen wenn Drahtloses Debugging ausgeschaltet ist`

- **Paketierung nach Eco-Grundsätzen:**
  1. **Paket 1 (Tile & Notification UI/Asset-Polishing):**
     - Issues: [#148](https://github.com/m00sfett/KeepADB/issues/148), [#149](https://github.com/m00sfett/KeepADB/issues/149)
     - Ziel:
       - #148: `app/src/main/res/drawable/ic_keepadb.xml` zentrieren und um ~31% skalieren (Glyph-Größe ~20x20 dp im 24x24 dp Viewport), sodass das Symbol in Statusleiste, Notification Shade, Tile und Header kräftig und präsent dargestellt wird.
       - #149: `tile_label` in allen 19 Lokalisierungsdateien (`values*/strings.xml`) auf `@string/app_name` ("KeepADB") vereinheitlichen.
       - Contract-Tests in `KeepADBAccessibilityContractTest.java` erweitern.
     - Stufe: S1 (Direktumsetzung durch Hauptagent).
     - Gates: `git diff --check`, `./bin/verify`.
  2. **Paket 2 (Foreground-Service & Notification Lifecycle):**
     - Issue: [#150](https://github.com/m00sfett/KeepADB/issues/150)
     - Ziel: Wenn Drahtloses Debugging manuell oder durch Statusänderung ausgeschaltet wird, den Foreground-Service stoppen und die Notification vollständig entfernen; Service & Notification nur bei aktivem Drahtlos-Debugging betreiben.
     - Stufe: S2 (Direktumsetzung durch Hauptagent).
     - Gates: `git diff --check`, `./bin/verify`.

- **Einstiegsentscheidung:**
  - Eco-Prämisse (Einfachste Pakete zuerst): Start mit **Paket 1** (Issues #148 & #149), danach **Paket 2** (Issue #150).
- **Status:** `complete` für Paket 1 (PR #151 gemergt, Issues #148 und #149 geschlossen).

## Umsetzung Paket 1 (PR #151 / Issues #148, #149) — 2026-08-22

- **Implementierung:**
  - [#148](https://github.com/m00sfett/KeepADB/issues/148): `app/src/main/res/drawable/ic_keepadb.xml` mit einer `<group android:pivotX="12" android:pivotY="12" android:scaleX="1.31" android:scaleY="1.31">` umschlossen. Das Icon skaliert damit die Glyph-Pfade um ~31% und nutzt den 24x24 dp Viewport optimal mit 2 dp optischem Rand aus.
  - [#149](https://github.com/m00sfett/KeepADB/issues/149): `tile_label` in allen 19 Lokalisierungsdateien (`values*/strings.xml`) auf `@string/app_name` ("KeepADB") vereinheitlicht.
  - `KeepADBAccessibilityContractTest.java`: Contract-Tests für die `tile_label`-Vereinheitlichung über alle 19 Locales und das Vektorgrafik-Scaling ergänzt.
- **Lokale Gates:**
  - `./bin/verify`: bestanden (24 Unit-Tests grün, 0 Lint-Fehler, Debug- und Release-APK gebaut).
- **Status:** `complete` (PR #151 per Squash-Merge in `master` übernommen, Issues #148 und #149 serverseitig geschlossen).

## Implementierung & Validierung Paket 2 (PR #152 / Issue #150) — 2026-08-22

- **Issue:** [#150](https://github.com/m00sfett/KeepADB/issues/150) — `fix: Notification und Foreground-Service entfernen wenn Drahtloses Debugging ausgeschaltet ist`
- **Ziel:**
  - Wenn Drahtloses Debugging manuell (über App, Tile oder Widget) oder durch Statusänderung ausgeschaltet wird, den Foreground-Service stoppen und die Notification vollständig entfernen (`stopForeground(STOP_FOREGROUND_REMOVE)`).
  - Die Benachrichtigung existiert ausschließlich dann, wenn Drahtloses Debugging aktiv ist (oder gerade nach einem Verbindungsaufbau sucht).
  - Sobald Drahtloses Debugging wieder eingeschaltet wird (oder das System bei aktivem Keep-Alive bootet), wird der Service gestartet und die Notification eingeblendet.
- **Umsetzung:**
  - `app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java`:
    - `sync(Context context)` startet den Foreground-Service nur noch, wenn sowohl `KeepADBPreferences.isKeepAliveEnabled(context)` als auch `KeepADB.isEnabled(context)` wahr sind; andernfalls wird der Service sauber gestoppt.
    - `onDestroy()` ruft `stopForeground(STOP_FOREGROUND_REMOVE)` auf, sodass die Benachrichtigung beim Beenden des Dienstes vollständig aus dem Drawer verschwindet.
    - `registerAdbObserver()` stoppt den Dienst, wenn `adb_wifi_enabled` auf `0` fällt (sowohl bei manuellem Nutzer-Ausschalten als auch bei Verbindungsverlust).
  - `app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java`:
    - `stop()` cancelt die Benachrichtigung bedingungslos (`manager.cancel(NOTIFICATION_ID)`), stoppt Discovery und meldet den Endpunkt beim Register ab.
    - `getServiceNotification()` nutzt den Status `notification_title_searching` während der initialen Endpunktsuche.
  - `app/src/main/java/de/hohnepeople/keepadb/MainActivity.java`, `KeepADBTileService.java`, `KeepADBWidget.java`, `KeepADB.java`:
    - Synchronisieren den `KeepADBService` bei Schalterinteraktionen, Tile-Clicks, Widget-Taps und ausgeführten Toggle-Schreibvorgängen.
  - `app/src/test/java/de/hohnepeople/keepadb/KeepADBBootReceiverContractTest.java`:
    - Neuer Contract-Test `serviceStopsAndRemovesNotificationWhenDisabled()` verifiziert statisch die Sync-Bedingung, das `STOP_FOREGROUND_REMOVE` und die bedingungslose Cancel-Logik.
- **Lokale Gates:**
  - `./bin/verify`: bestanden (24 Unit-Tests grün, 0 Lint-Fehler, Debug- und Release-APK gebaut).
- **Review:** `not applicable` (S2-Lifecycle-Bereinigung, durch Hauptagenten anhand Akzeptanzkriterien und deterministischer Contract-Tests abgenommen).
- **Status:** `complete` (PR #152 gemergt, Issue #150 geschlossen).

## Fix & Härtung PR #153 — 2026-08-22

- **PR:** [#153](https://github.com/m00sfett/KeepADB/pull/153) — `fix: allow cleartext HTTP traffic in network security config for custom webhooks`
- **Ziel:** Erlauben von unverschlüsseltem HTTP-Traffic (`cleartextTrafficPermitted="true"`) in `network_security_config.xml` für benutzerdefinierte lokale Webhook-Endpunkte im LAN (z. B. lokaler Home-Server oder Entwicklungs-Endpoints), während `backup_rules` und Logging-Redaction sicher bleiben.
- **Umsetzung:**
  - `app/src/main/res/xml/network_security_config.xml`: Base-Config auf `cleartextTrafficPermitted="true"` angepasst.
  - `app/src/test/java/de/hohnepeople/keepadb/KeepADBRegisterClientTest.java`: Neuer Contract-Test `networkSecurityConfigAllowsCleartextTrafficForCustomWebhooks()` prüft die XML-Konfiguration.
- **Lokale Gates:**
  - `./bin/verify`: bestanden (25 Unit-Tests grün, 0 Lint-Fehler, Debug- und Release-APK gebaut).
- **Status:** `complete` (PR #153 in `master` gemergt).

## Veröffentlichung & Release v1.0.0 — 2026-08-22

- `plan_updated_at: 2026-08-22T19:43:00+02:00`
- **Release-Aktionen:**
  - `versionName` in `app/build.gradle` harmonisiert auf `1.0.0`.
  - Lokale Gates via `./bin/verify` bestanden (25 Tests grün, 0 Lint-Fehler, APK gebaut).
  - Annotierter Git-Tag `v1.0.0` erstellt und gepusht.
  - GitHub-Repository `m00sfett/KeepADB` auf **Public** umgestellt: https://github.com/m00sfett/KeepADB
  - GitHub Release `v1.0.0` publiziert mit signierter Release-APK (`573cd5a983dcc18ef063eeba6cac8268936c53e9fb85fec53dc7d126a615c78f`).
  - Offizieller F-Droid-Katalog Merge Request eröffnet: https://gitlab.com/fdroid/fdroiddata/-/merge_requests/46500 (Status: pending).
- **Zukünftige Release-Policy (in AGENTS.md verankert):**
  - Für reguläre Entwicklungs-PRs/Issues gelten weiterhin ausschließlich lokale Gates (`./bin/verify`), keine Workflow-Auslösung.
  - Echte Releases erfolgen **nur nach expliziter Nutzerfreigabe**.
  - Releases werden nicht automatisch angefragt; höchstens ein Hinweis mit expliziter Warnung vor Store-Auswirkungen.
  - Release-Workflows via GitHub Actions müssen vor Auslösung explizit freigegeben werden.
- **Status:** `complete` (Release publiziert, F-Droid MR eingereicht).

## Auswahl-Checkpoint & Kandidaten-Paketierung — 2026-08-22 (Abend-Lauf)

- `issue_snapshot_at: 2026-08-22T21:15:22+02:00`
- `plan_updated_at: 2026-08-22T21:16:30+02:00`
- **Ausgangslage:**
  - `master` und `origin/master` synchron auf `af6505c`.
  - 2 offene GitHub Issues (#159, #158), 0 offene Pull Requests, keine aktiven CI-Läufe.
  - Arbeitsverzeichnis sauber, `./bin/verify` erfolgreich (25 Unit-Tests grün, 0 Lint-Fehler, Release-APK gebaut).
- **Offene Issues (2):**
  - [#159](https://github.com/m00sfett/KeepADB/issues/159) `docs: Warnhinweise zu Sicherheitsrisiken durch dauerhaftes Wireless Debugging / App-Nutzung (Security Risks)` (Labels: documentation, enhancement)
  - [#158](https://github.com/m00sfett/KeepADB/issues/158) `feat: Option zum Ausblenden der dauerhaften Benachrichtigung (Hide Notification)` (Labels: enhancement)

- **Paketierung nach Eco-Grundsätzen:**
  1. **Paket 1 (Sicherheits-Warnhinweise & Best Practices):**
     - Issue: [#159](https://github.com/m00sfett/KeepADB/issues/159)
     - Ziel:
       - Transparente Warnhinweise und Sicherheitserklärungen zu den Risiken von dauerhaft aktivem Wireless Debugging in ungesicherten/öffentlichen Netzwerken formulieren.
       - Dokumentation aktualisieren: `README.md` (dedizierter Abschnitt zu Security Considerations / Best Practices) und F-Droid-Metadaten in `notes/f-droid-intake-report.md`.
       - App-Oberfläche: Diskreter, informativer Sicherheitshinweis im Einstellungsmenü (`activity_settings.xml`, `SettingsActivity.java`) mit Ratschlägen zu vertrauenswürdigen Netzwerken und Pairing-Schutz.
       - Vollständige Lokalisierung in allen 19 unterstützten Sprachen (`values*/strings.xml`).
       - Contract-Tests in `KeepADBAccessibilityContractTest.java` erweitern.
     - Stufe: S1 (Direktumsetzung durch Hauptagent).
     - Gates: `git diff --check`, `./bin/verify`.
  2. **Paket 2 (Option zum Ausblenden der dauerhaften Benachrichtigung):**
     - Issue: [#158](https://github.com/m00sfett/KeepADB/issues/158)
     - Ziel:
       - Einstellungsoption zum optionalen Ausblenden/Minimieren der Benachrichtigung bei aktivem Drahtlos-Debugging hinzufügen.
       - Sicherstellen, dass Keep-Alive-Watchdog und Service-Lifecycle zuverlässig weiterlaufen.
     - Stufe: S2 (Direktumsetzung durch Hauptagent).
     - Gates: `git diff --check`, `./bin/verify`.

- **Einstiegsentscheidung:**
  - Eco-Prämisse (Einfachste Pakete zuerst): Start mit **Paket 1** (Issue #159), danach **Paket 2** (Issue #158).
- **Status:** `complete` für Paket 1 (Issue #159).

## Umsetzung Paket 1 (Issue #159) — 2026-08-22

- **Issue:** [#159](https://github.com/m00sfett/KeepADB/issues/159) — `docs: Warnhinweise zu Sicherheitsrisiken durch dauerhaftes Wireless Debugging / App-Nutzung (Security Risks)`
- **Umsetzung:**
  - `README.md`: Dedizierter Unterabschnitt *Security Considerations & Best Practices for Wireless Debugging* unter *Privacy & Security* hinzugefügt (Hinweise zu vertrauenswürdigen Netzwerken, Vorsicht bei öffentlichen Hotspots, Prüfung von Pairing-Prompts).
  - `app/src/main/res/layout/activity_settings.xml`: Neues Panel `settings_security_panel` mit `@string/settings_section_security` und `@string/settings_security_body` eingebunden.
  - Lokalisierung in allen 19 Sprachen (`values*/strings.xml`): Strings für `settings_section_security` und `settings_security_body` vollständig übersetzt.
  - `app/src/test/java/de/hohnepeople/keepadb/KeepADBAccessibilityContractTest.java`:
    - `everyLocaleProvidesAccessibilityAndPermissionText` prüft nun das Vorhandensein der Sicherheitsstrings über alle 19 Locales.
    - Neuer Test `settingsActivityIncludesSecurityAdvicePanel` validiert die Einbindung des Sicherheitspanels im Layout.
- **Review:** `not applicable` (S1-Dokumentation, String-Lokalisierung und UI-Hinweispanel, abgenommen gegen deterministische Contract-Tests).
- **Status:** `complete` (PR #160 gemergt, Issue #159 geschlossen).

## Umsetzung Paket 2 (Issue #158) — 2026-08-22

- **Issue:** [#158](https://github.com/m00sfett/KeepADB/issues/158) — `feat: Option zum Ausblenden der dauerhaften Benachrichtigung (Hide Notification)`
- **Umsetzung:**
  - `app/src/main/java/de/hohnepeople/keepadb/KeepADBPreferences.java`:
    - Neue Methoden `isNotificationHidden(Context)` und `setNotificationHidden(Context, boolean)` mit SharedPreferences-Key `hide_notification_enabled` hinzugefügt.
  - `app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java`:
    - `show(...)` und `showPlaceholder(...)` prüfen `KeepADBPreferences.isNotificationHidden(context)`. Wenn aktiv, wird `manager.cancel(NOTIFICATION_ID)` ausgeführt und keine Benachrichtigung gepostet.
  - `app/src/main/res/layout/activity_settings.xml`:
    - Neues Panel `settings_notification_panel` mit `settings_hide_notification_toggle` (`Switch`) und erklärendem Subtext eingebunden.
  - `app/src/main/java/de/hohnepeople/keepadb/SettingsActivity.java`:
    - Toggle-Handler und Status-Synchronisation in `refresh()` implementiert, Toast-Rückmeldung bei Umschaltung (`settings_notification_hidden_toast` / `settings_notification_visible_toast`).
  - Lokalisierung in allen 19 Sprachen (`values*/strings.xml`): Strings für `settings_section_notification`, `settings_hide_notification_toggle`, `settings_hide_notification_subtext`, `settings_notification_hidden_toast`, `settings_notification_visible_toast` vollständig übersetzt.
  - Tests:
    - `KeepADBAccessibilityContractTest.java`: Neuer Test `settingsActivityIncludesNotificationSettingsPanel` und Prüfung aller 19 Locales auf Vollständigkeit der neuen Notification-Strings.
    - `KeepADBBootReceiverContractTest.java`: Neuer Contract-Test `notificationRespectsHidePreferenceContract` verifiziert die `isNotificationHidden`-Prüfung in `show` und `showPlaceholder`.
- **Lokale Gates:**
  - `./bin/verify`: bestanden (26 Unit-Tests grün, 0 Lint-Fehler, Debug- und Release-APKs gebaut).
- **Review:** `not applicable` (S2-Einstellungsoption und Lifecycle-Erweiterung, abgenommen gegen deterministische Contract-Tests und lokale Gates).
- **Status:** `complete` (Bereit für PR & Merge).
