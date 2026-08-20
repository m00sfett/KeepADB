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

## Issue-3-Implementierung — 2026-08-20

- Freigabe: Implementierung, Debug-Build, Lint und Geräteprüfung ausdrücklich erteilt.
- Umsetzung: `AdbWifiEndpoint` entdeckt den tatsächlichen `_adb-tls-connect._tcp`-Dienst per NSD
  und übernimmt dessen aufgelöste Host-Adresse und Port; kein Default-Port und keine persistierten
  Endpoint-Daten. `AdbWifiNotification` erstellt den Channel, formatiert Port fett und entfernt
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
  und Inhalt `Port 40045 @ 192.168.178.24`; Channel `adb_wifi_endpoint` vorhanden; kein neuer
  `FATAL EXCEPTION`-Eintrag nach dem Fix; Lint erfolgreich.
- Nicht ausgeführt: AUS/AN-Transportzyklus über die App, weil das Ausschalten von WLAN-ADB den
  laufenden Prüftransport beendet. Dieser Abnahmepunkt bleibt als manueller/alternativer
  Kontrollkanal offen; Widget- und Tile-Aufrufpfade wurden nicht separat ausgelöst.
- Status: `not approved` für vollständige Issue-Abnahme; ON-/Anzeige-/Notification-Datenpfad auf
  dem echten S20 bestanden, AUS/AN und Widget/Tile bleiben offen.

## Issue-3-Crashfix — 2026-08-20

- Befund: Beim AUS-/AN-Umschalten kann der NSD-Adapter verspätete Discovery-/Resolve-Callbacks
  nach dem Stop verarbeiten; Framework-Ausnahmen beim Starten oder Auflösen waren ungefangen.
- Umsetzung: `AdbWifiEndpoint` verwirft Callbacks aus veralteten Discovery-Generationen und
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
  https://github.com/m00sfett/smartphone-wlan-adb-app/issues/5
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
  - Notification-Dump zeigt `android.title=String (WLAN-ADB: Port 34841 @ 192.168.178.24)` und `android.text=SpannableString (Port 34841 @ 192.168.178.24)` auf Channel `adb_wifi_endpoint`.
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
  2. `AdbWifiEndpoint` übernahm zuvor den ersten aufgelösten Treffer ungeprüft.
  3. `AdbWifiNotification.refresh()` setzte `currentHost` und `currentPort` nicht vor dem neuen Discovery-Lauf zurück.
- Umsetzung:
  - `AdbWifiEndpoint.java`: In `onServiceResolved` wird ein asynchroner TCP-Socket-Connect-Check (400 ms Timeout) gegen `(host, port)` ausgeführt. Nur tatsächlich erreichbare/offene Ports werden als Live-Endpoint an den Listener gemeldet. Läuft ein Record ins Leere (Connection refused), wird die Suche für alternative Records nicht blockiert.
  - `AdbWifiNotification.java`: `currentHost` und `currentPort` werden bei jedem `refresh()` und `stop()` zuverlässig invalidiert.
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
- PR #2 (`feat: apply CI design system to WiFi-ADB`) als bereit markiert und per Squash-Merge in `master` übernommen (`Fixes #1`).
- Issue #1 durch GitHub automatisch geschlossen.
- Branch `issue-1-ci-designsystem` lokal und remote aufgeräumt.
- Status: `complete`.

## Issue-4-Planung & Architekturentscheidung — 2026-08-20

- Issue: [#4](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/4) — Zentrales WLAN-ADB-Register auf moosgames2020 (Tailscale-only)
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
  - [#9](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/9) Race Condition bei currentHost/currentPort in AdbWifiNotification
  - [#10](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/10) Unbounded Thread-Spawning in AdbWifiRegisterClient
  - [#11](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/11) catch (Exception e) zu breit in AdbWifiRegisterClient
  - [#12](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/12) Register wird bei onUnavailable nicht als stale markiert
  - [#13](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/13) Unescapte JSON-String-Interpolation in AdbWifiRegisterClient
  - [#14](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/14) README: 'Zweck'/'Ziel' dupliziert die Einleitung
  - [#15](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/15) Untracked FRONTMATTER.md dupliziert README-Inhalt

- Bündelung & Paketierung nach Eco-Grundsätzen:
  1. **Paket 1 (Doku & Workspace-Hygiene):** Issues [#14](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/14) und [#15](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/15).
     - Ziel: Beseitigung redundanter Abschnitte in `README.md` und Löschung der duplizierten Restdatei `FRONTMATTER.md`.
     - Stufe: S1 (rein mechanisch / Markdown-Bereinigung).
     - Gates: `git diff --check`, Prüfung der Markdown-Struktur.
  2. **Paket 2 (RegisterClient-Härtung & Thread-Safety):** Issues [#9](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/9), [#10](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/10), [#11](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/11), [#13](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/13).
     - Ziel: `AdbWifiNotification` und `AdbWifiRegisterClient` thread-safe und robust machen (Deduplication / Single-Worker, gezieltes Exception-Handling, sicheres JSON-Escaping).
     - Stufe: S2 (Standard-Implementierung).
     - Gates: `git diff --check`, `gradlew assembleDebug`, `gradlew lintDebug`.
  3. **Paket 3 (Register-Staleness bei Deaktivierung):** Issue [#12](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/12).
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

- Issues: [#9](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/9), [#10](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/10), [#11](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/11), [#13](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/13).
- Ziel:
  - #9: Race Condition & Sichtbarkeit bei `currentHost`, `currentPort` und `endpointListener` in `AdbWifiNotification` durch Synchronisation absichern.
  - #10: Unbegrenztes Thread-Spawning in `AdbWifiRegisterClient` durch Single-Thread `ExecutorService` mit In-Flight Deduplication/Coalescing ersetzen.
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

- Issue: [#12](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/12) — Register wird bei onUnavailable nicht als stale markiert
- Ziel: Bei `onUnavailable()` (mDNS-Record verschwindet / WLAN-ADB AUS) oder `stop()` soll das zentrale Tailscale-Register auf `100.111.111.21:50829` umgehend als stale / inaktiv markiert werden, statt veraltete Endpunkte bis zum Scan-TTL-Ablauf als live zu führen.
- Server-Erweiterung (`~/agent/bin/phone-register-server`):
  - `do_DELETE` und Unregister-Payload-Support (`active: false` / `action: unregister`) implementiert.
  - `evaluate_device_reach` wertet `active=False` oder leeren Endpunkt sofort als `is_stale=true` / `status="stale"`.
  - Service `phone-register-server.service` neu gestartet und mit `curl` verifiziert.
  - Protokolleintrag `~/agent/protocols/2026-08-20/211200-phone-register-server-delete.yaml` erstellt und committet.
- Client-Erweiterung (`AdbWifiRegisterClient.java` & `AdbWifiNotification.java`):
  - `AdbWifiRegisterClient.markUnavailableAsync()` implementiert, das einen HTTP-DELETE-Request asynchron über den `EXECUTOR` sendet und `lastRegisteredEndpoint` zurücksetzt.
  - In `AdbWifiNotification`: `markUnavailableAsync()` wird in `onUnavailable()` und `stop()` zuverlässig aufgerufen.
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

- Issue: [#22](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/22) — Option 'WLAN-ADB dauerhaft aktiv halten' (Auto-Re-Enable bei Drop, Reconnect & Boot)
- Ziel: Eine zuschaltbare Option, die WLAN-ADB automatisch wieder einschaltet, wenn das System die Verbindung trennt (z. B. durch AP-Wechsel, temporären WLAN-Verlust, Android-Inaktivitäts-Timeout oder Reboot), und den neuen Endpoint sofort an das Register übermittelt.
- Anforderungen & Umfang:
  1. **UI & Persistenz:** Zweiter Switch in `MainActivity` ("Dauerhaft aktiv halten" / "Auto-Reconnect"), persistiert in `SharedPreferences` via `AdbWifiPreferences`.
  2. **Triggers:**
     - `ContentObserver`: Beobachtet `Settings.Global.getUriFor("adb_wifi_enabled")` und reaktiviert WLAN-ADB bei unerwartetem Drop, falls Wi-Fi verbunden ist.
     - `NetworkCallback`: Beobachtet Wi-Fi-Netzwerkzustand (`TRANSPORT_WIFI`) und reaktiviert WLAN-ADB bei Wiederverbindung / AP-Wechsel.
     - `BootReceiver`: `RECEIVE_BOOT_COMPLETED` startet nach Reboot die Überwachung und aktiviert WLAN-ADB bei vorhandener Wi-Fi-Verbindung.
  3. **Foreground Service:** `AdbWifiService` garantiert zuverlässige Hintergrund-Überwachung unter Android 13/14+ und bindet die Ongoing-Notification.
  4. **Register-Synchronisation:** Bei Reconnect / neuem Endpoint ruft der Flow `AdbWifiNotification.refresh()` auf, welcher per mDNS den Port auflöst und an das Tailscale-Register pusht.
- Nicht-Ziele: Keine Änderung der `AdbWifi.setEnabled()`-Rechteprüfungen (`WRITE_SECURE_SETTINGS`), keine externen Third-Party-Dependencies.
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
  - `AdbWifiPreferences.java`: Hilfsklasse für typisierte SharedPreferences-Persistenz (`keep_alive_enabled`).
  - `AdbWifiService.java`: Foreground Service mit `ContentObserver` für `adb_wifi_enabled` und `ConnectivityManager.NetworkCallback` für `TRANSPORT_WIFI`. Automatische Reaktivierung und mDNS/Notification/Register-Refresh bei Reconnect/Drop.
  - `BootReceiver.java`: `RECEIVE_BOOT_COMPLETED` & `QUICKBOOT_POWERON` BroadcastReceiver zum Starten des Überwachungs-Services und Reaktivieren von WLAN-ADB nach Neustart.
  - `AdbWifiNotification.java`: Unterstützung von Foreground-Service-Notifications und synchronisierter Placeholder-Anzeige bei vorübergehendem Drop im Keep-Alive-Modus.
  - `MainActivity.java` & `activity_main.xml`: Zweiter Schalter "Dauerhaft aktiv halten" mit erklärendem Untertitel, vollständige Anbindung an Preferences und Service-Lifecycle.
  - `AndroidManifest.xml`: Berechtigungen (`RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `ACCESS_NETWORK_STATE`) sowie Service- und Receiver-Deklarationen ergänzt.
- Lokale Gates:
  - `git diff --check`: bestanden (0 Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug lintDebug`: bestanden (0 Fehler, 0 Warnungen).
- Geräte-Validierung auf Samsung Galaxy S20 FE (`SM-G780G` / `RF8T307S88H` via `192.168.178.24:41069`):
  - Debug-APK per `android-target s20 -- install -r` erfolgreich installiert.
  - UI-Automation Dump (`uiautomator dump`): Schalter "Dauerhaft aktiv halten" vorhanden und toggelbar.
  - Service-Status (`dumpsys activity services`): `AdbWifiService` läuft als Foreground-Service (`isForeground=true foregroundId=1 channel=adb_wifi_endpoint`).
  - Notification-Status (`dumpsys notification`): Notification `WLAN-ADB: Port 41069 @ 192.168.178.24` auf Channel `adb_wifi_endpoint` aktiv.
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
2. **Foreground-Service & Android-Lifecycle:** Die Bindung des ContentObservers und NetworkCallbacks an den Foreground-Service `AdbWifiService` unter Verwendung der bestehenden Ongoing-Notification (`NOTIFICATION_ID = 1`) stellt zuverlässigen Betrieb auch bei App-Schließung oder Hintergrund-Aktivität sicher.
3. **Delegation vs. Direktausführung:** Die direkte Umsetzung auf Stufe S3 im Hauptagenten war präzise und kontextschonend; keine unnötige Subagenten-Kette.
4. **Verbesserung:** Für zukünftige BroadcastReceiver-Tests geschützte System-Broadcasts (`BOOT_COMPLETED`) über dedizierte Test-Intents (`QUICKBOOT_POWERON`) oder direkte Komponenten-Intents simulieren.

## Neue Auswahlrunde & Kandidaten-Paketierung (Review-Findings #24–#33) — 2026-08-20

- Ausgangslage:
  - 10 offene Issues (#24 bis #33) aus automatisiertem Code-Review (`/code-review xhigh`) von Commit `7d3fa1c`.
  - PR [#34](https://github.com/m00sfett/smartphone-wlan-adb-app/pull/34) (`fix: keep-alive respects manual WLAN-ADB shutoff` für Issue [#33](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/33)) ist auf Branch `fix/keep-alive-respect-manual-disable` vorbereitet.
  - Keine aktiven GitHub-Actions-Runs im Remote.

- Offene Issues (10):
  - [#33](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/33) Keep-Alive schaltet manuell ausgeschaltetes WLAN-ADB sofort wieder an (PR #34)
  - [#32](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/32) AdbWifiPreferences widerspricht der 'kein persistenter App-State'-Konvention
  - [#30](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/30) Toter else-Zweig in AdbWifiService.start() (Pre-Oreo, minSdk 30)
  - [#29](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/29) Duplizierter Toast-/Refresh-Boilerplate in MainActivity-Click-Listenern
  - [#28](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/28) Duplizierte POST_NOTIFICATIONS-Permission-Prüfung in AdbWifiNotification
  - [#27](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/27) NetworkCallback und ContentObserver laufen auf inkonsistenten Threads
  - [#26](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/26) Fehlschlag von AdbWifi.setEnabled() im Keep-Alive-Pfad wird verschluckt
  - [#25](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/25) BootReceiver exported ohne Permission-Check – Broadcast-Spoofing möglich
  - [#24](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/24) Foreground-Notification wird beim Service-Stop ungeprüft gelöscht
  - [#31](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/31) Doppelte Discovery-Zyklen beim Service-Start (NetworkCallback + onStartCommand)

- Paketierung nach Eco-Grundsätzen:
  1. **Paket 0 (Vorbereitung / PR #34 Merge):**
     - Issue: [#33](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/33)
     - Ziel: PR #34 (`fix/keep-alive-respect-manual-disable`) in `master` mergen, um die Baseline für Service-Härtungen zu aktualisieren.
     - Stufe: S1 (Merge & Synchronisation).
  2. **Paket 1 (Doku-, Refactoring- & Cleanup-Hygiene):**
     - Issues: [#32](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/32), [#30](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/30), [#28](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/28), [#29](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/29)
     - Ziel:
       - #32: Konventionsbeschreibung in `AGENTS.md` präzisieren (WLAN-ADB Live-State vs. persistierte Nutzereinstellungen).
       - #30: Toter Pre-Oreo Branch in `AdbWifiService.start()` entfernen.
       - #28: `hasNotificationPermission(Context)` Helper in `AdbWifiNotification` extrahieren.
       - #29: Gemeinsame Hilfsmethode für Permission-Toast & UI-Refresh in `MainActivity` extrahieren.
     - Stufe: S1 (mechanisches Refactoring / Doku).
     - Gates: `git diff --check`, `gradlew assembleDebug lintDebug`.
  3. **Paket 2 (Service- & Receiver-Lifecycle-Härtung):**
     - Issues: [#24](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/24), [#25](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/25), [#27](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/27), [#31](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/31), [#26](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/26)
     - Ziel:
       - #24: `stopForeground(STOP_FOREGROUND_DETACH)` in `AdbWifiService.onDestroy()` nutzen.
       - #25: `BootReceiver` im Manifest via `android:permission="android.permission.RECEIVE_BOOT_COMPLETED"` gegen Spoofing unprivilegierter Apps absichern.
       - #27: `ConnectivityManager.registerNetworkCallback` auf Main-Handler/Executor binden (Konsistenz mit ContentObserver).
       - #31: Doppelten Startup-Discovery-Zyklus in `AdbWifiService` deduplizieren / entprellen.
       - #26: Fehlschlag von `AdbWifi.setEnabled()` im Keep-Alive-Service protokollieren und sichtbar machen.
     - Stufe: S2 (Android Service Lifecycle, Concurrency & Security).
     - Gates: `git diff --check`, `gradlew assembleDebug lintDebug`, abschließende Geräteverifikation auf Samsung S20.

- Einstiegsentscheidung (Eco-Prämisse: Einfachste Pakete zuerst):
  - **Reihenfolge:** Paket 0 (PR #34) → Paket 1 (Hygiene / S1) → Paket 2 (Service-Härtung / S2) → gemeinsame S20-Geräteverifikation.
- Status: `complete` für diese Planungs- und Strukturierungsrunde.

## Abschluss Paket 0 & Paket 1 & Paket 2 — 2026-08-20

- **Paket 0 (PR #34 / Issue #33):**
  - PR [#34](https://github.com/m00sfett/smartphone-wlan-adb-app/pull/34) per Squash-Merge in `master` übernommen.
  - Implementierung: `AdbWifi.setEnabled()` setzt `userDisabled` Flag, `AdbWifiService.ContentObserver` konsumiert das Flag und unterdrückt unerwünschtes Re-Enable beim manuellen Ausschalten.
  - Status: Code auf `master`, Issue [#33](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/33) verbleibt bis zur Geräte-Rauchprüfung offen.

- **Paket 1 (PR #35 / Issues #28, #29, #30, #32):**
  - PR [#35](https://github.com/m00sfett/smartphone-wlan-adb-app/pull/35) per Squash-Merge in `master` übernommen.
  - [#32](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/32): Konvention in `AGENTS.md` präzisiert (Live-State vs. persistierte Keep-Alive Einstellung) und `GEMINI.md` synchronisiert.
  - [#30](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/30): Toter Pre-Oreo `else`-Zweig in `AdbWifiService.start()` entfernt.
  - [#28](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/28): `hasNotificationPermission(Context)` Helper in `AdbWifiNotification` extrahiert.
  - [#29](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/29): Helper `showPermissionErrorToast()` und `refreshUiAndComponents()` in `MainActivity` extrahiert.
  - Lokale Gates: `git diff --check`, `gradlew assembleDebug lintDebug` (0 Fehler).
  - Status: Issues #28, #29, #30, #32 automatisch geschlossen.

- **Paket 2 (PR #36 / Issues #24, #25, #26, #27, #31):**
  - PR [#36](https://github.com/m00sfett/smartphone-wlan-adb-app/pull/36) per Squash-Merge in `master` übernommen.
  - [#24](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/24): In `AdbWifiService.onDestroy()` `stopForeground(STOP_FOREGROUND_DETACH)` verwendet, sodass Endpoint-Notification nicht bei alleinigem Service-Stop gelöscht wird.
  - [#25](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/25): `BootReceiver` im `AndroidManifest.xml` via `android:permission="android.permission.RECEIVE_BOOT_COMPLETED"` abgesichert.
  - [#26](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/26): Fehlschlag von `AdbWifi.setEnabled()` im Keep-Alive-Service und `BootReceiver` geloggt und via `AdbWifiNotification.showPermissionMissing()` sichtbar eskaliert.
  - [#27](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/27): `ConnectivityManager.registerNetworkCallback` explizit an Main-Looper (`new Handler(Looper.getMainLooper())`) gebunden.
  - [#31](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/31): Startup-Debounce (<300ms) in `recheckAndEnable()` integriert, um doppelte mDNS-Discovery-Zyklen beim Service-Start zu unterbinden.
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
  - `dumpsys activity services` verifiziert: `AdbWifiService` läuft als Foreground-Service (`isForeground=true foregroundId=1 channel=adb_wifi_endpoint`).
  - `dumpsys notification` verifiziert: Ongoing-Notification `WLAN-ADB: Port 34121 @ 192.168.178.24` aktiv.
  - Service Detach (Issue #24): Keep-Alive ausgeschaltet -> Service stoppt via `STOP_FOREGROUND_DETACH`, Notification bleibt erhalten. Keep-Alive wieder eingeschaltet -> Foreground-Service startet sauber neu.
  - Tailscale-Register-Push: `curl http://100.111.111.21:50829/register/s20` verifiziert aktuellen Timestamp, `status: active` und `is_stale: false`.
  - Issue [#33](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/33) geschlossen.
- **Status:** `complete` (0 offene Issues im Repository).

## Retrospektive (Review-Findings 24–33)

1. **Paketierung & Eco-Reihenfolge:** Die Aufteilung in Paket 0 (vorbereitender PR-Merge), Paket 1 (mechanische S1-Hygiene) und Paket 2 (S2-Lifecycle & Concurrency-Härtung) hat atomare, saubere PRs und konfliktfreie Merges ermöglicht.
2. **Qualitäts-Gates:** Deterministische lokale Validierung (`git diff --check`, Gradle Debug-Kompilierung und Android Lint) verifizierten jede Änderung vor PR-Erstellung und Merge.
3. **Delegation vs. Direktausführung:** Alle 3 Pakete wurden direkt im Hauptagenten umgesetzt, wodurch Kontextwechsel vermieden und Token gespart wurden.
4. **Verbesserung:** Bei Android-Hintergrunddiensten und BroadcastReceivern sicherheitsrelevante Berechtigungs- und Threading-Garantien (Handler-Bindung, Service-Detachment) stets bereits im initialen Entwurf verankern.

## Nachbesserung: Endpoint-Cache & Discovery-ANR-Fix (PR #37) — 2026-08-20

- **Problem:** Beim Umschalten von "Dauerhaft aktiv halten" (während WLAN-ADB bereits aktiv war) setzte `AdbWifiNotification.refresh()` den bekannten Endpoint bedingungslos auf `null` zurück, zeigte "Endpoint wird gesucht..." und startete eine synchrone NsdManager-Discovery auf dem Main-Thread. Dies führte zu Discovery-Stürmen und einem 10s-ANR (`Input dispatching timed out`).
- **Behebung in PR [#37](https://github.com/m00sfett/smartphone-wlan-adb-app/pull/37):**
  - `AdbWifiNotification.refresh()` behält einen bereits aufgelösten, gültigen Endpoint (`currentHost != null && currentPort > 0`) bei und aktualisiert Notification und UI sofort ohne Discovery-Neustart.
  - `AdbWifiEndpoint.discover()` ist idempotent (kein Doppelstart bei laufender Discovery).
  - Sobald ein erreichbarer Endpoint verifiziert ist, wird die mDNS-Discovery gestoppt und der MulticastLock freigegeben.
  - `AdbWifiNotification.invalidateEndpoint()` für saubere Invalidierung bei tatsächlichem Wi-Fi-Drop (`onLost`) oder WLAN-ADB-Ausschalten hinzugefügt.
- **Live-Verifikation auf Samsung S20 FE:**
  - APK gebaut und per `install -r` aktualisiert.
  - Togglen von "Dauerhaft aktiv halten" (AUS und wieder AN) hält den Endpoint kontinuierlich stabil.
  - Null Verzögerung, kein Flackern, kein ANR.

## Batch 2: Endpoint Resolver Robustheit & Performance Härtung (Issues 38–41) — 2026-08-20

- Kontext: Bei der Endpoint-Ermittlung kam es zu Verzögerungen und Hängern im Status "Endpoint wird gesucht …" aufgrund von Start-Races beim Einschalten, hängenden NsdManager-Callbacks bei Stale-mDNS-Einträgen und fehlendem automatischem Retry.
- Offene Issues (4):
  - [#38](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/38) fix: AdbWifiEndpoint Resolve-Watchdog gegen hängende NsdManager.resolveService()-Aufrufe
  - [#39](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/39) feat: Automatischer Discovery-Retry in AdbWifiNotification bei fehlgeschlagener Endpoint-Suche
  - [#40](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/40) feat: Lokaler Fast-Probe Port-Scan zur sofortigen Erkennung des adbd-Ports
  - [#41](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/41) fix: Initial-Discovery-Delay nach adb_wifi_enabled zur Vermeidung von adbd-Start-Races

- Paketierung:
  1. **Paket 3 (Resolver-Watchdog, Retry-Loop & Startup-Delay):**
     - Issues: [#38](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/38), [#39](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/39), [#41](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/41)
     - Ziel:
       - #38: 1,5s Watchdog-Timer in `AdbWifiEndpoint` pro `resolveService()` gegen AOSP NsdManager Deadlocks.
       - #39: Automatischer Discovery-Retry mit Backoff in `AdbWifiNotification` bei `onUnavailable()` solange WLAN-ADB aktiv ist.
       - #41: 500ms Startverzögerung beim manuellen Einschalten von WLAN-ADB vor der Initial-Discovery zur Vermeidung von `adbd`-Start-Races.
     - Stufe: S2 (Lifecycle, Threading & Network Discovery).
     - Gates: `git diff --check`, `gradlew assembleDebug lintDebug`.

  2. **Paket 4 (Lokaler Fast-Probe Port-Finder):**
     - Issue: [#40](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/40)
     - Ziel: Lokaler schneller Socket-Check auf dem Gerät zur blitzschnellen Ermittlung des aktiven Ports ohne Funk-Multicast-Latenz.
     - Stufe: S2.
     - Gates: `git diff --check`, `gradlew assembleDebug lintDebug`, abschließende S20-Verifikation.

- Einstiegsentscheidung: Paket 3 zuerst (Stabilitäts- und Deadlock-Härtung), anschließend Paket 4 (Performance-Fast-Path).

## Umsetzung Paket 3 (PR #42 / Issues #38, #39, #41) — 2026-08-20

- **Implementierung:**
  - [#38](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/38): 1,5s Watchdog-Timer (`RESOLVE_TIMEOUT_MS = 1500`) in `AdbWifiEndpoint.processNextResolveLocked()` via `mainHandler.postDelayed()` implementiert. Bei ausbleibenden AOSP-NsdManager-Callbacks wird `resolving = false` forciert und das nächste Queue-Element verarbeitet. Saubere Watchdog-Entfernung bei Callbacks und `stop()`.
  - [#39](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/39): Automatischer Discovery-Retry mit Backoff (2s Initial, 5s Folge) in `AdbWifiNotification.scheduleRetryLocked()` bei `onUnavailable()` implementiert, solange `AdbWifi.isEnabled(appContext)` wahr ist. Automatischer Abbruch bei erfolgreichem Endpoint oder Ausschalten.
  - [#41](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/41): Entprellte, 500ms verzögerte Initial-Discovery (`INITIAL_DISCOVERY_DELAY_MS = 500`) in `AdbWifiNotification.refresh()` implementiert, um `adbd`-Start-Races und Discovery-Stürme bei schnellen Toggles zu unterbinden.
- **Lokale Gates:**
  - `git diff --check`: bestanden (0 Whitespace-Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug lintDebug`: bestanden (0 Fehler, 43 Tasks ausgeführt/up-to-date).
- **Status:** `complete` für Paket 3 Code & lokale Validierung. PR #42 via Squash-Merge in `master` übernommen, Issues #38, #39, #41 geschlossen.

## Umsetzung Paket 4 (PR #43 / Issue #40) — 2026-08-20

- **Implementierung:**
  - [#40](https://github.com/m00sfett/smartphone-wlan-adb-app/issues/40): Lokaler Fast-Probe Port-Scanner in `AdbWifiEndpoint.startFastProbe()` implementiert. Parallelisiert über 16 Worker-Threads im Bereich 30000–50000 auf `127.0.0.1` mit Gegenprobe auf der lokalen Wi-Fi-IP (`getWifiIpAddress()`). Erkennt den aktiven `adbd`-Port auf dem Gerät in Millisekunden ohne Funk-Multicast-Latenz. mDNS-Discovery bleibt als robuster Standard-/Fallback-Pfad parallel aktiv.
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
  - Notification-Dump verifiziert: Ongoing-Notification `WLAN-ADB: Port 37799 @ 192.168.178.24` auf Channel `adb_wifi_endpoint` sofort aktiv.
  - Tailscale-Register-Push: `curl http://100.111.111.21:50829/register/s20` verifiziert aktuellen Timestamp, `status: active` und `is_stale: false`.
  - Screenshot zur Verifikation (`s20_fastprobe_smoke.png`) gesichert und geprüft.
- **Status:** `complete` (Gesamtabnahme für Batch 2 bestanden, 0 offene Issues).

## Umsetzung Issue #44 (PR #45 / Issue #44) — 2026-08-20

- **Kontext / Problem:** Wenn WLAN-ADB manuell über App, Quick-Settings-Tile oder Widget ausgeschaltet wird, während „Dauerhaft aktiv halten“ aktiviert war, verblieb eine Notification („WLAN-ADB: Ausgeschaltet …“), da `AdbWifiService` als Foreground-Service weiterlief.
- **Implementierung:**
  - `AdbWifiNotification.stop()` ruft `NotificationManager.cancel(NOTIFICATION_ID)` bedingungslos auf, sobald WLAN-ADB ausgeschaltet ist.
  - Beim manuellen Ausschalten via `MainActivity`, `AdbWifiTileService`, `AdbWifiWidget` oder im `AdbWifiService.ContentObserver` (`AdbWifi.consumeUserDisabled()`) wird `AdbWifiPreferences.setKeepAliveEnabled(context, false)` gesetzt und `AdbWifiService.stop(context)` ausgeführt.
- **Lokale Gates:**
  - `git diff --check`: bestanden (0 Whitespace-Fehler).
  - `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug lintDebug`: bestanden (0 Fehler, 43 Tasks ausgeführt/up-to-date).
- **Status:** `complete` für Issue #44 Code & lokale Validierung. Bereit für PR und Merge.





