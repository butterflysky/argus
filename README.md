# Argus

[![CI](https://github.com/butterflysky/argus/actions/workflows/ci.yml/badge.svg)](https://github.com/butterflysky/argus/actions/workflows/ci.yml) [![Docs](https://github.com/butterflysky/argus/actions/workflows/pages.yml/badge.svg)](https://github.com/butterflysky/argus/actions/workflows/pages.yml) [![Release](https://img.shields.io/github/v/release/butterflysky/argus?logo=github&label=release)](https://github.com/butterflysky/argus/releases)

Cache-first Discord-linked whitelist for Minecraft 1.21.10 (Fabric & NeoForge). Argus ties each Minecraft player to a Discord user with a whitelist role, handles applications, and logs every decision.

Changelog: see [CHANGELOG.md](CHANGELOG.md).

**Features**
- Discord-linked access: `/whitelist apply` queue, Mojang-validated names, approvals/denials, and token-based linking.
- Enforcement: cache-first login, one-off Discord refresh only when cache would deny; legacy vanilla-whitelist kicks with link tokens; live check after join. If a player leaves the Discord guild or loses the whitelist role, access is revoked and logged.
- Audit & history: logs for links, approvals/denials, warnings/bans, comments, first-seen, legacy kicks, role loss, guild departure, name/nick changes—console plus optional Discord audit channel.
- Resilient: falls back to cache on Discord timeouts/unavailability; never blocks server startup; keeps `.bak` of the cache file.
- Multi-loader: Fabric and NeoForge builds from one codebase.

**Compatibility & downloads**
- Minecraft: 1.21.10
- Loaders: Fabric, NeoForge
- Releases: `argus-1.21.10-<version>-fabric.jar` and `argus-1.21.10-<version>-neoforge.jar` on the [Releases](https://github.com/butterflysky/argus/releases) page.

**Setup (quick)**
- Drop the jar for your loader into `mods/`.
- Start once to generate `config/argus.json`.
- Fill `botToken`, `guildId`, `whitelistRoleId`, `adminRoleId`, and optional `logChannelId` / `discordInviteUrl`.
- In-game: `/argus config set <field> <value>`, `/argus reload`; players get link tokens via `/token`, complete with `/link` in Discord.

**Docs**
- User & admin guide: https://butterflysky.github.io/argus/
- Build locally: `mdbook build book` (outputs to `docs-book/`).

**Build & test (dev)**
```bash
./gradlew check                # spotlessCheck + unit tests + Fabric/NeoForge smoke
./gradlew :fabric:runServer    # dev server (Fabric)
./gradlew :neoforge:runServer  # dev server (NeoForge)
```

**Contributing**
- Issue tracking via `bd` (see `AGENTS.md`).
- Kotlin preferred; target Java/Kotlin 21; keep dependency versions pinned in `gradle.properties`.
