# Setup

1. Build
   ```bash
   ./gradlew build
   ```
2. Fabric dev server (example)
   ```bash
   ./gradlew :fabric:runServer
   ```
3. Config file: `config/argus.json` is created on first run. Fill:

| Field | Description |
| --- | --- |
| botToken | Discord bot token (leave blank to disable Discord; server still runs) |
| guildId | Discord server ID |
| whitelistRoleId | Role granting access |
| adminRoleId | Role allowed to run admin commands |
| logChannelId | Channel for audit logs (optional) |
| applicationMessage | Deny message for unapproved users |
| cacheFile | Path to cache JSON (default `config/argus_db.json`) |
| discordInviteUrl | Optional invite link appended to deny messages |

4. Cache safety: writes rename the old file to `.bak`; loads fall back to `.bak` if the main file fails.
5. Fail-safe startup: if Discord is unconfigured/down, Argus skips the bot and stays cache-only.
