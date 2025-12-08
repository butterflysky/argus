# Changelog

All notable changes to this project will be documented in this file.

## [0.2.7] - 2025-12-08
- Distinguish Discord outcomes: NotInGuild vs MissingRole vs transient errors; revoke access and log when players leave the guild; keep cache on timeouts.
- Added jar/runtime smoke names clarity (`neoforgeJarSmoke`, `:neoforge:neoforgeRunSmoke`) and wired runtime smoke into CI/release.
- Shared icon moved to `:common` and included in both loader jars.

## [0.2.6] - 2025-12-08
- Added NeoForge runtime smoke task and CI wiring.

## [0.2.5] - 2025-12-07
- Release housekeeping.

## [0.2.4] - 2025-12-07
- Docs: README streamlined, setup docs reorganized, unnumbered overview.

## [0.2.3] - 2025-12-07
- Fixed NeoForge mods.toml expansion and command registration bus.

[Unreleased]: https://github.com/butterflysky/argus/compare/v0.2.7...HEAD
[0.2.7]: https://github.com/butterflysky/argus/compare/v0.2.6...v0.2.7
[0.2.6]: https://github.com/butterflysky/argus/compare/v0.2.5...v0.2.6
[0.2.5]: https://github.com/butterflysky/argus/compare/v0.2.4...v0.2.5
[0.2.4]: https://github.com/butterflysky/argus/compare/v0.2.3...v0.2.4
[0.2.3]: https://github.com/butterflysky/argus/compare/v0.2.2...v0.2.3
