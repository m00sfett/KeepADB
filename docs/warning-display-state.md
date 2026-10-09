# Anzeigezustand der Warnungen (#826)

Die neun stabilen Grund-IDs in `KeepADBWarningState.Reason` bilden die vorhandenen
Warnbedingungen ab. `KeepADBHomeWarnings.evaluate()` und `reasons()` bleiben reine
Auswertungen. Die zweite Methode erfasst Betriebsursachen auch während ihrer Unterdrückung
durch Systemberechtigung oder Pausiert-Karte. Unterdrückung beendet daher keine Episode.

`warnings_v1_state` speichert Schema 1, kanonische beobachtete und bestätigte Signaturen je
Karte sowie die ausdrücklich stummgeschalteten Gründe. Alle Anzeigeänderungen laufen über
denselben Speichermonitor. Eine X-Bestätigung deckt nur den angezeigten Snapshot ab,
geschnitten mit den weiterhin aktiven Ursachen. Beim Wegfall wird die Bestätigung dieses
Grundes entfernt, ohne die übrigen Gründe erneut anzuzeigen. Eine spätere Rückkehr ist
unbestätigt. Stummschaltungen gelten unabhängig davon und bleiben während der Inaktivität
erhalten. Zurücksetzen entfernt nur Mutes und die Bestätigungen dieser Gründe. System und
Force werden an beiden Mute-Grenzen zurückgewiesen.

Je Grund hält ein additives `reason_episode_<id>`-Token die beobachtete Anzeigeepisode fest.
Wegfall und Rückkehr erneuern diesen Token. X, Rückgängig und die Mute-Rückmeldung gleichen
den gezeigten Token mit dem aktuellen ab; gleiche Grund-IDs reichen dafür nicht aus. Die
Rückmeldung erhält ihre Tokens bei Rotation. Ein Update ergänzt fehlende Tokens, ohne gültige
gespeicherte Bestätigungen oder Mutes zu löschen.

Force-Aktivierung speichert einen neuen `warnings_force_episode`-Token atomar mit
`force_state`. `warnings_force_fingerprint` enthält dessen SHA-256-Fingerabdruck. Eigenes
Rebinding behält die Episode; eine unbekannte Änderung durch eine ältere App erzeugt einmal
konservativ eine neue Episode. Eine aktive Sitzung ohne Warn-Keys wird ebenfalls einmal
angezeigt. Zeitbasen dienen niemals als Episoden-ID. Der Monitor serialisiert Force-Schreiben
mit Anzeige-Snapshots. Die eigentlichen Force-Übergänge aktualisieren Oberflächen weiterhin
erst nach Freigabe des Monitors; Fristprüfung und Schutzregeln bleiben erhalten.

Unbekannte Schemas, beschädigte Speicherwerte und unbekannte Grund-IDs bleiben erhalten und
werden als unbestätigter, nicht stummer Anzeigezustand behandelt. Sie können kritische
Warnungen nicht verbergen. Fehlgeschlagene Commits melden kein erfolgreiches Schließen.
Die neuen Keys liegen in den vorhandenen, vom Backup ausgeschlossenen Preferences. Ältere
Apps ignorieren sie. Tatsächliche Schutz- und Force-Parameter werden nicht gelöscht.

Eigene Preference-Setter, Service-Sync, Heartbeat und Netzwerkpfade, Force-Übergänge,
Activity-Resume und Berechtigungsresultate beobachten Ursachen auch bei geschlossener
Hauptseite. Ein vollständig unbeobachteter externer Aus-/Ein-Wechsel bei gestoppter App ist
bei identischem Snapshot nicht rekonstruierbar; zusätzliche Polling-Infrastruktur entsteht
nicht.

Die Prüfung liegt beim Integrator: `./bin/verify`, Sensitivitäts-Gegenproben für
Signaturschnitt, kritische Mute-IDs und Force-Token sowie die freigegebene Tablet-/Fire-7-Matrix.
Dazu gehören Hoch/Quer, 100 %/200 % Schrift, lange Übersetzungen, Nachtmodus und echte
Tastatur-/TalkBack-Reihenfolge, einschließlich mehrfach eingebundener Karten-IDs. Die neuen
Unit-Tests nutzen echte Setter, Preferences und Force-Treiber. Activity-Tests führen X,
Rückgängig, Dreieck, Prüfen, Mute-Rücknahme, Neuanlage und Settings-Fokus aus.
