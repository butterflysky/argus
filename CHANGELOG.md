# Changelog

All notable changes to this project will be documented in this file.

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

[Unreleased]: https://github.com/butterflysky/argus/compare/v0.2.7...HEAD
[1.0.0]: https://github.com/butterflysky/argus/compare/v0.2.6...v1.0.0
[0.2.6]: https://github.com/butterflysky/argus/compare/v0.2.5...v0.2.6
[0.2.5]: https://github.com/butterflysky/argus/compare/v0.2.4...v0.2.5
[0.2.4]: https://github.com/butterflysky/argus/compare/v0.2.3...v0.2.4
[0.2.3]: https://github.com/butterflysky/argus/compare/v0.2.2...v0.2.3
