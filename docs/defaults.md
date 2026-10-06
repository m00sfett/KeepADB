# Standardwerte einer Neuinstallation (Defaults-Audit, #765)

Dieses Dokument hält fest, womit eine **neu installierte** KeepADB-App startet, warum, und was
dabei für bestehende Installationen gilt. Es ist das Ergebnis des Audits aus #765 (Teil von #758)
und gilt ab 1.9.30.

## Grundsatz

- **Nur eine Neuinstallation bekommt die sicheren Standardwerte.** Eine bestehende Installation
  behält jeden gespeicherten Wert: nichts wird still verschärft, nichts still gelockert.
- **„Neu" heißt: nichts gespeichert.** „Zurücksetzen" in den Einstellungen löscht alle App-Daten
  (`clearApplicationUserData`) und führt deshalb auf genau diese Werte zurück.
- Ein Standardwert wird gelesen, nicht zurückgeschrieben. Die einzige Ausnahme ist die
  Netzwerk-Schutzstufe (#760), die beim ersten Lesen einmal festgeschrieben wird, damit eine
  spätere Änderung des Standards keine Installation verschiebt.

## Ergebnis des Audits

Alle Einstellungen mit Sicherheitswirkung stehen in der Neuinstallation bereits auf dem sichersten
Wert. Der Netzwerk-Standard war die einzige Abweichung („In allen WLANs", `all_wifi`); #760 hat ihn
auf „nur vertraute Access Points" gestellt. Der Befund im Issue #765, der Standard sei heute
`MODE_ALL_WIFI`, beschreibt den Stand vor #760 und trifft nicht mehr zu. Es ändert sich daher kein
Standardwert; #765 belegt sie und sichert sie gegen unbemerkte Änderung ab (siehe „Belege").

### Einstellungen mit Sicherheitswirkung

| Einstellung | Schlüssel | Standard | Wirkung | Bewertung |
|---|---|---|---|---|
| Keep-Alive | `keep_alive_enabled` | aus | Dienst schaltet Drahtloses Debugging nicht von selbst wieder ein; Boot- und Update-Empfänger starten nichts | sicher; entschieden (F2 aus #758): auch im Assistenten (#761) vorausgewählt „Aus" |
| Netzwerkregel | `trusted_network_mode` | `allowlist` | automatisches Einschalten nur an vertrauten Access Points (BSSID); nicht lesbare Identität pausiert | sicher (#760); nur der exakte Wert `all_wifi` gilt als bisherige Einstellung „In allen WLANs", jeder andere Wert als `allowlist` |
| Auch nach WLAN-Name vertrauen | `trust_by_name` | aus | ein gleichnamiger Access Point gilt nicht als vertraut | sicher (Komfortschalter, nur in „Ausgewogen") |
| Namensfreigabe (Altbestand) | `trusted_network_ssid_matching` | aus | die alte SSID-Liste wirkt nicht | sicher; nur über den bisherigen Schalter der Netzwerkkarte erreichbar |
| USB-Übergabe | `usb_wlan_handover_mode` | `off` | beim Anstecken wird nichts eingeschaltet und keine Aktion angeboten; ein unbekannter gespeicherter Wert gilt als `off` | sicher |
| Webhook | `register_webhook_enabled`, `register_webhook_url` | aus, keine URL | kein ausgehender Verkehr; es gibt keine eingebaute Ziel-URL (Issue #64) | sicher |
| Details in Benachrichtigungen | `notification_details_enabled` | aus | Netzwerkname, BSSID und USB-Profil stehen nicht in Benachrichtigungen, damit nicht auf dem Sperrbildschirm | sicher (#592) |
| WLAN- und Access-Point-Beobachtung | `wifi_aps_feature_enabled` | aus | keine Aufzeichnung; seit #769 ohne Oberfläche und ohne neue Aufzeichnung, ein früher gespeicherter Wert bleibt unverändert | sicher (#507, #769) |

Listen, die eine Entscheidung lesen, beginnen leer: keine vertrauten Access Points, keine
Namensfreigaben, keine Sperren, keine USB-Profile.

### Darstellung und Komfort

Diese Werte entscheiden nicht über automatisches Einschalten oder Datenabfluss. Sie bleiben
unverändert, jeweils mit Begründung.

| Einstellung | Schlüssel | Standard | Begründung |
|---|---|---|---|
| Privatsphäre-Modus | `privacy_mode_enabled` | aus | Entscheidung des Nutzers (#758): bleibt optional und aus, nicht Teil des Onboardings. Er blendet nur Anzeigen aus, schützt keine Aktion |
| Benachrichtigung ausblenden | `hide_notification_enabled` | aus | die sichtbare Dauerbenachrichtigung zeigt, dass der Dienst läuft; sie zu verbergen ist eine bewusste Wahl |
| Display wach halten | `keep_display_on_enabled` | aus | Komfort mit Akkukosten, kein Schutzgewinn durch „an" |
| USB-Benachrichtigung | `usb_notification_enabled` | aus | opt-in; solange sie aus ist, greift der folgende Schalter nicht |
| Profil in der USB-Benachrichtigung | `usb_profile_notification_enabled` | an | nur die Aufteilung der USB-Benachrichtigung (Profil-Zeile und -Aktionen); der Inhalt bleibt hinter `notification_details_enabled` verborgen („Profil verborgen"). Ein Wechsel auf „aus" würde bei Bestandsinstallationen, die den Schlüssel nie gespeichert haben, still ändern, was ihre USB-Benachrichtigung zeigt, ohne etwas zu schützen |
| App-Sprache | `app_language` | leer (Systemsprache) | keine Sicherheitswirkung |
| Hinweis-Karten der Startseite | `advice_banner_visible`, `notification_permission_panel_visible`, `battery_optimization_panel_visible`, `network_onboarding_panel_visible`, `background_location_panel_visible` | sichtbar | „Nicht mehr anzeigen"-Flags, rein Darstellung; die Karten entfallen mit #761/#764 |

### Kein Einstellungswert

| Schlüssel | Standard | Begründung |
|---|---|---|
| `last_desired_on` | an | gespeicherte letzte ausdrückliche Ein-/Aus-Absicht, kein Schalter. Auf einer Neuinstallation löst er von selbst nichts aus: Dienst und Boot-Empfänger verlangen zuerst `keep_alive_enabled`, die USB-Übergabe zuerst ihren Modus (Standard `off`), und Keep-Alive einzuschalten schreibt die Absicht „an". Ein Standard „aus" würde dagegen Bestandsinstallationen treffen, die Keep-Alive vor diesem Schlüssel eingeschaltet haben, und ihre Wiederherstellung still abstellen |

Der Force-Modus (#763) ist auf einer Neuinstallation aus. `force_state` ist ohne eine
ausdrückliche Bestätigung im Force-Dialog nicht gespeichert; es enthält Laufzeit und Ablauf
einer solchen Entscheidung. `force_expired_notice_pending` ist ohne einen Ablauf `false`
und hält ausschließlich die noch zu meldende Ablaufbenachrichtigung fest. Beide Schlüssel
sind Laufzeitdaten, keine abweichenden Voreinstellungen.

### Berechtigungen und Systemzustand

Android-Berechtigungen sind keine App-Einstellungen und starten bei einer Neuinstallation alle
nicht erteilt: präziser Standort, „Immer zulassen", Benachrichtigungen, Ausnahme von der
Akku-Optimierung. `WRITE_SECURE_SETTINGS` kommt nur durch das einmalige `adb shell pm grant`.
`allowBackup` ist aus und `data_extraction_rules.xml` schließt alle Domänen aus, es wandert also
auch kein Standardwert und kein Bestand per Backup oder Gerätewechsel auf ein anderes Gerät.

## Neuinstallation und Bestand

| Zustand | Verhalten |
|---|---|
| nichts gespeichert (Neuinstallation, Zurücksetzen) | Standardwerte oben |
| Wert gespeichert, lockerer als der Standard (z. B. Keep-Alive an, `all_wifi`, Webhook an, Details an) | bleibt, nichts wird verschärft |
| Wert gespeichert, strenger als ein Komfort-Standard (z. B. Karte ausgeblendet, Profil-Benachrichtigung aus) | bleibt, nichts wird gelockert |
| Wert ausdrücklich gleich dem Standard gespeichert | bleibt gespeichert |
| beschädigter Wert (`usb_wlan_handover_mode`, `trusted_network_mode`) | liest als sicherer Wert (`off`, `allowlist`) |

Einen Standard später zu ändern ist damit nie ein bloßer Austausch der Rückfallkonstante im
Getter: das würde jede Installation, die den Schlüssel nie gespeichert hat, mitverschieben. Es
braucht einen einmal festgeschriebenen Standard nach dem Muster von #760
(`KeepADBTrustedNetwork.ensureModeInitialized`) und eine Entscheidung im Changelog.

## Belege

`KeepADBDefaultsAuditTest` ist die ausführbare Fassung dieser Tabelle:

- jede Einstellung liest ohne gespeicherten Wert ihren Standard; das Lesen schreibt nichts außer
  dem einmaligen Netzwerkregel-Flag;
- die Tabelle ist vollständig: ein neuer Preference-Schlüssel in den Quellen, der weder hier noch in
  der Liste der Datenschlüssel des Tests steht, lässt den Build fehlschlagen, ebenso ein Schlüssel
  der Tabelle, der aus den Quellen verschwunden ist; jeder Einstellungsschlüssel muss in diesem
  Dokument stehen;
- die handelnden Pfade verhalten sich auf den Standards sicher (Netzwerkentscheidung, Boot- und
  Update-Empfänger, USB-Übergabe), jeweils mit einer Gegenprobe mit gespeichertem Opt-in;
- gespeicherte Werte bleiben in beide Richtungen (lockerer und strenger als der Standard), einzeln
  und zusammen, und ein ausdrücklich gespeicherter Standardwert bleibt. Geschrieben wird dort mit
  den Schlüsseltexten, nicht über die Setter, damit eine Umbenennung eines Schlüssels (die jede
  Installation auf den Standard zurücksetzen würde) auffällt.

Die Netzwerkregel selbst ist zusätzlich in `KeepADBTrustMigrationTest` (Entscheidung wie 1.9.28 für
jeden gespeicherten Stand) und `KeepADBTrustPrecedenceTest` belegt, siehe
[Vertrauenswürdige WLANs](trusted-networks.md).
