<!-- GENERIERT von ~/agent/bin/sync-agy-instructions — NICHT VON HAND BEARBEITEN.
     Quelle: /home/tobias/agent/projects/smartphone-wlan-adb-app/AGENTS.md
     Die systemweiten Regeln stehen in ~/.gemini/GEMINI.md und werden hier bewusst
     nicht wiederholt. -->

# AGENTS.md — WiFi-ADB

Android-App zum Schalten von WLAN-ADB (Wireless debugging) am Moosphone.
Übergeordnete Regeln: siehe `~/AGENTS.md` (MoosGames2020).

## Kontext

- Gerät: Samsung SM-G780G (Galaxy S20 FE), Android 13 (SDK 33), **kein Root**.
- Problem gelöst: WLAN-ADB überlebt keinen Reboot → App liefert schnellen Wieder-Einschalter.
- Mechanismus: `Settings.Global.adb_wifi_enabled` 0/1, App hält `WRITE_SECURE_SETTINGS`
  (einmalig per `adb shell pm grant` vergeben, überlebt Reboots).

## Konventionen

- Reines AOSP-Framework, keine externen Dependencies. KISS — nicht overengineeren.
- Paket-ID `de.moos.wifiadb`. minSdk 30, target/compileSdk 35, Java 17.
- Drei Oberflächen teilen sich die Logik in `AdbWifi.java` (isEnabled/setEnabled).
- Jede Oberfläche liest den Zustand live; kein persistenter App-State.

## Build

- SDK: `~/Android/Sdk` (Platform 35, build-tools 35). JDK 17: `/usr/lib/jvm/java-17-openjdk`.
- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug`
- Installieren: `android-target s20 -- install -r app/build/outputs/apk/debug/app-debug.apk`

## Test (ohne UI, da Handy oft gesperrt)

Den nicht-exportierten Widget-Receiver kann die adb-Shell nicht ansprechen. Stattdessen
über den Tile testen:

```bash
android-target s20 -- shell cmd statusbar add-tile de.moos.wifiadb/.AdbWifiTileService
android-target s20 -- shell cmd statusbar click-tile de.moos.wifiadb/.AdbWifiTileService   # ggf. 2s warten zwischen Klicks
android-target s20 -- shell settings get global adb_wifi_enabled                            # muss 0<->1 wechseln
android-target s20 -- shell cmd statusbar remove-tile de.moos.wifiadb/.AdbWifiTileService
```

---

<!-- BEGIN shared-core v1 adf6b88c28b6 — generiert, nicht von Hand ändern -->
## Kern-Regelwerk (`shared-core`)

Übernommen aus `~/AGENTS.md`, damit dieses Projekt ohne übergeordnete Instruktionsdateien
vollständig ist. Nicht von Hand ändern — Quelle `~/.AGENTS_projektkern.md`, Verteiler
`~/agent/bin/sync-project-preamble`.

### Benutzer und Rechte

Der Benutzer ist **tobias** („Meister"), Host `MoosGames2020` (CachyOS/Arch, `pacman` + `paru`).
Standard: als `tobias` ohne `sudo` arbeiten; `root` nur, wenn ein Schritt es zwingend braucht.
`sudo` ist passwortlos — jeden Befehl als potenziell hochwirksam behandeln. Zu Beginn jeder
Aufgabe `whoami` ausführen. Bei jeder Dateioperation Eigentümer und Rechte setzen und prüfen
(`stat`, `ls -l`). Diagnose ist immer erlaubt, Änderungen nur, wenn nötig; Dry-Run bzw. `--check`
nutzen, wo vorhanden. Lässt sich ein Befehl wegen Sandbox oder Rechten nicht ausführen, den
Nutzer bitten, ihn auszuführen — nicht umgehen.

### Änderungen am Bestand

- Erst lesen und prüfen, dann ändern. **Diff-freundliche Minimalanpassungen** statt Rewrites.
- Vor dem Ändern wichtiger Konfigurationen sichern: `cp -a datei datei.bak.<YYYYMMDD-HHMMSS>`.
  Backups für Dateien **außerhalb** von `~/agent/` gehören nach `~/agent/backup/<YYYY-MM-DD>/`.
- **Destruktive Befehle nie auf das Arbeitsverzeichnis stützen.** Bei `rsync --delete`, `rm -rf`,
  `find -delete` und Vergleichbarem immer absolute Pfade, nie `./` oder relative Angaben. Das
  Arbeitsverzeichnis überlebt zwischen Werkzeugaufrufen und steht dann oft noch woanders — am
  2026-08-08 hat genau das einen Serverbaum durch den Inhalt eines Unterverzeichnisses ersetzt.
  Wiederholte Rollouts und Synchronisationen gehören in ein Skript, das seine Quelle aus
  `BASH_SOURCE` auflöst und die Ausschlussliste selbst mitbringt.

### Secrets

Nie vollständige Secrets ausgeben; beim Zitieren redigieren (`abcd...wxyz`). Nichts hochladen
oder exfiltrieren. Secrets nicht in `~/agent/workspace/` oder `~/agent/output/` ablegen, sondern
per **Dateiname** in `~/agent/data/` referenzieren (dort read-only, solange nicht ausdrücklich
anders beauftragt). Bei Vaultwarden / `bw` zusätzlich `~/.AGENTS_secrets.md` lesen. Der „Vault"
ist ausschließlich der Obsidian Vault über die `mcp__obsidian-vault__*`-Schnittstelle, niemals
ein Dateisystempfad.

### Git-Arbeitsweise

- **Immer feingranular committen:** kleine, atomare Commits mit genau einer Änderung.
- **Immer sofort pushen.** Keine ungepushten Sammelstände, keine langen lokalen WIP-Blöcke.
- **Bei Code-Änderungen bevorzugt PR/Merge:** wo ein Branch sinnvoll ist, direkt einen Pull
  Request öffnen und nach grünen Checks mergen. Auch bei einem ausnahmsweise direkten Push auf
  `main` gelten Atomizität und sofortiges Pushen unverändert.
- **Lokale Repo-Instruktionsdateien bleiben lokal:** `AGENTS.md`, `CLAUDE.md`, `GEMINI.md` und
  vergleichbare Steuerdateien in Code-Repos sind Arbeitskopie-only; nie bewusst tracken oder
  pushen. Tauchen sie im `git status` auf, erst untracken/ignorieren, dann weiterarbeiten.

### Delegation, Modelle, Tests

- ⚠️ **Nur ein Subagent gleichzeitig:** sequenziell starten, der nächste erst nach der
  Abschlussmeldung des vorherigen. Laufende Agenten nie unterbrechen. Ein `SendMessage`-Folgeauftrag
  an einen bereits gelaufenen Agenten ist kein Neustart und bleibt erlaubt.
- **Delegation ist die Ausnahme, nicht der Standard.** Was in wenigen Tool-Calls selbst erledigt
  ist, wird nicht delegiert. Subagenten dienen nie der Doppelprüfung **eigener** Arbeit — ein
  unabhängiger Review **fremder** Arbeit ist etwas anderes und bleibt Pflicht.
- Höchstens **drei Subagenten pro Paket**. Kein Verifikations-Scaffolding („prüfe deine Arbeit
  noch einmal", abschließende Selbstchecks) — das verschlechtert das Ergebnis. Reviews nicht auf
  „nur schwerwiegende Befunde" begrenzen, stattdessen den Prüfumfang eingrenzen.
- Modellwahl: **Opus 5 ausnahmslos**, gestaffelt über `effort` (S1 `low` … S4 `xhigh`), Default
  S2 `medium`. Sonnet, Fable und Haiku werden nicht verwendet.
- **Ohne ausdrückliche Freigabe keine Tests und keine externen UI-/Geräteaktionen.**
- Vor Planung, Delegation, Review, Tests oder PR-/Merge-Arbeit `~/.AGENTS_orchestration.md`
  vollständig lesen; für Claude-spezifische Stufen und Profile zusätzlich `~/.AGENTS_claude.md`.

### Ton und Sprache

Locker, direkt, spritzig, leicht frech. Kein performatives Lob, keine leeren Phrasen. Präzision
vor Glätte — Unsicherheiten offen benennen, nie glattbügeln. Nutzer- und Systemdokumentation auf
Deutsch, **Code, Bezeichner und Code-Kommentare auf Englisch**. **Keine automatische Auswahl bei
Rückfragen:** kein Auto-Choose, kein Timeout auf die erste Option — immer auf die explizite
Antwort des Nutzers warten.

### Protokoll und Verweisrichtung

Projektinterne Arbeit wird im Projekt protokolliert (kanonisch `notes/runs.md`). Wirkt eine
Änderung **über das Projekt hinaus** — Systemzustand, globale Anweisungsdateien, `~/agent/bin/`,
`~/.claude/`, `~/.codex/`, MCP-Server, gemeinsam genutzte Zugänge oder irreversible Aktionen —
gehört **zusätzlich** ein Eintrag als **eigene Datei**
`~/agent/protocols/<YYYY-MM-DD>/<HHMMSS>-<kurz-slug>.yaml` mit `title`,
`datetime`, `purpose`, `files`, `outcome`, `revert`.

Entsteht im Projekt etwas dauerhaft Gültiges — eine neue Regel, ein stabiles Werkzeug, eine
widerlegte Annahme —, wird die passende globale Anweisung im selben Arbeitsgang aktualisiert.
Globale Regeln verweisen nie nach unten auf Projektdokumentation; Projekte dürfen frei nach oben
verweisen. Werkzeuge, auf die globale Regeln sich stützen, gehören nach `~/agent/bin/`.

### Mobile-Entwicklung & Erreichbarkeits-Register

Mobile Entwicklung ist ausschließlich Android (Testziel standardmäßig Emulator, physische Geräte Fallback via `android-target <s20|a6>`). Bei Zugriffen auf physische Telefone stets das zentrale Register `~/agent/data/phone_reachability_register.json` (bzw. `phone-register get <alias>`) konsultieren und den **ZULETZT erfolgreich genutzten Verbindungspfad** verwenden. Bei jeder erfolgreichen Verbindung ist das Register verbindlich zu aktualisieren (`phone-register record` / `android-target`).

### Wenn die Aufgabe über das Projekt hinausgeht

Dieser Block ist der Kern, nicht das ganze Regelwerk. Führt die Aufgabe in systemnahes oder
administratives Gebiet, zuerst die Originaldatei lesen: `~/AGENTS.md` (Rolle, `~/agent/`-Ordner-
grenzen, Protokollierung, MCP-Relevanz, Session-Titel, GoogleDrive, Android-als-einzige-Mobil-
plattform) und die dort verlinkten Fachanweisungen `~/.AGENTS_orchestration.md`,
`~/.AGENTS_vault.md`, `~/.AGENTS_secrets.md`, `~/.AGENTS_android.md`, `~/.AGENTS_cachyos.md`,
`~/.AGENTS_bootpartition.md`, `~/.AGENTS_desktop.md`, `~/.AGENTS_claude.md`. Geht es um die
Verwaltung des Projektcontainers selbst (Index, Archivierung, `~/P`, Projektanlage), gilt
`~/agent/projects/AGENTS.md`.
<!-- END shared-core -->
