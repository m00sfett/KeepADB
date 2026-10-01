# Messzusammenfassung: vertrauenswürdige WLANs

Stand der Zusammenfassung: 2026-10-01. Die Messungen darunter sind historische Ergebnisse und
gelten nur für die genannten Builds und Geräte. Sie sind keine Garantie für alle Android-
Hersteller.

## Was gemessen wurde

- Auf AOSP-Emulatoren mit Android API 30–36.1 blieb bei einem Hintergrundstart ohne
  ACCESS_BACKGROUND_LOCATION der Foreground-Service aktiv. Android maskierte die WLAN-Identität;
  im Freigabelistenmodus blockierte KeepADB daher die automatische Wiederherstellung. Ab API 34
  wurde der Standorttyp des Dienstes beim Hintergrundstart abgelehnt und der Dienst fiel auf den
  Typ für verbundene Geräte zurück.
- Mit ACCESS_BACKGROUND_LOCATION war die WLAN-Identität bei den geprüften AOSP-Emulator-
  Hintergrundstarts lesbar. API 34 und neuer akzeptierten in diesen Läufen den Standorttyp.
- Ein echter Neustart des Samsung Galaxy S20 FE mit Android 13 und zusätzlicher
  ACCESS_BACKGROUND_LOCATION ließ KeepADB nach dem Boot-Start die WLAN-Identität lesen und den
  WLAN-ADB-Endpunkt wiederherstellen. Der System-Boot-Empfang traf in diesem Lauf etwa zweieinhalb
  Minuten nach dem Neustart ein.
- Ein Vordergrundstart mit präzisem Standortzugriff lieferte auf dem getesteten S20 FE die
  WLAN-Identität bei späterer Hintergrundarbeit und Display-Aus. Das deckt den gemessenen
  Vordergrund-Ursprung des Dienstes ab.

Die Protokolle dokumentieren weitere Vergleichsläufe und Korrekturen. Sie sind im
[Messarchiv](archive/trusted-networks-measurements-2026-09.md) erhalten.

## Offene Grenzen

- Es gibt keine breite Matrix über Hersteller, Android-Anpassungen und WLAN-Treiber.
- Der S20-FE-Lauf ohne ACCESS_BACKGROUND_LOCATION war durch eine installierte
  Schnelleinstellungskachel beeinflusst und belegt daher keinen unabhängigen Gegenfall.
- Das Hinzufügen einer BSSID über die normale Einstellungsoberfläche wurde nicht für jede
  Messvariante Ende-zu-Ende geprüft; einzelne Messwerte stammten aus einem temporären Prüfpfad.
- Ein akzeptierter Schreibversuch für Drahtloses Debugging ist nicht in jedem Emulator ein Beleg,
  dass Android die Funktion tatsächlich eingeschaltet hat. Android kann weiterhin eine eigene
  Bestätigung verlangen.
- Die Boot-Zeit und die Reaktion nach der ersten Geräteentsperrung können je nach Android-Version
  und Hersteller abweichen.

Die aktuelle Berechtigungs- und Fail-Closed-Regel steht unter
[Vertrauenswürdige WLANs](trusted-networks.md). Dieses Dokument beschreibt Messbelege und
Messlücken, nicht eine Zusage für ungeprüfte Geräte.
