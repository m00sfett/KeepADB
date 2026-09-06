# Contributing to KeepADB

Thanks for considering a contribution. KeepADB is a small, focused, zero-runtime-dependency
Android app, and it intentionally keeps Wireless Debugging available — please read
[SECURITY.md](SECURITY.md) as well if your change touches networking, permissions, or the
Keep-Alive/recovery logic.

## Setup

- **JDK 17** (the project pins `JAVA_HOME` for release builds via `bin/gradlew`; make sure a
  JDK 17 is available, e.g. at `/usr/lib/jvm/java-17-openjdk`, or export `JAVA_HOME` yourself).
- **Android SDK** with `compileSdk 35`, `build-tools 34.0.0`, and `minSdk 30` installed.
- Clone the repo and open it in Android Studio, or work from the command line with the Gradle
  wrapper (`./gradlew`, or `./bin/gradlew` for release builds — it sets `JAVA_HOME` for you).

## Verification

Before opening a pull request, run the full local verification gate:

```bash
./bin/verify
```

This checks (in order): a clean git diff (no trailing whitespace/conflict markers), the i18n
copy-paste check (`bin/check-i18n`), unit tests + lint + a debug build, and a release build.
All of these must pass — CI runs the same checks automatically on every push and pull request.

To run an individual step instead of the full gate:

```bash
./gradlew testDebugUnitTest   # unit tests
./gradlew lintDebug           # lint
./gradlew assembleDebug       # debug build
python3 bin/check-i18n        # i18n copy-paste check
```

## Coding conventions

- **Zero runtime dependencies.** The app ships with no third-party libraries — only Android
  platform APIs and JUnit for tests. A change that would add a runtime dependency (DI
  framework, HTTP client, etc.) needs a strong justification and should be discussed in an
  issue first.
- **Contract tests.** Because the project has no Robolectric or Mockito, many invariants that
  would otherwise need a real Android environment are instead protected by `*ContractTest.java`
  files under `app/src/test`: they read source/resource files as text and assert structural
  patterns (e.g. "every `State` value is handled in this switch", "this permission-guarded
  receiver has no unguarded call path"). If you touch code that has a contract test, keep the
  test passing or update it deliberately — don't just delete the assertion.
- **New user-facing strings** go in `app/src/main/res/values/strings.xml` first, then into
  *every* `values-*/strings.xml` locale with a real translation (not a copy of the English
  text) — `KeepADBResourceContractTest` enforces that every locale has the same key set, and
  `bin/check-i18n` catches values left identical to the English original.
- **Static mutable state and locking.** `KeepADB.java` in particular uses static state with
  deliberate, documented locking and generation-token logic to survive rapid toggles, process
  death, and recovery pulses (see the `#168` comment there). If your change touches it, explain
  the reasoning the same way — this file has caused real, hard-to-reproduce bugs before.

## Testing

Add or extend a test alongside your change wherever the existing suite has a natural place for
it (`app/src/test/java/de/hohnepeople/keepadb/`). Prefer a plain JUnit test with a small
hand-written fake (see the various `FakeContext`/`MemoryPreferences` helpers already in the
test sources) over adding a new test framework dependency.

## Translations

KeepADB ships 19 locales. To add or fix a translation:

1. Edit the relevant `app/src/main/res/values-<locale>/strings.xml` (create the directory if
   the locale doesn't exist yet, using an existing locale as a template for the full key set).
2. Run `python3 bin/check-i18n` to make sure nothing was left as a copy of the English text.
3. If a value is *legitimately* identical to English in your language (a loanword, a technical
   term, a pure format string), add it to the `ALLOWLIST` in `bin/check-i18n` with a short
   justification comment, rather than silently ignoring the check's finding.

## Pull requests

- Keep changes focused; split unrelated changes into separate PRs.
- Run `./bin/verify` locally before opening the PR.
- Describe the security impact of your change if it touches permissions, network behavior,
  backup/data-extraction rules, or the Keep-Alive/recovery state machine.
- Reference the issue your PR addresses, if any.
