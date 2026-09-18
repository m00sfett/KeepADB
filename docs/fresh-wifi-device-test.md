# Testweg „frisches WLAN" über den P60-Hotspot (#496)

Für Fixes am WLAN-ADB-Verhalten braucht der Pflicht-Gerätetest auf dem S20 (`s20`) ein
Netzwerk, für das die Android-Systemfreigabe „Immer in diesem Netzwerk zulassen" (WLAN-ADB-
Pairing-Dialog) garantiert noch nie erteilt wurde — ein bereits freigegebenes Heimnetz kann
den zu prüfenden Erststart-Pfad nicht reproduzieren. Dieses Dokument hält den dafür etablierten
Testweg fest, mit den beiden Problemen, die er tatsächlich verursacht hat, damit sie nicht bei
jedem Lauf neu entdeckt werden müssen.

## Aufbau

- Zielgerät: Samsung SM-G780G (Galaxy S20 FE), Alias `s20`, Android 13 / SDK 33, kein Root.
- Hotspot-Quelle: CUBOT P60, Alias `rolfphone`, ebenfalls mit installierter KeepADB-Instanz.
- Ablauf je Testlauf:
  1. KeepADB (Debug-Variante) auf dem S20 deinstallieren — entfernt `WRITE_SECURE_SETTINGS`
     automatisch mit.
  2. Auf `rolfphone` einen WLAN-Hotspot mit **neuer, noch nie verwendeter SSID** aktivieren
     (z. B. `KeepADB-Verify-<Datum>`, bei Wiederholung mit fortlaufendem Suffix), damit
     garantiert kein alter Freigabe-Zustand vom vorherigen Lauf greift.
  3. S20 mit diesem Hotspot verbinden, KeepADB frisch installieren, `WRITE_SECURE_SETTINGS`
     neu per `pm grant` vergeben.
  4. Test durchführen (Keep-Alive aktivieren, Systemdialog beobachten, Logcat auf
     `KeepADBDiag` auswerten).
  5. Cleanup: S20 zurück ins normale WLAN, temporäres Profil entfernen; Hotspot auf `rolfphone`
     wieder deaktivieren, ursprünglicher Hotspot-Name wiederherstellen, Gerät wieder sperren.

Durchgeführt am 2026-09-18 in zwei vollständigen Zyklen für #496 — der erste gegen den
ursprünglichen (fehlerhaften) Fix, der zweite gegen den reparierten Stand.

## Beobachtete Problematiken

### 1. Rolfphones eigene KeepADB-Instanz reagiert auf den eigenen Netzwerkwechsel

Nach dem Aktivieren des Test-Hotspots auf `rolfphone` war das Gerät im zweiten Testzyklus
zeitweise über ADB/SSH nicht mehr erreichbar (weder WLAN-ADB noch USB), obwohl es laut
Tailscale-Ping online war. Plausibelste Ursache: Die auf `rolfphone` selbst installierte
KeepADB-Instanz hat den eigenen Netzwerkwechsel (Wechsel in den Hotspot-Modus zählt aus
Android-Sicht als Konnektivitätsänderung) als Trigger genommen und ihr eigenes Wireless
Debugging deaktiviert — vermutlich als Teil derselben Trusted-Network-/Recovery-Logik, die
auch auf dem S20 getestet wird. Nicht abschließend im Code verifiziert, da es sich um einen
Seiteneffekt am Testinstrument handelt, nicht um einen Befund am Testobjekt.

**Konsequenz für künftige Läufe:** Vor dem Aktivieren des Test-Hotspots einen alternativen
Erreichbarkeitspfad zu `rolfphone` sicherstellen (USB bereithalten oder den Hotspot-Wechsel
mit ausreichend Puffer einplanen, bevor `rolfphone` selbst gebraucht wird), und nach dem Test
aktiv prüfen, ob `rolfphone` sein eigenes Wireless Debugging wieder aktiviert hat — das
geschieht nicht automatisch und braucht ggf. manuelle Nachbereitung direkt am Gerät.

### 2. Cleanup-Reihenfolge: Hotspot-Name und Sperrzustand bleiben leicht hängen

Der testweise geänderte Hotspot-Name (z. B. `KeepADB-Verify2-20260918`) wird nicht automatisch
zurückgesetzt, wenn `rolfphone` zwischenzeitlich unerreichbar wird (siehe Problem 1) — das
Cleanup in Schritt 5 setzt Erreichbarkeit voraus und wurde in genau diesem Fall erst in einer
späteren, separaten Sitzung nachgeholt. Der Name driftet damit sichtbar vom Originalzustand
(„P60") weg, ohne dass das sofort auffällt, wenn nicht ausdrücklich am Gerät nachgesehen wird.

**Konsequenz für künftige Läufe:** Cleanup-Schritt 5 nicht als optionalen letzten Punkt
behandeln, sondern als eigenständig zu verifizierenden Schritt mit explizitem Soll-Ist-
Abgleich (`Name des Hotspots` in den `rolfphone`-Einstellungen lesen, nicht nur den Toggle
prüfen), unabhängig davon, wie reibungslos der eigentliche S20-Test verlief.

## Belastbare Schlussfolgerung

Der Testweg selbst liefert einen realistischen, reproduzierbaren Nachweis für das im Code
geprüfte Verhalten (siehe Nachweis-Dateien `~/agent/projects/keepadb/.workspace/
eco-496-device-evidence*.md`, lokal, ungetrackt). Sein Preis ist, dass das Hilfsgerät
`rolfphone` selbst zum Testsubjekt der eigenen KeepADB-Netzwerklogik wird — der Testweg ist
nicht „neutral", sondern nutzt ein Gerät mit identischer Software als Werkzeug. Das ist für die
Aussagekraft des S20-Ergebnisses unproblematisch, verlangt aber eigene Sorgfalt für die
Erreichbarkeit und den Rückbau von `rolfphone` selbst.
