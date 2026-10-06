# Sicherheitsrichtlinie

## Bedrohungsmodell

KeepADB soll Androids Drahtloses Debugging verfügbar halten. Damit kann `adbd` auf einem
dynamischen Port im lokalen Netzwerk lauschen. Das ist der Zweck der App und zugleich die zentrale
Sicherheitsabwägung: KeepADB schränkt vor allem ein, **wann eine automatische Wiederherstellung
erfolgt**. Es umgeht weder Androids TLS-Kopplung noch bestätigt es Kopplungsanfragen für dich.

KeepADB schützt nicht vor einem bereits kompromittierten Gerät, vor einem Nutzer, der eine
unerwartete Kopplungsabfrage bestätigt, oder vor einem Angreifer im selben WLAN, das ausdrücklich
als vertrauenswürdig zugelassen wurde.

## Automatische Wiederherstellung und Netzwerkvertrauen

Eine Neuinstallation startet im Freigabelistenmodus: Automatisch wird nur dann wieder
eingeschaltet, wenn KeepADB die aktuelle WLAN-Identität lesen und einem zugelassenen BSSID
zuordnen kann. Eine unbekannte oder vom System maskierte Identität bleibt gesperrt.
Bestehende Installationen behalten ihre bisherige Einstellung unverändert, auch „In allen WLANs“;
**Einstellungen → Netzwerk** bietet weiterhin die Wahl zwischen allen WLANs und der Freigabeliste.

Das Datenmodell kennt außerdem Sperren pro BSSID und pro WLAN-Name. Eine Sperre gewinnt in jedem
Modus gegen Vertrauen, löst keine Nachfrage aus und schaltet nie automatisch ein; nur ein
ausdrückliches Aufheben nimmt sie zurück, nie das Hinzufügen von Vertrauen. Gesperrt wird über den
Hinweis auf ein unbekanntes WLAN und den Dialog „Diesem Netzwerk vertrauen?“; eine Bedienoberfläche
zum Aufheben folgt mit der Netzwerkliste. Vorrang, Migration und Rückweg stehen im
[Netzwerk-Leitfaden](docs/trusted-networks.md).

Der optionale **Force-Modus** (Einstellungen → Netzwerk) hebt diesen Schutz bewusst auf: Für eine
gewählte Zeit (1 Stunde, 24 Stunden, 7 Tage, 30 Tage oder ohne Ablaufzeit) schaltet Keep-Alive
Drahtloses Debugging in jedem WLAN wieder ein und übergeht Sperren und Vertrauen. Er braucht eine
Bestätigung mit gestaffelten Warnungen, ist auf der Startseite und in der Benachrichtigung
dauerhaft sichtbar und lässt sich dort mit einem Tap beenden. Nach Ablauf gilt wieder genau die
gespeicherte Einstellung; die Frist übersteht Neustart, App-Update und Zeit- oder Zeitzonenwechsel
(eine gestellte Uhr kann den Modus nur verkürzen, nicht verlängern), eine einmalige Meldung nennt
das Ende. Er ist nie ein Standard und wird von keinem Assistenten, Update oder Import ohne den
Dialog gestartet. Einzelheiten und Grenzen stehen im
[Netzwerk-Leitfaden](docs/trusted-networks.md#force-modus).

Die Identität dient ausschließlich zur Wiedererkennung des WLANs; KeepADB ermittelt oder speichert
keinen Gerätestandort. Dafür benötigt Android den präzisen Standortzugriff. Der optionale Zugriff
„Immer zulassen“ wird nicht von KeepADB angefordert: Die App führt zu den Android-App-Einstellungen,
in denen der Nutzer ihn selbst setzen kann. Ohne lesbare Identität bleibt die Freigabe fail-closed.
Die beobachteten Plattformfälle und ihre offenen Grenzen stehen im
[Netzwerk-Leitfaden](docs/trusted-networks.md) und in der
[Messzusammenfassung](docs/trusted-networks-measurement.md).

Der Schalter „Auch nach Netzwerkname (SSID) freigeben“ ist eine separate, standardmäßig ausgeschaltete
Erweiterung. Weil beliebige Zugangspunkte denselben frei gewählten Namen senden können, ist diese
Regel schwächer als die Freigabe einer einzelnen BSSID. KeepADB zeigt den Hinweis direkt an der
Option.

Das Netzwerkvertrauen steuert automatische Wiederherstellung. Die Kachel und andere manuelle
Schaltflächen sind keine Freigabe einer neuen Netzwerkidentität. Zum Einschalten über die
Schnelleinstellungskachel oder die USB-Benachrichtigung muss ein gesperrtes Gerät entsperrt werden;
Ausschalten über die Kachel bleibt vom Sperrbildschirm aus möglich. Eine Netzwerkfreigabe über
eine Benachrichtigung benötigt ebenfalls ein entsperrtes Gerät. Wenn Benachrichtigungsdetails
verborgen sind, hat der Hinweis keine Aktionsknöpfe; ein Tippen öffnet in KeepADB den Dialog
„Diesem Netzwerk vertrauen?“, der den Netzwerknamen zeigt, damit die Entscheidung mit sichtbarem
Namen getroffen wird. Der Dialog zeigt den Namen auch im Privatsphäre-Modus, weil man wissen muss,
wem man vertraut, aber nie auf einem gesperrten Display: Er erscheint erst nach dem Entsperren und
prüft die Displaysperre zusätzlich selbst. Wegwischen des Hinweises entscheidet nichts; dasselbe
Netz wird frühestens nach 24 Stunden erneut gefragt.

## Bedienregeln mit Sicherheitswirkung

KeepADB benötigt die einmalige Berechtigung `WRITE_SECURE_SETTINGS`, die du von einem Computer
über ADB vergibst. Die App kann sie nicht selbst erteilen. Automatische Wiederherstellung setzt
WLAN-Verbindung, Keep-Alive und den zuletzt gespeicherten ausdrücklichen Benutzerwunsch voraus.
Android kann zusätzlich eine eigene Bestätigung verlangen; wenn der Zustand nicht bestätigt wird,
wartet KeepADB, statt schnell wiederholt einzuschalten.

Zum Einschalten über die Schnelleinstellungskachel oder eine USB-Übergabebenachrichtigung muss ein
gesperrtes Gerät entsperrt werden. Ausschalten über die Kachel bleibt auf dem Sperrbildschirm
möglich. Eine Freigabe im Hinweis auf ein unbekanntes WLAN verlangt ebenfalls Entsperrung und zeigt
den Zugangspunktnamen in KeepADB an, wenn Android Benachrichtigungsdetails verbirgt.

## Benachrichtigungen, Webhook und lokale Daten

Netzwerkadressen und Zugangspunktnamen bleiben in Benachrichtigungen standardmäßig verborgen.
Eine Einstellung kann Verbindungsdetails sichtbar machen; ob Android vertrauliche
Benachrichtigungsinhalte auf dem Sperrbildschirm zeigt, hängt zusätzlich von den Android-
Einstellungen dafür ab.

Webhook-Verkehr ist standardmäßig aus und geht nur an die vom Nutzer eingetragene URL. Der
[Webhook-Vertrag](docs/webhook-register.md) beschreibt die vom App-Code erzeugte Nutzlast und
grenzt sie von nicht überprüften Aussagen über einen externen Server ab. HTTPS wird empfohlen;
HTTP bleibt für selbst betriebene LAN- oder VPN-Endpunkte möglich, ist aber unverschlüsselt und wird
in der App kenntlich gemacht. KeepADB sendet keine Analyse- oder Absturzberichte.

Android-Cloud-Backup und Geräteübertragung sind deaktiviert. Dazu zählen Webhook-URL, gespeicherte
Netzwerkfreigaben, USB-Profile und Diagnosedaten. Diese Daten gehen bei einer Deinstallation oder
einem Gerätewechsel verloren. Diagnoseinformationen werden nicht automatisch hochgeladen; Details
zur Freigabe und Maskierung stehen in [Diagnoseexport](docs/diagnostics.md).

## Unterstützte Versionen

Sicherheitskorrekturen gelten für die jeweils neueste veröffentlichte Version. Es gibt keinen
separaten Langzeitpflege-Zweig.

## Sicherheitslücke melden

Bitte veröffentliche keine Sicherheitslücke als öffentliches GitHub-Issue. Verwende die
[private GitHub-Sicherheitsmeldung](https://github.com/m00sfett/KeepADB/security/advisories/new).
Falls du GitHub nicht verwenden kannst, nutze das Feedback-Formular in den Einstellungen oder
[keepadb.roteson.de/feedback](https://keepadb.roteson.de/feedback) und markiere den Bericht
als sicherheitsrelevant.

Nenne KeepADB-Version, Android-Version/API-Level, Schritte zur Reproduktion und nötige
Netzwerkbedingungen. Füge keine Webhook-URL, Kopplungsschlüssel oder sonstigen Zugangsdaten bei.
