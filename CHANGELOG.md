# Changelog

All notable changes to this project will be documented in this file.

## [1.0.9] - 2025-12-08
- Strip signature files when bundling Javacord to prevent “Invalid signature file digest” on Fabric/NeoForge.
- Packaging-only change; logic unchanged.

## [1.0.10] - 2025-12-08
- Stop shading Kotlin stdlib into the jars (it’s provided by fabric-language-kotlin / NeoForge runtime); fixes `NoSuchMethodError: DurationKt.toDuration`.
- Added jar-level smoke test that boots ArgusCore from the remapped jar with only production deps (catches packaging slips).
- Packaging-only; logic unchanged.

## [1.0.11] - 2025-12-08
- Added graceful Discord shutdown on server stop (Fabric + NeoForge) to prevent long exit hangs.
- `/argus reload` now fully restarts Discord (stop + start) instead of only reloading config.
- No functional gameplay changes.

## [1.0.8] - 2025-12-08
- Embedded Javacord (and its deps) inside the Fabric jar so Discord starts even on servers without external libs.
- Retained bundled common classes; NeoForge packaging unchanged.
- No gameplay/logic changes—packaging fix only.

## [1.0.7] - 2025-12-08
- Attempted to bundle Javacord into both Fabric and NeoForge jars; NeoForge succeeded but Fabric was missing it.
- No behavior changes; packaging intent only.

## [1.0.6] - 2025-12-08
- Included common classes in both Fabric and NeoForge jars (validated contents) to avoid NoClassDefFoundError on prod.
- No code logic changes; packaging fix only.

## [1.0.5] - 2025-12-08
- Bundle shared common classes into the Fabric jar (fixes NoClassDefFoundError for ArgusCore on prod).
- Prevent duplicate resources in Fabric jar; keep headless smokes passing on fabric-api 0.135.0.

## [1.0.4] - 2025-12-08
- Corrected jar stamping: fabric/neoforge artifacts now carry version 1.0.4 (placeholder now expands during resource processing).
- Fabric smoke regenerated launch config after cache wipes and still supports fabric-api 0.135.0.

## [1.0.3] - 2025-12-08
- Fixed Fabric version placeholder expansion so Loader sees the real mod version (no more `${version}` warning).
- Made Fabric smoke depend on launch config generation and use isolated workdir; verified successful smoke with `fabric-api 0.135.0+1.21.10`.
- Kept declared minimums (`fabric-api >= 0.135.0`, `fabric-language-kotlin >= 1.13.6`) while defaulting to current tested set.

## [1.0.2] - 2025-12-08
- Declared Fabric dependency floors: `fabric-api >= 0.135.0`, `fabric-language-kotlin >= 1.13.6`.
- Hardened Fabric smoke run: isolated temp run dir, forced headless/`--nogui`, fixed port binding to 25765 to avoid collisions, and auto-writes config/eula/server.properties for CI.

## [1.0.1] - 2025-12-08
- Added Fabric runtime smoke test with auto-stop and EULA provisioning; wired into `check`.
- Ensured smoke runs don’t hang: shared stop flag, headless run, and explicit server props.
- Version bump housekeeping.

## [1.0.0] - 2025-12-08
- Added enforcement toggle (`enforcementEnabled`, default false) for dry-run: log would-be actions without kicking/denying.
- Distinguish Discord outcomes: NotInGuild vs MissingRole vs transient errors; revoke (and log) when leaving guild/losing role; skip changes on timeouts.
- Clarified NeoForge smokes (`neoforgeJarSmoke`, `:neoforge:neoforgeRunSmoke`) and wired runtime smoke into CI/release.
- Shared icon moved to `:common` and included in both loader jars.

## 0.2.6 - 2025-12-08
- Added NeoForge runtime smoke task and CI wiring.

## 0.2.5 - 2025-12-07
- Release housekeeping.

## 0.2.4 - 2025-12-07
- Docs: README streamlined, setup docs reorganized, unnumbered overview.

## 0.2.3 - 2025-12-07
- Fixed NeoForge mods.toml expansion and command registration bus.

[Unreleased]: https://github.com/butterflysky/argus/compare/v1.0.7...HEAD
[1.0.7]: https://github.com/butterflysky/argus/compare/v1.0.6...v1.0.7
[1.0.6]: https://github.com/butterflysky/argus/compare/v1.0.5...v1.0.6
[1.0.5]: https://github.com/butterflysky/argus/compare/v1.0.4...v1.0.5
[1.0.4]: https://github.com/butterflysky/argus/releases/tag/v1.0.4
[1.0.3]: https://github.com/butterflysky/argus/releases/tag/v1.0.3
[1.0.2]: https://github.com/butterflysky/argus/releases/tag/v1.0.2
[1.0.1]: https://github.com/butterflysky/argus/releases/tag/v1.0.1
[1.0.0]: https://github.com/butterflysky/argus/releases/tag/v1.0.0
