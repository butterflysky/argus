# Build & CI

Local commands
- `./gradlew check` — spotlessCheck + unit tests + smoke builds for Fabric/NeoForge.
- `./gradlew :fabric:build` / `:neoforge:build` — produce loader-specific jars.
- `./gradlew spotlessApply` — format code.

CI workflows (GitHub Actions)
- `ci.yml` — runs check and builds docs.
- `pages.yml` — builds and publishes mdBook to GitHub Pages.
- `release.yml` — on tags `v*` builds artifacts and attaches Fabric/NeoForge jars; optional Modrinth/CurseForge publish if secrets provided.

Artifacts
- Fabric jar: `fabric/build/libs/argus-<mc>-<version>-fabric.jar`
- NeoForge jar: `neoforge/build/neoforge/<mc>/argus-<mc>-<version>-neoforge.jar`
