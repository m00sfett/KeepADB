# Auftrag: Umfassendes Code-Review für KeepADB

## Ziel

Führe ein unabhängiges, read-only Code-Review des aktuellen KeepADB-Stands durch. Bewerte,
ob die App auf Android 11–13+ Wireless Debugging zuverlässig schaltet, den Zustand über App,
Quick-Settings-Tile und Widget konsistent darstellt, den Keep-Alive über Netzwerkwechsel und
Boot korrekt betreibt und dabei keine vermeidbaren Sicherheits-, Datenschutz-, Lifecycle- oder
Ressourcenfehler einführt.

Der Review ist ein Befundauftrag, kein Implementierungsauftrag. Änderungen am Quelltext,
Commits, Pushes, Pull Requests, Issue-Änderungen, GitHub-Actions-Läufe und Geräteaktionen sind
nicht erlaubt, sofern sie nicht in einem eigenen, typisierten Folgeauftrag ausdrücklich
freigegeben werden.

## Ausführung und Instruktionen

Lies vor der Analyse die für den Checkout geltenden Instruktionsquellen vollständig:

- `~/AGENTS.md`
- `~/.AGENTS_orchestration.md`
- die lokale Projektanweisung `AGENTS.md` (sie ist lokal und nicht zu committen)
- `README.md`
- `notes/reviews/2026-08-21-keepadb-identity.md`
- `notes/issue-orchestrator-plan.md`

Prüfe außerdem den tatsächlichen Git-Stand read-only (`git status --short --branch`, relevante
Commits und Remote nur zur Einordnung). Verlasse dich nicht auf README-Behauptungen, Kommentare
oder frühere Reviewtexte, ohne sie gegen den Code zu prüfen.

## Geprüfter Scope

### Produkt- und Berechtigungsgrenzen

- `app/src/main/AndroidManifest.xml`
- `app/build.gradle`
- `settings.gradle`
- `gradle.properties`
- `app/src/main/res/xml/network_security_config.xml`
- `app/src/main/res/xml/widget_info.xml`
- `app/src/main/res/xml/locales_config.xml`

Prüfe insbesondere Export-/Permission-Grenzen, `WRITE_SECURE_SETTINGS`, Boot- und Foreground-
Service-Deklarationen, Notification- und Netzwerkberechtigungen, Backup-Verhalten, Cleartext-
Netzwerkregeln sowie die Kompatibilität von `minSdk 30`, `targetSdk 35` und den verwendeten
Android-APIs.

### Zentrale Zustands- und Nebenläufigkeitslogik

- `app/src/main/java/de/hohnepeople/keepadb/KeepADB.java`
- `app/src/main/java/de/hohnepeople/keepadb/KeepADBPreferences.java`
- `app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java`

Analysiere Zustandsübergänge, Synchronisation, Handler-/Looper-Annahmen, Debouncing,
verzögerte Runnables, Prozessneustarts, Callback-Generationen, Abbruch und Wiederanlauf,
Thread-/Executor-Lebensdauer, Race Conditions und Fehlerpfade. Für jeden Guard, Timeout,
Cooldown oder Recovery-Puls muss klar sein, was im Auslösefall passiert und ob sich der Zustand
selbst wieder erholen kann.

Bewerte bei der Endpoint-Erkennung besonders:

- mDNS Discovery und Resolve-Reihenfolge;
- veraltete oder verspätete Callbacks nach `stop()` bzw. einem neuen Discovery-Lauf;
- Loopback-Portprobe, Socket-/Selector-Schließung, Timeout und Threadzahl;
- MulticastLock-Erwerb und -Freigabe;
- Auswahl und Validierung von Host/Port;
- Verhalten bei VPN, mehreren Netzwerken, Wi-Fi-Wechsel, fehlendem NSD und nicht erreichbarem
  `adbd`;
- ob eine gefundene Adresse tatsächlich der erwartete Wireless-Debugging-Endpunkt ist und ob
  ein falscher lokaler Listener als gültig akzeptiert werden kann.

### Service, Boot und Benachrichtigungen

- `app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java`
- `app/src/main/java/de/hohnepeople/keepadb/BootReceiver.java`
- `app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java`
- `app/src/main/java/de/hohnepeople/keepadb/KeepADBRegisterClient.java`

Prüfe den gesamten Lebenszyklus von `onCreate`, `onStartCommand`, `onDestroy`, Listenern,
Connectivity-Callbacks, Retry-/Backoff-Timern, Notification-Updates und Webhook-Registrierung.
Prüfe insbesondere idempotentes Starten/Stoppen, Leaks und doppelte Beobachter, Verhalten nach
Boot ohne WLAN, nach absichtlichem Ausschalten, bei fehlender Berechtigung und bei Prozess-
oder Geräte-Neustart. Der Service darf einen expliziten Nutzerwunsch zum Ausschalten nicht
sofort wieder überstimmen.

Für den Webhook prüfe URL-Validierung, erlaubte Protokolle, Redirects, TLS-/Cleartext-Verhalten,
Timeouts, Netzwerk-Threading, Antwortprüfung, Fehler- und Retry-Verhalten, Umgang mit
vertraulichen Daten sowie Konsistenz von Register/Deregister bei Endpoint-Wechsel und Restart.
Prüfe auch, ob UI-Status und tatsächlicher Registerzustand auseinanderlaufen können.

### Oberflächen und Android-Lifecycle

- `app/src/main/java/de/hohnepeople/keepadb/MainActivity.java`
- `app/src/main/java/de/hohnepeople/keepadb/SettingsActivity.java`
- `app/src/main/java/de/hohnepeople/keepadb/KeepADBTileService.java`
- `app/src/main/java/de/hohnepeople/keepadb/KeepADBWidget.java`
- `app/src/main/java/de/hohnepeople/keepadb/KeepADBLocaleHelper.java`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/layout/activity_settings.xml`
- `app/src/main/res/layout/widget_keepadb.xml`
- `app/src/main/res/values/strings.xml` und alle vorhandenen `values-*`-Übersetzungen

Prüfe, ob alle drei Oberflächen den Live-Zustand aus `Settings.Global` korrekt anzeigen und
setzen, ob schnelle Mehrfachaktionen und verzögerte Toggles korrekt dargestellt werden und ob
Observer, Dialoge, PendingIntents, Widget-Updates und Activity-Lifecycle sauber funktionieren.
Prüfe Berechtigungsfehler, Notification-Permission ab API 33, RTL/Locale-Wechsel, fehlende oder
unvollständige Übersetzungen, String-Formatierung und Accessibility-relevante Android-
Eigenschaften, soweit sie aus dem Code und den Ressourcen belegbar sind.

### Tests, Build und CI als Review-Artefakte

- `app/src/test/java/de/hohnepeople/keepadb/KeepADBTest.java`
- `app/src/test/java/de/hohnepeople/keepadb/KeepADBEndpointTest.java`
- `app/src/test/java/de/hohnepeople/keepadb/KeepADBPreferencesTest.java`
- `app/src/test/java/de/hohnepeople/keepadb/KeepADBLocaleHelperTest.java`
- `.github/workflows/ci.yml`
- `.github/workflows/release.yml`
- `README.md` (Build-, Installations- und Verhaltensaussagen)

Bewerte die Aussagekraft der vorhandenen Tests: deterministische Ausführung, Isolation,
Ressourcenfreigabe, echte Abdeckung der Race-/Lifecycle-/Fehlerpfade und mögliche Flakes oder
Tests, die nur eine Messung ausgeben, aber keine belastbare Regression absichern. Gleiche
Dokumentation, Buildskripte und Workflow-Inhalte gegen den tatsächlichen Stand ab. Löse keinen
Workflow aus; beachte, dass dieses Projekt lokale Gates bevorzugt und GitHub-Actions aktuell
nicht gestartet werden sollen.

## Prüffragen und Akzeptanzkriterien

Der Review ist vollständig, wenn jede Kategorie mit konkreten Codebelegen behandelt wurde:

1. **Funktionalität:** Sind Enable/Disable, Live-Status, Keep-Alive, Boot-Wiederanlauf,
   Wi-Fi-Wechsel, Endpoint-Aktualisierung und absichtliches Ausschalten korrekt?
2. **Nebenläufigkeit:** Gibt es reproduzierbar plausible Race Conditions zwischen UI, Service,
   Handlern, NSD-Callbacks, Probe-Threads und Recovery-Puls? Werden alte Ergebnisse verworfen?
3. **Lifecycle/Ressourcen:** Werden Receiver, Observer, NetworkCallbacks, NSD-Discovery,
   MulticastLock, Sockets, Selector, Executor und Runnables in allen Pfaden beendet oder
   kontrolliert wiederverwendet?
4. **Security/Privacy:** Können externe Intents, Webhook-Konfiguration, Redirects, Cleartext,
   Backup oder unzureichende Eingabeprüfung zu ungewollten Aktionen, Datenabfluss oder
   Privilegienmissbrauch führen? Sind Logs und Notifications angemessen?
5. **Kompatibilität:** Sind API-Level, Berechtigungen, Foreground-Service-Regeln und
   Gerätezustände auf dem dokumentierten Ziel plausibel?
6. **UI/Localization:** Sind Oberflächen-, Berechtigungs-, Locale-, RTL- und Accessibility-
   Pfade konsistent und frei von veralteten Zuständen?
7. **Tests/Release:** Decken Tests die kritischen Verträge ab, sind Build-/Lint-/Release-
   Aussagen korrekt und fehlen lokale oder Geräte-Gates?
8. **Dokumentation:** Stimmen README, Kommentare und Review-/Issue-Historie mit dem Code überein?

## Befundstandard

Melde alle belastbaren Findings, nicht nur hohe Schweregrade. Sortiere nach Schweregrad:

- **Critical:** Privilegienmissbrauch, unkontrollierter Datenabfluss, unbrauchbarer Kernpfad
  oder hohes Risiko für dauerhafte Fehlfunktion.
- **High:** Kernfunktion, Sicherheit, Datenfluss, Lifecycle oder Recovery unter realistischen
  Bedingungen fehlerhaft.
- **Medium:** Relevanter Fehler mit begrenztem Umfang, erheblicher Zuverlässigkeitsverlust oder
  fehlender Absicherung eines wichtigen Pfads.
- **Low:** Kleine Robustheits-, Dokumentations-, UI- oder Wartbarkeitslücke.

Jeder Befund enthält:

- Schweregrad und kurze Überschrift;
- Datei und exakte Zeile bzw. engsten Codebereich;
- beobachteten Ist-Zustand;
- erwarteten Zustand und konkrete Auslösebedingungen;
- technische Begründung mit Daten-/Kontrollfluss;
- Auswirkungen und, falls sinnvoll, Reproduktions- oder Testidee;
- minimalen Fix-Rahmen, ohne den Fix im Review selbst umzusetzen;
- Klassifikation: **Muss-Fix im aktuellen Scope**, **Folge-Issue** oder **bewusst akzeptiertes
  Restrisiko**.

Trenne echte Befunde klar von Unsicherheiten. Wenn eine Aussage nur auf statischer Analyse
beruht, kennzeichne das. Keine pauschalen Stilpräferenzen ohne konkrete Auswirkung.

## Verifikation und Abschluss

Der Reviewer darf lokale Dateien lesen und statische Werkzeuge verwenden. Build, Unit-Tests,
Lint, Installation, Logcat, Tile-Test und jede andere Geräteaktion benötigen die jeweils
explizite Freigabe nach den Projektregeln; ohne diese Freigabe bleibt der Befund statisch und
nennt die offene Verifikation.

Der Abschlussbericht enthält:

1. ein kurzes Gesamturteil: `approved`, `approved with follow-ups` oder `not approved`;
2. Findings nach Schweregrad mit allen geforderten Belegen;
3. geprüfte, aber unauffällige Risikobereiche;
4. offene Verifikationslücken und deren konkreten Nachweis;
5. vorgeschlagene Folge-Issues, ohne sie anzulegen;
6. Status von Server, Commit, Review und offenem Scope.

Die abschließende Empfehlung muss den aktuellen Projektzweck berücksichtigen: eine kleine,
verlässliche, dependency-freie Android-App zum schnellen Wieder-Einschalten und dauerhaften
Aktivhalten von Wireless Debugging auf dem dokumentierten Zielgerät.

## Empfohlene Agentenstufe

**Empfehlung: S4 — `gpt-5.6-sol` mit `xhigh` (Codex-Profil `sol·xhigh`).**

Begründung: Das Review ist kein gewöhnliches S2-Review. Es deckt eine asynchrone,
mehrthreadige Endpoint-State-Machine, Android-Lifecycle und Netzwerk-/Webhook-Datenfluss ab
und enthält sicherheitsrelevante Berechtigungs- und Sichtbarkeitsfragen. Nach der
Orchestrierungsleiter ist S4 für Security-, Autorisierungs-, Datenfluss- und komplexe
Concurrency-/Async-Risiken die passende höchste lokale Reviewstufe. `sol·max` ist nicht der
Default und wäre erst nach belegtem Scheitern von `sol·xhigh` zulässig.

Der Review sollte unabhängig von einer späteren Implementierung ausgeführt werden. Falls nach
dem Review nur eine klar begrenzte Reparatur ohne neue Architektur- oder Sicherheitsentscheidung
folgt, kann diese als eigener S2-Auftrag behandelt werden; Änderungen an Security, Concurrency-
Architektur oder Breaking-Change-Verhalten bleiben mindestens S4.
