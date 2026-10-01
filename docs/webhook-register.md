# Webhook: lokaler Sendervertrag

Diese Seite beschreibt nur, was der KeepADB-App-Code lokal konfiguriert und sendet. Sie belegt
keinen aktuellen Zustand eines externen Empfängers, keine Serverroute, keine Datenbankwirkung und
keine dauerhaft gespeicherten Empfängerfelder. Eine frühere Serverinspektion ist als historischer,
nicht erneut verifizierter Entwurf unter
[Issue 539](design/issue-539-multi-transport-register-contract.md) abgelegt.

## Einrichtung und Ein-/Ausschalten

Die Webhook-Synchronisierung ist standardmäßig ausgeschaltet. Nutzer tragen die Ziel-URL ein und schalten die
Synchronisierung in den App-Einstellungen selbst ein. Beide Werte liegen in den privaten App-Einstellungen
und werden nicht per Android-Cloud-Backup oder Geräteübertragung wiederhergestellt. Nach
Neuinstallation müssen sie erneut gesetzt werden. KeepADB sendet ausschließlich an die vom Nutzer
gespeicherte URL.

## Aktueller Senderpfad

Bei erfolgreicher WLAN-ADB-Endpunktprüfung erzeugt der App-Code ein JSON-POST mit dem
Header **Content-Type: application/json; charset=utf-8**. Aktuell ist nur die Methode
**wlan-adb** zur Übertragung freigegeben. Der Code erzeugt zusätzlich Entwürfe für Tailscale- und USB-Ereignisse, hält diese
aber vor dem HTTP-Aufruf zurück. Diese lokale Sperrliste dokumentiert den Senderstand; sie sagt
nicht aus, welche Methoden ein externer Server heute akzeptiert.

Das WLAN-Ereignis enthält:

- **contract_version**: numerischer Wert 2
- **method**: wlan-adb
- **active**: true
- **endpoint**: der bestätigte WLAN-ADB-Host und Port
- **source**: keepadb-app
- **observed_at**: ISO-8601-Zeitpunkt in UTC, den der WLAN-Sender beim Versand einträgt
- **event_id**: deterministischer, gekürzter SHA-256-basierter Bezeichner aus Methode, Endpunkt und
  Aktivstatus

Beispiel mit Platzhalterwerten; der Endpunkt steht hier nur für die Form host:port:

~~~json
{"contract_version":2,"method":"wlan-adb","active":true,"endpoint":"192.0.2.10:5555","source":"keepadb-app","observed_at":"2026-10-01T12:34:56Z","event_id":"keepadb-v2-wlan-adb-<gekürzter-digest>"}
~~~

Der Sender nimmt keine SSID, BSSID, USB-Profile, Pairing-Codes oder Zugangstoken in diesen
Nachrichtenkörper auf. Der bestätigte Endpunkt kann trotzdem eine private LAN- oder VPN-Adresse
enthalten.

Die Registrierung verwendet HTTP POST an die gespeicherte URL. Bei Entfernen oder Wechsel der
Konfiguration sendet der Produktionscode HTTP DELETE an die dafür gespeicherte Ziel-URL. Der
separate Builder für ein Ereignis mit active=false wird vom Produktionspfad derzeit nicht
aufgerufen. Dieses Repository beschreibt damit die ausgehenden Methoden und Inhalte, nicht die
Semantik, die ein Empfänger einem POST oder DELETE gibt.

Der HTTP-Client folgt Weiterleitungen nicht. Ein HTTP-Status von 200 bis 299 gilt lokal als
erfolgreicher Request. Das beweist weder, dass ein Empfänger die Daten fachlich akzeptiert hat,
noch dass sie dauerhaft gespeichert oder für Clients abrufbar sind.

## Transport und Privatsphäre

HTTPS wird empfohlen. Das Projekt erlaubt außerdem HTTP für vom Nutzer konfigurierte lokale
oder selbst betriebene Ziele; HTTP verschlüsselt weder URL noch Nachrichtenkörper. Androids
Netzwerksicherheitskonfiguration lässt diesen Versandpfad ausdrücklich zu, und die App zeigt bei
HTTP eine Warnung. Nutze unverschlüsseltes HTTP nur in einem Netz, dem du vertraust. Bei
Weitergabe von URL oder Diagnoseprotokoll Zugangsdaten entfernen.

Die Webhook-URL wird aus privaten App-Einstellungen gelesen. App-Deinstallation löscht diesen Wert
wie andere App-Daten. Wenn ein Build verändert wird, muss der aktuelle Sendercode erneut gegen
dieses Dokument und die Sender-Vertragstests geprüft werden.
