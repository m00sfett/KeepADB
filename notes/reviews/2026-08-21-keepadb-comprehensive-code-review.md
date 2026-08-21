# KeepADB — umfassendes Code-Review

Stand: 2026-08-21
Review-Basis: `e88ef7309c63006f9890d31571294cff8913e633` (`master`, identisch mit `origin/master`)
Auftrag: `notes/reviews/keepadb-comprehensive-code-reviewauftrag.md`
Status: statisches Review abgeschlossen; dynamische Verifikation nicht ausgeführt
Urteil: **not approved**

## Kurzfassung

Der geprüfte Stand hat keine festgestellten Critical-Befunde, aber vier High-Befunde in
Kernpfaden von Keep-Alive, Endpunkt-Erkennung und Webhook-Synchronisierung. Hinzu kommen zehn
Medium- und drei Low-Befunde. Besonders kritisch für das Produktversprechen sind:

1. Der laufende Foreground-Service erkennt einen ausgefallenen oder rotierten ADB-Endpunkt bei
   unverändertem WLAN nicht selbstständig.
2. Asynchrone Ergebnisse alter Discovery-Generationen können einen neuen Lauf vergiften oder an
   dessen Listener ausgeliefert werden.
3. Ein beliebiger offener Port beziehungsweise ein fremder `_adb-tls-connect`-Dienst kann als
   eigener Geräte-Endpunkt gemeldet werden.
4. Beim Wechsel der Webhook-URL bleibt die alte Registrierung bestehen und die neue URL erhält
   denselben bereits bekannten Endpunkt nicht.

| Schweregrad | Anzahl | Freigaberelevant |
|---|---:|---:|
| Critical | 0 | 0 |
| High | 4 | 4 |
| Medium | 10 | 8 |
| Low | 3 | 0 |

## Findings

### H1 — Keep-Alive überwacht den Endpunkt im Leerlauf nicht

**Klassifikation:** High · Must-Fix

- **Beobachtet:** Der Heartbeat des Foreground-Service schreibt nur einen Zeitstempel in die
  Preferences ([KeepADBService.java:123-133](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java#L123-L133)).
  Der NetworkCallback reagiert nur auf `onAvailable` und `onLost`, nicht auf unveränderte Netze
  oder Link-Änderungen
  ([KeepADBService.java:200-215](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java#L200-L215)).
  Die Notification prüft einen gespeicherten Endpunkt nur bei einem expliziten `refresh()`
  ([KeepADBNotification.java:105-120](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java#L105-L120)).
  Nach einem Fund wird Discovery sofort beendet
  ([KeepADBEndpoint.java:251-264](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java#L251-L264),
  [KeepADBEndpoint.java:329-340](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java#L329-L340)).
- **Erwartet:** Solange Keep-Alive aktiv ist, muss der Service den tatsächlichen ADB-Listener
  eigenständig überwachen und einen Ausfall beziehungsweise Portwechsel auch ohne UI-Aktion oder
  WLAN-Reconnect erkennen.
- **Trigger/Kontrollfluss:** `adb_wifi_enabled` bleibt `1`, aber `adbd` beendet oder rotiert seinen
  Listener; WLAN und Prozess bleiben bestehen. Kein Observer- oder NetworkCallback-Ereignis löst
  eine erneute Endpunktprüfung aus.
- **Auswirkung:** Notification und Webhook können dauerhaft einen toten Endpunkt zeigen. Die App
  erfüllt ihr zentrales Keep-Alive-Versprechen im unbeaufsichtigten Betrieb nicht.
- **Testidee:** Fake-Endpoint zunächst erreichbar machen, Discovery erfolgreich abschließen,
  Listener bei unverändertem Fake-Netz schließen und mit kontrollierter Uhr prüfen, dass der
  Service den Ausfall erkennt, deregistriert und die Recovery genau einmal startet.
- **Minimaler Fix:** Eine vom Service besessene, gedrosselte Endpunkt-Gesundheitsprüfung in den
  Heartbeat integrieren oder Discovery/Lost-Signale dauerhaft beobachten; zusätzlich
  `onLinkPropertiesChanged` behandeln. Ausfälle müssen Notification, Webhook und Recovery über
  einen gemeinsamen Zustandsübergang aktualisieren.

### H2 — Veraltete Discovery-Ergebnisse sind nicht generationssicher

**Klassifikation:** High · Must-Fix

- **Beobachtet:** Der asynchrone Reachability-Thread prüft nach dem Socket-Test weder vor dem
  globalen `endpointDelivered.compareAndSet(false, true)` noch im nachfolgenden Lock erneut seine
  `generation`
  ([KeepADBEndpoint.java:308-349](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java#L308-L349)).
  Er liest danach den aktuellen `currentListener`, der bereits zu einer neuen Discovery gehören
  kann. Auch der Quick-Probe hat ein Fenster zwischen Generationstest und CAS
  ([KeepADBEndpoint.java:238-264](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java#L238-L264)).
  Resolve-Versuche teilen außerdem `resolving` und einen einzigen Watchdog; ein verspäteter
  Callback kann den Watchdog eines nachfolgenden Versuchs entfernen
  ([KeepADBEndpoint.java:270-356](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java#L270-L356)).
- **Erwartet:** Ergebnisse dürfen ausschließlich den Lauf und Resolve-Versuch abschließen, der sie
  erzeugt hat. `stop()` beziehungsweise eine neue Generation muss sämtliche älteren Ergebnisse
  logisch ungültig machen.
- **Trigger/Kontrollfluss:** Ein Reachability- oder Resolve-Callback verzögert sich; inzwischen
  läuft `stop()` und anschließend eine neue Discovery. Das alte Ergebnis gewinnt das globale CAS,
  setzt den neuen Lauf auf „ausgeliefert“ oder ruft dessen Listener mit dem alten Endpunkt auf.
- **Auswirkung:** Falscher Endpunkt, unterdrücktes Timeout oder dauerhaft blockierte neue
  Discovery. Das Verhalten ist timingabhängig und im Feld schwer reproduzierbar.
- **Testidee:** Resolver und Reachability über Latches steuern: Generation A bis kurz vor CAS
  pausieren, stoppen, Generation B starten, dann A freigeben. B darf weder Callback noch
  `endpointDelivered` von A übernehmen. Analog einen verspäteten Resolve-Callback gegen den
  Watchdog des nächsten Versuchs testen.
- **Minimaler Fix:** Ergebniszustand an ein unveränderliches Run-/Attempt-Token binden. Generation
  unmittelbar vor CAS und erneut im Lock prüfen; pro Resolve-Versuch eine ID und einen eigenen
  Watchdog verwenden. Alte Tasks nur ignorieren, nie gemeinsamen Zustand mutieren lassen.

### H3 — Endpunkt-Erkennung bestätigt Port-Erreichbarkeit, nicht Geräteidentität

**Klassifikation:** High · Must-Fix

- **Beobachtet:** Der Quick-Probe scannt Loopback auf beliebige offene Ports im Bereich
  30000–50000 und nimmt den ersten Treffer
  ([KeepADBEndpoint.java:400-451](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java#L400-L451)).
  Danach ersetzt er Loopback durch die WLAN-IPv4-Adresse, ohne denselben Port dort erneut zu
  prüfen oder als `adbd` zu identifizieren
  ([KeepADBEndpoint.java:238-264](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java#L238-L264)).
  Der mDNS-Pfad filtert nur auf den Diensttyp und prüft anschließend lediglich, ob TCP erreichbar
  ist
  ([KeepADBEndpoint.java:145-154](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java#L145-L154),
  [KeepADBEndpoint.java:308-349](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java#L308-L349)).
- **Erwartet:** Gemeldet werden darf nur der Wireless-Debugging-Endpunkt dieses Geräts.
- **Trigger/Kontrollfluss:** Eine andere lokale App lauscht auf einem Port im Scanbereich, ein
  fremdes Android-Gerät oder LAN-Dienst annonciert `_adb-tls-connect`, oder ein Dienst ist nur an
  Loopback gebunden. Der erste erreichbare Treffer beendet die echte Discovery.
- **Auswirkung:** Webhook und UI veröffentlichen einen fremden oder unbrauchbaren Endpunkt; ein
  angreifbarer LAN-Teilnehmer kann den veröffentlichten Zielhost/-port beeinflussen.
- **Zusatzbefund IPv6:** mDNS kann eine IPv6-Adresse liefern, während der Quick-Pfad nur eine
  IPv4-WLAN-Adresse auswählt
  ([KeepADBEndpoint.java:560-597](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java#L560-L597)).
  `host + ":" + port` erzeugt für IPv6 eine mehrdeutige Darstellung
  ([KeepADBRegisterClient.java:71-92](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBRegisterClient.java#L71-L92)).
- **Testidee:** Parallel einen fremden Loopback-Listener und einen fremden mDNS-Service
  bereitstellen; beide dürfen nie als eigener ADB-Endpunkt geliefert werden. Zusätzlich IPv6-only,
  Link-Local-Scope und bracketed `host:port` abdecken.
- **Minimaler Fix:** mDNS-Adresse gegen aktive lokale Link-Adressen des Geräts korrelieren und
  Quick-Probe-Treffer auf der tatsächlich veröffentlichten WLAN-Adresse validieren. Die Auswahl
  muss ADB-spezifische Evidenz verwenden; nicht den ersten beliebigen TCP-Port akzeptieren. Host
  und Port intern getrennt halten, IPv6 bei Darstellung/Transport korrekt klammern.

### H4 — Wechsel der Webhook-URL verliert die Registrierungsidentität

**Klassifikation:** High · Must-Fix

- **Beobachtet:** `KeepADBRegisterClient` merkt sich nur den zuletzt registrierten Endpunkt. Ist
  dieser unverändert, wird vor dem POST abgebrochen
  ([KeepADBRegisterClient.java:71-92](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBRegisterClient.java#L71-L92)).
  Die Settings überschreiben zuerst die URL und starten dann einen Refresh
  ([SettingsActivity.java:75-102](../../app/src/main/java/de/hohnepeople/keepadb/SettingsActivity.java#L75-L102)).
  Die alte URL ist danach nicht mehr als Ziel einer Deregistrierung verfügbar. Queued Work führt
  URL und Endpunkt zudem nicht als atomaren Operationszustand.
- **Erwartet:** Eine Registrierung ist das Paar `(URL, Endpunkt)`. Beim URL-Wechsel muss die alte
  URL zuverlässig deregistriert und die neue URL registriert werden, auch wenn der Endpunkt gleich
  bleibt.
- **Trigger/Kontrollfluss:** Endpunkt E ist bei URL A registriert; der Nutzer speichert URL B.
  `updateEndpointAsync` sieht erneut E und kehrt zurück. A behält E, B erhält nichts, während die
  UI bereits B zeigt.
- **Auswirkung:** Stale Remote-Registrierung, potenzieller Datenschutzverlust und falscher
  UI-Erfolg. Bei Queue-Rennen kann ein neuer Endpunkt an eine alte URL gesendet werden.
- **Testidee:** A/E erfolgreich registrieren, zu B wechseln und E unverändert lassen. Erwartete
  Reihenfolge: DELETE A/E, dann POST B/E; Netzwerkfehler und schnelle URL-/Endpunktwechsel
  deterministisch simulieren.
- **Minimaler Fix:** Zuletzt bestätigte URL gemeinsam mit dem Endpunkt persistieren. Queue-Einträge
  als unveränderliche `(generation, action, url, endpoint)` modellieren und URL-Wechsel geordnet
  als DELETE alt → POST neu ausführen; Fehlerzustand sichtbar und retryfähig halten.

### M1 — Recovery-Puls kann eine bewusste Nutzer-Deaktivierung überschreiben

**Klassifikation:** Medium · Must-Fix

- **Beobachtet:** Der Recovery-Puls prüft Zustand und Generation nur vor dem Start des Threads,
  schaltet dann aus, wartet 800 ms und schaltet unbedingt wieder ein
  ([KeepADBEndpoint.java:201-222](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java#L201-L222)).
  `stop()` kann den Thread nicht abbrechen. Weitere Service-Rechecks berücksichtigen den
  `userDisabled`-Marker nicht durchgehend.
- **Erwartet:** Die jüngste explizite Nutzerentscheidung „aus“ muss jede automatische
  Wiederaktivierung schlagen.
- **Trigger:** Nutzer schaltet während eines laufenden Recovery-Pulses aus oder ein NetworkCallback
  reaktiviert zeitgleich.
- **Auswirkung:** Wireless Debugging wird gegen den Nutzerwillen wieder aktiviert.
- **Testidee:** Recovery nach dem Off-Schritt pausieren, Nutzer-Off einspeisen, Recovery fortsetzen;
  Endzustand muss aus bleiben.
- **Minimaler Fix:** Intent-/Versions-Token für manuelle und automatische Zustandswechsel; Restore
  nur, wenn der Recovery-Off-Schritt noch der jüngste gültige Intent ist. Alle Auto-Enable-Pfade
  müssen denselben Guard verwenden.

### M2 — Deregistrierung meldet Erfolg trotz DELETE-Fehler

**Klassifikation:** Medium · Must-Fix

- **Beobachtet:** `unregisterAndDisableAsync` ignoriert den Rückgabewert von `deleteEndpoint`,
  löscht anschließend lokalen Zustand und meldet Deregistrierung
  ([KeepADBRegisterClient.java:139-155](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBRegisterClient.java#L139-L155)).
- **Erwartet:** Lokaler „nicht registriert“-Zustand erst nach bestätigtem 2xx; ein fehlgeschlagener
  DELETE bleibt als ausstehende Operation sichtbar.
- **Trigger:** Timeout, DNS-/TLS-Fehler oder HTTP-Fehler während DELETE.
- **Auswirkung:** Remote bleibt der Endpunkt gespeichert, aber ein späterer Retry ist lokal nicht
  mehr möglich.
- **Testidee:** DELETE zunächst fehlschlagen, Prozess neu starten, später Erfolg zulassen; die
  ausstehende Deregistrierung muss erhalten bleiben und abgeschlossen werden.
- **Minimaler Fix:** URL/Endpunkt und Pending-DELETE persistieren, nur bei 2xx löschen, begrenzten
  Backoff-Retry und sichtbaren Fehlerstatus vorsehen.

### M3 — Debounce bestätigt noch nicht ausgeführte Schaltvorgänge als Erfolg

**Klassifikation:** Medium · Must-Fix

- **Beobachtet:** `setEnabled` gibt bei einem verzögert eingeplanten Toggle sofort `true` zurück
  ([KeepADB.java:56-76](../../app/src/main/java/de/hohnepeople/keepadb/KeepADB.java#L56-L76)).
  Activity, Tile und Widget lesen anschließend sofort den noch alten Live-Zustand; nach der
  verzögerten Ausführung gibt es keinen gemeinsamen Completion-Callback
  ([MainActivity.java:51-59](../../app/src/main/java/de/hohnepeople/keepadb/MainActivity.java#L51-L59),
  [KeepADBTileService.java:16-35](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBTileService.java#L16-L35),
  [KeepADBWidget.java:20-50](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBWidget.java#L20-L50)).
- **Erwartet:** UI-Erfolg und angezeigter Zustand folgen dem tatsächlich geschriebenen Wert.
- **Trigger:** Mehrere Aktionen innerhalb von 1,5 Sekunden, insbesondere wiederholte Tile-/Widget-
  Taps, die alle vom alten Wert aus toggeln.
- **Auswirkung:** Falsche Toasts/Anzeige und unerwartet kollabierte Toggle-Folge.
- **Testidee:** Deterministische Uhr/Looper: off→on→off innerhalb des Cooldowns; tatsächliche
  Writes, endgültiger Zustand und UI-Callbacks exakt prüfen.
- **Minimaler Fix:** Asynchrones Completion-Ergebnis oder zentraler beobachtbarer Toggle-State;
  gewünschte Zustandsübergänge statt erneuter Berechnung aus stale live state serialisieren.

### M4 — Webhook-Transport ist zu permissiv und kann Zielinformationen leaken

**Klassifikation:** Medium · Must-Fix

- **Beobachtet:** Die App erlaubt global Cleartext-Traffic
  ([network_security_config.xml:3](../../app/src/main/res/xml/network_security_config.xml#L3));
  die URL-Prüfung akzeptiert HTTP
  ([KeepADBPreferences.java:74-87](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBPreferences.java#L74-L87)).
  `HttpURLConnection`-Redirects werden nicht deaktiviert oder auf Same-Origin geprüft. Fehlerlogs
  enthalten die vollständige Ziel-URL
  ([KeepADBRegisterClient.java:157-215](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBRegisterClient.java#L157-L215)).
- **Erwartet:** HTTPS als sicherer Standard; keine unkontrollierten Cross-Origin-Redirects und
  keine Secrets aus Userinfo/Query in Logs.
- **Trigger:** HTTP-Konfiguration, 3xx auf fremden Host oder URL mit Token in Query/Userinfo.
- **Auswirkung:** Endpunkt-/Token-Offenlegung im LAN oder Logcat und unerwartete Datenweitergabe.
- **Testidee:** Redirects gleicher und anderer Origin/Protokolle, HTTP-MITM sowie URL mit
  Query-Secret testen; Logs dürfen nur redigierte Host-Informationen enthalten.
- **Minimaler Fix:** HTTPS voraussetzen oder HTTP eng und ausdrücklich opt-in behandeln;
  Redirects deaktivieren beziehungsweise Ziel nach jedem Redirect validieren; URLs in Logs
  redigieren. Android empfiehlt domänenspezifische Network-Security-Regeln statt einer globalen
  Cleartext-Freigabe: <https://developer.android.com/privacy-and-security/security-config>.

### M5 — Auto Backup umfasst geräte- und datenschutzsensitive Preferences

**Klassifikation:** Medium · Must-Fix

- **Beobachtet:** `android:allowBackup="true"` ist aktiv, ohne
  `fullBackupContent`/`dataExtractionRules`
  ([AndroidManifest.xml:15-23](../../app/src/main/AndroidManifest.xml#L15-L23)). Preferences
  enthalten Webhook-URL, zuletzt gemeldeten Endpunkt, Keep-Alive- und Heartbeat-Zustand.
- **Erwartet:** Gerätespezifische Endpunkte und externe Ziele werden nicht ungeprüft auf ein
  anderes Gerät restauriert.
- **Trigger:** Android Auto Backup und Restore auf ein neues beziehungsweise zurückgesetztes
  Gerät.
- **Auswirkung:** Stale Remote-Ziele und Einstellungen können auf einem anderen Gerät aktiv
  erscheinen, noch bevor `WRITE_SECURE_SETTINGS` erneut gewährt wurde.
- **Testidee:** Backup/Restore mit gesetzter URL, registriertem Endpunkt und aktivem Keep-Alive;
  sensible Schlüssel müssen ausgeschlossen oder bewusst migriert sein.
- **Minimaler Fix:** Backup deaktivieren oder sensible/device-bound Schlüssel über beide
  Regeldateien ausschließen. Referenz:
  <https://developer.android.com/identity/data/autobackup>.

### M6 — Hintergrundarbeit ist nicht vollständig gebunden oder koalesziert

**Klassifikation:** Medium · Follow-up-Issue

- **Beobachtet:** Ein statischer Executor mit acht Core-Threads besitzt keinen Shutdown und kein
  Core-Timeout; Reachability und Recovery verwenden zusätzliche rohe Threads
  ([KeepADBEndpoint.java:55-62](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java#L55-L62),
  [KeepADBEndpoint.java:201-222](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java#L201-L222),
  [KeepADBEndpoint.java:329-349](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java#L329-L349)).
  Notification-Refreshes können parallele Cached-Endpoint-Prüfungen starten
  ([KeepADBNotification.java:135-157](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java#L135-L157)).
- **Erwartet:** Eine begrenzte, abbrechbare und pro Zweck koaleszierte Work-Queue.
- **Trigger:** Häufige Activity-/Tile-/Widget-Refreshes und wiederholte Discovery-Zyklen.
- **Auswirkung:** Thread-/Socket-Spitzen, verspätete stale Callbacks und unnötiger Akkuverbrauch.
- **Testidee:** 100 schnelle Refreshes; maximale Parallelität, Thread-Rückgang und keine Callbacks
  nach `stop()` prüfen.
- **Minimaler Fix:** Gemeinsamen bounded Executor verwenden, in-flight Checks koaleszieren,
  Futures/Generation abbrechbar machen und Core-Thread-Timeout zulassen.

### M7 — Exportierter BootReceiver vertraut auf eine normale Permission

**Klassifikation:** Medium · Must-Fix

- **Beobachtet:** Der Receiver ist exportiert und mit `RECEIVE_BOOT_COMPLETED` geschützt
  ([AndroidManifest.xml:66-73](../../app/src/main/AndroidManifest.xml#L66-L73)). Diese Permission
  hat Protection Level `normal`, ist also keine Sender-Authentisierung. Zusätzlich akzeptiert der
  Receiver den OEM-String `android.intent.action.QUICKBOOT_POWERON`
  ([BootReceiver.java:13-29](../../app/src/main/java/de/hohnepeople/keepadb/BootReceiver.java#L13-L29)).
- **Erwartet:** Nur vertrauenswürdige System-Boot-Ereignisse dürfen den Foreground-Service starten
  und bei aktivem Keep-Alive Wireless Debugging einschalten.
- **Trigger:** Dritt-App mit der normalen Permission sendet — sofern die OEM-Action selbst nicht
  systemseitig geschützt ist — einen expliziten QUICKBOOT-Broadcast.
- **Auswirkung:** Ungewollter Service-Start und mögliches Einschalten von Wireless Debugging.
- **Testidee:** Auf Zielgerät und AOSP-Emulator expliziten Broadcast aus einer Test-App senden;
  System-Boot muss weiter funktionieren, Fremdsender nicht.
- **Minimaler Fix:** Receiver, soweit für Systembroadcasts möglich, nicht exportieren; andernfalls
  OEM-Action entfernen oder belastbare Sender-/Signaturgrenze verwenden. FGS-Startfehler explizit
  behandeln. Permission-Referenz:
  <https://developer.android.com/reference/android/Manifest.permission#RECEIVE_BOOT_COMPLETED>.

### M8 — App-Sprache hat ab API 33 zwei Wahrheitsquellen

**Klassifikation:** Medium · Follow-up-Issue

- **Beobachtet:** Die Auswahl liest ab API 33 `LocaleManager`, `wrapContext` liest aber immer die
  SharedPreferences
  ([KeepADBLocaleHelper.java:50-104](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBLocaleHelper.java#L50-L104)).
  Eine Sprachänderung in den Android-Systemeinstellungen kann daher einen stale Preference-Wert
  hinterlassen. `setAppLanguage` stößt nach der Plattformänderung zusätzlich `recreate()` an.
  Der Tile-Service verwendet keinen lokalisierten Wrapper.
- **Erwartet:** Ab API 33 ist die Plattform-Locale die einzige Wahrheit; ältere APIs nutzen die
  Preference. Alle Oberflächen zeigen dieselbe Sprache.
- **Trigger:** Sprache über Android-App-Einstellungen ändern oder auf API 30–32 den Tile öffnen.
- **Auswirkung:** Gemischte beziehungsweise zurückspringende Sprache und mögliche Doppel-
  Recreation.
- **Testidee:** API 30/32/33/35: Sprache abwechselnd in App und System ändern, Prozess neu starten,
  Activity, Settings, Notification, Widget und Tile vergleichen.
- **Minimaler Fix:** Preference nur pre-33 beziehungsweise einmalig zur Migration verwenden;
  danach Plattform-Locale lesen. Tile-Kontext lokalisieren und unnötiges explizites `recreate()`
  vermeiden. Referenz: <https://developer.android.com/guide/topics/resources/app-languages>.

### M9 — Tests decken die riskanten Zustandsautomaten nicht ab

**Klassifikation:** Medium · Must-Fix zusammen mit H1–H4

- **Beobachtet:** `KeepADBTest` prüft nur den Default des User-Disable-Flags
  ([KeepADBTest.java:9-12](../../app/src/test/java/de/hohnepeople/keepadb/KeepADBTest.java#L9-L12)).
  `KeepADBEndpointTest` implementiert einen separaten Scanner statt Produktionscode zu testen,
  verwendet einen festen Port und ungebundene Thread-Joins
  ([KeepADBEndpointTest.java:15-57](../../app/src/test/java/de/hohnepeople/keepadb/KeepADBEndpointTest.java#L15-L57)).
  Es fehlen Tests für Service-Liveness, Discovery-Generationen, Webhook-URL-Wechsel,
  Fehler-Retry, Debounce-Completion und Nutzer-Intent-Rennen.
- **Erwartet:** Die nebenläufigen Zustandsübergänge sind über injizierbare Uhr,
  Executor/Handler, Resolver und HTTP-Transport deterministisch testbar.
- **Trigger:** Timing-, Netzwerk- oder Prozess-Lifecycle-Änderungen.
- **Auswirkung:** Die High-Befunde und Regressionen bleiben im lokalen Gate unsichtbar.
- **Testidee:** Die in H1–H4 und M1–M3 beschriebenen Latch-/Fake-Szenarien als fokussierte
  Unit-/Integrationstests umsetzen.
- **Minimaler Fix:** Kleine Produktions-Seams statt eines zweiten Scanners; keine realen Ports oder
  Wall-Clock-Abhängigkeit in Unit-Tests, alle Threads/Futures mit begrenztem Abschluss.

### M10 — Release-Workflow veröffentlicht ein Debug-APK

**Klassifikation:** Medium · Must-Fix vor dem nächsten Release

- **Beobachtet:** Der Release-Workflow baut `assembleDebug` und hängt das Debug-APK an ein Release
  ([release.yml:33-42](../../.github/workflows/release.yml#L33-L42)). Release-Signing,
  Unit-/Lint-Gates, Tag-/Versionsabgleich und Checksumme fehlen.
- **Erwartet:** Ein reproduzierbares, nicht debuggable und kontrolliert signiertes Release-Artefakt
  entsteht erst nach den lokalen Pflichtgates.
- **Trigger:** Manuelles Auslösen des Workflows für einen Tag.
- **Auswirkung:** Debug-Key/-Flags im veröffentlichten APK und fehlende Qualitätsbarriere.
- **Testidee:** Lokal signierten Release-Build erzeugen und mit `apksigner verify`, Manifestdump,
  Versions-/Tagabgleich sowie SHA-256 prüfen.
- **Minimaler Fix:** `assembleRelease` plus Signing-Secrets, explizite lokale Gate-Nachweise und
  Artefaktprüfung. Gemäß Projektregel derzeit **keinen** Actions-Run auslösen.

### L1 — Abgelehnte Notification-Permission bleibt unsichtbar

**Klassifikation:** Low · Follow-up-Issue

- **Beobachtet:** Die Permission wird beim Activity-Start angefragt; Ablehnung wird im Resultat
  nicht erklärt
  ([MainActivity.java:44-49](../../app/src/main/java/de/hohnepeople/keepadb/MainActivity.java#L44-L49),
  [MainActivity.java:137-143](../../app/src/main/java/de/hohnepeople/keepadb/MainActivity.java#L137-L143)).
  Notification-Pfade kehren ohne Permission still zurück
  ([KeepADBNotification.java:266-285](../../app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java#L266-L285)).
- **Erwartet:** Nutzer versteht, dass Keep-Alive laufen kann, die versprochene Statusanzeige aber
  fehlt, und erhält einen Weg zu den Systemeinstellungen.
- **Testidee/Fix:** API 33 Permission dauerhaft ablehnen; Inline-Hinweis mit Settings-Link und
  korrektem Service-Verhalten ergänzen.

### L2 — Kleine Touch-Ziele und richtungsgebundene Zeichen

**Klassifikation:** Low · Follow-up-Issue

- **Beobachtet:** Haupt-ImageButton 40×40 dp
  ([activity_main.xml:31-38](../../app/src/main/res/layout/activity_main.xml#L31-L38));
  mehrere Settings-Buttons liegen bei 36–40 dp und der Zurück-Button verwendet ein hartcodiertes
  `←`
  ([activity_settings.xml:18-33](../../app/src/main/res/layout/activity_settings.xml#L18-L33),
  [activity_settings.xml:224-255](../../app/src/main/res/layout/activity_settings.xml#L224-L255)).
- **Erwartet:** Mindestens 48×48 dp Touch-Ziel und RTL-adaptive Navigation.
- **Auswirkung:** Schlechtere Bedienbarkeit mit motorischen Einschränkungen und falsche Richtung in
  RTL-Sprachen.
- **Testidee/Fix:** Accessibility Scanner/TalkBack und RTL-Pseudolocale; Touch-Ziele auf 48 dp
  vergrößern und ein start-richtungsabhängiges Icon mit Content Description verwenden. Referenz:
  <https://developer.android.com/guide/topics/ui/accessibility/apps.html>.

### L3 — README-Aussagen stimmen nicht vollständig mit Implementierung/Artefakt überein

**Klassifikation:** Low · Follow-up-Issue

- **Beobachtet:** README beschreibt mDNS als kontinuierlich, obwohl Discovery nach dem ersten Fund
  stoppt; die Behauptung „APK < 350 KB“ widerspricht dem vorhandenen Debug-Artefakt mit 460.192
  Byte. Der dokumentierte Lint-Befehl bildet das verbindliche lokale Gate aus Unit-Test, Lint,
  Assemble und Gerätetest nicht vollständig ab.
- **Erwartet:** Produkt- und Build-Dokumentation beschreibt den tatsächlich ausgelieferten Stand.
- **Einschränkung:** Das vorhandene APK wurde kurz vor dem aktuellen Dokumentations-HEAD gebaut;
  ein exakter HEAD-Rebuild wurde mangels Testfreigabe nicht ausgeführt.
- **Testidee/Fix:** Nach freigegebenem lokalen Vollgate exakte APK-Größe übernehmen, mDNS-/Keep-
  Alive-Text an H1-Fix angleichen und vollständiges lokales Abnahme-Kommando dokumentieren.

## Geprüfte Bereiche ohne Befund

- Build-Konfiguration: `compileSdk`/`targetSdk` 35, `minSdk` 30, Java 17 und keine Runtime-
  Dependencies entsprechen dem Auftrag.
- Manifest-Exposure außerhalb M7: SettingsActivity, Service und WidgetReceiver sind nicht
  exportiert; der Tile-Service ist durch `BIND_QUICK_SETTINGS_TILE` geschützt.
- `connectedDevice`-Foreground-Service-Type und die vorhandenen Permissions sind für den
  beschriebenen WLAN-Anwendungsfall plausibel. Dieser Typ gehört nicht zu den auf API 35 vom
  Boot-Start ausgeschlossenen Typen. Referenzen:
  <https://developer.android.com/develop/background-work/services/fgs/service-types> und
  <https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>.
- PendingIntents sind immutable.
- Selector, SocketChannels, MulticastLock, NSD-Listener, ContentObserver, NetworkCallback und
  Heartbeat-Callback besitzen grundsätzlich passende Cleanup-Pfade; M6 betrifft zusätzlich
  erzeugte Worker und Koaleszierung.
- Alle Oberflächen lesen `adb_wifi_enabled` live; es existiert kein persistierter Schattenzustand
  für den Android-Schalter.
- Alle 19 geprüften Übersetzungsdateien enthalten dieselben 59 Schlüssel und konsistente
  Format-Platzhalter. Der Setup-Befehl für `WRITE_SECURE_SETTINGS` ist konsistent.
- API-abhängige Aufrufe sind im statisch geprüften Code passend gegen die Mindestversion
  abgegrenzt.

## Nicht ausgeführte Verifikation

Aufgrund der Projektregel „keine Tests oder externen Geräteaktionen ohne ausdrückliche Freigabe“
wurden folgende Schritte bewusst nicht ausgeführt:

- `git diff --check`
- `./gradlew testDebugUnitTest lintDebug assembleDebug`
- APK-Installation, Tile-Toggle, `settings get global adb_wifi_enabled`, Logcat und Reboot-Test auf
  dem registrierten S20-Transport
- dynamische Reproduktion der Discovery-Rennen, des Recovery-vs.-User-Off-Rennens, fremder
  mDNS-/Loopback-Dienste sowie Webhook-URL-/DELETE-Fehlerpfade
- Laufzeitmatrix API 30/32/33/35, Locale-/RTL-Sichtprüfung und Accessibility Scanner/TalkBack
- exakter Größenvergleich eines aus dem Review-HEAD gebauten APK

Es wurde kein GitHub-Actions-Workflow ausgelöst. Für den Review-HEAD existiert kein eigener
CI-Nachweis; das ist mit der aktuellen Projektregel vereinbar, ersetzt aber nicht die noch offene
lokale Abnahme.

## Angelegte Follow-up-Issues

Die 17 Findings wurden in acht umsetzbare Pakete gebündelt. Jedes Finding ist in mindestens
einem Issue ausdrücklich referenziert:

1. [#125 Discovery-State-Machine generationssicher machen](https://github.com/m00sfett/KeepADB/issues/125) — H2, M6, M9; Resolve-Attempt-IDs und Cancellation/Koaleszierung.
2. [#126 Eigenen ADB-Endpunkt belastbar identifizieren und dauerhaft überwachen](https://github.com/m00sfett/KeepADB/issues/126) — H1, H3, IPv6 und Link-Änderungen.
3. [#127 Manuelle Toggle-Intention gegenüber Recovery/Keep-Alive priorisieren](https://github.com/m00sfett/KeepADB/issues/127) — M1 und M3.
4. [#128 Webhook als transaktionalen `(URL, Endpunkt)`-Zustand modellieren](https://github.com/m00sfett/KeepADB/issues/128) — H4, M2, Retry und Prozessneustart.
5. [#129 Webhook-Datenschutz härten](https://github.com/m00sfett/KeepADB/issues/129) — M4 und M5 inklusive redigierter Diagnostik.
6. [#130 Boot-Einstieg absichern](https://github.com/m00sfett/KeepADB/issues/130) — M7 inklusive Zielgeräteprüfung der OEM-Action.
7. [#131 Locale-, Notification- und Accessibility-Nacharbeiten](https://github.com/m00sfett/KeepADB/issues/131) — M8, L1 und L2.
8. [#132 Lokale Test- und Release-Gates belastbar machen](https://github.com/m00sfett/KeepADB/issues/132) — M9, M10 und L3; keine GitHub-Actions-Ausführung.

## Repository- und Prozessstatus

- Repository: `m00sfett/KeepADB`, privat, Default-Branch `master`.
- Review-Startzustand: sauberer Worktree; lokaler HEAD und `origin/master` identisch auf
  `e88ef7309c63006f9890d31571294cff8913e633`.
- GitHub: acht neue offene Follow-up-Issues (#125–#132), keine offenen Pull Requests, keine
  aktiven Workflow-Runs.
- Reviewdurchführung: ein Hauptlauf, keine Subagenten, keine doppelten Reviews, keine Tests oder
  externen Aktionen.
- Durch dieses Review wurden bis zur expliziten Dokumentationsanforderung keine Dateien geändert.
  Diese Datei ist die einzige beabsichtigte neue Projektdatei des Reviewlaufs.
