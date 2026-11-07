# Contributing to KeepADB

Thank you for your interest. Before changing permissions, network behavior, notifications, or
Keep-Alive, also read the [security policy](SECURITY.md).

## Development environment

The standard local verification requires:

- JDK 17
- Android SDK Platform 35
- Android SDK Build Tools 35.0.0
- Android 11 or newer as the minimum target (minSdk 30)

The app is built with Java 17, compileSdk 35, and targetSdk 35. Use `./bin/gradlew` for Gradle
commands; the wrapper selects JDK 17 unless `JAVA_HOME` has already been explicitly set.

## Local verification

Run the full project gate before opening a pull request:

~~~sh
./bin/verify
~~~

The script runs `git diff --check`, translation checks with `bin/check-i18n`, unit tests, lint,
debug and release builds, and debug/release markers in the generated APKs.

Individual steps can be run with the wrapper:

~~~sh
./bin/gradlew testDebugUnitTest
./bin/gradlew lintDebug
./bin/gradlew assembleDebug
python3 bin/check-i18n
~~~

## Runtime and test dependencies

**The shipped app has no third-party runtime dependencies.** The only third-party libraries in
`app/build.gradle` are under `testImplementation`: JUnit, Robolectric, and `androidx.test:core`.
They are used for tests and are not included in the release app.

Tests in `app/src/test/java` include both Android-free unit tests and Robolectric tests with
Android resources. Contract tests protect invariants in the manifest, resources, and call paths.
Change a check deliberately when its contract really changes; do not remove an assertion merely
because an implementation no longer satisfies it.

## Code conventions

- Add runtime dependencies to the app code only when there is a justified need; use the existing
  JUnit and Robolectric support for tests.
- Add or update checks under `app/src/test/java/de/hohnepeople/keepadb`. Contract tests protect
  manifest, resource, and call-path rules; remove an assertion only when the checked contract has
  deliberately changed.
- `KeepADB.java` contains shared state, generation tokens, and locks. Changes to these paths
  should explain the required ordering and synchronization next to the code.

## Translations and visible text

KeepADB has 19 language variants. Add new text to `app/src/main/res/values/strings.xml` first,
then translate it in every existing `values-*/strings.xml` file.
`KeepADBResourceContractTest` checks key sets; `bin/check-i18n` reports strings copied
word-for-word from the English reference value. Identical brand or technical terms are allowed
only with a justified exception in the script's allowlist.

## CI and release toolchain

`.github/workflows/ci.yml` runs automatically for changes to `master`, for pull requests, and
manually through `workflow_dispatch`. The workflow uses JDK 17, Android Platform 35, Build Tools
35.0.0, and runs `./bin/verify`.

The separate release workflow is triggered by tags prefixed with `v`. Its build and signing path
uses JDK 21; signing invokes `apksigner` from Android Build Tools 34.0.0. That apksigner version
is part of the signing path and does not replace the app project's Build Tools 35.0.0 setting. A
successful local build or CI run alone does not prove that two independently signed store artifacts
are byte-identical. A tag prefixed with `v` can trigger the real release workflow; create or push
one only after explicit release approval. A release publishes artifacts and starts the planned
F-Droid path.

## Documentation check before a stable release

Store metadata and documentation ship with the release tag, so fix drift before tagging, not after.
Check each point against the code of the release commit:

1. `fastlane/metadata/android/en-US/full_description.txt` and `short_description.txt` describe the
   current features and the security defaults recorded in `docs/defaults.md` (network rule,
   Keep-Alive, webhook, notification details). Remove features that no longer exist.
2. `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` summarizes the user-visible
   changes since the last stable release.
3. `CHANGELOG.md`: the release-status paragraph and the section heading describe the version being
   released, and no released version is still called a candidate.
4. Every Settings path or label quoted in `README.md`, `SECURITY.md`, and `docs/` matches
   `app/src/main/res/values/strings.xml`.
5. `docs/README.md` links every file under `docs/`.
6. Statements about external state (F-Droid metadata, merge requests, website links in
   `docs/package-id.md`) are current.

## Pull requests

- Keep each change focused on one clear purpose.
- Link the issue being addressed, if there is one.
- Run `./bin/verify` before opening the pull request.
- Describe security and privacy effects when permissions, network traffic, backups, or recovery are
  affected.
