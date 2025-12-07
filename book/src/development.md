# Development & CI

- GitHub Actions: `.github/workflows/ci.yml`
  - `spotlessCheck` (ktlint)
  - `test` (unit + headless integration)
  - `fabricSmoke` and `neoforgeSmoke` (jar metadata sanity)
- Pages deploy: `.github/workflows/pages.yml` builds mdBook and publishes to GitHub Pages.
- Local one-liner: `./gradlew check` (runs spotless + tests + smokes)
- Local docs: `mdbook build book` (outputs to `docs-book/`, ignored)
