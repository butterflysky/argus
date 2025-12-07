# Configuration at a glance

**Where:** `config/argus.json` (created on first run)

**Key fields**
- `botToken`, `guildId`, `whitelistRoleId`, `adminRoleId` — required for Discord enforcement.
- `logChannelId` — optional audit channel.
- `applicationMessage` — text shown to strangers/denied users (invite link is appended if `discordInviteUrl` is set).
- `cacheFile` — leave default unless you have a custom path.

**Set and view (in-game)**
- `/argus config get <field>`
- `/argus config set <field> <value>` (tab-complete shows fields and allowed values)
- `/argus reload` to apply changes.

**Runtime rules**
- Cache-first login; one short Discord refresh only if cache would deny a linked player.
- Live role check after join; kicks if whitelist role is missing, updates cache/audit.
- OPs always bypass; if Discord/config is down, Argus stays cache-only.
- Legacy vanilla-whitelisted but unlinked are denied with a link token.
- Bans: active bans deny; expired allow; permanent bans deny.

**Audit**
- Every cache mutation logs to console and (if set) to the Discord audit channel with names + IDs: link/approve/deny/warn/ban/unban/comments/role loss/first login/legacy kick/name or nick changes.
