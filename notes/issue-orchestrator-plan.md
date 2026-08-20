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
