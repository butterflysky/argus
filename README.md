# Argus

Kotlin-based, cache-first access control for Minecraft 1.21.x with Discord identity linking. Argus now uses a multi-loader layout (`common`, `fabric`, `neoforge`) and Javacord for Discord integration.

## Status
- **Current scope:** Scaffolded to match `ARGUS_MASTER_SPEC.md`. Core cache, config loader, and Fabric wiring are in place; Discord bot logic and NeoForge entrypoint are still placeholders.

## Architecture
- `:common` — platform-agnostic logic: JSON cache (`config/argus_db.json` + `.bak`), permission gate, config loader, and token service.
- `:fabric` — Fabric entrypoint; hooks join events to enforce the permission gate and stubs `/argus reload`.
- `:neoforge` — placeholder module that depends on `:common`.

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
# NeoForge run target will be added when the scaffold is fleshed out
```

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
