# Argus

Kotlin-based, cache-first access control for Minecraft 1.21.10 with Discord identity linking. Argus uses a multi-loader layout (`common`, `fabric`, `neoforge`) and Javacord for Discord integration.

## Status
- Core cache, config loader, login gate, and Discord slash-command link flow are implemented in `:common` and wired to Fabric.
- NeoForge entrypoint now compiles against NeoGradle (21.10.64) and forwards login/join to shared logic.
- Multi-loader builds pass for 1.21.10; further version-matrix support is planned.

## Architecture
- `:common` — platform-agnostic logic: JSON cache (`config/argus_db.json` + `.bak`), permission gate, config loader, Javacord bot, and token service.
- `:fabric` — Fabric entrypoint; hooks login/join events and exposes `/argus`/`/token` commands.
- `:neoforge` — entrypoint compiled with NeoGradle userdev; forwards login/join to `ArgusCore`.

## Login Rules (spec)
- OPs bypass checks.
- Linked users must have `hasAccess` in the cache.
- Legacy vanilla-whitelisted users get a temporary allow + kick with a `!link <token>` message.
- Strangers are denied with `applicationMessage` from config.
- Cache is the only source used during login; no Discord calls on the login thread.

## Build & Run
```bash
./gradlew build              # build all modules
./gradlew :fabric:runServer  # dev server (Fabric)
# NeoForge run target can be added via NeoGradle run configs when needed
```

## Documentation
Static docs (HTML) live in `docs/` and can be served as a simple static site. Start at `docs/index.html` for setup, commands, and playtest checklist.

## Configuration
`config/argus.json` is created on first run (or via `ArgusCore.initialize()`):
```json
{
  "botToken": "",
  "guildId": null,
  "whitelistRoleId": null,
  "adminRoleId": null,
  "logChannelId": null,
  "applicationMessage": "Access Denied: Please apply in Discord.",
  "cacheFile": "config/argus_db.json"
}
```

## Cache Safety
- Reads are from `cacheFile` only.
- Saves rename the previous file to `.bak`; loads fall back to `.bak` if the primary fails.

## Contributing
- Use `bd` for issue tracking (see `AGENTS.md`).
- Prefer Kotlin; target Java/Kotlin 21.
- Keep dependency versions pinned in `gradle.properties` (update intentionally).
