# Issue #523 – finaler Sprach- und Hardcode-Gate

Stand: 2026-09-20
Arbeitsstand: Branch codex/issue-523-release-gate im isolierten Worktree
Audit-Basis: 769ef1c54a50ee4e2cefb4ac6c764e1b8b63fb5b (origin/master zum Start)
Entscheidung: **PASS**

Der vollständige Issue-Text #523, die Projektanweisungen, die Orchestrierungsregeln und
release-common.md wurden vor der Änderung gelesen. Dieser Bericht ist die reproduzierbare
Abnahme für den Scope von #523. Den konkreten finalen Commit liefert der Git-Handoff; er ist mit
git rev-parse HEAD aus diesem Worktree reproduzierbar.

## 1. Scope und Grenzen

Geprüft wurden:

- die zehn relevanten Release-Wave-Diffs bis zum Kandidaten,
- sichtbare Literale in Layouts und den betroffenen Java-Oberflächen,
- die englische Referenz, Deutsch und alle 18 weiteren unterstützten Sprachvarianten,
- Ressourcen-Keys, Verwendungen, Formatargumente, Zeilenumbrüche, Markup und Plural-/Array-Bestände,
- Version, technischer Changelog und Fastlane-Changelog für die tatsächlich reparierte sichtbare Änderung.

Nicht ausgeführt wurden wie beauftragt: vollständige manuelle UI-Review, Gerätetest, manuell
ausgelöste CI, F-Droid-Vorbereitung/-Einreichung, Release-Publikation und Arbeiten an #514.

## 2. Review der zehn Release-Cycle-Diffs

Die Issue-Serie #515–#521 liegt in den Integrations-PRs #524 und #525. Die Folgeänderungen
#528/#529 liegen in #531/#532 und deren Integrations-PR #533; #530 liegt in #534. Zusätzlich
wurden die unmittelbar vorausgehenden UI-Flächen #508, #511, #513 und #527 geprüft, weil sie
die im Kandidaten sichtbaren Settings-/Debug-Oberflächen einführen oder verändern.

Die Statistiken sind der First-Parent-Merge-Diff, reproduzierbar mit
git diff --shortstat <merge>^1 <merge>:

| PR | Merge-Commit | Inhalt / abgedeckte Issues | Diffumfang | Audit-Ergebnis |
| --- | --- | --- | --- | --- |
| #508 | c94700c3b8984575872f1cd84069d722cfe56c32 | WLAN & Access Points, Opt-in und Beta-Kennzeichnung (#507) | 33 Dateien, +1058/-1216 | Ressourcen vollständig; ein sichtbares BETA-Literal wurde als Befund repariert |
| #511 | 93bf00ef2f990b8c089b7ad9f95e4b4c109a2010 | Privacy-Default und Settings-Umgruppierung (#509/#510) | 28 Dateien, +551/-242 | Keys, Texte und Gruppierungsbezeichnungen unauffällig |
| #513 | 4b3862d434637a914f28bcaae99ae76909e2dd82 | Debug-Build-Warnhinweis (#512) | 25 Dateien, +77/-2 | DEBUG BUILD ist absichtlich englisch und translatable=false |
| #524 | 61be2a6da7e556b2036d8a1fdbaae9d481843664 | Ersteinrichtung, Benachrichtigung, Sicherheitshinweis (#515–#517) | 30 Dateien, +442/-156 | Setup-Commands, Placeholders und neue Panel-Texte geprüft |
| #525 | 9c0f155feb8037475cdcc878ff3149e169f27754 | Sprache, Netzwerk-Beta, USB-ADB, Sonstiges (#518–#521) | 33 Dateien, +479/-474 | Zweites BETA-Literal und Ressourcenbestand geprüft; keine fehlenden Keys |
| #527 | 5223d241718ab8ebec490237d223c8e4af3f189e | Debug-Installationen sichtbar unterscheiden (#526) | 4 Dateien, +14/-2 | Launcher-/Tile-Label als absichtliche Debug-Marke geprüft |
| #531 | bdc08b1e7b4fb106ab71f9c9537b0a84d3dfa341 | Benachrichtigungsberechtigungs-Panel dismissbar (#528) | 9 Dateien, +127/-12 | Neue Benachrichtigungstexte vollständig in Ressourcen |
| #532 | 8097f72d25c2d5d0a7bbaf91247cc436104e0be5 | USB-ADB-Unterbereiche direkt anzeigen (#529) | 6 Dateien, +177/-125 | Keine neuen natürlichen UI-Literale; USB-ADB bleibt technischer Begriff |
| #533 | 11a3ae37051cfde8924f09851b6ae6c18f3118a3 | Integration der #528/#529-Folgeänderungen | 12 Dateien, +304/-137 | Integrationsdiff ohne zusätzlichen Ressourcenverlust |
| #534 | 769ef1c54a50ee4e2cefb4ac6c764e1b8b63fb5b | Debug-Badge verkürzen (#530) | 25 Dateien, +50/-33 | ⚠ DEBUG BUILD bleibt bewusst nicht lokalisiert |

## 3. Hardcode-Scan

Der relevante XML-Scan war:

    rg -n 'android:(text|hint|contentDescription|summary|title|label|textOn|textOff)="[^@"]' app/src/main/res --glob '*.xml'

Vor der Reparatur wurden sieben Settings-Karten-Expander mit `+` und ein `▼`-Selektor als reine
Interaktionsglyphen sowie zwei natürliche sichtbare BETA-Literale gefunden. Die beiden
BETA-Vorkommen in
app/src/main/res/layout/activity_settings.xml (Trusted Network und Wi-Fi/AP) wurden durch
@string/settings_beta_badge ersetzt. Die Ressource ist in allen 19 Ressourcenbündeln vorhanden
und explizit translatable="false", weil das Produkt-Badge in diesem Release-Zyklus bewusst
exakt BETA lautet.

Nach der Reparatur meldet der XML-Scan nur noch:

- sieben `+`-Expander an den Settings-Karten,
- ein `▼`-Glyph am USB-Handover-Selektor.

Die korrespondierenden Java-Literale +/− in
SettingsActivity.CARD_COLLAPSED_SYMBOL/CARD_EXPANDED_SYMBOL sind Interaktionsglyphen. Der
bestehende Kommentar in SettingsActivity.java dokumentiert diese Ausnahme. Die ·-Zeichen
in den SSID-/USB-Profil-Zusammenfassungen sind typografische Datenfeldtrenner, keine
sprachabhängigen Sätze. Sie wurden deshalb nicht künstlich als Übersetzungen modelliert.

Eine ergänzende Suche nach sichtbaren Textpfaden (getString(R.string...), setText, Toasts
und Notifications) ergab keine weiteren neu eingeführten natürlichen UI-Literale. Logs,
URLs, Permission-Konstanten, Shell-Befehle und Datenfeldformatierung wurden als nicht
übersetzbare technische Inhalte behandelt.

## 4. Ressourcen- und Übersetzungsprüfung

Der Bestand umfasst 19 strings.xml-Dateien: Default plus
ar,de,es,fr,hi,id,it,ja,ko,nl,pl,pt,ru,tr,uk,vi,zh-rCN,zh-rTW.

Die automatisierte Vergleichsprüfung nach der Reparatur ergab für jedes der 18 Zielbündel:

    keys=277 missing=0 extra=0 duplicates=0 blank=0 placeholders=0 escaped_linebreaks=0 PASS

Gesamt:

    files=19 default_keys=277 physical_newline_permission_values=0 plural_or_array_files=0 overall=PASS

Zusätzlich wurde der Ressourcen-Verwendungsgraph geprüft:

    declared=277 referenced=264 missing=[] reflection_exclusion=R.string.class

R.string.class ist die absichtliche Reflection-Stelle im Vertragstest und kein Ressourcen-
Verweis. Für alle echten @string-/R.string-Verwendungen gibt es einen deklarierten Key.

### Englisch und Deutsch

Die englische Referenz und Deutsch wurden mit höchster Priorität für alle 22 überlebenden,
seit dem Release-Wave-Baseline-Commit geänderten Keys gelesen und semantisch gegengeprüft:

    notification_permission_panel_body
    notification_permission_panel_title
    notification_permission_request_button
    notification_permission_settings_button
    security_notice_dismiss
    settings_network_beta_group_subtext
    settings_section_misc
    settings_section_network_beta
    settings_section_usb_adb
    settings_section_wifi_aps
    settings_version_debug_badge
    settings_wifi_aps_beta_description
    settings_wifi_aps_feature_toggle
    setup_body
    setup_command_multi
    setup_multi_device_hint
    setup_multi_device_label
    setup_single_device_label
    setup_state_offline_body
    setup_state_offline_title
    setup_state_unauthorized_body
    setup_state_unauthorized_title

Die Übersetzungen für Benachrichtigungsberechtigung, Sicherheitshinweis, Netzwerk-Beta,
USB-ADB, Setup-Zustände (offline/unauthorized/device) und die Zielauswahl sind inhaltlich
vollständig. %1$s, Shell-Befehle und technische Statuswörter bleiben an den richtigen Stellen
erhalten. Der neue gemeinsame Badge-Key ist in Englisch und Deutsch absichtlich identisch.

Die zwei seit der Baseline entfernten, nicht mehr verwendeten Sprach-Selector-Keys
settings_language_subtext und settings_section_language wurden als Entfernung geprüft
und nicht als fehlende Übersetzung gewertet.

### Weitere Sprachen

./bin/check-i18n meldet:

    check-i18n: keine unübersetzten (kopierten) Strings gefunden.

Die bestehende Prüfung erkennt nur wortidentische Kopien der englischen Werte; deshalb wurde sie
durch die kompilierten Ressourcen-Vertragstests, den Placeholder-/Zeilenumbruchvergleich und
gezielte technische Sichtprüfung der Setup-/ADB-Texte ergänzt. Die Ressourcen-Vertragstests
prüfen alle 18 Sprach-Tags auf Key-Präsenz, Runtime-/Compiled-Table-Provenienz, Formatargumente
und echte Argument-Witnesses.

Der erste fokussierte Testlauf deckte dabei einen echten technischen Befund auf: In 17
Übersetzungsdateien war der Umbruch im XML physisch geschrieben. AAPT2 kompiliert diesen
physisch eingerückten Zeilenumbruch als Leerraum, während die Referenz ein escaped \n
enthält. Alle 17 Werte wurden auf explizites \n umgestellt; Deutsch und Default waren
bereits korrekt. Der abschließende Vertragstest bestätigt jetzt einen kompilierten Umbruch in
allen 18 Zielsprachen und der Default-Ressource.

Es gibt im aktuellen Ressourcenbestand keine <plurals>- oder <string-array>-Dateien;
die Pluralprüfung ist daher vollständig und vacuous clean.

## 5. Konkrete Befunde und Reparaturen

| Befund | Reparatur | Verifikation |
| --- | --- | --- |
| Zwei sichtbare BETA-Literale in activity_settings.xml | Gemeinsamer Key settings_beta_badge, in allen 19 Sprachbündeln mit translatable="false"; beide Layouts referenzieren ihn | Bestehende Settings-Tests, Key-Contract und Lint |
| Türkisch: PCden ... çalıştırın:den bir kez çalıştırın: | Einmalige, korrekt gesetzte Formulierung PC’den bir kez çalıştırın:\nadb shell ... | Exakter Turkish-Contract-Test auf kompiliertem Wert |
| 17 übersetzte permission_error_toast-Werte hatten physische XML-Zeilenumbrüche | Alle auf explizites Android-\n umgestellt | Contract-Test permissionGuidancePreservesCompiledLineBreaksInEveryLocale |

Es wurden keine fachfremden oder nicht notwendigen Änderungen vorgenommen.

## 6. Version, Changelog und Fastlane

Die sichtbaren Korrekturen sind eine Produkt-/UI-Änderung im Patch-Scope:

- versionName: 1.8.27 → 1.8.28
- versionCode: 124 → 125
- CHANGELOG.md: neuer Unreleased-Fixed-/Testing-Abschnitt für #523
- fastlane/metadata/android/en-US/changelogs/125.txt: user-facing Kurznotiz

Es wurde kein Release ausgelöst, kein Tag erstellt, nichts gepusht und keine F-Droid- oder
CI-Aktion gestartet.

## 7. Reproduzierbare Checks

Aus dem Code-Root dieses Worktrees:

    ./bin/check-i18n
    rg -n 'android:(text|hint|contentDescription|summary|title|label|textOn|textOff)="[^@"]' app/src/main/res --glob '*.xml'
    ANDROID_HOME=/home/tobias/Android/Sdk ANDROID_SDK_ROOT=/home/tobias/Android/Sdk ./bin/gradlew testDebugUnitTest --tests de.hohnepeople.keepadb.KeepADBResourceContractTest --no-daemon --max-workers=2
    ANDROID_HOME=/home/tobias/Android/Sdk ANDROID_SDK_ROOT=/home/tobias/Android/Sdk ./bin/verify

Ergebnisse am finalen Arbeitsstand:

- git diff --check: PASS
- ./bin/check-i18n: PASS
- fokussierter KeepADBResourceContractTest: 24 Tests, 0 Fehler, PASS
- ./bin/verify: PASS; Git-Diff-Check, i18n, vollständige Unit-Tests, lintDebug,
  assembleDebug und assembleRelease erfolgreich
- Ressourcen-/Verwendungs-/Hardcode-Audit: PASS

## 8. Restbefunde, Ausnahmen und Entscheidung

Begründete Restfunde sind ausschließlich technische oder visuelle Ausnahmen:

- +, − und ▼ sind Bedienglyphen ohne natürliche Sprache.
- USB-ADB, ADB, WRITE_SECURE_SETTINGS, adb devices, offline, unauthorized und device sind
  Protokoll-, Permission-, Shell- oder technische Statusbegriffe.
- KeepADB, Keep-Alive, Webhook und das ⚠ DEBUG BUILD-Badge sind Marken-/Debug-Konventionen;
  das Badge bleibt absichtlich englisch und nicht lokalisierbar.
- check-i18n beweist keine idiomatische Vollkorrektheit jeder Sprache. Für diesen Gate-Scope
  wurde das durch die vollständige Key-/Format-/Compiled-Table-Prüfung und gezielte technische
  Prüfung ergänzt; eine vollständige manuelle Fremdsprachen-UI-Review war ausdrücklich nicht
  Teil des Auftrags.

Es bleiben keine offenen in-scope Befunde und keine Blocker für Issue #523. Die Abnahme lautet
**PASS** für den Sprach-/Hardcode-Gate-Scope. Das ist keine Freigabe für Veröffentlichung,
F-Droid-Einreichung, Gerätetest oder sonstige Release-Ausführung.
