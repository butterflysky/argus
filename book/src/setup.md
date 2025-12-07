# Setup (Server Operator)

1) Install the mod
- Fabric: drop the Fabric jar from releases into `mods/`.
- NeoForge: drop the NeoForge jar into `mods/`.

2) Start once to generate `config/argus.json`.

3) Fill `config/argus.json`:

| Field | Description |
| --- | --- |
| botToken | Discord bot token (leave blank to disable Discord; server still runs) |
| guildId | Discord server ID |
| whitelistRoleId | Role granting access |
| adminRoleId | Role allowed to run admin commands |
| logChannelId | Channel for audit logs (optional) |
| applicationMessage | Deny message for unapproved users |
| cacheFile | Path to cache JSON (default `config/argus_db.json`) — usually leave default |
| discordInviteUrl | Optional invite link appended to deny messages |

4) Configure from in-game (optional)
- `/argus config get <field>`
- `/argus config set <field> <value>` (tab-complete fields/allowed values)
- `/argus reload` to apply changes.

5) Whitelist behavior
- If vanilla whitelist is ON: Argus enforces Discord link + role; legacy whitelisted but unlinked players are denied with a link token; strangers get the application message.
- If vanilla whitelist is OFF: everyone is allowed (OPs always bypass).

Safety notes
- Cache writes keep a `.bak`; loads fall back to `.bak` if the main file fails.
- If Discord/config is unavailable, Argus stays cache-only and never blocks startup.
