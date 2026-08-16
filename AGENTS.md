# AGENTS.md

## Quality gate

CI runs `./gradlew assembleDebug lintDebug ktlintCheck testDebugUnitTest` and
`./tests/run-local.sh` on every push to main and every PR — failing means
blocked. After changing Kotlin code, match the gate before claiming done:

- `./gradlew ktlintCheck` — style (android ruleset via `.editorconfig`);
  auto-fix with `./gradlew ktlintFormat`
- `./gradlew lintDebug` — Android lint errors block (`abortOnError`)
- `./gradlew testDebugUnitTest` — JVM unit tests (JUnit4 + Robolectric + mockk)
- `./tests/run-local.sh` — JS polyfill tests + exec-hook C tests (node + gcc)

## Build

- Requires JDK 17+ and Android SDK; the runtime snapshot
  `app/src/main/assets/snapshot.tar.xz` must exist (CI downloads it from the
  upstream release)
- `assembleRelease` is CI-only — the signing keystore lives in the repo
  secret, never run it locally
