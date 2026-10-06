# Vertrauenswürdige WLANs

KeepADB kann automatische Wiederherstellung von Drahtlosem Debugging auf die WLANs beschränken,
die du freigibst. Die Regel schützt automatische Aktionen vor einem unerwarteten Zugangspunkt; sie
ändert nicht die manuelle Ein-/Ausschaltfläche.

## Modell: Vertrauen, Sperren und Vorrang

Ein einheitliches Modell (#760) entscheidet über jede automatische Wiederherstellung: Dienst
(Beobachter und 60-Sekunden-Prüfung), Wiederbelebungsimpuls, USB-Übergabe und der Keep-Alive-
Schalter fragen alle dieselbe Stelle. Die Oberfläche sagt dazu noch „freigegeben“ und
„Freigabeliste“; gemeint ist „vertraut“. Die Umbenennung folgt mit der Netzwerkliste (#762).

**Bausteine**

- **Vertraut pro Access Point (BSSID).** Eine Neuinstallation startet im Freigabelistenmodus:
  automatische Wiederherstellung nur, wenn die aktuelle WLAN-Identität bekannt ist und ihre BSSID
  genau in der Liste steht.
- **Sperre pro BSSID und pro WLAN-Name (SSID „niemals“).** Die Adresse wird ohne Beachtung der
  Groß-/Kleinschreibung verglichen, der Name genau, so wie Android gespeicherte Netze abgleicht.
  Platzhalter (maskierte oder leere BSSID, unbekannter Name) lassen sich nicht sperren, weil eine
  solche Sperre jedes nicht lesbare Netz träfe.
- **Komfortschalter „Auch nach WLAN-Name vertrauen“** (`trust_by_name`, standardmäßig aus):
  Access Points, die den Namen eines vertrauten Access Points tragen, gelten ebenfalls als
  vertraut. Die Namen werden aus den vertrauten Access Points abgeleitet; es gibt keine zweite
  Namensliste. Ein gesperrter Access Point trägt seinen Namen nicht bei. Ein Name lässt sich
  nachahmen, deshalb bleibt der Schalter aus.
- **Bisherige Einstellung „In allen WLANs“** (gespeicherter Wert `all_wifi`): jedes Netz gilt als
  vertraut, auch eines mit nicht lesbarer Identität, **außer es ist gesperrt**. Die Einstellung
  bleibt für bestehende Installationen unverändert; sie ist weder Force noch eine der
  Schutzstufen (siehe Migration).
- **Namensfreigabe (Altbestand):** die bisherige SSID-Liste mit ihrem Schalter „Auch nach
  Netzwerkname (SSID) freigeben“. Sie bleibt in Kraft, wie gespeichert, und wirkt nur im
  Freigabelistenmodus; SSIDs sind frei wählbar und können kopiert werden, die Freigabe ist deshalb
  schwächer als eine BSSID-Freigabe.

**Vorrang** (von oben nach unten, das erste Zutreffende entscheidet; umgesetzt an einer Stelle,
`KeepADBTrustedNetwork.evaluate`):

1. Sperre über die BSSID.
2. Sperre über den WLAN-Namen (gilt auch, wenn nur der Name lesbar ist).
3. Bisherige Einstellung „In allen WLANs“: vertraut.
4. Vertrauter Access Point.
5. Vertrauter Name: abgeleitet (Komfortschalter) oder Namensfreigabe (Altbestand), genau und mit
   Beachtung der Groß-/Kleinschreibung.
6. Sonst: Identität lesbar = unbekannt, KeepADB fragt nach; nicht lesbar = pausieren.

Eine Sperre gewinnt gegen jedes Vertrauen, auch gegen das derselben BSSID. Das Vertrauen bleibt
gespeichert und gilt wieder, sobald die Sperre ausdrücklich aufgehoben wurde. Vertrauen hinzuzufügen
hebt nie eine Sperre auf, weder über die Benachrichtigung noch über Liste, Karte oder Mesh-Angebot;
es wird dann nichts gespeichert. Gesperrte Netze lösen keine Nachfrage aus, werden nicht als „zuletzt
verhindert“ vermerkt und schalten nie automatisch ein. Nur der Force-Modus ([siehe unten](#force-modus))
übergeht eine Sperre; er ist eine Überlagerung oberhalb dieser Reihenfolge.

Bekannte Grenze: Unter „In allen WLANs“ bleibt ein Netz mit nicht lesbarer Identität vertraut, wie
vor #760. Eine Sperre kann es nicht erkennen, weil ihr die Identität fehlt. Im Freigabelistenmodus
pausiert dasselbe Netz. Der Force-Modus ändert daran nichts: Er übergeht Sperren und Vertrauen,
solange er läuft, und lässt danach exakt die gespeicherte Einstellung wieder gelten.

## Nachfrage und Entscheidungsdialog

Trifft KeepADB beim automatischen Einschalten auf einen lesbaren Access Point, der weder vertraut
noch gesperrt ist, erscheint der Hinweis „Neues WLAN / neuer Access Point erkannt“ (#446, #766). Er hat zwei Formen und
ein Tipp-Ziel.

- **Mit Benachrichtigungsdetails:** Name und BSSID stehen im Text, dazu zwei Knöpfe: „Ja, zulassen“
  (verlangt Entsperrung bzw. Authentifizierung) und „Nein, blockieren“. Der zweite sperrt den
  Access Point tatsächlich, nur die BSSID, nicht den Namen; vorher schloss er nur den Hinweis.
- **Ohne Details (Standard):** Der Hinweis nennt nichts und hat keine Knöpfe; das Tippen führt in
  die App. Die öffentliche Fassung für den Sperrbildschirm bleibt in beiden Formen ohne Name,
  Adresse, Aktion und Ziel.
- **Tippen** öffnet den Dialog „Diesem Netzwerk vertrauen?“ (`NetworkDecisionActivity`, Dialog-Design,
  nicht in der Liste der zuletzt benutzten Apps, ohne Anzeige über dem Sperrbildschirm). Er zeigt
  WLAN-Name und Access Point (BSSID), bewusst auch im Privatsphäre-Modus, und bietet: Vertrauen,
  „Nur diesen Access Point blockieren“, „WLAN-Namen … immer blockieren“ (entfällt bei verborgenem
  oder nicht lesbarem Namen) und „Später entscheiden“. Ist der Komfortschalter an, nennt ein Hinweis,
  dass dann auch andere Access Points mit diesem Namen akzeptiert werden. Der Dialog ist als
  eigene, einbettbare Ansicht gebaut (`NetworkDecisionView`), die Netzwerkliste (#762), der Assistent
  (#761) und die Startseite (#764) können sie übernehmen.

**Was nichts entscheidet.** „Später entscheiden“, Zurück, ein Tippen neben den Dialog, Drehen des
Geräts und das Wegwischen des Hinweises ändern keinen Status. Derselbe Access Point wird frühestens
nach 24 Stunden erneut gefragt (zuvor 6 Stunden), ein gesperrter nie.

**Gesperrtes Gerät.** Der Hinweis bleibt neutral (kein Name, #578/#592/#598). Der Dialog erscheint
erst nach dem Entsperren: Die Activity trägt weder `showWhenLocked` noch `turnScreenOn`, sodass
Android vor dem Öffnen die Entsperrung verlangt, und sie prüft die Displaysperre zusätzlich selbst.
Solange sie besteht, bindet sie weder Name noch Adresse; beim Anhalten (Display aus, andere App)
werden beide wieder aus der Ansicht entfernt.

**Bindung.** Der Dialog gilt dem Access Point, für den die Anfrage entstand, nicht dem aktuellen
WLAN; der Name kommt aus der eigenen Aufzeichnung, nie aus dem Intent. Ist die Anfrage inzwischen
beantwortet (vertraut oder gesperrt, etwa über die Netzwerkliste) oder gibt es keine Aufzeichnung
mehr (verdrängt, unbekannt, Platzhalter-BSSID), öffnet sich kein Dialog, sondern eine kurze Meldung.
Wird in einem Dialog, der vor einer Sperre geöffnet wurde, auf „Vertrauen“ getippt, verweigert
KeepADB das (die Sperre gewinnt, #760), speichert nichts und sagt das auch.

Bekannte Grenze dieses Stands: Eine Sperre aus Hinweis oder Dialog lässt sich in der App noch nicht
aufheben; die Aufhebung folgt mit der Netzwerkliste (#762). Das gespeicherte Vertrauen bleibt
dabei erhalten und gilt nach dem Aufheben wieder.

## Migration und Rückweg

Die Migration schreibt nichts um: das Modell liest die bisherigen Schlüssel an Ort und Stelle.
Damit ist sie idempotent, verliert nichts und verschärft oder lockert nichts still.

| Gespeicherter Stand | Lesart im Modell (`getProtectionLevel`) | Verhalten |
|---|---|---|
| Modus `all_wifi` | Bisherige Einstellung „In allen WLANs“ | unverändert; Sperren gelten zusätzlich |
| Freigabelistenmodus, Namensfreigabe aus oder leer | Maximal sicher | unverändert |
| Freigabelistenmodus, Namensfreigabe an mit Einträgen | Bisherige Namensliste | unverändert; die Namensliste bleibt in Kraft |
| Freigabelistenmodus, Komfortschalter (neu) an | Ausgewogen | Namen vertrauter Access Points |
| nichts gespeichert (Neuinstallation) | Maximal sicher | nur vertraute BSSIDs |

Die zwei „bisherigen“ Stände sind Einstellungen, die keine der Schutzstufen genau abbildet. Sie
werden deshalb als solche gezeigt, nicht auf eine Stufe umgelegt: „In allen WLANs“ auf
„Maximal sicher“ oder „Ausgewogen“ zu legen würde still verschärfen, die Namensliste auf den
abgeleiteten Komfortschalter zu legen würde zugleich Namen verlieren (nur über die Liste freigegebene
Namen ohne vertrauten Access Point) und neue freigeben (die Namen aller vertrauten Access Points).
Abgelöst werden sie erst durch die ausdrückliche Wahl im Assistenten (#761) bzw. in der Liste (#762).

Der Modus wird beim ersten Lesen einmal festgeschrieben, damit ein späterer Wechsel des Standards
keine Installation verschiebt. Ein gespeicherter Modus bleibt wörtlich erhalten; nur der exakte Wert
`all_wifi` bedeutet die bisherige Einstellung, jeder andere (fehlende oder beschädigte) Wert gilt
als Freigabelistenmodus. Eine Installation ganz ohne gespeicherten Modus (Neuinstallation, oder eine
sehr alte, die die Regel seit 1.8.9 nie ausgewertet hat) startet im Freigabelistenmodus.

Der Verlauf „zuletzt verhindert“ und die Beobachtungsliste haben nie über Vertrauen entschieden und
gehen nicht in das Modell ein. Ihre Speicher bleiben in diesem Stand unangetastet, weil die
bestehende Oberfläche sie noch liest; ihre Entfernung gehört zu #762. Der Entscheidungsdialog (#766)
nutzt den Verlaufseintrag als Aufzeichnung der Anfrage (Name und BSSID) und löscht ihn, sobald die
Anfrage beantwortet ist.

**Rückweg.** Neu sind nur zusätzliche Schlüssel in `keepadb_prefs` (`blocked_bssids`,
`blocked_ssids`, `trust_by_name`); alle bisherigen Schlüssel behalten Format und Bedeutung. Eine
ältere App-Version liest daher dieselben Freigaben, Namensfreigaben und denselben Modus, ignoriert
die neuen Schlüssel und beachtet Sperren nicht. Beim Zurückgehen auf eine ältere Version gehen also
nur die Wirkung der Sperren verloren, keine Daten. Backup und Gerätewechsel nehmen `keepadb_prefs`
nicht mit (`allowBackup="false"` und `data_extraction_rules.xml`).

Belege im Code: `KeepADBTrustPrecedenceTest` (Vorrang, beidseitig), `KeepADBTrustMigrationTest`
(gleiche Entscheidung wie 1.9.28 für jeden gespeicherten Stand, nichts umgeschrieben, Rückweg),
`KeepADBBlockedNetworkCallPathTest` (die Sperre hält auf den tatsächlich handelnden Wegen).

Eine Neuinstallation und jeder Wechsel in den Freigabelistenmodus brauchen die Standortfreigabe aus
dem folgenden Abschnitt; ohne lesbare Identität pausiert KeepADB und weist darauf hin.

## Force-Modus

Der Force-Modus (#763) ist die einzige Ausnahme vom Vorrang oben: Für eine gewählte Zeit schaltet
Keep-Alive Drahtloses Debugging in **jedem** WLAN wieder ein, in fremden, gesperrten und nicht
lesbaren. Er ist eine Überlagerung, kein Modus des Modells. Aktivieren und Beenden lesen und
schreiben nichts, was das Modell speichert (vertraute Access Points, Sperren, Komfortschalter,
bisherige Einstellung). „Zurück auf die vorherige Schutzstufe“ heißt deshalb nur: die Überlagerung
fällt weg. Die Überlagerung wirkt an der einen Stelle, die jeder automatische Pfad befragt
(`KeepADBTrustedNetwork.evaluateCurrent`), also im Dienst (Beobachter und Minutentakt), im
Wiederbelebungsimpuls, in der USB-Übergabe und in den Schutzprüfungen unmittelbar vor dem
Schreiben. Was sie nicht ersetzt: Keep-Alive und eine WLAN-Verbindung, und ein manuelles
„Drahtloses Debugging aus“ bleibt bestehen. Das Hinzufügen von Vertrauen zu einem gesperrten Netz
bleibt auch im Force-Modus unmöglich; er verändert weder Sperren noch Vertrauen.

**Starten und Beenden.** Starten kann ihn nur der Bestätigungsdialog (Einstellungen → Netzwerk →
Force-Modus), und zwar mit Pflicht-Zeitlimit: 1 Stunde (Vorauswahl), 24 Stunden, 7 Tage, 30 Tage oder
„Ohne Ablaufzeit“. Der Dialog warnt gestaffelt (allgemein, ab 7 Tagen zusätzlich über die
Netzwerke unterwegs, ohne Ablauf zusätzlich über das Fortbestehen nach Neustarts und Updates); „Ohne
Ablaufzeit“ lässt sich nur mit gesetztem Kästchen bestätigen. Keep-Alive wird mit eingeschaltet, wenn
es aus ist (der Dialog sagt das). Eine andere Dauer ist ein neuer, voller Start durch denselben
Dialog; es gibt keine stille Verlängerung. Beenden kann der Nutzer jederzeit ohne Rückfrage: Karte
auf der Startseite, Zeile in den Einstellungen, erste Aktion der Benachrichtigung.

**Ablauf.** Die Zeit endet an der Frist selbst: Die Prüfung ist eine reine Lesung und liefert vom
ersten Moment nach der Frist „aus“, auch bevor irgendein Zeitgeber lief. Den sichtbaren Übergang
(Zustand löschen, Oberflächen aktualisieren, einmalige Meldung) erledigt der Minutentakt des
Dienstes, ein ungenauer Wecker (er läuft auch ohne Prozess), die Empfänger für Neustart, App-Update
und gestellte Uhr sowie das Öffnen der Startseite oder der Einstellungen. Die Meldung erscheint
genau einmal, auch wenn der Ablauf in einen Neustart oder ein Update fiel; sie hat einen eigenen
Kanal „Sicherheitshinweise“, bietet keine Wiederaufnahme an und benennt die wiederhergestellte
Schutzstufe. Läuft Drahtloses Debugging dann noch in einem WLAN, dem die Schutzstufe nicht
vertraut, meldet sie das und bietet „Jetzt ausschalten“ an; ausgeschaltet wird nichts von selbst.

**Zeitregeln.** Gespeichert wird ein einzelner Wert in `keepadb_prefs` (`force_state`, mit
`commit()` geschrieben): Dauer, verbleibendes Zeitbudget und die Messbasis, von der es zählt
(Wanduhr, monotone Uhr und Boot-Zähler in diesem Moment). Beim Start ist das Budget die ganze
Dauer. Die Restzeit ist das **Kleinere** aus zwei Maßen, sodass jede Uhrenabweichung den Modus nur
früher beenden, nie verlängern kann: der Wanduhr (`Basis + Budget − jetzt`, epochenbasiert, daher
unberührt von Zeitzone und Sommerzeit) und, solange der Boot-Zähler der Basis läuft, der monotonen
Uhr (`SystemClock.elapsedRealtime`, zählt Tiefschlaf mit). Eine rückwärts gestellte Wanduhr
verlängert ihn deshalb innerhalb eines Boots nicht, eine vorwärts gestellte beendet ihn früher.

Nach einem Neustart beginnt die monotone Uhr von vorn. Der erste Treiber im neuen Boot (Minutentakt
des Dienstes, Empfänger für Neustart, Update und gestellte Uhr oder das Öffnen der App, je nachdem,
was zuerst läuft) bindet deshalb das verbleibende Budget an diesen Boot und speichert es: Wanduhr,
monotone Uhr und Boot-Zähler dieses Moments sind die neue Basis. Ab dann kann eine rückwärts
gestellte Wanduhr die Frist auch in diesem Boot nicht mehr verlängern; ein weiterer Neustart
wiederholt das von der neuen Basis aus. Ein Neustart allein beendet den Modus nicht und schreibt
keine Zeit gut, das Budget wird nur kleiner. Bis zu dieser Bindung zählt die Wanduhr allein und nur
vorwärts ab der Basis: Liegt sie davor (zurückgesetzte Uhr), lässt sich die Restzeit nicht
bestimmen und der Modus endet (fail-closed).

Auch innerhalb eines Boots wird eine rückwärts gestellte Wanduhr festgehalten: Fällt sie gegenüber
der monotonen Uhr seit der Basis um mehr als fünf Sekunden zurück, macht der Treiber, der es als
Erster sieht (die Meldung über die gestellte Uhr sofort, der Minutentakt und das Öffnen der App
spätestens dann), das, was die monotone Uhr als Rest nennt, zur neuen Basis. Ein Neustart danach
findet so den richtigen Stand und schreibt den Sprung nicht gut. Eine vorwärts gestellte Wanduhr
wird nie festgehalten: Sie verkürzt nur, und wer sie wieder richtig stellt, verliert nichts. Der
umgekehrte Weg kostet dagegen etwas: Wurde eine falsch zurückgestellte Uhr von einem Treiber gesehen
und danach wieder vorwärts richtiggestellt, gilt diese Korrektur als vorwärts gestellte Uhr und
verkürzt den Modus um die Sprunggröße. Das ist die sichere Seite: früher, nie später.

Ist der Boot-Zähler des Systems nicht lesbar, endet ein befristeter Modus sofort: Die Prüfung
liefert vom ersten Lesen an „aus“, die einmalige Ablaufmeldung folgt wie sonst, und ein befristeter
Modus lässt sich in diesem Zustand gar nicht erst starten; der Dialog sagt das in einem Hinweis und
bleibt offen. Wird der Zähler wieder lesbar, bleibt der
Modus beendet und kann aus seinem Dialog neu gestartet werden; nichts bleibt gesperrt. „Ohne
Ablaufzeit“ hat keine Frist, braucht den Zähler nicht und bleibt bis zum Beenden, auch über
Neustarts und Updates.

Bekannter Rest: Eine Wanduhr, die nach dem Neustart vor dem ersten Treiber zurückgestellt wird (ein
kurzes Fenster, die Boot-Meldung kommt nach dem Entsperren), ist nur für die Wanduhr sichtbar. Der
Modus verlängert sich dann um die Sprunggröße. Das Gleiche gilt für eine Rückstellung unter der
Fünf-Sekunden-Toleranz und für eine, auf die ein Neustart folgt, bevor ein Treiber sie festhalten
konnte. Alle drei brauchen eine Rückstellung in genau so einem Fenster, nicht durch Zeitzone oder
Sommerzeit. Nicht auf einem Gerät geprüft ist, dass `Settings.Global.boot_count` bei allen
Herstellern lesbar ist; wo nicht, lässt sich ein befristeter Modus nicht starten.

**Sperrbildschirm.** Warnzeile und Beenden-Aktion stehen nur in der privaten Fassung der
Benachrichtigung; deren öffentliche Fassung bleibt neutral, und auch die Ablaufmeldung zeigt dort
nur die Kanalbezeichnung. Wie bei allen privaten Benachrichtigungsinhalten gilt: Wer in Android
sensible Inhalte auf dem Sperrbildschirm erlaubt, sieht dort die private Fassung.

**Rückweg.** Neu sind nur zwei zusätzliche Schlüssel in `keepadb_prefs` (`force_state`,
`force_expired_notice_pending`). Eine ältere App-Version ignoriert sie; ein Zurückgehen beendet den
Modus (der engere Zustand) und verliert keine Daten. Beide Schlüssel verlassen das Gerät nicht
(Backup und Gerätewechsel sind ausgeschlossen).

Belege im Code: `KeepADBForceModeTest` (Zeitregeln von beiden Seiten, Neustart, Update, gestellte Uhr,
Zeitzone, genau eine Meldung, Wecker), `KeepADBForceModeCallPathTest` (Vorrang auf jedem handelnden
Pfad, mit Gegenproben und Ablauf), `KeepADBForceNotificationTest`, `KeepADBForceDialogTest`,
`MainActivityForceCardTest` und `KeepADBForceContractTest` (nur der Dialog startet; Gate und
Darstellung lesen nur).

## Berechtigungen und Hintergrundverhalten

Android schützt SSID und BSSID als Standortdaten. Für den Freigabelistenmodus benötigt KeepADB
**präzisen Standortzugriff** und eingeschaltete Android-Standortdienste, um die WLAN-Identität zu
lesen. Die Standortdaten dienen nur diesem Vergleich; KeepADB ermittelt und speichert keinen
physischen Gerätestandort. Ein Nur-ungefähr-Zugriff reicht nicht.

Ist der präzise Standortzugriff erteilt, verwendet der Keep-Alive-Foreground-Service zusätzlich
den Android-Typ für Standortzugriff, damit WLAN-Identität bei einem aus der App gestarteten Dienst
auch nach dem Verlassen der App lesbar bleibt. Android kann die Identität nach einem Start aus dem
Hintergrund, etwa nach Neustart oder App-Aktualisierung, dennoch maskieren. Optional kann der
Nutzer **Standortzugriff → Immer zulassen** auf der Android-App-Berechtigungsseite selbst
einschalten. KeepADB fordert diese Berechtigung nicht im Android-Berechtigungsdialog an und öffnet
nur nach einer erklärenden App-Ansicht die Systemeinstellungen.

Ohne lesbare Identität bleibt die Freigabe geschlossen. Ab Android 14 kann Android bei einem
Hintergrundstart auch den Standorttyp des Foreground-Service ablehnen; KeepADB versucht dann mit
dem Typ für verbundene Geräte weiterzulaufen, während die Netzwerkidentität gesperrt bleibt. Ein
laufender Keep-Alive-Dienst bedeutet in diesem Fall nicht, dass der aktuelle Zugangspunkt
freigegeben wurde.

## Neustart und manuelle Bedienung

Der Boot-Empfänger startet Keep-Alive nur, wenn es eingeschaltet ist und der gespeicherte letzte
Benutzerwunsch dem Start nicht widerspricht. Er reagiert auf abgeschlossenen Systemstart und
App-Aktualisierung. Android kann den normalen Boot-Empfang bei verschlüsseltem Gerätespeicher bis
zur ersten Entsperrung aufschieben. Startzeit und Wiederherstellung hängen zusätzlich von Android,
Hersteller und Berechtigungsstatus ab.

Die Vertrauensregel gilt für automatische Wiederherstellung. Manuelles Umschalten in der App,
über Widget oder Schnelleinstellung bleibt eine bewusste Nutzeraktion und wird nicht durch die
Freigabeliste gesperrt. Sperrbildschirmregeln für Kachel und Benachrichtigungsaktionen stehen in
der [Sicherheitsrichtlinie](../SECURITY.md).

## Nummer, eigener Name und Band in der Access-Point-Liste

Jeder freigegebene Access Point steht in der Liste **Freigegebene APs** mit einem Eintrag.
Der Eintrag zeigt den Namen in der Form `Name (Nr.)`, wenn derselbe Name mindestens zweimal
in der gerade angezeigten Liste vorkommt. Jeder Eintrag erhält die Nummer einmal beim Freigeben;
sie wird nie wiederverwendet und ändert sich nicht, wenn andere Einträge hinzukommen oder
entfallen; fehlt eine Nummer in der Reihe, bleibt die Lücke. Die Zeile des aktuellen Access Points
zählt mit, wenn er freigegeben ist: Teilt er seinen Namen mit einem aufgelisteten Eintrag, tragen beide
eine Nummer; ist er nicht freigegeben, zählt er nicht mit. Access Points, die nur beobachtet
oder verhindert wurden, sind kein gespeicherter Eintrag und haben weder Nummer noch eigenen Namen.

Im Datenschutzmodus wird der Name ausgeblendet und als `Name verborgen #n (Nr.)` angezeigt,
wobei `#n` die Zählung für den verborgenen Namen ist — genau eine Zählung pro Eintrag.

Das kleine Stift-Symbol neben dem Namen öffnet ein einfaches Eingabefenster mit **OK**, **Abbrechen**
und, sobald ein Name gesetzt ist, **Zurücksetzen**. Ein leeres Feld mit **OK** setzt ebenfalls
zurück. Der eigene Name ist höchstens 40 Zeichen lang, steht in der Zeile anstelle des
Netzwerknamens und liegt nur lokal in den App-Einstellungen. Der Netzwerkname (SSID) bleibt
unverändert sichtbar, und der Name wirkt auf keine Freigabeentscheidung: Maßgeblich bleibt allein die
BSSID. Bei eingeschaltetem Datenschutzmodus wird der Name wie der Netzwerkname ausgeblendet und das
Stift-Symbol nicht angeboten.

Hinter jeder BSSID steht in Klammern das Band, etwa `AA:BB:CC:DD:EE:01 (5 GHz)`. Ist kein Band
bekannt, steht dort gar nichts: keine Klammern und kein Ersatztext. KeepADB löst dafür **keinen**
WLAN-Scan aus und plant keinen. Gelesen werden beim Öffnen der Liste nur Daten, die Android bereits
hält: die Frequenz der aktuellen Verbindung und die zwischengespeicherten Scanergebnisse des
Systems, je BSSID. Zwei BSSIDs desselben Netzes, etwa die 2,4- und die 5-GHz-Funkeinheit eines
Routers, bleiben getrennte Einträge mit eigenem Band. Android gibt Scanergebnisse nur bei präzisem
Standortzugriff und eingeschalteten Standortdiensten heraus und kann sie zeitweise veraltet halten;
ohne verfügbare Frequenz erscheint kein Band, bis Android sie beim nächsten Öffnen der Liste
liefert.

Nur wenn die Option **WLAN-Beobachtung** („Observe access points“) eingeschaltet ist, speichert
KeepADB zusätzlich das zuletzt gesehene Band je BSSID in der Beobachtungshistorie
(`keepadb_prefs`, Feld `bssid_history_<id>_bands`): nur das letzte Band, bei einer neuen
Beobachtung überschrieben, ohne Verlauf und ohne Zeitstempel, mit denselben Grenzen wie die
Historie (8 BSSIDs je Netzwerkname, 50 Netzwerknamen) und gelöscht bei Deinstallation. Es erscheint
nur dort, wo Android das Band gerade nicht liefert; das aktuelle Band hat immer Vorrang. Beim
Ausschalten der Option werden alle gespeicherten Bänder gelöscht; die Historie der BSSIDs bleibt
bestehen, es sei denn, die anschließende Rückfrage „Verlauf löschen?“ wird ausdrücklich mit „Ja“
beantwortet (Abbrechen, Zurück, Drehen oder Prozessende zählen als „Nein“). Ohne eingeschaltete Beobachtung wird nichts gespeichert und nur das aktuelle Band
angezeigt. Das Band ist reine Anzeige und fließt nie in eine Freigabeentscheidung ein.

## Messgrenzen

Die Messungen auf AOSP-Emulatoren und dem Galaxy S20 FE belegen die oben beschriebenen Fälle für
die jeweils getesteten Android-Versionen. Sie decken nicht alle Hersteller, WLAN-Treiber,
Berechtigungsabläufe oder Neustartvarianten ab. Die
[Messzusammenfassung](trusted-networks-measurement.md) trennt die konkreten Ergebnisse von den
offenen Fragen; das [vollständige Archiv](archive/trusted-networks-measurements-2026-09.md)
bewahrt die ursprünglichen Protokolle. Der Code- und Manifeststand ist maßgeblich, falls eine
historische Messnotiz einer heutigen Implementierung widerspricht.
