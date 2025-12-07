# Development & CI

- GitHub Actions: `.github/workflows/ci.yml`
  - `spotlessCheck` (ktlint)
  - `test` (unit + headless integration)
  - `fabricSmoke` and `neoforgeSmoke` (jar metadata sanity)
- Local one-liner: `./gradlew check` (runs spotless + tests + smokes)
