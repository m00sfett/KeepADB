# Vertrauenswürdige WLANs

KeepADB kann automatische Wiederherstellung von Drahtlosem Debugging auf die WLANs beschränken,
die du freigibst. Die Regel schützt automatische Aktionen vor einem unerwarteten Zugangspunkt; sie
ändert nicht die manuelle Ein-/Ausschaltfläche.

## Modi und Übereinstimmung

- **Alle WLANs** ist der Standard einer Neuinstallation. Bestehende Installationen, die vor dem
  Wechsel des Standards bereits eine Freigabeliste verwendeten, behalten diesen Modus beim Upgrade.
- **Nur zugelassene Zugangspunkte** schaltet automatische Wiederherstellung nur dann frei, wenn
  die aktuelle WLAN-Identität bekannt ist und ihre BSSID genau in der Liste steht.
- Die optionale zusätzliche Freigabe **Auch nach WLAN-Namen (SSID) erlauben** ist standardmäßig
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

## Messgrenzen

Die Messungen auf AOSP-Emulatoren und dem Galaxy S20 FE belegen die oben beschriebenen Fälle für
die jeweils getesteten Android-Versionen. Sie decken nicht alle Hersteller, WLAN-Treiber,
Berechtigungsabläufe oder Neustartvarianten ab. Die
[Messzusammenfassung](trusted-networks-measurement.md) trennt die konkreten Ergebnisse von den
offenen Fragen; das [vollständige Archiv](archive/trusted-networks-measurements-2026-09.md)
bewahrt die ursprünglichen Protokolle. Der Code- und Manifeststand ist maßgeblich, falls eine
historische Messnotiz einer heutigen Implementierung widerspricht.
