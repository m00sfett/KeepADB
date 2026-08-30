# Runs

## 2026-08-22 — F-Droid preparation handoff

- **Purpose:** Record the project-specific preparation for the official F-Droid catalog as
  a clear handoff for the product agent.
- **Changes:** Added `F-DROID-PREPARATION.md`. The handoff covers publication inventory,
  license/dependency/data-flow findings, release/build evidence, and a proposed F-Droid
  metadata file.
- **Not performed:** no builds, tests, repository visibility change, uploads, or merge
  requests.
- **Next step:** Create the findings report at `notes/f-droid-intake-report.md`.

## 2026-08-21 — License change

- Replaced the repository license with the official GNU Affero General Public License v3.0, or (at your option) any later version.
- Updated the README badge and license section.
- Updated the changelog license entry.
- Verification: `cmp` against `/usr/share/licenses/spdx/AGPL-3.0-or-later.txt` and `git diff --check` passed.

## 2026-08-21 — Issue orchestrator selection

- Read-only inventory of the current GitHub issues, PRs, runs, workflows, branch-protection
  response, and drift status completed.
- Selected issue #130 as the next private-hardening package after verifying its actual
  `BootReceiver`/manifest call path.
- No implementation, build, test, device, workflow, or GitHub write action performed.
- Persistent plan updated with `not approved` status and typed gates.

## 2026-08-21 — Issue #130 implementation

- Full user approval received for implementation, local gates, and device validation.
- Restricted `BootReceiver` to system boot, removed `QUICKBOOT_POWERON`, and made foreground
  service start failures explicit and non-successful.
- Added `KeepADBBootReceiverContractTest`; validation and independent S4 review remain open.

## 2026-08-21 — Issue #130 review repair

- Independent S4 review 1 returned `NOT APPROVED` because asynchronous FGS promotion could
  still be followed by boot re-enable and because the static contract test had false positives.
- Repaired the ordering with a post-promotion re-enable path and a pre-foreground callback guard;
  tightened the contract test.
- Repeated `git diff --check`, unit tests, lint, and debug build successfully. Review 2 and the
  subsequent device gate remain open.

## 2026-08-22 — Issue #130 circuit breaker

- Independent S4 review 2 returned `NOT APPROVED`: pre-foreground heartbeat/state mutations and
  an unguarded network-loss callback remain; the static test does not prove ordering.
- Circuit breaker activated after two consecutive independent review failures. No third repair,
  review, or device gate started.
- Plan records three scoped options; status is `closed-pending-decision`.

## 2026-08-22 — Issue #130 option 1 repair

- User selected option 1: foreground promotion before recovery initialization.
- Moved heartbeat, user-intent consumption, observers, network callback, and ticker setup after
  successful `startForeground`; guarded late network-loss callbacks and strengthened ordering
  assertions in the static contract test.
- Local gates and a fresh independent S4 review remain required before device validation.

## 2026-08-22 — Issue #130 review 3 circuit breaker

- Independent S4 review 3 returned `NOT APPROVED`: failed FGS promotion on service re-entry is
  not fail-closed and the static test does not guard cleanup/order.
- No fourth review, automatic repair, or device gate started. Plan records three new options;
  status is `closed-pending-decision`.

## 2026-08-22 — Issue #130 fail-closed repair

- User selected fail-closed startup cleanup.
- Added cleanup for failed FGS promotion and service re-entry, including callback/ticker
  teardown, notification removal, and evaluated `stopSelfResult`.
- Repeated local diff, unit, lint, and debug-build gates successfully. Independent S4 review 4
  remains required before device validation.

## 2026-08-22 — Issue #130 review 4 scope circuit breaker

- Independent S4 review 4 returned `NOT APPROVED` with a high cross-component lifecycle finding:
  endpoint, notification, and webhook callbacks can outlive FGS re-entry cleanup.
- No fifth review, automatic repair, or device gate started. Plan records three new scope/
  architecture options; status is `closed-pending-decision`.

## 2026-08-22 — Issue #130 scope split

- User selected option 3. Created and read-back verified GitHub issue #135 for the
  cross-component FGS/endpoint/notification/webhook lifecycle.
- Kept #130 limited to receiver boundary, QUICKBOOT removal, foreign-broadcast protection,
  initial FGS start/promotion, and explicit startup failure handling.
- Continued the already authorized #130 device gate; no implementation work started for #135.

## 2026-08-22 — Issue #130 narrowed device blocker

- Installed the narrowed #130 APK and confirmed the explicit foreign BOOT_COMPLETED broadcast is
  rejected with `SecurityException`.
- After reboot, registered WLAN endpoint `192.168.178.24:34513` refused connections and the
  required scan found no valid WLAN-ADB port. USB fallback fingerprint validation succeeded.
- With `keep_alive_enabled=true`, `adb_wifi_enabled` remained `0`; no BootReceiver/FGS recovery
  evidence was obtained. No more reboot or install retries started. Status: `blocked`.

## 2026-08-22 — Issue #130 delayed transport recovery

- After allowing the device additional startup time, WLAN-ADB returned on
  `192.168.178.24:45099`; the endpoint, model, serial, SDK, and fingerprint were validated.
- Post-reboot evidence showed `keep_alive_enabled=true`, `adb_wifi_enabled=1`, and a current
  service heartbeat. The earlier blocker was superseded by this measured state.
- Updated and read back the narrowed #130 issue scope; #135 remains the open lifecycle follow-up.

## 2026-08-22 — Issues #145 & #146 (Vector icon for Notification and Tile)

- Generated and selected vector asset variant A (potrace contour tracing of monochrome launcher artwork).
- Updated `app/src/main/res/drawable/ic_keepadb.xml` with the new 24x24dp vector graphic.
- Added contract unit test in `KeepADBAccessibilityContractTest.java`.
- Local verification (`bin/verify`) passed cleanly.

## 2026-08-22 — Issues #148 & #149 (Tile & Notification icon scaling and label standardisation)

- Scaled vector icon glyph in `ic_keepadb.xml` to 20x20dp (~31% scale) for crisp appearance in status bar and tile.
- Standardized `tile_label` across all 19 locale string files to `@string/app_name` ("KeepADB").
- Added contract tests in `KeepADBAccessibilityContractTest.java`.
- PR #151 created, verified with `./bin/verify`, merged into `master`.

## 2026-08-22 — Issue #150 (Remove notification and FGS on wireless debugging disabled)

- Updated `KeepADBService`, `KeepADBNotification`, and toggle call sites to stop foreground service and cancel notification when wireless debugging is disabled.
- Added contract tests in `KeepADBBootReceiverContractTest.java`.
- PR #152 created, verified with `./bin/verify`, merged into `master`.

## 2026-08-22 — PR #153 (Allow cleartext HTTP traffic in network security config)

- Allowed cleartext HTTP traffic in `network_security_config.xml` for custom LAN webhooks.
- Added contract test in `KeepADBRegisterClientTest.java`.
- Verified with `./bin/verify`, merged into `master`.

## 2026-08-22 — F-Droid preparation and intake report

- Executed F-Droid preparation checklist from `F-DROID-PREPARATION.md` (Sections A through D).
- Verified clean-checkout reproducibility, Zero-Dependencies, AGPL-3.0-or-later licensing, permissions rationale, zero trackers/anti-features, and local release APK build.
- Generated `notes/f-droid-intake-report.md` with complete metadata YAML proposal for `fdroiddata`.

## 2026-08-22 — Release v1.0.0 and Public Publication

- Harmonized `versionName` in `app/build.gradle` to `1.0.0` (matching `CHANGELOG.md` and release tag).
- Executed `./bin/verify` (all 25 unit tests green, lint clean, release APK built).
- Created annotated Git tag `v1.0.0` on commit `a17e7e4` and pushed to `origin`.
- Changed GitHub repository visibility of `m00sfett/KeepADB` to **PUBLIC**.
- Created GitHub Release `v1.0.0` with signed APK and SHA-256 checksums.

## 2026-08-22 — F-Droid Official Catalog Merge Request

- Stored GitLab PAT securely in Vaultwarden (`git/gitlab-pat`) and deleted temporary auth file.
- Forked `fdroid/fdroiddata` on GitLab under `m00sfett/fdroiddata`.
- Created branch `add-de.hohnepeople.keepadb` and committed `metadata/de.hohnepeople.keepadb.yml`.
- Opened official F-Droid Merge Request: https://gitlab.com/fdroid/fdroiddata/-/merge_requests/46500

## 2026-08-22 — Issue #154 (Notification title status and deduplication)

- Updated notification title to `KeepADB: <STATUS>` (e.g. `KeepADB: Drahtloses Debugging ist AN` / `KeepADB: Wireless Debugging is ON`, `KeepADB: Endpoint wird gesucht …`, `KeepADB: Berechtigung fehlt`).
- Removed redundant connection endpoint info (`Port @ IP`) from notification title; second line continues to display formatted endpoint string (`Port <port> @ <ip>`).
- Updated all 19 locale string resources consistently.
- Created PR #155, verified via `./bin/verify`, merged into `master`, built and deployed to S20.

## 2026-08-22 — Issue #156 (Notification title refinement to Wifi-ADB status)

- Refined notification title to `KeepADB: Wifi-ADB AKTIVIERT` / `KeepADB: Wifi-ADB ENABLED` across all 19 locales.
- Added `notification_title_disabled` (`KeepADB: Wifi-ADB DEAKTIVIERT` / `KeepADB: Wifi-ADB DISABLED`) for completeness.
- Verified 100% key completeness and consistency across all 19 locale files (63 keys each).
- Created PR #157, verified via `./bin/verify`, merged into `master`, built and deployed to S20.

## 2026-08-22 — Issue #159 (Security risk warnings & network advice)

- Added *Security Considerations & Best Practices for Wireless Debugging* section to `README.md` (trusted networks, public hotspot precautions, pairing prompt checks).
- Added `settings_security_panel` with `@string/settings_section_security` and `@string/settings_security_body` to `activity_settings.xml`.
- Added localized security advice strings across all 19 supported languages (`values*/strings.xml`).
- Added static contract test `settingsActivityIncludesSecurityAdvicePanel` and expanded locale contract tests in `KeepADBAccessibilityContractTest.java`.
- Executed `./bin/verify` (all 25 unit tests passed, 0 lint warnings/errors, debug and release APKs built).

## 2026-08-22 — Issue #158 (Option to hide persistent notification)

- Added `hide_notification_enabled` preference to `KeepADBPreferences`.
- Updated `KeepADBNotification` to cancel and suppress status notifications when the hide preference is enabled.
- Added `settings_notification_panel` with toggle switch and explanatory subtext in `activity_settings.xml` and wired in `SettingsActivity.java`.
- Added localized strings for notification settings across all 19 supported languages (`values*/strings.xml`).
- Added contract tests in `KeepADBAccessibilityContractTest.java` and `KeepADBBootReceiverContractTest.java`.
- Executed `./bin/verify` (all 26 unit tests passed, 0 lint warnings/errors, debug and release APKs built).

## 2026-08-23 — Reproducible v1.1.0 release preparation

- Created a durable upstream PKCS12 release identity (`keepadb-release`) with certificate fingerprint `C5:2B:CD:17:1B:5C:CE:E8:87:F3:C1:6C:C3:A4:74:B3:8B:D9:CC:D7:71:CA:8C:D9:92:F8:2E:4D:17:75:3C:04`; the private key is outside the repository, backed up in Vaultwarden, and exposed to GitHub Actions only through write-only secrets.
- Prepared PR [#162](https://github.com/m00sfett/KeepADB/pull/162) for version `1.1.0` / version code `2`, upstream Fastlane metadata, pinned Android build-tools `34.0.0`, and unsigned Gradle builds signed externally with `apksigner`.
- Local `./bin/verify` passed. Two clean unsigned release builds were byte-identical. `apksigcopier compare --unsigned` passed for the signed APK, whose package/version and certificate were verified.
- Installed the signed `1.1.0` APK on the physical SM-G780G (`RF8T307S88H`) after backing up the previous APK and Preferences. The pulled installed APK was byte-identical to the signed candidate.
- On the unlocked S20, the Quick Settings tile toggled `adb_wifi_enabled` `1→0→1`; the active notification disappeared on OFF and returned on ON. The Settings switch hid and restored the notification. Added an English Fastlane screenshot captured with Wireless Debugging OFF, containing no endpoint address.
- No GitLab files, branches, commits, MR descriptions, labels, comments, or issue-bot actions were changed.

## 2026-08-23 — Independent v1.1.0 release review

- Moved the unsigned Gradle build ahead of keystore restoration so build scripts never receive the release key or signing passwords.
- Corrected the USB installation example to use the signed GitHub artifact and limited reproducibility wording to the verification state actually proven before publication.
- `./bin/verify`, workflow YAML/order checks, ShellCheck, Fastlane length/image checks, two byte-identical clean unsigned builds, `apksigner verify`, and `apksigcopier compare --unsigned` passed. The exact merged/tagged release artifact still requires the final post-release comparison.
- No device action, GitLab mutation, merge, release tag, workflow dispatch, or release publication was performed.

## 2026-08-23 — Vor-GitLab-Plan nach F-Droid-Anforderungsabgleich

Read-only-Abgleich mit der aktuellen F-Droid-Inclusion-Policy, dem aktuellen App-Inclusion-Template, den Build-Metadata-Regeln und Linsuis MR-Hinweisen:

1. Den offenen MR !46500 auf Upstream `v1.1.0` aktualisieren: Version `1.1.0`, VersionCode `2`, vollständiger Commit `7b2e1ad45c1645b16b891f07918ad9ff7e798891`; niemals Tag/Branch als `Builds.commit` verwenden.
2. Die aktuelle App-Inclusion-Vorlage vollständig übernehmen: Titel `New app: KeepADB`, Checklisten und belegte Antworten; unzutreffende Issue-Platzhalter entfernen.
3. fdroiddata auf reine App-YAML reduzieren: `metadata/de.hohnepeople.keepadb/en-US/summary.txt` sowie `Name`/`Description` aus der YAML entfernen; Benutzertexte ausschließlich aus upstream Fastlane beziehen.
4. Upstream-signierte reproduzierbare Veröffentlichung in der YAML deklarieren: versionierte `Binaries`-URL zum GitHub-Release und `AllowedAPKSigningKeys` mit dem geprüften Zertifikatsfingerprint.
5. Lokale F-Droid-Gates und den Build des exakten `v1.1.0`-Commits ausführen; anschließend signed/unsigned Vergleich und Fastlane-Struktur erneut prüfen.
6. Nach dem MR-Update die GitLab-Pipeline, Code-Quality-Report und License-Compliance-Auswertung frisch lesen. Den Fastlane-Critical-Fund muss der `v1.1.0`-Stand beseitigen; Permission/ABI/Signed-APK-Einträge sind erwartete Informational-Funde. `License Compliance failed loading results` und `No security scans enabled` sind separat als CI-/Projekt-Infrastrukturstatus zu bewerten.
7. Erst wenn alle Punkte 1–6 sauber und reviewed sind, das separate GitLab-Gate einholen. Bis dahin keine GitLab-Änderung.

Festgehalten: Der Plan ist verbindlich vor dem GitLab-Gate; bei einem neuen Blocker wird nicht automatisch gepusht oder die Pipeline wiederholt.

## 2026-08-23 — Vor-GitLab-Umsetzung lokal abgeschlossen

- Lokale fdroiddata-Arbeitskopie auf `v1.1.0` / VersionCode `2` und den vollständigen Commit `7b2e1ad45c1645b16b891f07918ad9ff7e798891` aktualisiert.
- fdroiddata-Summary-Datei, `Name`, `Description` und die nicht passende Source-Code-Website entfernt; upstream Fastlane bleibt alleinige Quelle für Storetexte.
- `Binaries` und `AllowedAPKSigningKeys` für den upstream-signierten GitHub-Release ergänzt.
- Lokaler fdroidserver-Scanner und `fdroid build -v -l de.hohnepeople.keepadb` bestanden; der F-Droid-Build verglich das unsigned Ergebnis erfolgreich mit dem signierten GitHub-APK und akzeptierte den Zertifikatsfingerprint.
- `fdroid lint` und `fdroid checkupdates --allow-dirty` bestanden. Die lokale fdroiddata-Änderung ist als `607401e64d0f9dd230564a01daa6d868418d4fcc` gesichert, aber noch nicht nach GitLab gepusht.
- Frisch bestätigt: upstream-Tag/Commit und Fastlane-Dateien sind vorhanden; MR !46500 steht unverändert auf `17ea3d0cce36566bde9833247c3b5f50275dc76a`.

## 2026-08-23 — Letzter Vor-GitLab-Anforderungsabgleich

- Der vorbereitete lokale Stand wird jetzt abschließend gegen die aktuelle F-Droid-App-Inclusion-Vorlage, die Build-Metadata-Referenz, die Reproducible-Build-Regeln und Linsuis konkrete Hinweise geprüft.
- Gate-Kriterium: Erst wenn Titel/Template, v1.1.0-Voll-Commit, Fastlane-only-Metadaten, `Binaries`, `AllowedAPKSigningKeys`, lokale Gates und die Report-Bewertung vollständig passen, wird das separate GitLab-Gate angefragt.
- GitLab bleibt bis zu dieser Freigabe unverändert.

## 2026-08-23 — F-Droid-Publish-Skill-Verbesserungspunkte aus GitLab-Gate

- GitLab-MR-API und Commits-API unterscheiden das offizielle Projekt (`fdroid/fdroiddata`, ID `36528`) vom Quellprojekt des Fork-MR (`m00sfett/fdroiddata`, ID `85658298`). Ein Commit gegen die offizielle Projekt-ID scheitert mit Branch-Schutz/403; der Skill sollte die `source_project_id` des MR vor jedem Commit automatisch auslesen.
- Der Vaultwarden-Eintrag für das GitLab-PAT enthält neben dem Token zusätzliche Notizen. Für HTTP-Header muss der Skill den eigentlichen Tokenwert sicher extrahieren, ohne Notiztext oder Zeilenumbrüche zu übernehmen; andernfalls entstehen irreführende `Invalid json`-Antworten.
- Die aktuelle fdroiddata-Schema-Prüfung verlangt bei `Binaries` zwingend `%v` oder `%c`. Eine literal versionierte GitHub-Release-URL ist trotz F-Droid-Dokumentation nicht schema-konform; der Skill sollte diese Platzhalter vor dem Push validieren und die URL-Ersetzung lokal testen.
- `fdroidserver` entfernt beim Build den `gradlew`-Wrapper und `gradle-wrapper.jar`; lokale Tests brauchen deshalb einen systemweiten Gradle-Adapter (`gradlew-fdroid`) oder eine explizite Gradle-Konfiguration. Der Skill sollte diesen Umgebungsfehler früh erkennen und klar als Toolchain-Gap kennzeichnen.
- GitLab-Pipeline 2783225916: Schemafehler durch literal versionierte `Binaries`-URL; nach Umstellung auf `%v` bestand die Schema-Prüfung.
- GitLab-Pipeline 2783227459: `fdroid rewritemeta` verlangte die kanonische Einzeilenform für `Binaries` und `AllowedAPKSigningKeys`; der lokale Formatter-Diff wurde übernommen.
- Derselbe Lauf zeigte den wichtigen Toolchain-Unterschied: F-Droid baut mit Gradle 8.9/JDK 21, während der GitHub-Release mit JDK 17 erstellt wurde. Der F-Droid-unsigned-APK-Hash `4f1c768f…` stimmt lokal mit JDK 21 exakt überein, aber nicht mit dem veröffentlichten JDK-17-Release `f2549224…`. Eine testweise `JavaCompile.options.release = 17`-Lösung wird vom Android Gradle Plugin abgelehnt. Vor Abschluss muss deshalb der upstream Release reproduzierbar mit JDK 21 neu gebaut/signiert werden; dafür ist ein separates GitHub-Release-Gate erforderlich.
- Pipeline 2783232973: Schema, Source-Code, Rewritemeta, Lint und Checkupdates grün; ausschließlich `fdroid build` bleibt wegen des nachweisbaren JDK-17/JDK-21-Artefaktunterschieds rot. Die MR-Beschreibung lässt die Pipeline-Checkbox deshalb korrekt offen.

## 2026-08-23 — GitHub-Release-JDK-Reparatur und finales F-Droid-Gate

- GitHub PR [#164](https://github.com/m00sfett/KeepADB/pull/164) stellte den Release-Workflow von JDK 17 auf JDK 21 um und wurde als Merge-Commit `38cd0126eb8a7542fb897a211c665c5c7bce63c0` gemergt.
- Der bestehende Tag `v1.1.0` wurde im freigegebenen Reparatur-Gate auf diesen Commit verschoben; Release-Workflow [32646442850](https://github.com/m00sfett/KeepADB/actions/runs/32646442850) lief erfolgreich durch. Das signierte APK hat SHA-256 `813a3f187f116825799fe1c429f7dcb8ef75655a77f61d64adf098616cdc28ee` und Zertifikat `c52bcd171b5ccee887f3c16cc3a474b38bd9ccd771ca8cd992f82e4d17753c04`.
- Bei der Reproduzierbarkeitsdiagnose wurde präzisiert: `META-INF/version-control-info.textproto` enthält die Git-Revision. Deshalb muss der F-Droid-`Builds.commit` exakt dem Release-Merge-Commit entsprechen; ein alter Commit erzeugt trotz identischem Quellcode einen anderen unsigned APK-Inhalt.
- F-Droid-MR !46500 wurde mit Commit `03caa0b7ea9d1525065ff40af83644488d25f74e` auf `38cd0126…` aktualisiert. Pipeline [2783240386](https://gitlab.com/fdroid/fdroiddata/-/pipelines/2783240386) ist vollständig grün (inklusive `fdroid build` und `check apk`); die Build-Checkbox im Inclusion-Template ist gesetzt.
- Die GitLab-Code-Quality-Jobs sind im grünen Pipeline-Lauf erfolgreich. Die zuvor beobachteten Fastlane-/Summary-Funde stammen aus dem alten Stand; License-Compliance-/Security-Scan-Hinweise waren projektseitige Infrastrukturmeldungen und keine Build-Blocker.

## 2026-08-26 — Issue #170 unabhängiger S2-Review

- `KeepADBUsbProfile.delete()` und der Settings-Löschdialog wurden gegen die Akzeptanzkriterien,
  Aufrufer-/Lifecycle-Gegenrichtung und die USB-Notification-Abgrenzung geprüft.
- Zwei Testabdeckungsbefunde wurden innerhalb des lokalen Scopes repariert: vollständige
  Feldbereinigung beim letzten Profil, Auswahl des vorherigen Profils sowie engerer Dialog-
  Contract für Profilname, positive Löschung und Cancel/Back.
- 22 gezielte Tests und die vollständige lokale Suite mit 41 Tests bestanden. `./bin/verify`
  bestand mit Unit-Tests, Lint, Debug- und Release-Build.
- Mutationstest gegen die Tailnet-Feldbereinigung wurde erwartungsgemäß rot und danach
  zurückgenommen. Keine GitHub-Actions, keine Geräteaktion, kein Commit, Push oder Merge.
- Reviewstatus: `approved` im lokalen #170-Scope. Echte UI-/Notification-/Neustart-Nachweise
  bleiben ungeprüft.

## 2026-08-26 — Issue #170 Abschluss

- Featurecommit und Reviewdokumentation atomar committed, Branch `feat/170-delete-usb-profiles`
  gepusht und PR [#176](https://github.com/m00sfett/KeepADB/pull/176) erstellt.
- PR #176 nach expliziter Freigabe squash-gemergt; Issue #170 ist geschlossen. `master` und
  `origin/master` stehen auf `352e0436c66e18f3884292c20788bc4dba3d3e83`.
- Board-Sync: 0 hinzugefügt, 0 aktualisiert. Drift-Prüfung: Repository und Project #8 stimmen
  überein. Keine GitHub-Actions und keine Geräteaktion.

## 2026-08-26 — Auswahl Issue #178

- Issue #178 wurde gegen GitHub read-only abgeglichen: offen, Enhancement, keine offene PR und
  kein aktiver Run für `master`.
- Die Roadmap-Übergabe nach abgeschlossenem #177 benennt #178 ausdrücklich als nächstes Paket.
- Code-Prämissen geprüft: `MainActivity.onResume()`/`refresh()` sind der Live-UI-Pfad, ein
  Akku-/Doze-Statuspfad fehlt; der Hauptseiten-Layout- und Lokalisierungspfad ist vorhanden.
- Paket als ein lokales S2-UI-/Systemstatuspaket geschnitten. Keine Delegation, keine
  Implementierung, keine Tests, keine Geräteaktion und keine GitHub-Actions ausgeführt.
- Typisierte nächste Freigaben: Implementierung plus lokale Gates; Android-Gerätenachweis und
  unabhängiger Review separat.
- Der reine Plan-/Lauf-Checkpoint wurde als `a8c2c5c` auf `origin/master` gepusht; kein
  Code-Commit, PR, Merge oder Issue-/Board-Schreibvorgang.

## 2026-08-27 — Issue #178 Implementierung

- Typisierte Freigabe erhalten: Implementierung sowie lokale Tests/Build/Lint; keine
  Geräteabnahme, kein Review und keine GitHub-Actions.
- Umsetzung: `KeepADBBatteryOptimization` liest den Live-Status über `PowerManager`, öffnet
  die Akku-Optimierungseinstellungen und fällt bei fehlendem Intent sicher auf die App-Details
  zurück. `MainActivity` aktualisiert das sichtbare Warnpanel bei jedem `onResume`.
- Hauptseite und alle 19 unterstützten Locale-Dateien enthalten lokalisierte Warntexte sowie
  eine barrierefreie 48-dp-Aktion. Contract-Tests decken Statuspfad, Fallback und Locale-Keys ab.
- Nach defensiver Behandlung einer möglichen `SecurityException` im Akku-Statusgetter bestand
  `./bin/verify` erneut vollständig: Diff-Check, 41 Unit-Tests, Lint sowie Debug-/Release-Build.
- Kein Gerätezugriff, kein unabhängiger Review, kein PR/Merge und kein GitHub-Actions-Run.
- Lokaler Codepfad ist abnahmefähig; echter Systemstatuswechsel, Settings-Rückkehr und
  Hersteller-Fallback bleiben ungeprüft.
- Commit `4406e81` ist auf `origin/feat/178-battery-optimization`; Server-Readback zeigt keinen
  PR und keinen Workflow-Run.

## 2026-08-27 — Issue #178 Review und S20-Abnahme

- Der unabhängige S2-Review reparierte drei scoped Findings: zielgenauer Akku-Ausnahmedialog,
  semantische Warnüberschrift und explizitere Contract-Tests. Bericht:
  `notes/reviews/2026-08-27-issue-178-s2.md`.
- Nach Review: `./bin/verify` grün mit 48 Unit-Tests, Lint sowie Debug-/Release-Build.
- S20/Android 13: Warnung ohne Ausnahme sichtbar; „Akku-Einstellungen öffnen“ öffnete den
  `RequestIgnoreBatteryOptimizations`-Dialog. „Zulassen“ verbarg die Warnung nach Resume,
  Entfernen des kontrollierten Whitelist-Eintrags stellte sie wieder her. `adb_wifi_enabled=1`.
- Review und S20-Gate `approved` im Scope. Android 11–12/14–15 und nicht verfügbarer OEM-
  Fallback ungeprüft. Kein PR/Merge und keine GitHub Actions.

## 2026-08-27 — Issue #178 Cross-Version-Abnahme

- Freigabe für Cross-Version-/OEM-Abnahme geprüft. Verfügbar: S20 Android 13, AVD Android 15
  (Galaxy A6) und AVD Android 16; keine Android-11-/12-/14-Ziele und kein zweites registriertes
  OEM-Gerät.
- Kein zusätzlicher AVD- oder Geräte-Lauf gestartet, weil der Android-15-A6-AVD nicht als
  registriertes Ziel freigegeben ist und der kanonische Emulator außerhalb des Zielbereichs liegt.
- Ergebnis: Android 13 nachgewiesen, Cross-Version-/OEM-Teil nicht vollständig nachweisbar.

## 2026-08-27 — Ausführungsplan Cross-Version-Abnahme #178

- Für einen neuen Agenten wurde ein eigenständig ausführbarer Plan in
  `notes/issue-orchestrator-plan.md` ergänzt.
- Der Plan beschreibt SDK-/AVD-Provisionierung für API 30/31/34, die Testreihenfolge API 30–35,
  Whitelist-/`onResume`-Readbacks, `adb_wifi_enabled`-Kontrolle, Rückbau, Artefakte und klare
  Stop-/Bewertungsregeln.
- SDK-/AVD-Provisionierung wurde in diesem Lauf nicht ausgeführt. OEM-Fallback bleibt ohne
  zweites registriertes Gerät nur statisch belegt.

## 2026-08-27 — Folgeissues für #178

- Nach Duplikatprüfung wurden Issue #180 für die Android-11–15-Testmatrix/AVDs und Issue #181
  für den OEM-Fallback auf einem zweiten Gerät angelegt.
- Beide Issues sind offen, als `enhancement` markiert und in Project #8 auf `backlog` synchronisiert.
- Der abschließende Drift-Readback findet nur den erwarteten ungeprüften Feature-Branch ohne
  gemergten PR; keine Board- oder Issue-Statusabweichung.

## 2026-08-27 — Issue #178 S2-Review und Reparatur

- Freigabe: unabhängiger Review mit Reparatur im benannten Scope, lokale Gates; keine
  Geräteaktion und keine GitHub Actions.
- Reviewbefund: Der bestehende Code öffnete die allgemeine Akku-Optimierungsliste, obwohl die
  Implementierungsbehauptung einen Akku-Optimierungsdialog nannte. Zusätzlich fehlte die
  semantische Screenreader-Überschrift; der Contract-Test prüfte Aufruf- und
  Nichtveränderungsverträge nicht explizit.
- Reparatur: appbezogener `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`-Intent mit `package:`-
  URI, sicherer App-Detail-Fallback unverändert; `android:accessibilityHeading="true"`; Contract-
  Tests für `onResume`, Fallback, Statuspfad und unveränderte WLAN-/Keep-Alive-Semantik ergänzt.
- Gates: Baseline-Contract-Test 20 Tests grün. Erster Verify-Lauf fand einen zu breiten
  statischen Testausschnitt; Test korrigiert. Zweiter `./bin/verify`-Lauf vollständig grün:
  48 Unit-Tests, Lint, Debug-/Release-Build und Diff-Check.
- Rest: keine Geräte-/Laufzeitabnahme (Android 11–15, Settings-Rückkehr, Hersteller-Fallback),
  kein PR/Merge. Reparaturcommit `c1118fe2e3c0bf9766d23b3b449449906d47f456` ist auf dem
  Feature-Branch. Reviewstatus `approved` nur für den lokalen benannten Scope.

## 2026-08-29 — Issue #181 Hardware-Abnahme auf Zweitgerät (rolfphone / CUBOT P60)

- **Gerät:** CUBOT P60 (`P60_EEA`, Android 12 / API 31, Fingerprint `CUBOT/P60_EEA/P60:12/SP1A.210812.016/23140:user/release-keys`) via WLAN-ADB `192.168.178.203:41591`.
- **Register:** `phone-register` verbindlich auf den validierten Fingerprint aktualisiert.
- **Freigabe:** Vorab durch den Meister für die Hardware-Prüfung auf `rolfphone` typisiert erteilt.
- **Durchführung & Verifikation:**
  1. Installation der aktuellen Debug-APK (`app-debug.apk`) via `android-target rolfphone -- install -r` erfolgreich.
  2. Baseline-Prüfung: Bei bestehender Doze-Ausnahme (`user,de.hohnepeople.keepadb,10158`) ist das Banner `battery_optimization_panel` in `MainActivity` vollständig ausgeblendet (`GONE`).
  3. Non-Exempt-Zustand: KeepADB temporär aus der Whitelist entfernt (`cmd deviceidle whitelist -de.hohnepeople.keepadb`) -> Banner `Akkuoptimierung kann Keep-Alive einschränken` mit Button `AKKU-EINSTELLUNGEN ÖFFNEN` wird auf `MainActivity` sichtbar (`VISIBLE`).
  4. Intent-Dispatch: Klick auf den Button löst `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` aus und öffnet direkt den Systemdialog `com.android.settings/.fuelgauge.RequestIgnoreBatteryOptimizations` ("Soll die App immer im Hintergrund ausgeführt werden?") ohne Exception oder Absturz.
  5. Gewährung & Live-Refresh: Nach Bestätigung ("ZULASSEN") wird KeepADB in die Whitelist eingetragen und das Banner verschwindet bei Rückkehr zur `MainActivity` (`onResume`) sofort.
  6. State-Konsistenz & Cleanup: `adb_wifi_enabled` blieb über den gesamten Testzyklus unverändert (`1`); Whitelist-Zustand sauber wiederhergestellt (`user,de.hohnepeople.keepadb,10158`).
- **Status:** `APPROVED` — Issue #181 vollständig verifiziert.

## 2026-08-29 — Dauerhafte Release-Signatur in Vaultwarden verifiziert

- Die einzige produktive Upstream-Signieridentität für `de.hohnepeople.keepadb` wurde als
  Vaultwarden-Eintrag `android/keepadb-signing` bestätigt und um Paket-, Kanal-, Gültigkeits-,
  Fingerprint-, Wiederherstellungs- und Rotationsangaben ergänzt.
- Der Eintrag enthält den vollständigen PKCS#12-Keystore, Alias sowie Store- und Key-Passwort;
  Secret-Werte wurden weder ausgegeben noch im Repository gespeichert.
- Der aus Vaultwarden temporär wiederhergestellte Keystore war bytegleich zur geschützten
  lokalen Referenz. Zertifikatsfingerprint und Signatur des veröffentlichten GitHub-APKs
  `v1.1.0` stimmten überein.
- Zertifikat SHA-256:
  `C5:2B:CD:17:1B:5C:CE:E8:87:F3:C1:6C:C3:A4:74:B3:8B:D9:CC:D7:71:CA:8C:D9:92:F8:2E:4D:17:75:3C:04`;
  gültig bis 2054-01-08.
- Die dauerhafte Betriebs- und Wiederherstellungsdokumentation liegt in
  `docs/release-signing.md`. Es wurden kein Schlüssel erzeugt oder rotiert, kein Release
  gestartet und keine GitHub Action ausgelöst.

## 2026-08-30 — Lokaler 1.3.0-Vorabtest auf S20 und rolfphone

- Versionsstand von 1.2.0/Code 3 auf 1.3.0/Code 4 angehoben; Changelog-Eintrag bleibt als
  `Unreleased` gekennzeichnet, da kein Release gestartet wurde.
- `./bin/verify` erfolgreich mit Unit-Tests, Lint sowie Debug-/Release-Build.
- Debug-APK per `install -r` auf S20 (SM-G780G, Android 13) und rolfphone (CUBOT P60,
  Android 12) installiert; beide Readbacks bestätigen 1.3.0/Code 4.
- Shared-Prefs und Diagnosedaten vorab gesichert; keine Deinstallation oder Datenlöschung.
- Keine UI-/Funktionsabnahme, kein Release, kein Tag und kein GitHub-Action-Run.

## 2026-08-30 — Rückwirkende Issue-Versionierung und Changelog-Regel

- Die Nutzerregel „nach jedem umgesetzten Issue Patch oder Minor bumpen und den Changelog
  pflegen“ wurde umgesetzt.
- Seit `v1.2.0` wurden die abgeschlossenen Issues rückwirkend als Implementierungsfolge
  `#192 → 1.2.1`, `#193 → 1.3.0`, `#196 → 1.3.1`, `#197 → 1.3.2`, `#198 → 1.3.3` und
  `#200 → 1.3.4` dokumentiert.
- Der aktuelle Buildstand ist `versionName 1.3.4` / `versionCode 9`; `CHANGELOG.md` bleibt
  `Unreleased`. Separate Tags, Releases und GitHub-Actions wurden nicht ausgelöst.
- `git diff --check` erfolgreich. Gradle-Verify nicht ausgeführt, da für Tests/Builds in diesem
  Lauf keine typisierte Freigabe vorlag.

## 2026-08-30 — PR #206 Verify und Review

- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./bin/verify` vollständig erfolgreich: Diff-Check,
  Unit-Tests, Lint sowie Debug-/Release-Build.
- Der enge S1-Review des PR-Diffs fand keine Befunde. Die Versionierungsfolge und die
  rückwirkende Kennzeichnung sind konsistent.
- PR #206 bleibt offen und ist laut GitHub `CLEAN`, meldet aber keine Checks. Kein Merge,
  Release oder GitHub-Actions-Run ausgeführt.

## 2026-08-30 — PR #206 Mergeabschluss

- PR #206 wurde nach ausdrücklicher Freigabe per Squash gemergt; Merge-Commit `eedddba` liegt
  auf `master`. Der Feature-Branch wurde entfernt.
- `master` und `origin/master` sind synchron. Board-Sync und Drift-Readback sind sauber.

## 2026-08-30 — Roadmap auf alle offenen Issues erweitert

- GitHub-Abgleich: #203, #204 und #205 offen; keine offenen PRs.
- Reihenfolge festgelegt: #203 Versionsanzeige, #204 Settings-Reihenfolge, #205
  Ressourcen-/Literal-Audit. #204 hängt fachlich an #203; #205 folgt nach den UI-Änderungen.
- Die übergeordnete Versionierungsregel überschreibt die entgegenstehenden Issue-Nicht-Ziele
  zur Versionsanhebung.
- Keine Implementierung, kein Build, kein Gerätetest und keine GitHub Action ausgeführt.
