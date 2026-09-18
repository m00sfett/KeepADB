# Trusted networks: measured Android identity limits (#492)

Vor der Umsetzung von #492 wurde reproduzierbar gemessen, welche Netzwerkidentität KeepADB unter
Android tatsächlich geliefert bekommt. Grundlage sind nicht Annahmen aus der Doku, sondern Werte
aus dem laufenden Prozess.

## Aufbau

- Gerät: Samsung SM-G780G (Galaxy S20 FE), Alias `s20`, Android 13 / SDK 33, kein Root.
- Build: Debug-APK aus dem Issue-Head, Paket `de.hohnepeople.keepadb.debug`.
- Referenzwahrheit aus der Shell: `cmd wifi status` meldete `SSID: "moosNET",
  BSSID: 2c:91:ab:0f:13:05`.
- Messinstrument: temporäre Log-Sonde in `KeepADBNetworkIdentity.current()`, die SSID-Rohwert,
  `displaySsid()`, BSSID, `isKnown()` und den Aufrufer protokolliert, plus ein temporärer,
  exportierter `BroadcastReceiver`, der dieselbe Lesung im Hintergrund-Prozesszustand auslöst.
  Beides war reine Messausrüstung und ist **nicht Teil des Commits** — die Matrix unten ist das
  Ergebnis, nicht das Werkzeug.
- Kontrolle: Fall A wurde nach den Negativfällen erneut gemessen und lieferte wieder die echte
  Identität. Die Maskierung ist also ein echter Zustandswechsel und kein hängengebliebener Wert.

## Matrix

| Fall | App-Zustand | Standortberechtigung | Standortdienste | Gelieferte SSID | Gelieferte BSSID | `isKnown()` |
|---|---|---|---|---|---|---|
| A | Aktivität im Vordergrund | FINE erteilt | an (`location_mode=3`) | `moosNET` | `2c:91:ab:0f:13:05` | `true` |
| B | keine sichtbare Aktivität, **Keep-Alive-Foreground-Service läuft** (`isForeground=true`, Typ `connectedDevice`, gleiche PID wie die Lesung) | FINE erteilt | an | `<unknown ssid>` → `displaySsid()` = `null` | `02:00:00:00:00:00` | `false` |
| C | keine sichtbare Aktivität, kein Foreground-Service | FINE erteilt | an | `<unknown ssid>` | `02:00:00:00:00:00` | `false` |
| D | Aktivität im Vordergrund | FINE entzogen | an | `<unknown ssid>` | `02:00:00:00:00:00` | `false` |
| E | Aktivität im Vordergrund | FINE erteilt | **aus** (`location_mode=0`) | `<unknown ssid>` | `02:00:00:00:00:00` | `false` |
| A2 | wie A, nach D/E gemessen | FINE erteilt | an | `moosNET` | `2c:91:ab:0f:13:05` | `true` |

## Belastbare Schlussfolgerungen

1. **Nur der Vordergrund liefert eine verwertbare Identität.** Genau ein gemessener Fall (A/A2)
   ergibt eine echte SSID und BSSID: sichtbare Aktivität, FINE-Grant, aktive Standortdienste.
   Fehlt irgendeines davon, liefert die Plattform Platzhalter.
2. **Ein laufender Foreground Service ersetzt den Vordergrund nicht.** Fall B ist der
   entscheidende: Der Dienst lief nachweislich (`isForeground=true`) im selben Prozess, in dem die
   Lesung stattfand, und die Identität war trotzdem maskiert. Der Dienst hat den Typ
   `connectedDevice`, nicht `location`, und die App hält kein `ACCESS_BACKGROUND_LOCATION` — das
   ist eine Betriebssystem-/Berechtigungsgrenze, die kein anderer App-Codepfad umgeht.
3. **SSID und BSSID werden gemeinsam maskiert, nie einzeln.** In allen Negativfällen sind beide
   Felder gleichzeitig Platzhalter. Das bestätigt die Quellcode-Analyse aus #355 empirisch: Es gibt
   auf diesem Gerät keinen Zustand „BSSID verborgen, SSID lesbar".
4. **Daraus folgt der eigentliche Umfang der SSID-Alternative.** Sie kann im Hintergrund
   *nichts* retten, weil dort auch die SSID fehlt. Ihr echter Nutzen ist ausschließlich, dass im
   Vordergrund ein Listeneintrag mehrere Zugangspunkte gleichen Namens abdeckt. Auf diesem Netz ist
   das messbar real: `moosNET` wird von zwei BSSIDs ausgesendet (`2c:91:ab:0f:13:05` auf 5 GHz,
   `2c:91:ab:0f:13:04` auf 2,4 GHz). Genau diese breitere Freigabe ist der Sicherheits-Trade-off.
5. **Deshalb ist die globale Einschränkung Opt-in und standardmäßig aus.** Mit eingeschalteter
   Einschränkung fällt die Vertrauensprüfung im Hintergrund fail-closed aus, und die automatische
   Keep-Alive-Wiedereinschaltung greift dort im Regelfall nicht mehr. Das ist eine bewusste
   Nutzerentscheidung, kein stiller Standard. Die verbleibende Hintergrundfähigkeit stammt allein
   aus dem verbindungsgebundenen Cache in `KeepADBTrustedNetwork` (#270/#354) und gilt nur, solange
   der Dienst-Callback jede Verbindungsänderung invalidiert.

## Messlücken

- **Echter AP-/Mesh-Wechsel nicht gemessen.** Das Testnetz hat zwar zwei BSSIDs unter einer SSID
  (siehe oben), ein Bandwechsel lässt sich aber nicht fernsteuernd erzwingen, und ein zweiter
  physischer Zugangspunkt stand nicht bereit. Ein Wechsel hätte zudem die WLAN-ADB-Verbindung
  gekappt, über die gemessen wurde. Nicht simuliert, sondern hier als offene Lücke vermerkt.
- **Nur eine Plattform.** Alle Werte stammen von Android 13 auf dem S20. Ein OEM-WLAN-Stack, der
  die beiden Berechtigungsprüfungen trennt, würde Schlussfolgerung 3 lokal aufheben; genau dafür
  bleibt der defensive Fallback in `KeepADBTrustedNetwork` bestehen.
