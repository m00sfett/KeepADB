# Vertrauenswürdige WLANs

KeepADB kann automatische Wiederherstellung von Drahtlosem Debugging auf die WLANs beschränken,
die du freigibst. Die Regel schützt automatische Aktionen vor einem unerwarteten Zugangspunkt; sie
ändert nicht die manuelle Ein-/Ausschaltfläche.

## Modi und Übereinstimmung

- **In allen WLANs** ist der Standard einer Neuinstallation. Bestehende Installationen, die vor dem
  Wechsel des Standards bereits eine Freigabeliste verwendeten, behalten diesen Modus beim Upgrade.
- **Nur freigegebene Access Points** schaltet automatische Wiederherstellung nur dann frei, wenn
  die aktuelle WLAN-Identität bekannt ist und ihre BSSID genau in der Liste steht.
- Die optionale zusätzliche Freigabe **Auch nach Netzwerkname (SSID) freigeben** ist standardmäßig
  aus. Ist sie eingeschaltet, kann ein exakt und groß-/kleinschreibungssensitiv passender SSID-Name
  ebenfalls freigeben. SSIDs sind frei wählbar und können kopiert werden; eine SSID-Freigabe ist
  deshalb schwächer als eine BSSID-Freigabe. Die SSID-Liste wirkt nur im Freigabelistenmodus.

Eine unbekannte Verbindung, ein fehlender Eintrag oder eine von Android maskierte Identität wird
im Freigabelistenmodus als nicht vertrauenswürdig behandelt. KeepADB speichert kein WLAN als
freigegeben, wenn die aktuelle Identität nicht lesbar ist. Das Hinzufügen eines WLANs schaltet
Drahtloses Debugging nicht selbst ein.

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
entfallen; fehlt eine Nummer in der Reihe, bleibt die Lücke. Access Points, die nur beobachtet
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
