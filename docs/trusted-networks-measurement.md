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
> Hintergrundmaskierung auf API 33 #630).

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
2. **Minimaler Datenschutz-Impact & kein Background-Location-Grant:** Es ist kein aggressives
   `ACCESS_BACKGROUND_LOCATION` („Immer zulassen“) nötig. Die normale While-in-Use-Berechtigung
   `ACCESS_FINE_LOCATION` („Beim Verwenden der App“) reicht für Androids Einstufung des FGS mit
   Service-Typ `location` völlig aus.
3. **Optimale UX:** Keine verwirrenden Dialoge oder Umwege über Systemeinstellungen („Immer zulassen“);
   vollständig kompatibel mit den Richtlinien von F-Droid und Google Play.
4. **Defensiver Fallback:** Der Service-Typ `location` wird beim `startForeground()` dynamisch
   nur dann angefordert, wenn `ACCESS_FINE_LOCATION` tatsächlich erteilt ist. Das verhindert
   `SecurityException`s auf Android 14+ (API 34+) im Standardmodus `all_wifi` ohne Allowlist.

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
