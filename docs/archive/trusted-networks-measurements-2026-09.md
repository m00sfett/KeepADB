# Historisches Messarchiv: vertrauenswürdige WLANs

Dieses Archiv bewahrt die vollständigen, datierten Messnotizen aus September 2026. Es enthält
Zwischenstände, nachträgliche Korrekturen und Schlussfolgerungen, die sich im Lauf der Untersuchung
verändert haben. Für das aktuelle Verhalten und die heute belastbare Auswertung gelten
[die Netzwerkregeln](../trusted-networks.md) und die
[Messzusammenfassung](../trusted-networks-measurement.md). Aussagen in den folgenden Notizen sind
historische Messbeobachtungen, keine zusätzliche Produktzusage.

---

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

> **Überholt durch #606 (Variante C2) und deren Nachmessung in #626.** Die fünf Schlussfolgerungen
> unten beschreiben den Stand vor #492/#606, als der Foreground-Service ausschließlich den Typ
> `connectedDevice` hielt und `ACCESS_BACKGROUND_LOCATION` nicht zur Debatte stand. Sie bleiben als
> historischer Messbefund korrekt, sind aber für den aktuellen C2-Stand **nicht mehr maßgeblich**
> — insbesondere Schlussfolgerung 2 ("Ein laufender Foreground Service ersetzt den Vordergrund
> nicht") gilt unter C2 nur noch eingeschränkt: Nachtrag 3 zeigt, dass der *Ursprung* des
> Service-Starts (Vordergrund- vs. Hintergrund-Record) entscheidend ist, nicht der deklarierte
> Servicetyp. Aktueller Stand: siehe "Nachtrag" (C1-Spike), "Nachtrag 2" (C1/C2-Vergleich) und
> "Nachtrag 3" (#626, Service-Record-Ursprung, API 34/35-Regression #629, dauerhafte
> Hintergrundmaskierung auf API 33 #630) sowie "Nachtrag 5" (Emulator-API-Matrix API 30 bis 36.1).

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
  physischer Zugangspunkt stand nicht bereit. Ein Wechsel hätte zudem die Wifi-ADB-Verbindung
  gekappt, über die gemessen wurde. Nicht simuliert, sondern hier als offene Lücke vermerkt.
- **Nur eine Plattform.** Alle Werte stammen von Android 13 auf dem S20. Ein OEM-WLAN-Stack, der
  die beiden Berechtigungsprüfungen trennt, würde Schlussfolgerung 3 lokal aufheben; genau dafür
  bleibt der defensive Fallback in `KeepADBTrustedNetwork` bestehen.

## Nachtrag (#606, 2026-09-27): Mess-Spike mit `ACCESS_BACKGROUND_LOCATION` (C1)

Vor der Produktentscheidung zu #606 (Richtung C, Hintergrund-Standort) wurde auf einem
Wegwerf-Branch (`spike/606-background-location-measurement`, nicht gemergt) gemessen, ob
`ACCESS_BACKGROUND_LOCATION` die in Fall B/C oben dokumentierte Maskierung tatsächlich aufhebt.
Aufbau: dieselbe Log-Sonde in `KeepADBNetworkIdentity.current()` wie oben, plus ein temporärer
exportierter `BroadcastReceiver` (`Spike606ProbeReceiver`), der die Lesung per
`adb shell am broadcast -a de.hohnepeople.keepadb.SPIKE606_PROBE` unabhängig vom App-Zustand
auslöst. Berechtigung erteilt via `pm grant … android.permission.ACCESS_BACKGROUND_LOCATION`,
Service-Typ unverändert `connectedDevice`. Referenzwahrheit: `moosNET`,
`2c:91:ab:0f:13:05` (5 GHz) bzw. `2c:91:ab:0f:13:04` (2,4 GHz, nach Reboot neu verbunden).

| Fall | App-/Geräte-Zustand | Gelieferte SSID | Gelieferte BSSID | `isKnown()` |
|---|---|---|---|---|
| C1-B | Foreground-Service läuft, keine sichtbare Activity | `moosNET` | `2c:91:ab:0f:13:05` | `true` |
| C1-C | kein Service läuft, App vollständig im Hintergrund | `moosNET` | `2c:91:ab:0f:13:05` | `true` |
| C1-Reboot | echter Reboot, App **nie geöffnet**, Service durch `BootReceiver` automatisch gestartet, Bildschirm gesperrt | `moosNET` | `2c:91:ab:0f:13:04` | `true` |

**Ergebnis: eindeutig positiv in allen drei kritischen Fällen.** Mit erteiltem
`ACCESS_BACKGROUND_LOCATION` liefert `WifiManager#getConnectionInfo()` in genau den Zuständen,
die Fall B/C oben maskiert hatten, die echte Identität — auch nach einem kompletten Reboot ohne
jede App-Interaktion. Das hebt die in Schlussfolgerung 2 oben beschriebene
Plattformgrenze auf, sofern die Berechtigung erteilt ist.

**Randbefund (Boot-Timing, kein #606-Fehler):** Nach einem echten Reboot liefert Android
`BOOT_COMPLETED` an nicht direct-boot-fähige Apps erst **nach der ersten Entsperrung** des
Geräts (FBE/credential-encrypted storage). Vor der ersten Entsperrung bleibt der Foreground-
Service also aus, unabhängig von #606 — das betrifft den bestehenden `BootReceiver`-Mechanismus
insgesamt, nicht nur die Hintergrund-Standort-Frage, und ist hier nur als Randbeobachtung
vermerkt, nicht weiter untersucht.

## Nachtrag 2 (#606, 2026-09-27): Vollständige Messreihe C1, C2 und Kontrolle

Im weiteren Verlauf von #606 wurde auf dem Samsung Galaxy S20 FE (Android 13 / API 33) über USB
die vollständige Vergleichsmatrix zwischen C1 (`ACCESS_BACKGROUND_LOCATION`), C2
(`foregroundServiceType="connectedDevice|location"` mit While-in-Use `ACCESS_FINE_LOCATION`) und
der Kontrolle (`connectedDevice` mit `ACCESS_FINE_LOCATION`) gemessen:

| Variante | Fall | Display | FGS | Grant | Gelieferte SSID | Identifiziert (`isKnown()`) | Trust-Ergebnis |
|---|---|---|---|---|---|---|---|
| **C1** (`connectedDevice` + Background-Grant) | `fg_act` | An | Ja | FINE + BACKGROUND | `moosNET` | `true` | `trusted` |
| | `bg_fg` | An (Home) | Ja | FINE + BACKGROUND | `moosNET` | `true` | `trusted` |
| | `bg_no_act`† | An (Home) | Ja† | FINE + BACKGROUND | `moosNET` | `true` | `trusted` |
| | `wifi_reconnect` | An (Home) | Ja | FINE + BACKGROUND | `moosNET` | `true` | `untrusted`‡ |
| | `display_off` | Aus | Ja | FINE + BACKGROUND | `moosNET` | `true` | `untrusted`‡ |
| **C2** (`connectedDevice\|location` + While-in-Use) | `fg_act` | An | Ja | Nur FINE | `moosNET` | `true` | `untrusted`‡ |
| | `bg_fg` | An (Home) | Ja | Nur FINE | `moosNET` | `true` | `untrusted`‡ |
| | `bg_no_act`† | An (Home) | Ja† | Nur FINE | `moosNET` | `true` | `untrusted`‡ |
| | `wifi_reconnect` | An (Home) | Ja | Nur FINE | `moosNET` | `true` | `untrusted`‡ |
| | `display_off` | Aus | Ja | Nur FINE | `moosNET` | `true` | `untrusted`‡ |
| **Kontrolle** (`connectedDevice` + While-in-Use) | `fg_act` | An | Ja | Nur FINE | `moosNET` | `true` | `untrusted`‡ |
| | `bg_fg` | An (Home) | Ja | Nur FINE | `moosNET` | `true` | `untrusted`‡ |
| | `bg_no_act`† | An (Home) | Ja† | Nur FINE | `<unknown ssid>` | `false` | `identity_unavailable` |
| | `wifi_reconnect` | An (Home) | Ja | Nur FINE | `<unknown ssid>` | `false` | `identity_unavailable` |
| | `display_off` | Aus | Ja | Nur FINE | `<unknown ssid>` | `false` | `identity_unavailable` |

*†Hinweis zu `bg_no_act`:* Die Testabsicht war ursprünglich `bg_no_fgs` (Hintergrund **ohne**
laufenden Foreground-Service, App vollständig beendet). `am stopservice` beendet einen
unexportierten FGS ohne Root aber nicht; der Dienst lief in allen `bg_no_act`-Läufen nachweislich
weiter (Spalte „FGS" = `Ja`). Der Fall deckt deshalb nur „Hintergrund, keine sichtbare Aktivität,
Dienst läuft" ab, nicht „Hintergrund ohne Dienst" — das unterscheidet ihn nicht von `bg_fg`.

*‡Korrektur (Audit #624/#627):* Die als `untrusted` markierten Zeilen wurden bei der
ursprünglichen Dokumentation fälschlich als `trusted` eingetragen. Tatsächlich war in diesen
Läufen der aktuelle Access Point nicht in der Allowlist gelistet, sodass `isCurrentNetworkTrusted()`
korrekt `false` lieferte, obwohl die Identität erfolgreich aufgelöst wurde (`isKnown()=true`).
Belegt ist damit ausschließlich **`identity_known`** (die SSID/BSSID-Auflösung funktioniert in
diesen Zuständen) — nicht der **positive Trust-Ausgang** selbst. Der einzige in dieser Messreihe
tatsächlich belegte positive Trust-Fall unter C2 mit gelistetem AP ist Fall 5 in Nachtrag 3
unten. Innerhalb C1 waren `fg_act`, `bg_fg` und `bg_no_act` mit gelistetem AP gemessen (dort
stimmt `trusted`); `wifi_reconnect` und `display_off` liefen nach einem Reconnect auf einen zum
Messzeitpunkt nicht gelisteten AP. Dasselbe gilt für die Kontrolle `fg_act`/`bg_fg` (AP `…13:05`,
nicht gelistet); die maskierten Kontrollzeilen stehen in den Rohdaten als `identity_unavailable`.

### Verbindliche Produktentscheidung (#606)

*Präzisierung (#626, siehe Nachtrag 3 unten):* Punkt 1 galt für alle hier gemessenen Fälle, in
denen der Service-Record aus dem Vordergrund stammte. Nachtrag 3 zeigt, dass ein rein im
Hintergrund entstandener Service-Record (Boot, Prozess-Neustart eines solchen Records,
App-Update — jeweils bis zum nächsten Öffnen der App) die Maskierung *nicht* aufhebt — auf API 33 dauerhaft (#630), auf API 34+ beendet Android den
Dienst dabei sogar (#629). Die Entscheidung für C2 bleibt unverändert richtig, der Anspruch
"vollständige Aufhebung" gilt aber nur bedingt auf den Vordergrund-Ursprung.

KeepADB setzt verbindlich **Variante C2** um:

1. **Vollständige Aufhebung der Maskierung während des Keep-Alive-Betriebs:** C2 liefert bei
   laufendem FGS dieselben zuverlässigen echten BSSID/SSID-Werte wie C1 – auch bei Reconnects und
   bei ausgeschaltetem Display.
2. **Minimaler Datenschutz-Impact & kein Background-Location-Grant standardmäßig erforderlich:**
   Es ist kein aggressives `ACCESS_BACKGROUND_LOCATION` („Immer zulassen“) nötig. Die normale
   While-in-Use-Berechtigung `ACCESS_FINE_LOCATION` („Beim Verwenden der App“) reicht für Androids
   Einstufung des FGS mit Service-Typ `location` völlig aus. *Stand vor #616 (2026-09-27):* Diese
   Aussage beschreibt den Standardfall ohne Hintergrundstart-Problematik. Seit #616 bietet KeepADB
   `ACCESS_BACKGROUND_LOCATION` zusätzlich als **optionalen** Opt-in an, weil #626/#629/#630 zeigen,
   dass ein rein im Hintergrund gestarteter Service-Record ohne diesen Grant dauerhaft maskiert
   bleibt (siehe Punkt 4 unten und Nachtrag 4). Der Grant bleibt weiterhin nicht Voraussetzung für
   den normalen Betrieb, sondern eine bewusste Nutzerentscheidung für den Hintergrundstart-Fall.
3. **Optimale UX:** Keine verwirrenden Dialoge oder Umwege über Systemeinstellungen („Immer zulassen“);
   vollständig kompatibel mit den Richtlinien von F-Droid und Google Play.
4. **Defensiver Fallback:** Der Service-Typ `location` wird beim `startForeground()` dynamisch
   nur dann angefordert, wenn `ACCESS_FINE_LOCATION` tatsächlich erteilt ist. Das verhindert
   `SecurityException`s auf Android 14+ (API 34+) von vornherein nur im Standardmodus `all_wifi`
   ohne Allowlist (kein FINE-Grant, also kein `location`-Typ in der Anfrage). Mit erteiltem
   FINE-Grant und einem Hintergrundstart wirft `startForeground()` die Exception weiterhin (siehe
   Nachtrag 3, Fall 6); seit #629 fängt `onStartCommand()` sie ab und promotet stattdessen einmalig
   mit `connectedDevice` allein, statt den Service zu beenden — die Identität bleibt dabei
   maskiert, bis ein späterer, vordergrund-ausgelöster Start den Service erneut mit `location`
   promotet (#630). Das #616-Opt-in (`ACCESS_BACKGROUND_LOCATION`) hebt diese Maskierung in der
   Praxis auf, ohne dass ein solcher Neustart nötig ist (Nachtrag 4).

## Nachtrag 3 (#626, 2026-09-28): C2-Messlücken Boot, Sticky-Restart, Hintergrund-`sync()`, Laufzeit-Grant, API 34/35

Gemessen auf dem Wegwerf-Branch `test/626-c2-measurement-gaps` (nicht gemergt). Basis ist
`origin/master` mit versionName 1.8.61 / versionCode 158, also der C2-Stand aus #606/#617:
`connectedDevice|location`, der Typ `location` wird nur mit erteiltem `ACCESS_FINE_LOCATION`
angefordert. Paket `de.hohnepeople.keepadb.debug`.

**Messwerkzeug (nur auf dem Branch):** Die Log-Sonde `Probe626Receiver` (Tag `C626`) schreibt
SSID, BSSID, `isKnown()`, FINE-Status, Trust-Modus, `isCurrentNetworkTrusted()`,
`getBlockReason()`, den angeforderten FGS-Typ und `Service#getForegroundServiceType()`. Sie läuft
in `onStartCommand` direkt nach `startForeground`, bei jedem Heartbeat und auf
`am broadcast`-Anstoß. Ein Fehlschlag von `startForeground` wird mit Stacktrace geloggt.
Zusätzliche Aktionen: `SYNC626` ruft `KeepADBService.sync()` im Receiver auf, `SYNCDELAY626`
ruft es 20 s nach dem Receiver prozessintern ohne Broadcast-Kontext auf, `MODE626` setzt
Trust-Modus, Allowlist-BSSID und Keep-Alive.
**Zusätzliche Systemsicht** (unabhängig von der App): `dumpsys activity processes` →
`curCapability`. Das `L` an erster Stelle ist die Standort-Capability des Prozesses, also
While-in-Use. `dumpsys activity services` liefert `allowWhileInUsePermissionInFgs` und
`allowStartForeground` des ServiceRecords.

Trust-Konfiguration für alle Trust-Aussagen: Modus `allowlist`, **aktueller AP gelistet**
(S20: `0c:72:74:a4:69:00`, Emulator: `00:13:10:85:fe:01`). `IDENTITY_UNAVAILABLE` heißt deshalb
immer: Der Zugangspunkt wäre vertrauenswürdig, die App kann ihn nur nicht sehen.

### Messung: Samsung Galaxy S20 FE (SM-G780G), Android 13 / API 33

| Nr. | Fall | Ursprung des ServiceRecords | FGS-Typ angefordert/aktiv | `curCapability` | `allowWhileInUse…` | Identität | Trust |
|---|---|---|---|---|---|---|---|
| A | Neuinstallation → `MY_PACKAGE_REPLACED` → `BootReceiver` startet Service (Hintergrund), 2× reproduziert | Hintergrund (`SYSTEM_ALLOW_LISTED`) | 24/24 | `---N` | `false` | maskiert (`<unknown ssid>`, `02:00:…`) | `untrusted` / `IDENTITY_UNAVAILABLE` |
| 1 | **Echter Reboot**, Keep-Alive an, erste Entsperrung manuell durch den Nutzer, danach keine App-Interaktion | Hintergrund, aber `ACTIVITY_STARTER` (Prozess war von SystemUI über den KeepADB-QS-Tile gebunden, `uidState: BFGS`) | 24/24 | `L--N` | `true` | `moosNET` / `0c:72:74:a4:69:00` | `trusted`, Auto-Re-Enable `success` |
| 2a | Process-death eines **aus dem Hintergrund** gestarteten Service (`am crash`) → START_STICKY-Restart nach 1000 ms | Hintergrund, vom alten Record geerbt | 24/24 | `---N` | `false` | maskiert | `untrusted` / `IDENTITY_UNAVAILABLE` |
| 2b | Process-death eines **aus dem Vordergrund** gestarteten Service (FINE entziehen und sofort wieder erteilen; Prozess stirbt, der Record bleibt) → START_STICKY-Restart | Vordergrund (`PROC_STATE_TOP`), vom alten Record geerbt | 24/24 | `L--N` | `true` | echt | `trusted` |
| 3a | `MainActivity` im Vordergrund, `onResume` → `sync()` | Vordergrund | 24/24 | `LCMN` | `true` | echt | `trusted` |
| 3b | danach HOME, kein weiterer Aufruf | Vordergrund | 24/24 | `L--N` | `true` | echt | `trusted` |
| 3c | danach `sync()` aus dem Hintergrund (Receiver) | Vordergrund | 24/24 | `L--N` | `true` | echt | `trusted` |
| 3d | Hintergrund-Record (Fall A), dann prozessinternes `sync()` ohne Broadcast-Kontext (`SYNCDELAY626`) | Hintergrund | 24/24 | `---N` | `false` | maskiert | `untrusted` |
| 3e | Hintergrund-Record, `sync()` im Receiver eines Shell-Broadcasts | Hintergrund | 24/24 | `---N` | `false` | maskiert | `untrusted` |
| 3f | automatisches Re-Enable nach Reconnect (Fall 5) löst über den Surface-Refresher `sync()` → `onStartCommand` aus | Vordergrund-Äquivalent aus Fall 1 | 24/24 | `L--N` | `true` | echt | `trusted` |
| 4a | FINE entzogen (Prozess stirbt, Sticky-Restart) | Vordergrund-Record | 16/16 | `---N` | – | maskiert | `IDENTITY_UNAVAILABLE` |
| 4b | FINE zur Laufzeit erteilt, Service läuft, kein neues `onStartCommand` | unverändert | 16/16 | `---N` | – | **maskiert** | `IDENTITY_UNAVAILABLE` |
| 4c | danach `sync()` aus dem Hintergrund → `onStartCommand` fordert 24 an | Vordergrund-Record | 24/24 | `L--N` | `true` | echt | `trusted` |
| 4'a–c | wie 4a–4c, aber Record aus dem Hintergrund (Neuinstallation) | Hintergrund | 16 → 24 | `---N` durchgehend | `false` | maskiert, auch nach `sync()` | `IDENTITY_UNAVAILABLE` |
| 5 | **Positiver Trust-Fall:** Display aus (`mWakefulness=Dozing`), WLAN aus/ein, Reconnect auf den gelisteten AP | Record aus Fall 1 (`allowWhileInUse=true`) | 24/24 | `L--N` | `true` | `moosNET` / `0c:72:74:a4:69:00` | `trusted`, `Auto-enabling Wireless Debugging (Wi-Fi connected)`, `recovery_attempt … success`, `adb_wifi_enabled=1` |

Rohdaten-Auszüge (S20):

```
# A (Hintergrundstart nach Paket-Update)
C626: probe caller=onStartCommand startId=1 … ssid=<unknown ssid> bssid=02:00:00:00:00:00 known=false fine=true mode=allowlist trusted=false block=IDENTITY_UNAVAILABLE requestedType=24 actualType=24
curCapability=---N ; allowWhileInUsePermissionInFgs=false ; allowStartForeground=SYSTEM_ALLOW_LISTED
# 1 (Reboot, BOOT_COMPLETED erst 20:28:10 nach manueller Entsperrung)
KeepADBDiag: event=boot_completed … keepAlive=true ; event=boot_recovery … success
C626: probe caller=onStartCommand startId=1 … ssid="moosNET" bssid=0c:72:74:a4:69:00 known=true … trusted=true block=NONE requestedType=24 actualType=24
allowWhileInUsePermissionInFgs=true ; allowStartForeground=ACTIVITY_STARTER ; uidState: BFGS
# 2a
ActivityManager: Scheduling restart of crashed service …KeepADBService in 1000ms for start-requested
C626: probe caller=onStartCommand startId=4 … known=false … block=IDENTITY_UNAVAILABLE requestedType=24 actualType=24 ; curCapability=---N
# 4b → 4c
C626: probe caller=broadcast … known=false fine=true … requestedType=16 actualType=16      (nach pm grant)
C626: probe caller=onStartCommand startId=4 … known=true fine=true … trusted=true requestedType=24 actualType=24
# 5
KeepADBService: NetworkCallback: Wi-Fi network available
KeepADBService: Auto-enabling Wireless Debugging (Wi-Fi connected)
KeepADBDiag: event=recovery_attempt … outcome=success detail=intentId=2 desired=true actual=true writeAccepted=true
C626: probe caller=onStartCommand startId=3 … ssid="moosNET" bssid=0c:72:74:a4:69:00 known=true … trusted=true
```

### Messung: Android-Emulator (`sdk_gphone64_x86_64`), API 34 (`UE1A.230829.050`) und API 35 (`AE3A.240806.043`)

Gleiche Konfiguration: FINE erteilt, Allowlist mit dem aktuellen AP, Keep-Alive an, Paket per
`dumpsys deviceidle whitelist` vom Akku-Optimieren ausgenommen. Ohne diese Ausnahme lehnt
Android den Hintergrundstart schon vorher mit `ForegroundServiceStartNotAllowedException` ab
(`code:DENIED`); das gilt unabhängig von C2.

| Fall | API 34 | API 35 |
|---|---|---|
| 6a: Hintergrundstart (Receiver → `sync()` → `startForegroundService`) | `startForeground` wirft `SecurityException: Starting FGS with type location … the app must be in the eligible state/exemptions to access the foreground only permission`. `failForegroundStart()` beendet den Service (`foreground_promotion_failed`, `onDestroy`), 0 ServiceRecords | identisch |
| 6b: echter Reboot → `BootReceiver` | identisch: `boot_recovery … success`, danach `SecurityException` in `onStartCommand`, Service gestoppt, **Keep-Alive nach Reboot tot** | identisch |
| 6c: Start aus dem Vordergrund (`MainActivity`), HOME, danach `sync()` aus dem Hintergrund | läuft, `curCapability=L--NFU`, Identität echt, `trusted`; das erneute `startForeground` wirft nicht | läuft, `L--NFUA`, echt, `trusted` |
| 6d: Process-death eines vordergrund-gestarteten Service, Sticky-Restart | läuft weiter, Identität echt, `trusted` (Record-Ursprung `PROC_STATE_TOP` bleibt erhalten) | nicht separat gemessen (siehe Annahmen) |
| Kontrolle: FINE nicht erteilt, Hintergrundstart | – | Service läuft mit Typ 16 (`connectedDevice`), keine Exception; der dynamische Fallback aus #606 greift nur hier |

Rohdaten-Auszug (API 34, Reboot):

```
KeepADBDiag: sdk=34 event=boot_completed … keepAlive=true
ActivityManager: Background started FGS: Allowed [… code:SYSTEM_ALLOW_LISTED …]
ActivityManager: Foreground service started from background can not have location/camera/microphone access: service de.hohnepeople.keepadb.debug/de.hohnepeople.keepadb.KeepADBService
C626: startForeground failed startId=1 flags=0
C626: java.lang.SecurityException: Starting FGS with type location … targetSDK=35 requires permissions: … [android.permission.FOREGROUND_SERVICE_LOCATION] any of … [ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION] and the app must be in the eligible state/exemptions to access the foreground only permission
KeepADBDiag: event=service_start_command … outcome=stopped detail=foreground_promotion_failed cleanupRequested=true
KeepADBDiag: event=service_destroy …
```

### Belastbare Schlussfolgerungen (Messung)

1. **Entscheidend ist der Ursprung des ServiceRecords, nicht der angeforderte Typ.** Unter C2
   meldet Android in allen S20-Fällen `actualType=24` (location aktiv). Standortzugriff
   (`curCapability` mit `L`) und damit eine lesbare Identität gibt es trotzdem nur, wenn der
   Record mit `allowWhileInUsePermissionInFgs=true` entstanden ist: Start aus dem Vordergrund
   oder mit einer Systemausnahme wie `ACTIVITY_STARTER`.
2. **`sync()` aus dem Hintergrund stuft nicht herab (3c, 3f) und nicht herauf (3d, 3e).** Das
   erneute `startForeground` übernimmt den Status des bestehenden Records.
3. **START_STICKY-Restart erbt den Record-Status (2a/2b).** Nach einem Process-death bleibt ein
   Vordergrund-Record lesbar, ein Hintergrund-Record bleibt maskiert.
4. **Ein Laufzeit-Grant wirkt erst beim nächsten `onStartCommand` (4b → 4c), und nur bei einem
   Vordergrund-Record.** Bei einem Hintergrund-Record bleibt die Identität auch danach maskiert
   (4'). Die Codeanalyse aus #624 ist damit bestätigt und präzisiert.
5. **Positiver End-to-End-Trust unter C2 ist belegt (5):** Display aus, Reconnect, `trusted`,
   automatisches Re-Enable erfolgreich. Voraussetzung ist ein Record mit While-in-Use.
6. **API 34/35: Ein Hintergrundstart mit FINE-Grant beendet den Keep-Alive-Service.** Das gilt
   für Boot und jeden Hintergrundstart ohne laufenden Vordergrund-Record. Die Aussage in der
   #606-Entscheidung (Punkt 4), der dynamische Fallback verhindere `SecurityException`s auf API
   34+, stimmt nur für den Fall *ohne* FINE-Grant. Mit FINE-Grant ist der Fehler reproduzierbar.

### Annahmen und nicht gemessene Varianten (keine Messung)

- **Fall 1 ohne QS-Tile nicht gemessen.** Beim S20-Reboot war der Debug-Tile eingerichtet. Der
  Prozess wurde dadurch von SystemUI gebunden und bekam `ACTIVITY_STARTER`. Ein Reboot ohne Tile
  hätte den Tile entfernen und eine weitere manuelle Entsperrung durch den Nutzer erfordern
  müssen. Beides war in diesem Lauf nicht vorgesehen: Der Tile bleibt auf dem Gerät, und die
  Entsperrung lässt sich nicht fernsteuern. **Annahme** nach Fall A (gleicher `BootReceiver`-Pfad,
  Hintergrundstart ohne Tile-Bindung → `---N`): Ohne Tile oder eine andere Systemausnahme ist die
  Identität nach dem Boot auf API 33 maskiert. Die Freigabe über den Tile ist ein
  Samsung/SystemUI-Nebeneffekt und keine zugesicherte Plattformgarantie.
- **Vor der ersten Entsperrung** kam weder `BOOT_COMPLETED` an die App noch USB-ADB zustande. Die
  Messung beginnt deshalb frühestens nach der Entsperrung, wie schon in Nachtrag 1 beschrieben.
- **Tile-Klick und Widget als `sync()`-Auslöser nicht direkt gemessen.** `cmd statusbar
  click-tile` blieb auf One UI 5 wirkungslos (kein App-Log, `adb_wifi_enabled` unverändert),
  der Widget-Receiver ist nicht exportiert. Ersatzweise gemessen wurden derselbe
  `KeepADBService.sync()`-Pfad aus dem Hintergrund (3c–3e) und der reale automatische
  Re-Enable-Pfad (3f). **Annahme:** Tile und Widget verhalten sich wie 3c/3d. Für den Tile
  könnte die SystemUI-Bindung wie in Fall 1 zusätzlich While-in-Use gewähren; das ist
  ungemessen.
- **`am kill`** beendet einen laufenden FGS-Prozess nicht (PID unverändert), `run-as … kill`
  scheiterte an Samsungs PID-Namespace. Process-death wurde deshalb über `am crash` (2a) und über
  Entziehen und sofortiges Wiedererteilen von FINE (2b) ausgelöst. Ein zweites `am crash`
  innerhalb kurzer Zeit löste Samsungs Dialog „wird wiederholt beendet“ aus. „App schließen“
  beendete den Prozess dort **ohne** Service-Restart; dieser Lauf zählt nicht als
  Sticky-Stichprobe.
- **API 35, Fall 6d** (Sticky-Restart eines vordergrund-gestarteten Service) nicht separat
  gemessen. **Annahme:** wie API 34, weil 6a–6c auf beiden Versionen identisch waren.
- **Emulator-WLAN** ist virtuell (`AndroidWifi`). Aussagen zur Identitäts-Maskierung auf API
  34/35 gelten für die AOSP-Plattformlogik, nicht für OEM-WLAN-Stacks.

## Nachtrag 4 (#629/#630, 2026-09-28): SecurityException-Fallback und die #616-Gegenprobe mit `ACCESS_BACKGROUND_LOCATION`

Gemessen auf `fix/629-630-background-start-fgs`, versionName 1.8.64 / versionCode 161. Zwei
getrennte Nachweise geplant: der #629-Fix auf einem API-34/35-Emulator und die
#630-Workaround-Gegenprobe auf dem S20 (API 33, physisch, `android-target s20`). Beide Nachweise
sind inzwischen vollständig; der Emulator-Nachweis wurde im unabhängigen Review dieses Commits
nachgeholt (siehe unten), nicht mehr in der ursprünglichen Implementierungs-Session.

### #629: `startForeground()`-Fallback auf `connectedDevice` bei `SecurityException`

`KeepADBService.onStartCommand()` fängt die `SecurityException` aus Fall 6a/6b jetzt ab und
promotet erneut mit dem um `FOREGROUND_SERVICE_TYPE_LOCATION` reduzierten Typ, statt den Service
über `failForegroundStart()` zu beenden (Nutzerentscheidung 2026-09-28 auf #629).

**Emulator-Nachweis (nachgeholt im Review, 2026-09-28, `KeepADB_API34`, API 34, Build 1.8.64/161,
Paket `de.hohnepeople.keepadb.debug`):** Ausgangssituation ohne den in Fall 6a/6b beschriebenen
Umweg über eine echte Neuinstallation — der Hintergrundstart wurde über einen echten,
warmen `adb reboot` der AVD erzeugt, nachdem die App zuvor einmal im Vordergrund geöffnet worden
war (um den Android-"stopped state" zu verlassen, der einer frisch installierten/force-gestoppten
App sonst jede Broadcast-Zustellung inklusive `BOOT_COMPLETED` verweigert). Vorbereitung:
`pm grant … ACCESS_FINE_LOCATION` gesetzt, `keep_alive_enabled=true` in den SharedPreferences
hinterlegt, App einmal per `am start` geöffnet (setzt `stopped=false`, promotet dabei selbst
erfolgreich mit `location`, siehe Gegenprobe unten), dann `adb reboot`.

Ergebnis nach dem Reboot — `dumpsys activity services de.hohnepeople.keepadb.debug` zeigt einen
lebenden `ServiceRecord` (`isForeground=true`, `types=00000010` = `connectedDevice` allein,
`mAllowWhileInUsePermissionInFgsReason=DENIED`, wie von #630 erwartet), Logcat bestätigt exakt den
neuen Pfad:

```
ActivityManager: Foreground service started from background can not have location/camera/microphone access: service de.hohnepeople.keepadb.debug/de.hohnepeople.keepadb.KeepADBService
KeepADBService: startForeground denied type=location from background; retrying with connectedDevice only (#629)
KeepADBService: java.lang.SecurityException: Starting FGS with type location … targetSDK=35 requires permissions: … and the app must be in the eligible state/exemptions to access the foreground only permission
KeepADBDiag: event=service_start_command source=lifecycle outcome=retrying detail=foreground_promotion_denied_location fallbackType=16
KeepADBDiag: event=service_start_command source=lifecycle outcome=ready detail=foreground=true
```

Kein `outcome=failed`/`foreground_promotion_failed` in der gesamten Prozess-Session; der Service
lief im Anschluss normal weiter (Heartbeat, `NetworkCallback`-Registrierung, `keep_alive_check`).
Das ist der frische, lebende End-to-End-Nachweis, den die ursprüngliche Implementierungs-Session
mangels rechtzeitig gebooteter AVD nicht mehr erbringen konnte — die ursprüngliche
`SecurityException` selbst war schon vorher in Nachtrag 3 real auf API 34 **und** API 35
reproduziert (Rohdaten dort); dieser Nachtrag bestätigt jetzt zusätzlich, dass der Fallback in
#629 sie auf einer echten, frisch gebooteten AVD tatsächlich abfängt und den Service am Leben
hält, statt es nur über Robolectric zu simulieren.

**Tatsächlicher Nachweis in diesem Durchlauf:** die reale `onStartCommand()`-Kontrollfluss-Probe
per Robolectric (`KeepADBServiceLifecycleRobolectricTest`,
`onStartCommandFallsBackToConnectedDeviceWhenLocationPromotionIsDeniedInBackground` plus die zwei
Regressionsproben daneben). Da kein Mocking-Framework zur Verfügung steht und Robolectrics Shadow
die reale API-34-Restriktion nicht modelliert, wirft ein Test-Seam
(`KeepADBService#promoteToForeground(int)`, per Testunterklasse überschrieben) exakt die
gemessene `SecurityException` für den `location`-Typ; der reale Produktionscode in
`onStartCommand()` -- Catch, Fallback-Typberechnung, Diagnostik, `START_STICKY`-Rückgabe,
Listener-Registrierung -- läuft dabei unverändert durch. Ergebnis: `START_STICKY` statt
`START_NOT_STICKY`, `dumpsys`-Shadow-Äquivalent zeigt `foregroundServiceType=16`
(`connectedDevice` allein), Diagnose-Export enthält `outcome=retrying
detail=foreground_promotion_denied_location fallbackType=16` gefolgt von `outcome=ready
detail=foreground=true`, nie `detail=foreground_promotion_failed`.

### #630: `ACCESS_BACKGROUND_LOCATION` (#616) hebt die Hintergrundmaskierung tatsächlich auf

Vor dem Schließen von #630 als Plattformgrenze wurde ernsthaft geprüft, ob ein automatischer
Workaround ohne Nutzerinteraktion existiert (Nutzerentscheidung 2026-09-28 auf #630). Erwogen und
verworfen: ein periodischer Selbst-Trigger des Service, der sich selbst erneut promotet — bringt
nichts, weil `allowWhileInUsePermissionInFgs` am `ServiceRecord` hängt und durch ein erneutes
`startForeground()` aus demselben, bereits im Hintergrund entstandenen Record **nicht** neu bewertet
wird (Nachtrag 3, Schlussfolgerung 2); ein automatischer Trampolin-Start von `MainActivity` aus dem
Service heraus — blockiert seit Android 10 durch die Background-Activity-Start-Restriktionen ohne
Nutzerinteraktion, würde also selbst wieder eine Ausnahme brauchen, die nicht besteht.

Stattdessen wurde der zum Zeitpunkt von #616 nur angenommene, nie gemessene Mechanismus jetzt
nachgewiesen: `ACCESS_BACKGROUND_LOCATION` hebt die Maskierung eines **aus dem Hintergrund
entstandenen** `ServiceRecord` tatsächlich auf, obwohl dessen `allowWhileInUsePermissionInFgs`
dabei `false` bleibt. Der ursprünglich in Nachtrag 3 verwendete Indikator (`curCapability`/
`allowWhileInUsePermissionInFgs`) zeigt also nur, ob der Record die FGS-Typ-Deklaration `location`
*legal* halten darf — nicht, ob der eigentliche Standortzugriff (und damit die WLAN-Identität)
gewährt wird. Letzteres hängt zusätzlich an der Berechtigungsstufe: Der Hintergrund-Tier
(`ACCESS_BACKGROUND_LOCATION`) umgeht die Vordergrund-Prüfung dafür unabhängig vom
`ServiceRecord`-Capability-Flag.

Kontrollierter A/B-Vergleich auf dem S20 (gleicher AP `0c:72:74:a4:68:ff`, allowlist-Modus,
gleicher Hintergrundstart-Pfad Neuinstallation → `MY_PACKAGE_REPLACED`, gleicher
`settings put global adb_wifi_enabled 0`-Auslöser für den ContentObserver-Recovery-Pfad):

| Lauf | `ACCESS_BACKGROUND_LOCATION` | `allowWhileInUsePermissionInFgs` | ContentObserver-Ergebnis |
|---|---|---|---|
| 1 | erteilt (`pm grant`) | `false` (unverändert) | `event=recovery_attempt … outcome=success` — Wireless Debugging automatisch wieder an, ganz ohne App-Vordergrund |
| 2 (Kontrolle) | entzogen (`pm revoke`), sonst identisch | `false` | `Wireless Debugging dropped on an untrusted Wi-Fi network; not auto re-enabling` / `event=recovery_or_stop … outcome=blocked detail=untrusted_network` — exakt der #630-Befund |

Damit ist #630 für Nutzer, die den bereits ausgelieferten #616-Opt-in abschließen, tatsächlich
gelöst — nicht nur angenommen. Für Nutzer, die den Hintergrund-Grant ablehnen, bleibt die Maskierung
bis zum manuellen Öffnen von `MainActivity` bestehen (#628); dafür existiert nachweislich kein
sauberer automatischer Workaround ohne Nutzerinteraktion oder ohne die Berechtigung selbst. Kein
Code-Fix in KeepADB nötig; die Entscheidung ist Dokumentation der bereits bestehenden Lösung plus
Korrektur der bis dahin unbelegten Annahme im #616-Changelog-Eintrag.

## Nachtrag 5 (#642–#646, 2026-09-29): Emulator-API-Matrix API 30 bis 36.1

Gemessen auf sieben Emulatoren (`KeepADB_API30` bis `KeepADB_API35`, Pixel-2-Profil, sowie
`Dev_Galaxy_S20_API_36_1_Play`, S20-Profil) mit einer isolierten Messkopie
`de.hohnepeople.keepadb.debug.apimatrix`, gebaut aus `master` `3351c4e` (1.8.67 / 164). Plan:
`notes/emulator-background-location-api-matrix-plan.md` im Projekt-Metadatenordner. Bericht und
Rohdaten (Probe-JSON im Logcat, `dumpsys activity services`, Permission-Readbacks) liegen lokal
unter `~/agent/output/keepadb-api-matrix-20260929/`; sie sind nicht Teil des Repositories. Die
Probe selbst war reine Messausrüstung und ist nicht Teil eines Commits. Die Tabelle unten ist aus
den Rohdaten nachgeprüft, nicht aus der Zusammenfassung des Berichts übernommen.

| API | Hintergrundstart ohne `ACCESS_BACKGROUND_LOCATION` | Hintergrundstart mit `ACCESS_BACKGROUND_LOCATION` |
|---|---|---|
| 30–33 | FGS läuft, Identität maskiert (`identity_known=false`) | FGS läuft, echte SSID/BSSID (`identity_known=true`), auch bei Display aus |
| 34, 35, 36.1 | `location`-Hochstufung abgelehnt, #629-Fallback aktiv (`types=0x10`, `fallbackType=16`), Identität maskiert | **kein** Fallback: FGS hält `connectedDevice|location` (`types=0x18`), echte SSID/BSSID, auch bei Display aus |

Hintergrundstart heißt hier: `install -r` löst `MY_PACKAGE_REPLACED` im `BootReceiver` aus,
`MainActivity` wird danach nicht geöffnet. FINE war in beiden Spalten erteilt.

### Belastbare Schlussfolgerungen

1. **`ACCESS_BACKGROUND_LOCATION` entmaskiert die Identität nach einem Hintergrundstart auf jeder
   unterstützten API (30 bis 36.1).** Das bestätigt den S20-Befund aus Nachtrag 4 plattformweit.
2. **Mit Hintergrund-Grant wirft die `location`-Hochstufung auf API 34+ keine
   `SecurityException`.** Der #629-Fallback greift nur ohne Hintergrund-Grant. Die Annahme in
   #642, `startForeground()` mit Typ `location` scheitere aus dem Hintergrund auf API 34+
   grundsätzlich, stimmt deshalb nur für den Fall ohne Hintergrund-Grant.
3. **Die aktuelle C2-Typwahl bleibt richtig.** Mit Hintergrund-Grant ist der `location`-Typ nicht
   redundant, sondern wird regulär gehalten. Ohne Hintergrund-Grant ist er für
   Vordergrundstarts nötig (Nachtrag 3), und der Fallback fängt den Hintergrundstart ab. Ob
   `connectedDevice` allein zusammen mit dem Hintergrund-Grant ebenfalls eine lesbare Identität
   liefert, hat diese Matrix **nicht** gemessen: In allen Hintergrund-Grant-Fällen hielt der
   Record den `location`-Typ.

### Messlücken dieser Matrix

- **Vordergrund-Kontrolle nur auf API 36.1 gültig.** Auf API 30 bis 35 lief in der Probe des
  Vordergrundfalls kein Keep-Alive-Service (`keep_alive_service_running=false`,
  `foreground_service_type_requested=0`). Gemessen wurde dort nur die Lesung aus der sichtbaren
  Aktivität, nicht ein aus dem Vordergrund gestarteter Service. Für API 33 bis 35 deckt Nachtrag 3
  diesen Fall ab, für API 30 bis 32 bleibt er ungemessen.
- **Allowlist-Entscheidung nur auf API 36.1 gemessen.** Auf API 30 bis 35 stand die Messkopie im
  Modus `all_wifi`. Dort belegt `network_trusted=true` nur den Standardmodus, nicht eine
  Allowlist-Freigabe. Die Identitätslesung selbst ist davon unabhängig gültig.
- Die Recovery-Proben (ContentObserver, `writeAccepted=true`) liefen auf allen sieben APIs
  erfolgreich, auf API 30 bis 35 aber ebenfalls im Modus `all_wifi`.
- Emulatoren prüfen Plattformverhalten, keine OEM-Abweichungen.

## Nachtrag 6 (#646, 2026-09-30): E2E-Emulator-Matrix API 30 bis 36.1

Gemessen auf denselben sieben Emulatoren wie Nachtrag 5 (`KeepADB_API30` bis `KeepADB_API35`,
Pixel-2-Profil, 1080x1920; `Dev_Galaxy_S20_API_36_1_Play`, S20-Profil, 1440x3200; `SDK_INT_FULL`
nur auf 36.1 vorhanden: `36.1`), jeweils einzeln, sichtbares Fenster, `-gpu host`, `-no-snapshot`,
Port 5554 / ADB 5038, alle ADB-Aktionen über `android-target emulator`. Code-Stand: `master`
`f985c92` (enthält #642 bis #645, Schritt-2-Dialog, dreistufige Statuszeile und die neuen
IDENTITY_UNAVAILABLE-Texte). Die Messkopie war eine frische `git archive`-Kopie dieses Stands mit
Paket `de.hohnepeople.keepadb.debug.apimatrix` und einer DUMP-geschützten Probe
(`ApiMatrixProbeReceiver`, JSON-Sample im Logcat); beides ist reine Messausrüstung und nicht Teil
eines Commits. Die Probe hat gegenüber Nachtrag 5 zwei zusätzliche Operationen
(`prep_allowlist`, `add_bssid`: Allowlist-Modus bzw. reale BSSID über die Prefs-API) und liefert
zusätzlich die aktiven Notifications und die Zahl der Allowlist-Einträge. Rohdaten, Screenshots und
UI-Dumps liegen lokal unter `~/agent/output/keepadb-e2e-646-20260930/raw/<avd>/`, die Messskripte
unter `~/agent/workspace/keepadb-e2e-646/`; beides ist nicht Teil des Repositories.

Die WLAN-Referenz war auf allen sieben AVDs identisch und wurde je Lauf aus `dumpsys wifi` gelesen
(SSID `AndroidWifi`, BSSID `00:13:10:85:fe:01`), nicht festverdrahtet. Alle Fälle liefen im
Allowlist-Modus mit genau diesem Eintrag (`trust_mode=allowlist`, `allowlist_entries=1`), Standortdienste an,
Keep-Alive an, `WRITE_SECURE_SETTINGS` per `pm grant`. Jede Zeile hat WLAN-Referenz,
Permission-Readback (`dumpsys package`), ein frisches Probe-Sample und den FGS-/Trust-Zustand.

### Ergebnisse

**F1 Vordergrund-Kontrolle mit laufendem Service** (nur FINE, Service über den Keep-Alive-Schalter in
`MainActivity` gestartet, danach Home, Samples bei Display an und aus):

| API | Identität | Trust-Entscheidung | FGS |
|---|---|---|---|
| 30 bis 33 | lesbar, `00:13:10:85:fe:01`, Display an und aus | `network_trusted=true`, `NONE` | läuft (`isForeground=true`), angefordert Typ 24 (`connectedDevice|location`); das `dumpsys` nennt den Typ vor API 34 nicht |
| 34, 35, 36.1 | lesbar, `00:13:10:85:fe:01`, Display an und aus | `network_trusted=true`, `NONE` | läuft, `types=0x18` |

Damit ist die in Nachtrag 5 offene Vordergrund-Kontrolle mit tatsächlich laufendem Service auf
allen sieben APIs gemessen (der Service-Record hat `createdFromFg=true`).

**F2 Zweistufiger Grant über die echte UI** (frische Installation, nur `WRITE_SECURE_SETTINGS` und
`POST_NOTIFICATIONS` per `pm grant`, danach ausschließlich `uiautomator dump` und `input tap`):
Das Ergebnis ist auf **allen sieben APIs identisch und vollständig per UI** erreicht, kein
`pm grant`-Fallback und keine als "blockiert (UI)" markierte Stufe.

1. Einstellungen, Bereich "Network", Schalter "Restrict Keep-Alive to trusted networks": Rationale-Dialog
   "Location permission needed", "Grant".
2. Systemdialog: "While using the app" (ab API 31 mit Precise/Approximate-Auswahl, Precise
   vorgewählt; API 30 ohne). Readback danach: FINE und COARSE `granted=true`, Background
   `granted=false`.
3. Schritt-2-Dialog "Background access for trusted networks" erscheint sofort (Screenshot und
   Dump je AVD als `04-step2-dialog.png` / `.xml`), mit "Open settings", "Later" und "Trust all
   Wi-Fi networks instead".
4. "Later": Statuszeile wechselt von neutral auf "not allowed, restricted". Danach bringt der Button
   "Check background access" denselben Dialog erneut (`06-step2-dialog-via-button.png`).
5. "Open settings": App-Info, Permissions, Location, "Allow all the time". Readback:
   `ACCESS_BACKGROUND_LOCATION granted=true`.
6. Zurück in den Einstellungen: Text "Background access: allowed …". Die Textfarbe wurde aus dem
   Screenshot gemessen:

| Stufe | Textfarbe (Mittel über alle Text-Pixel) | Nächste App-Farbe |
|---|---|---|
| vor dem Modus (neutral) | `(163,151,137)` bis `(164,152,137)` | `night_muted` `#A79B8C` (Abstand 5 bis 6) |
| Modus an, Grant fehlt (eingeschränkt) | `(221,182,57)` bis `(221,181,57)` | `text_yellow` `#E0B83A` (Abstand 4) |
| Grant erteilt | `(124,185,103)` bis `(124,186,104)` | `status_ok_green` `#7FBF6A` (Abstand 6 bis 7) |

Die reale BSSID kam bei F2 nicht aus der UI, sondern über die Probe (`add_bssid`, Prefs-API), weil
der Allowlist-Modus zwar über den UI-Schalter eingeschaltet wurde, die Eintragung der aktuellen
BSSID über die UI aber nicht Teil des Auftrags war. Mit dem Eintrag und dem Grant blendet die
Einstellungsseite die Zeile "this Wi-Fi network isn't in your trusted list" aus (vorher sichtbar),
die Identität ist lesbar (`identity_known=true`, `NONE`).

**F3 Reboot mit Background-Grant** (`adb reboot`, `MainActivity` danach nicht geöffnet, Paket vorher
einmal geöffnet, also nicht im Stopped-State):

| API | Service nach dem Boot | Identität | Recovery (`adb_wifi_enabled` 1 dann 0) |
|---|---|---|---|
| 30 bis 33 | läuft aus `BOOT_COMPLETED` (`createdFromFg=false`), Typ 24 angefordert | lesbar, `NONE` | `recovery_attempt accepted`, `success writeAccepted=true` |
| 34, 35, 36.1 | läuft, `types=0x18` (kein Fallback) | lesbar, `NONE` | `recovery_attempt accepted`, `success writeAccepted=true` |

**F4 Reboot ohne Background-Grant** (nur FINE, sonst wie F3):

| API | Service nach dem Boot | Identität | Recovery und Notification |
|---|---|---|---|
| 30 bis 33 | läuft, Typ 24 angefordert | maskiert (`02:00:00:00:00:00`), `IDENTITY_UNAVAILABLE` | `recovery_or_stop blocked untrusted_network`, kein `recovery_attempt`; Notification-ID 3 "Can't identify the current Wi-Fi network" mit dem neuen Text (vollständiger Wortlaut geprüft) |
| 34, 35, 36.1 | läuft, `types=0x10`; `foreground_promotion_denied_location fallbackType=16` genau einmal im Boot-Log | maskiert, `IDENTITY_UNAVAILABLE` | wie oben |

**F5 (Ergänzung) Identität nicht lesbar, weil FINE fehlt** (frische Installation, Allowlist, Service aus
`MainActivity` gestartet): die Hauptseite zeigt auf allen sieben APIs den neuen Statustext "Wireless
Debugging is OFF – Keep-Alive is paused: network identity unavailable, check Location permission
(“Allow all the time”) or reopen the app", und die Notification "Can't identify the current Wi-Fi
network" steht.

### Belastbare Schlussfolgerungen

1. **Die Nachtrag-5-Lücken sind geschlossen.** Vordergrundstart mit laufendem Service (F1) und
   Allowlist-Entscheidung statt `all_wifi` sind auf API 30 bis 36.1 gemessen; der zweistufige
   Grant-Flow (#644) läuft auf jeder API vollständig über die UI.
2. **Der Boot-Pfad entspricht dem `MY_PACKAGE_REPLACED`-Pfad aus Nachtrag 5.** Mit
   Background-Grant liest ein aus `BOOT_COMPLETED` gestarteter Service die Identität und hält auf
   API 34+ `connectedDevice|location` (`0x18`). Ohne Grant bleibt sie maskiert, der Allowlist-Modus
   sperrt fail-closed, und auf API 34+ greift der #629-Fallback (`0x10`).
3. **Vordergrundstart mit nur FINE liest die Identität auf jeder API**, auch bei ausgeschaltetem
   Display, solange der Service aus `MainActivity` heraus gestartet wurde (Nachtrag 3, jetzt auf
   API 30 bis 32 nachgezogen).
4. **Die neuen Texte (#643) erscheinen wie vorgesehen**: Notification bei blockierter Recovery (F4),
   Hauptseiten-Status (F5). Die Einstellungsseiten-Zeile `settings_trusted_network_status_identity_unavailable`
   wurde nicht live gerendert (siehe Lücken).
5. **Ein Öffnen der App hebt die Sperre auf, wie der Hinweistext sagt**: In F4 zeigte die Hauptseite nach
   dem Öffnen den Backoff-Text "confirm Android's Wireless Debugging network dialog" statt des Identitäts-Texts.
   Das setzt eine lesbare Identität voraus (`resolveKeepAliveWaitingDetail` prüft die Identität zuerst); die
   sichtbare Aktivität hat sie also entmaskiert. Der Identitäts-Text ist auf der Hauptseite deshalb
   nur bei fehlendem FINE oder ausgeschalteten Standortdiensten sichtbar (F5).

### Messlücken und Einschränkungen

- Vor API 34 nennt `dumpsys activity services` keinen FGS-Typ; dort belegt nur der angeforderte
  Typ aus der Probe, dass `location` mitgesendet wird.
- Die Allowlist-BSSID kam über die Prefs-API der Probe, nicht über die UI. Das Hinzufügen des
  aktuellen Netzwerks über die Oberfläche ist in dieser Matrix nicht gemessen.
- Die Einstellungszeile `settings_trusted_network_status_identity_unavailable` wurde nicht live
  gerendert: In F2 und F4 wurde die Einstellungsseite nicht im Zustand "Allowlist an, Identität
  maskiert" geöffnet. Ihren Text deckt nur die Lektüre in Teil 1 ab.
- Recovery ist nur bis zur akzeptierten Schreibung belegt. Auf keinem Emulator wird Wireless
  Debugging real aktiv: Nach 3 s liest die App `actual=false` (`state_mismatch`), der Backoff
  #496 greift, und Android zeigt seinen "Allow wireless debugging on this network?"-Dialog
  (`WifiDebuggingActivity`), ausgelöst durch den `adb_wifi_enabled`-Schreibzugriff. Das ist
  Emulatorverhalten und keine Aussage über echte Geräte.
- `adb reboot` ist ein harter Neustart ohne Framework-Shutdown. Ohne etwa 15 s Wartezeit vorher
  gingen frisch per `pm grant` erteilte Berechtigungen (`WRITE_SECURE_SETTINGS`,
  `POST_NOTIFICATIONS`, Background) verloren; ein Probelauf auf API 33 zeigte das (Ordner
  `raw/_dev-KeepADB_API33-flow-development/`). Alle gewerteten Läufe warten 15 s und prüfen die Grants
  nach dem Boot erneut (alle Läufe im ersten Anlauf). Auf echten Geräten mit normalem Neustart ist das nicht zu erwarten.
- Auf den AVDs lagen fremde KeepADB-Installationen: API 30 bis 32 das Release-Paket
  `de.hohnepeople.keepadb`, API 34 zusätzlich `.debug`, API 35 `.debug`, API 36.1 `.debug`, Release und
  `de.hohnepeople.boksy`; auf API 34 lief deren Keep-Alive-Service nach dem Boot mit. Die Auswertung
  wertet daher nur Log-Zeilen mit der PID des eigenen Prozesses (aus dem `ServiceRecord`); diese
  Pakete wurden nicht angefasst.
- F4 wurde je AVD dreimal gefahren (`F4-run1`, `F4-run2`, `F4`): Der erste Lauf blieb von Androids
  Wireless-Debugging-Dialog verdeckt, sodass die Hauptseite nicht lesbar war, der zweite scrollte
  noch nicht zur Statuskarte. Identität, FGS-Typ, Notification und Recovery sind in allen drei
  Läufen je API gleich; die Tabelle stützt sich auf den letzten.
- F2 lief nur mit AOSP-Permission-Controller und -Einstellungen. One UI und andere OEM-Oberflächen
  sind nicht abgedeckt; die Emulatoren prüfen Plattformverhalten.
- Ob `connectedDevice` allein mit Background-Grant eine lesbare Identität liefert, bleibt wie in
  Nachtrag 5 ungemessen.
- `package_verifier`- und `verifier_verify_adb_installs`-Einstellungen standen auf den AVDs bereits
  seit dem Lauf aus Nachtrag 5 auf `0` und wurden auf diesen Ausgangswert zurückgesetzt, nicht auf
  den Auslieferungszustand.

## Nachtrag 7 (#651, 2026-09-30): Realer S20-Reboot mit Zwei-Schritt-Grant (Restkriterium AK3 aus #646)

Gemessen auf dem Samsung Galaxy S20 FE (SM-G780G, Android 13 / API 33, One UI) per USB-ADB
(Serial `RF8T307S88H`), Debug-Build `master` `d795643`, versionName 1.8.71 / versionCode 168, Paket
`de.hohnepeople.keepadb.debug`, installiert per `adb install -r` über 1.8.64. Das QS-Tile blieb
eingerichtet (Projektregel), WLAN `moosNET` (BSSID `0c:72:74:a4:69:00` steht in der Allowlist),
Allowlist-Modus, Keep-Alive an. Rohdaten (Logcat vor/nach beiden Reboots, Screenshots, Ablaufprotokoll)
liegen lokal unter `~/agent/output/keepadb-e2e-651-20260930/`, die UI-Helfer unter
`~/agent/workspace/keepadb-e2e-651/`; beides ist nicht Teil des Repositories.

### Zwei-Schritt-Grant über die UI

Vor dem Lauf wurden FINE, COARSE und BACKGROUND per `pm revoke` entzogen (Readback `granted=false`).
Danach ausschließlich über die Oberfläche: Einstellungen › Netzwerk › „Restrict Keep-Alive to
trusted networks“ aus und wieder an → Erklärdialog „Location permission needed“ → GRANT →
System-Dialog „Bei Nutzung der App“ → Schritt-2-Dialog „Background access for trusted networks“
(OPEN SETTINGS / LATER / TRUST ALL WI-FI NETWORKS INSTEAD) → OPEN SETTINGS → App-Info › Berechtigungen
› Standort › „Immer zulassen“. Readback danach: FINE, COARSE und `ACCESS_BACKGROUND_LOCATION`
`granted=true`. Der Grant wurde an keiner Stelle von der App selbst angefordert; der Dialog hat den
Nutzer wie beabsichtigt nur in die Systemeinstellungen geführt.

### Fall 1: echter Reboot mit Hintergrund-Grant

`adb reboot`, danach nur entsperrt, KeepADB nicht geöffnet. Ergebnis (Logcat, `KeepADBDiag`):

```
04:01:35 event=boot_completed source=system outcome=received detail=keepAlive=true
04:01:36 event=boot_recovery source=boot_receiver outcome=success detail=service_start
04:01:36 event=keep_alive_check source=service outcome=started detail=wifiConnected=true adbWifi=false
04:01:36 event=recovery_attempt source=keep_alive_check outcome=success detail=intentId=1 desired=true actual=true writeAccepted=true
04:01:36 event=state_observed source=content_observer outcome=changed detail=adbWifi=true
04:01:37 event=endpoint_discovered source=nsd_or_probe outcome=success detail=host=192.168.178.24 port=44697
```

`settings get global adb_wifi_enabled` lieferte danach `1`; `AdbDebuggingManager` nahm eine TLS-Verbindung
an (`Received WIFI TLS connected key message`). **Wireless Debugging war nach einem echten Reboot ohne
Zutun wieder real aktiv.** Damit ist das, was auf dem Emulator nicht darstellbar war
(`state_mismatch actual=false`, Nachtrag 6), auf echter Hardware belegt.

### Fall 2 (Gegenprobe): echter Reboot ohne Hintergrund-Grant

`pm revoke … ACCESS_BACKGROUND_LOCATION` (FINE und Allowlist blieben), `adb reboot`, nur entsperrt.
Ergebnis: **kein fail-closed.** Identisches Bild wie in Fall 1 (`boot_completed` 04:07:57,
`recovery_attempt … success writeAccepted=true`, `adbWifi=on`, Endpoint `192.168.178.24:44491`,
`endpoint_verified reachable`; auf dem Gerät erschien wieder der `adb-tls-connect`-Transport). Der
Hinweistext aus #643 kam nicht zum Einsatz, weil die Identität lesbar war.

Das widerspricht dem Erwartungsbild des Issues nicht als Fehler der App, sondern passt zu der bereits
in Nachtrag 3 festgehaltenen Annahme (hier nicht isoliert geprüft): Mit eingerichtetem QS-Tile bindet SystemUI den Prozess und
gewährt While-in-Use (Samsung-/SystemUI-Nebeneffekt, keine Plattformgarantie). Der fail-closed-Pfad
`IDENTITY_UNAVAILABLE` ohne Grant lässt sich daher **auf dem S20 nur ohne Tile** messen. Das Tile wurde
nicht entfernt (Projektregel). Der fail-closed-Nachweis stützt sich weiter auf Nachtrag 3 (Fall A, S20
ohne Tile-Bindung) und die Emulator-Matrix (Nachtrag 5 und 6).

### Beobachtung: verzögerte `BOOT_COMPLETED`-Zustellung

Trotz sofortigem Entsperren (Gerät ab ca. 03:58:50 entsperrt) kam `BOOT_COMPLETED` erst um 04:01:35, in
Fall 2 um 04:07:57 (Boot ca. 04:05:40). Das sind rund zweieinhalb Minuten bis zur Zustellung; die
Ursache (Samsung-Broadcast-Verzögerung, Last direkt nach dem Boot) wurde nicht untersucht. Für das
Nutzerbild heißt das: Die Wiederherstellung passiert nicht sofort nach dem Entsperren, sondern nach der
Zustellung des Boot-Broadcasts.

### Schluss

- **AK3 aus #646 ist auf echter Hardware erfüllt:** Autonomes Wiedereinschalten nach echtem Reboot mit
  Zwei-Schritt-Grant, Allowlist und aktuellem `master` (1.8.71).
- Nicht belegt bleibt der fail-closed-Fall auf dem S20 mit Tile (siehe Fall 2); das ist eine
  Messgrenze des Gerätezustands, kein App-Befund.
- Nach dem Lauf: BACKGROUND wieder erteilt, QS-Tile vorhanden, KeepADB im Vordergrund,
  Wireless Debugging an.
