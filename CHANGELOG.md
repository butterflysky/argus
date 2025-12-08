# Changelog

All notable changes to this project will be documented in this file.

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

## [0.2.6] - 2025-12-08
- Added NeoForge runtime smoke task and CI wiring.

## [0.2.5] - 2025-12-07
- Release housekeeping.

## [0.2.4] - 2025-12-07
- Docs: README streamlined, setup docs reorganized, unnumbered overview.

## [0.2.3] - 2025-12-07
- Fixed NeoForge mods.toml expansion and command registration bus.

[Unreleased]: https://github.com/butterflysky/argus/compare/v1.0.3...HEAD
[1.0.3]: https://github.com/butterflysky/argus/compare/v1.0.2...v1.0.3
[1.0.2]: https://github.com/butterflysky/argus/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/butterflysky/argus/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/butterflysky/argus/compare/v0.2.6...v1.0.0
[0.2.6]: https://github.com/butterflysky/argus/compare/v0.2.5...v0.2.6
[0.2.5]: https://github.com/butterflysky/argus/compare/v0.2.4...v0.2.5
[0.2.4]: https://github.com/butterflysky/argus/compare/v0.2.3...v0.2.4
[0.2.3]: https://github.com/butterflysky/argus/compare/v0.2.2...v0.2.3
