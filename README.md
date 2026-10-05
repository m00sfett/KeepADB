# KeepADB

[![Lizenz: AGPL-3.0-or-later](https://img.shields.io/badge/License-AGPLv3%20or%20later-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-11%2B%20(API%2030%2B)-green.svg)](https://developer.android.com/about/versions/11)
![Keine Laufzeit-Abhängigkeiten](https://img.shields.io/badge/Runtime--Abhängigkeiten-0-orange.svg)

KeepADB schaltet Androids **Drahtloses Debugging** per App, Startbildschirm-Widget oder
Schnelleinstellungskachel. Der optionale Keep-Alive-Dienst kann es nach einer Netzwerkunterbrechung
oder einem Neustart wieder einschalten, soweit Android, die gewählte Netzwerkregel und der zuletzt
gespeicherte Benutzerwunsch das erlauben.

- Android 11 oder neuer (API 30+)
- Kein Root erforderlich
- Keine Abhängigkeiten im ausgelieferten App-Code
- App-ID: `de.hohnepeople.keepadb`

KeepADB steuert Androids globale Einstellung `Settings.Global.adb_wifi_enabled`. Den dynamischen
WLAN-ADB-Endpunkt ermittelt Androids mDNS-Dienstsuche; KeepADB startet keinen allgemeinen
Portbereich-Scan. Auch danach verlangt Android weiterhin die eigene TLS-Kopplung für neue ADB-
Clients.

## Installation und erster Start

Installiere KeepADB aus dem [offiziellen F-Droid-Katalog](https://f-droid.org/packages/de.hohnepeople.keepadb/)
oder lade das APK von den [GitHub Releases](https://github.com/m00sfett/KeepADB/releases).

Erteile anschließend einmalig `WRITE_SECURE_SETTINGS` von einem Computer aus, auf dem das Gerät per
ADB erreichbar ist:

~~~sh
adb shell pm grant de.hohnepeople.keepadb android.permission.WRITE_SECURE_SETTINGS
~~~

Diese Android-Berechtigung gilt für die installierte App und überlebt einen Neustart. Nach einer
Deinstallation und Neuinstallation muss sie erneut erteilt werden. KeepADB kann sie nicht selbst
gewähren und zeigt den Befehl in der App an, solange die Berechtigung fehlt.

## Bedienung

- **App:** Drahtloses Debugging ein- oder ausschalten und den aktuellen Zustand sehen.
- **Widget:** den Zustand direkt vom Startbildschirm aus umschalten.
- **Schnelleinstellung:** die Kachel „KeepADB“ in den aktiven Kacheln hinzufügen und von der
  Schnelleinstellungsleiste aus umschalten.
- **Keep-Alive:** in der App einschalten, wenn KeepADB Zustandsänderungen beobachten und eine
  Wiederherstellung versuchen soll. Android kann beim ersten Einschalten in einem WLAN eine eigene
  Bestätigung anzeigen. Wenn Android den Wechsel nicht bestätigt, wartet KeepADB, statt schnell
  hintereinander erneut einzuschalten.

In den Einstellungen findest du außerdem lokale USB-Hostprofile, die optionale USB-zu-WLAN-ADB-
Übergabe, Benachrichtigungsschutz, Diagnoseexport und die optionale Webhook-Synchronisierung.
Der Datenschutzmodus blendet Netzwerkadressen in der Oberfläche aus; er verändert nicht den
Endpunkt, den ein aktivierter Webhook erhält.

Auf einer Neuinstallation lässt die Netzwerkregel automatische Wiederherstellung zunächst nur an
zugelassenen Zugangspunkten zu. Bestehende Installationen behalten ihre bisherige Einstellung,
auch „In allen WLANs“; unter **Einstellungen → Netzwerk** lässt sich die Regel ändern. Diese Regel
steuert automatische Aktionen; ein manuelles Einschalten bleibt eine eigene
Benutzeraktion. Die vollständige Erklärung zu BSSID, optionaler SSID-Freigabe, Standortrechten,
Hintergrundstarts und Messgrenzen steht im [Netzwerk-Leitfaden](docs/trusted-networks.md).

## Webhook

Die optionale Webhook-Synchronisierung ist standardmäßig aus. Nach eigener Konfiguration sendet
KeepADB bestätigte WLAN-ADB-Endpunktdaten an die eingetragene URL. USB-Hostprofile bleiben lokal;
der Sender gibt derzeit nur WLAN-ADB-Ereignisse zur Übertragung frei. `http://` wird unterstützt,
aber unverschlüsselt übertragen. Verwende HTTP nur für einen eigenen vertrauenswürdigen LAN- oder
VPN-Endpunkt und beachte die Warnung in der App.

Die genaue Nachricht, Löschanforderung, lokale Opt-in-Regel und die Grenze zwischen App-Sender und
externem Empfänger beschreibt der [Webhook-Vertrag](docs/webhook-register.md).

## Datenschutz und Sicherheit

KeepADB sendet keine Analyse- oder Absturzberichte. Netzwerkadressen in Benachrichtigungen bleiben
standardmäßig verborgen. Der Diagnoseverlauf wird nicht automatisch übertragen; ein Export wird
erst geteilt, wenn du in Android selbst ein Ziel auswählst. Webhook-, Netzwerkvertrauens- und
Sperrbildschirmregeln sind in der [Sicherheitsrichtlinie](SECURITY.md) und im
[Dokumentationsindex](docs/README.md) erläutert.

Android verlangt weiterhin seine eigene TLS-Kopplung, bevor ein neuer ADB-Client Drahtloses
Debugging verwenden kann. KeepADB ersetzt oder umgeht diese Kopplung nicht.

## Mitwirken

Build-Voraussetzungen, lokale Verifikation, Test-Abhängigkeiten, CI und der gesonderte Release-
Werkzeugpfad stehen in [CONTRIBUTING.md](CONTRIBUTING.md). Sicherheitslücken bitte privat melden;
die Kontaktwege nennt [SECURITY.md](SECURITY.md).

## Lizenz

KeepADB ist unter der **GNU Affero General Public License v3.0 oder, nach deiner Wahl, jeder
späteren Version** lizenziert. Siehe [LICENSE](LICENSE). Die Lizenz gilt auch für die mitgelieferten
Anwendungsbilder und Symbole, sofern eine Datei nichts anderes angibt.
