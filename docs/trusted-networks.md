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
verhindert“ vermerkt und schalten nie automatisch ein. Nur der Force-Modus (#763, noch nicht
umgesetzt) darf eine Sperre übergehen; er kommt als Überlagerung oberhalb dieser Reihenfolge.

Bekannte Grenze: Unter „In allen WLANs“ bleibt ein Netz mit nicht lesbarer Identität vertraut, wie
vor #760. Eine Sperre kann es nicht erkennen, weil ihr die Identität fehlt. Im Freigabelistenmodus
pausiert dasselbe Netz.

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
bestehende Oberfläche und die In-App-Bestätigung sie noch lesen; ihre Entfernung gehört zu #762 und
#766.

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
