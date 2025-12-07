# Formatting & Style

- Spotless with ktlint is enforced in CI (`./gradlew spotlessCheck`).
- Run `./gradlew spotlessApply` before committing to fix style issues.
- Java/Kotlin target 21; prefer Kotlin for new code, keep imports ordered (stdlib, third-party).
- Avoid unchecked warnings; address `@Deprecated` usages promptly.
