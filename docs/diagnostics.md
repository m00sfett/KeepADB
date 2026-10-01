# KeepADB-Diagnosen

KeepADB protokolliert strukturierte Ereignisse mit dem Logcat-Tag KeepADBDiag. Ereignisse enthalten
Zeitstempel, verstrichene Zeit, Prozess-ID, Android-SDK, Ereignistyp, Auslöser, Ergebnis und ein
kurzes Detailfeld.

## Anzeigen und Teilen

In **Einstellungen → Diagnose → Diagnose exportieren** öffnet Androids Teilen-Menü einen
Text-Export. KeepADB lädt Diagnosen nicht automatisch hoch. Ein Export verlässt die App erst, wenn
du selbst ein Ziel auswählst. Für einen flüchtigen Live-Auszug kann Androids Logcat verwendet
werden:

~~~sh
adb logcat -s KeepADBDiag
~~~

Release-Builds halten höchstens 128 Ereignisse in einem privaten Ringpuffer; ältere Einträge
werden überschrieben. Debug-Builds verwenden stattdessen ein Journal mit einem 48-Stunden-Fenster
und zusätzlichen periodischen Zustandsständen. Android-Cloud-Backup und Geräteübertragung sind
deaktiviert.

## Maskierung und enthaltene Daten

Paarungscodes, Kennwörter, Tokens, Autorisierungswerte und URLs werden vor Speicherung und Export
redigiert. Diagnoseausgaben können dennoch den WLAN-ADB-Endpunkt mit Host und Port enthalten,
weil dieser Gegenstand der Diagnose ist.

Beim Export bleibt von einer gültigen BSSID nur der erste Dreierblock (OUI, Herstellerkennung)
sichtbar; die restlichen drei Blöcke werden maskiert. Ungültige BSSID-Werte und SSIDs werden
vollständig maskiert. Ein Fehlerbericht aus **Einstellungen → Problem melden** maskiert zusätzlich
Host und Port sowie BSSID und SSID vollständig, bevor die Diagnosen in den editierbaren
Berichtsentwurf eingefügt werden.

Logcat hat Androids eigene Aufbewahrungsdauer. Ein Diagnoseexport kann weiterhin private
Netzwerkadressen enthalten. Prüfe die Vorschau und entferne vertrauliche Angaben, bevor du den
Text an andere weitergibst.

## Ereignisfolge lesen

Typische Abläufe erscheinen als Benutzeraktion oder Umschaltversuch, beobachteter Zustand,
Wiederherstellungsversuch oder Stopp und danach Service-Ereignisse. intentId verbindet geplante und
abgeschlossene Umschalt- oder Wiederherstellungsschreibvorgänge. Prozess-ID und verstrichene Zeit
helfen, einen Dienstneustart oder eine Lücke zwischen Ereignissen.
