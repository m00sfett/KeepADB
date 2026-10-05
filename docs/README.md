# KeepADB-Dokumentation

## Aktuelle Produkt- und Einrichtungsinformationen

- [README](../README.md): Installation, einmalige Berechtigung, Bedienung und kurze Privatsphäre-
  Hinweise.
- [Vertrauenswürdige WLANs](trusted-networks.md): heutige Regeln für BSSID/SSID, Berechtigungen,
  Hintergrundstarts, Boot und sichere Sperrentscheidungen.
- [Webhook-Vertrag](webhook-register.md): was der Android-Sender tatsächlich überträgt und welche
  Empfängeraussagen dieses Repository nicht belegt.
- [Diagnoseexport](diagnostics.md): lokaler Ereignispuffer, Debug-Journal, Maskierung und manuelles
  Teilen.
- [Paketname](package-id.md): warum `de.hohnepeople.keepadb` trotz Website-Umzug bleibt.
- [Sicherheitsrichtlinie](../SECURITY.md): Bedrohungsmodell und private Meldung einer Schwachstelle.

## Code- und Quelldateiübersicht

- [Berechtigungen und Komponenten](../app/src/main/AndroidManifest.xml): Standortzugriff,
  Foreground-Service-Typen, Boot-Empfänger und deaktivierte Backups.
- [Netzwerkidentität](../app/src/main/java/de/hohnepeople/keepadb/KeepADBNetworkIdentity.java)
  und [Freigaberegeln](../app/src/main/java/de/hohnepeople/keepadb/KeepADBTrustedNetwork.java):
  BSSID-/SSID-Lesen, Vergleich und fail-closed Entscheidung.
- [Keep-Alive-Dienst](../app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java) und
  [Boot-Empfänger](../app/src/main/java/de/hohnepeople/keepadb/BootReceiver.java): Laufzeittyp,
  Hintergrund-Fallback und erlaubte Boot-Starts.
- [Webhook-Payload](../app/src/main/java/de/hohnepeople/keepadb/KeepADBRegisterPayload.java)
  und [HTTP-Sender](../app/src/main/java/de/hohnepeople/keepadb/KeepADBRegisterClient.java):
  Nutzlast und ausgehender App-Request.
- [Diagnoseexport](../app/src/main/java/de/hohnepeople/keepadb/KeepADBDiagnostics.java):
  lokaler Puffer, Journal und Exportmaskierung.
- [Settings-Oberfläche](../app/src/main/java/de/hohnepeople/keepadb/SettingsActivity.java)
  und [gespeicherte Einstellungen](../app/src/main/java/de/hohnepeople/keepadb/KeepADBPreferences.java):
  Nutzeraktionen und app-lokale Optionen. `SettingsActivity` bleibt der Screen- und
  Android-Lifecycle-Eigentümer und delegiert klar abgegrenzte Bereiche an Controller:
- [KeepADBNetworkCard](../app/src/main/java/de/hohnepeople/keepadb/KeepADBNetworkCard.java):
  besitzt die Netzwerk-Kartenansicht, ihre Aktionen und Dialoge sowie die WLAN-Beobachtung.
  `SettingsActivity` delegiert Wiederherstellung, Start/Stop, Aktualisierung, Zustandsicherung,
  Zerstörung und die Ergebnisbehandlung der Berechtigungsanfragen.
- [KeepADBDiagnosticsController](../app/src/main/java/de/hohnepeople/keepadb/KeepADBDiagnosticsController.java):
  besitzt den Diagnoseexport und den Fehlerbericht-Dialog samt Entwurf. Die Activity delegiert
  Wiederherstellung, Zustandsicherung und Zerstörung; der App-zurücksetzen-Dialog bleibt in der
  Activity. Der Controller öffnet die Feedbackseite über deren gemeinsame `openWebLink`-Methode.
- [Build-Konfiguration](../app/build.gradle), [lokaler Gate](../bin/verify),
  [CI](../.github/workflows/ci.yml) und [Release-Workflow](../.github/workflows/release.yml):
  Versionen, Werkzeuge und Prüfpfade.

## Entwicklung und Tests

- [Mitwirken](../CONTRIBUTING.md): JDK/SDK, vollständige lokale Verifikation, Test-Abhängigkeiten,
  CI und getrennter Signierpfad.

## Messberichte und historische Unterlagen

- [Messzusammenfassung für vertrauenswürdige WLANs](trusted-networks-measurement.md): konkrete
  Ergebnisse und offene Messgrenzen.
- [Archiv der Messreihe](archive/trusted-networks-measurements-2026-09.md): vollständige frühere
  Notizen und Nachträge. Historische Befunde sind keine zusätzlichen Produktregeln.
- [Frisches WLAN am P60-Hotspot](fresh-wifi-device-test.md): einzelner historischer Gerätetest,
  keine allgemeine Zusage für andere Hersteller.
- [Entwurfsnotiz zu mehreren Webhook-Transporten](design/issue-539-multi-transport-register-contract.md):
  historischer Vertragsentwurf; für den gegenwärtigen App-Sender gilt ausschließlich der oben
  verlinkte Webhook-Vertrag.
- [Sprach- und Hardcode-Prüfung vom 2026-09-20](release/issue-523-language-hardcode-gate-2026-09-20.md):
  datierter Prüfbericht, keine aktuelle Release-Anweisung.
