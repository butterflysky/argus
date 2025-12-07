# Playtest Checklist

- Configure `config/argus.json` (token, guildId, whitelistRoleId, adminRoleId, logChannelId optional invite).
- Ensure `config/argus_db.json` exists (auto-created).
- Start Fabric dev server: `./gradlew :fabric:runServer`.
- Invite the bot with application commands + members intents.

Scenarios to exercise (headless equivalents covered in tests):
- Unconfigured Discord: server starts; login uses cache only.
- Application flow: `/whitelist apply` → list & approve/deny via buttons.
- Ban/warn/comment/review: ensure visibility and user-facing `/whitelist my`.
- Legacy unlinked: vanilla-whitelisted user is denied with link token.
- Role removal: user logs in (cache allow), join hook kicks if whitelist role missing and updates cache/audit.
- Cache deny with live refresh: cache says no, Discord role says yes → login refresh allows.
- Link token reuse: reconnect quickly; same token is reused.
