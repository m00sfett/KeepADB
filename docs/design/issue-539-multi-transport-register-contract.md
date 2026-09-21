# Vertragsentwurf: Mehrere verifizierte ADB-Transporte im Webhook-/Register-Vertrag (#539)

Stand: 2026-09-21. Gilt für den Webhook-POST der KeepADB-App an
`http://<register-host>:<port>/register/<alias>` und für die Lesesicht der bestehenden
`GET`-Verbraucher (`phone-register`, `android-target`, direkte `curl`-Abfragen).

## 1. Ausgangslage, gemessen statt angenommen

Der Issue-Text von #539 legt nahe, der Vertrag sei auf beiden Seiten noch offen. Die Prüfung des
tatsächlich laufenden `phone-register-server` (`~/agent/bin/phone-register-server`,
`~/agent/bin/phone_register_common.py`) zeigt ein anderes Bild:

| Bestandteil | Serverseitig vorhanden | App-seitig vorhanden (vor #539) |
|---|---|---|
| `contract_version` (Wert 2) | ja, validiert | nein |
| Getrennte Transport-Slots je `method` | ja (`transports`, `merge_transport`) | entfällt |
| Idempotenz über `event_id` | ja (`event_status` → `new`/`duplicate`/`stale`) | nein |
| Reihenfolgeschutz über `observed_at` | ja | nein |
| Rückwärtskompatible Projektion `last_successful_reach` | ja (`project_legacy`) | entfällt |
| Schreibtransaktion unter Dateisperre | ja (`register_lock`) | entfällt |
| Akzeptierte `method`-Werte | `wlan-adb`, `ssh-termux`, `usb-ssh-tunnel` | nur `wlan-adb` gesendet |

Die App war damit der einzige Verbraucher, der noch die Vor-v2-Nutzlast
`{"method":"wlan-adb","endpoint":"IP:Port"}` gesendet hat. Der Mehrtransport-Teil des Vertrags
existiert serverseitig bereits; es fehlen dort nur zwei konkrete Punkte (Abschnitt 7).

**Abgrenzung zu #416.** #416 wollte beide Seiten gleichzeitig entwerfen und umsetzen, inklusive
USB-Hostprofilen, Host-originierten Endpunkten und SSH-Feldern. Dieser Entwurf setzt tiefer an
und ist enger: Er beschreibt genau den Teil, den die App als Sender erzeugt, nimmt das bereits
deployte Serververhalten als gegeben hin statt es neu zu definieren, führt keine Hostprofil- oder
SSH-Felder ein und verlangt keine Änderung am produktiv laufenden Server, um wirksam zu werden.
Die USB-Hybridvariante aus #416 (Host meldet den nutzbaren USB-Endpunkt) bleibt möglich, ist hier
aber weder Voraussetzung noch stillschweigend reaktiviert.

## 2. Ereignismodell

Ein Webhook-POST beschreibt **genau einen Transport zu genau einem Zeitpunkt**, nicht den
Gesamtzustand des Geräts. Mehrere verifizierte Transporte erzeugen mehrere POSTs. Daraus folgt
unmittelbar die Anforderung aus #539, dass WLAN, Tailscale und USB sich nicht gegenseitig löschen:
Ein Ereignis kann per Konstruktion nur den Slot berühren, dessen `method` es nennt.

Es gibt keinen „alles andere ist jetzt weg“-Modus. Fällt ein Transport aus, ist das ein eigenes
Deaktivierungsereignis mit ebendieser `method` (Abschnitt 4).

## 3. Nutzlast eines aktiven Transports

```json
{
  "contract_version": 2,
  "method": "wlan-adb",
  "active": true,
  "endpoint": "192.168.1.50:41234",
  "source": "keepadb-app",
  "observed_at": "2026-09-21T09:41:02.113Z",
  "event_id": "keepadb-v2-wlan-adb-3f1c0a9b2d4e6f81"
}
```

| Feld | Pflicht | Bedeutung |
|---|---|---|
| `contract_version` | ja | Ganzzahl `2`. Der Server weist jeden anderen Wert ab; Fehlen bedeutet Vor-v2-Client. |
| `method` | ja | Transportklasse, siehe Tabelle unten. Bestimmt den Slot. |
| `active` | ja | `true` = verifiziert erreichbar, `false` = Deaktivierung dieses Slots. |
| `endpoint` | bei Netztransporten ja | `IP:Port`, exakt der verifizierte ADB-Endpunkt. Bei USB entfällt das Feld. |
| `source` | ja | `keepadb-app` für app-originierte Meldungen; grenzt sie von host-originierten ab. |
| `observed_at` | ja | ISO-8601 UTC des letzten erfolgreichen Nachweises, **nicht** des Sendezeitpunkts. |
| `event_id` | ja | Idempotenzschlüssel, siehe Abschnitt 5. |

Transportklassen:

| `method` | Transport | Endpunkt | Quelle der Verifikation |
|---|---|---|---|
| `wlan-adb` | WLAN/LAN | `IP:Port` | erreichbarkeitsgeprüfter Cache-Endpunkt der App |
| `tailscale-adb` | Tailscale/VPN | `IP:Port` (CGNAT-Adresse `100.64.0.0/10`) | eigener Socket-ADB-Nachweis |
| `usb-adb` | USB | keiner | System-Broadcast `connected` + `configured` + `adb` |

WLAN und Tailscale bleiben **getrennte Slots**, auch wenn sie momentan denselben ADB-Port teilen.
Sie sind über verschiedene Wege erreichbar und verfallen unabhängig voneinander; ein gemeinsamer
Slot würde genau die Überschreibung wiederherstellen, die #539 beseitigen soll.

Bewusst **nicht** im Vertrag:

- Kein `primary`-Feld. Die Primärwahl leitet der Server aus den Slots ab (Abschnitt 6); zwei
  Quellen der Wahrheit für dieselbe Aussage wären eine Fehlerquelle ohne Gegenwert.
- Keine Tokens, Schlüssel oder fest codierten Endpunkte. Ziel bleibt die vom Nutzer eingetragene,
  app-gebundene Webhook-URL aus den SharedPreferences (`AGENTS.md`, Abschnitt
  „Webhook-Konfiguration“). Die Datenschutzentscheidung aus Issue #64 bleibt unberührt.
- Keine Hostprofil-, SSH- oder Fingerprint-Felder aus der App. Ein Fingerprint-Bezug ist nur dort
  belastbar, wo er tatsächlich geprüft wurde, also auf dem Host; die App würde ihn nur behaupten.
  Der Server nimmt `verified_fingerprint` und `adb_server_port` weiterhin von host-originierten
  Meldungen entgegen und führt sie im selben Slot zusammen.

## 4. Deaktivierung

```json
{"contract_version": 2, "method": "wlan-adb", "active": false,
 "source": "keepadb-app", "observed_at": "...", "event_id": "..."}
```

Die `method` ist hier **zwingend explizit**. Der Server weist eine Deaktivierung ohne eigene
`method` ab, weil der Slot sonst aus der Legacy-Projektion geerbt würde und dasselbe Request je
nach Registerhistorie einen anderen Transport träfe. Die historische Form `{"endpoint": ""}` eines
Vor-v2-Clients behält ihr altes, aliasweites Verhalten; ein v2-Client darf sie nicht senden.

## 5. Idempotenz, Reihenfolge und Stale-/TTL-Verhalten

- **`event_id` leitet sich ausschließlich aus dem gemeldeten Zustand ab**
  (`method` + `endpoint` + `active`, als SHA-256-Präfix), nicht aus der Uhrzeit. Eine
  unveränderte Wiederholung trägt denselben Schlüssel, der Server antwortet `duplicate` und lässt
  den gespeicherten Slot unangetastet. Jede echte Zustandsänderung erzeugt einen anderen
  Schlüssel und wird angewandt. Damit sind Wiederholungen, Retries nach Netzfehler und parallel
  eintreffende Meldungen desselben Zustands folgenlos.
- **`observed_at` entscheidet die Reihenfolge.** Trifft ein Ereignis mit älterem `observed_at` ein
  als der gespeicherte Slot, antwortet der Server `409` mit `status: "stale"` und verwirft es.
  Eine verspätet zugestellte Altmeldung kann einen neueren Endpunkt also nicht zurückdrehen.
- **TTL ist Lesesicht, nicht Speicherzustand.** Der Server verfallslässt Einträge nicht, sondern
  bewertet sie beim Lesen gegen `ttl_seconds` (Default 86400). Ein Slot jenseits der TTL wird als
  nicht aktuell erreichbar ausgewiesen, bleibt aber für die Diagnose lesbar. Die App verlängert
  eine TTL nie durch bloßes Wiedersenden desselben Zustands — das ist die bewusst in Kauf
  genommene Kehrseite der zustandsbasierten `event_id`: Ein Transport, der sich lange nicht
  ändert, altert in der Lesesicht aus, statt eine Erreichbarkeit zu behaupten, die zuletzt vor
  einem Tag geprüft wurde. Wer eine Auffrischung braucht, meldet einen neu verifizierten Zustand.
- **Nur Verifiziertes wird gemeldet.** Ein Kandidat, eine bloße Interface-Adresse oder ein aktives
  VPN ohne ADB-Nachweis erzeugt kein Ereignis. Es entsteht insbesondere auch kein
  Deaktivierungsereignis daraus: „nichts verifiziert“ ist keine Aussage über einen fremden Slot.

## 6. Primär-/Kompatibilitätsprojektion für bestehende `GET`-Leser

`GET /register/<alias>` liefert unverändert `last_successful_reach` als flache Sicht mit
`endpoint`, `ip`, `port`, `method`, `active` und Status. Der Server bildet sie aus den Slots:

1. Unter allen Slots mit nutzbarem Endpunkt (aktiv, `endpoint` gesetzt, Status `available`)
   gewinnt der zuletzt aktualisierte.
2. Gibt es keinen nutzbaren, gewinnt der zuletzt aktualisierte aktive Slot, dessen `endpoint` in
   der Projektion dann geleert wird — die Projektion behauptet keine Erreichbarkeit, die sie
   nicht belegen kann, der Slot behält seinen Endpunkt für die Diagnose.
3. Gibt es gar keinen aktiven, gewinnt der zuletzt aktualisierte Slot überhaupt.

Ein Vor-v2-Leser sieht damit dieselbe Struktur wie bisher und bricht nicht. Ein Leser, der
mehrere Transporte unterscheiden will, liest zusätzlich `transports.<method>`.

Diese Projektion ist absichtlich zeitbasiert und nicht nach Transportklasse priorisiert: Die App
kennt die Topologie des Lesers nicht. Ein Host im selben WLAN und ein Host, der das Gerät nur über
Tailscale erreicht, hätten gegensätzliche „richtige“ Reihenfolgen. Die UI-seitige Priorisierung
aus #538 (WLAN/LAN vor Tailscale vor USB) ist eine Anzeigeentscheidung auf dem Gerät und wird
bewusst nicht auf den Register-Vertrag übertragen.

## 7. Offene Serverseite

Zwei Punkte kann die App nicht einseitig herstellen; sie sind Gegenstand eines eigenen
Folge-Issues, weil sie ein Deployment am produktiv laufenden `phone-register-server` erfordern:

1. **`VALID_METHODS` kennt `tailscale-adb` und `usb-adb` nicht.** Beide Methoden werden aktuell
   mit HTTP 400 abgewiesen.
2. **Ein aktives USB-Ereignis ohne `endpoint` wird abgewiesen.** Die Zusammenführungslogik
   (`merge_transport`) beherrscht den Zustand `awaiting-endpoint` bereits; nur die Eingangs-
   validierung verlangt bei `active: true` unbedingt einen Endpunkt.

Bis dahin baut die App alle Ereignisse vollständig, sendet aber nur die Methoden aus
`KeepADBRegisterPayload.SERVER_SUPPORTED_METHODS` und hält die übrigen protokolliert zurück,
statt gegen die laufende Registrierung absehbare 400er zu erzeugen. Nach dem Server-Deployment
ist genau diese Konstante zu erweitern.

Der produktive Aufrufer steht bereits: `KeepADBRegisterClient.performUpdateTransaction` meldet
nach dem WLAN-POST über `reportAdditionalVerifiedTransports` jeden weiteren aktuell verifizierten
Transport, aus demselben Auslöser, an dieselbe vom Nutzer eingetragene Webhook-URL und unter
demselben `register_webhook_enabled`-Opt-in. Solange nur `wlan-adb` unterstützt ist, entsteht
dadurch kein zusätzlicher Request; das Erweitern der Konstante ist der einzige nötige Schalter.
`KeepADBRegisterMultiTransportWiringTest` belegt beide Seiten davon.

## 8. Migration und Rollback

**Migration, App-Seite (dieses Paket).** Der WLAN-POST wird von der Vor-v2-Form auf die v2-Form
umgestellt. `method` und `endpoint` bleiben an derselben Stelle mit derselben Bedeutung; alles
Weitere ist additiv. Es gibt keinen Zwischenzustand, in dem ein Feld fehlt oder umbenannt ist, und
keine Datenmigration: Der Register-Datenbestand wird nicht umgeschrieben, sondern nur durch
denselben `merge_transport`-Pfad wie bisher fortgeschrieben.

**Mischversionsbetrieb.** Der Server bedient beide Formen dauerhaft parallel. Eine ältere
KeepADB-Installation (Vor-v2-Payload ohne `contract_version`) und eine neue nebeneinander sind
unproblematisch, weil beide denselben `wlan-adb`-Slot schreiben und der Server die fehlenden
Felder selbst ergänzt (`observed_at` ← Empfangszeit, `event_id` ← generierte UUID). Andere
Geräte-Aliase sind ohnehin getrennt.

**Rollback.** Reines Zurücknehmen des App-Commits genügt und ist jederzeit gefahrlos möglich:

- Die Vor-v2-Nutzlast wird vom Server weiterhin akzeptiert (Pfad `contract_version is None`).
- Es wurde kein Registerfeld entfernt, umbenannt oder umgeschrieben, das ein zurückgerollter
  Client nicht mehr lesen könnte.
- Kein Zustand lebt in der App, der eine Rückmigration bräuchte: Die Webhook-Konfiguration bleibt
  unverändert in denselben SharedPreferences-Keys.
- Einziger sichtbarer Effekt eines Rollbacks: Wiederholungen desselben Zustands sind wieder
  nicht idempotent (jede erzeugt serverseitig ein neues Ereignis) und verspätete Altmeldungen
  werden nicht mehr als `stale` erkannt. Beides ist der Zustand vor diesem Paket, kein Datenverlust.

Ein Rollback der Serverseite ist hier nicht erforderlich, weil dieses Paket den Server nicht
anfasst.

## 9. Nachweise

Vertragsanpassungstests in `app/src/test/java/de/hohnepeople/keepadb/KeepADBRegisterPayloadTest.java`:

- je ein eigenes Ereignis mit eigener `method` pro verifiziertem Transport, ohne
  Endpunktvermischung und ohne implizites Löschen eines Peer-Slots;
- nur ein einziger verifizierter Transport → genau ein Ereignis, kein Platzhalter für die übrigen;
- leere Eingabe → kein Ereignis (insbesondere kein Deaktivierungsereignis);
- Netztransport ohne bestätigten Host/Port wird nicht als erreichbar veröffentlicht;
- Wiederholung eines unveränderten Zustands wiederholt die `event_id` (Idempotenz) und jede
  Änderung von `method`, `endpoint` oder `active` ändert sie (Gegenprobe: eine echte Änderung darf
  nicht als Duplikat verschluckt werden);
- Pflichtfelder von contract v2 sowie unveränderte Lage von `method`/`endpoint`;
- Deaktivierung nennt ihren Slot explizit und trägt keinen Endpunkt.

Der bestehende `KeepADBRegisterClientTest` prüft die tatsächlich gesendete Nutzlast des
WLAN-Pfades gegen dieselbe Vertragszusage.

Beide Schutzrichtungen wurden per Mutationsprobe belegt: Eine zeitabhängige `event_id` und ein
entfernter Endpunkt-Guard lassen die zugehörigen Tests rot laufen.

Nicht Teil dieses Pakets: ein Lauf gegen den produktiv laufenden Server oder gegen
`~/agent/data/phone_reachability_register.json`. Das wäre ein Betriebseingriff mit eigener
Freigabe; die Serververhaltensaussagen oben sind aus dem Quellcode des laufenden Dienstes belegt,
nicht aus einem Testlauf gegen ihn.
