# Zu KeepADB beitragen

Danke für dein Interesse. Lies vor Änderungen an Berechtigungen, Netzwerkverhalten,
Benachrichtigungen oder Keep-Alive auch die [Sicherheitsrichtlinie](SECURITY.md).

## Entwicklungsumgebung

Für die normale lokale Prüfung werden benötigt:

- JDK 17
- Android SDK Platform 35
- Android SDK Build Tools 35.0.0
- Android 11 oder neuer als Mindestziel (minSdk 30)

Die App wird mit Java 17, compileSdk 35 und targetSdk 35 gebaut. Für Gradle-Aufrufe verwende
`./bin/gradlew`; der Wrapper setzt JDK 17, sofern `JAVA_HOME` nicht bereits ausdrücklich gesetzt
ist.

## Lokale Verifikation

Vor einem Pull Request den vollständigen Projekt-Gate ausführen:

~~~sh
./bin/verify
~~~

Das Skript prüft nacheinander `git diff --check`, Übersetzungen mit `bin/check-i18n`, Unit-Tests,
Lint und Debug-Build, den Release-Build sowie Debug-/Release-Markierungen in den erzeugten APKs.

Einzelne Schritte können mit dem Wrapper ausgeführt werden:

~~~sh
./bin/gradlew testDebugUnitTest
./bin/gradlew lintDebug
./bin/gradlew assembleDebug
python3 bin/check-i18n
~~~

## Laufzeit- und Test-Abhängigkeiten

**Die ausgelieferte App hat keine Drittanbieter-Laufzeit-Abhängigkeiten.** Die einzigen
Drittanbieter-Bibliotheken in `app/build.gradle` stehen im `testImplementation`-Bereich:
JUnit, Robolectric und `androidx.test:core`. Sie werden für Tests verwendet und gelangen nicht in
die Release-App.

Die Tests in `app/src/test/java` enthalten sowohl Android-freie Unit-Tests als auch Robolectric-
Tests mit Android-Ressourcen. Contract-Tests sichern unter anderem Manifest-, Ressourcen- und
Aufrufpfad-Invarianten. Ändere eine solche Prüfung gezielt, wenn sich der Vertrag wirklich ändert;
entferne keine Assertion nur deshalb, weil eine Implementierung sie nicht mehr erfüllt.

## Code-Konventionen

- Neue Laufzeitabhängigkeiten gehören nur nach begründetem Bedarf in den App-Code; nutze für
  Tests die vorhandenen JUnit- und Robolectric-Möglichkeiten.
- Ergänze oder passe Prüfungen unter `app/src/test/java/de/hohnepeople/keepadb` an. Contract-Tests
  schützen Manifest-, Ressourcen- und Aufrufpfadregeln; entferne eine Assertion nur, wenn der
  geprüfte Vertrag bewusst geändert wurde.
- `KeepADB.java` enthält gemeinsam genutzten Zustand, Generationstoken und Sperren. Änderungen an
  diesen Pfaden sollen die notwendige Reihenfolge und Synchronisierung direkt am Code erläutern.

## Übersetzungen und sichtbare Texte

KeepADB enthält 19 Sprachvarianten. Lege neue Texte zuerst in `app/src/main/res/values/strings.xml`
an und übersetze sie in allen vorhandenen `values-*/strings.xml`-Dateien. `KeepADBResourceContractTest`
prüft die Schlüsselmengen; `bin/check-i18n` meldet Texte, die wortgleich aus dem englischen
Referenzwert übernommen wurden. Zulässige identische Marken- oder Technikbegriffe benötigen eine
begründete Ausnahme in der Allowlist des Skripts.

## CI und Release-Werkzeugpfad

`.github/workflows/ci.yml` läuft automatisch bei Änderungen an `master`, bei Pull Requests und
manuell über `workflow_dispatch`. Der Workflow verwendet JDK 17, Android Platform 35 sowie
Build Tools 35.0.0 und führt `./bin/verify` aus.

Der davon getrennte Release-Workflow wird durch Tags mit Präfix `v` ausgelöst. Sein Build- und
Signierpfad verwendet JDK 21; für die Signatur ruft er `apksigner` aus Android Build Tools 34.0.0
auf. Diese apksigner-Version ist Teil des Signierpfads und ersetzt nicht die Build-Tools-
Festlegung 35.0.0 des App-Projekts. Ein grüner lokaler Build oder CI-Lauf allein ist keine
Aussage, dass zwei unabhängig signierte Store-Artefakte bytegleich sind. Ein Tag mit Präfix `v`
kann den echten Release-Workflow auslösen; erstelle oder pushe ihn nur nach ausdrücklicher
Release-Freigabe. Ein Release veröffentlicht Artefakte und startet den vorgesehenen F-Droid-Pfad.

## Pull Requests

- Halte Änderungen auf einen klaren Zweck begrenzt.
- Verknüpfe das bearbeitete Issue, falls vorhanden.
- Führe `./bin/verify` vor dem Pull Request aus.
- Beschreibe Sicherheits- und Datenschutzauswirkungen, wenn Berechtigungen, Netzwerkverkehr,
  Datensicherung oder Wiederherstellung betroffen sind.
