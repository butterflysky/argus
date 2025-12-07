# Discord Application & Bot Setup

Follow these steps once. They give you the values for `botToken`, `guildId`, `whitelistRoleId`, `adminRoleId`, and `logChannelId`.

## 1) Create the application
1. Open https://discord.com/developers/applications and click **New Application** → name it (e.g., “Argus”).
2. In **Bot** panel, click **Add Bot** → **Reset Token** → copy the token for `botToken`. Keep it secret.

## 2) Enable intents
Argus only needs the **Server Members Intent** to read roles for linked users. In *Bot*:
- **SERVER MEMBERS INTENT**: ON
- **MESSAGE CONTENT INTENT**: OFF (not needed)
- **PRESENCE INTENT**: OFF (not needed)

## 3) Generate the invite URL
1. In **OAuth2 → URL Generator** select scopes **bot** and **applications.commands**.
2. Permissions (minimal):
   - Send Messages
   - Embed Links
   - Read Message History
   - Use Slash Commands
   - (Optional) Create Public Threads / Send Messages in Threads if your audit channel is thread-only.
3. Copy the generated URL and open it to invite the bot to your Discord server.

## 4) Collect IDs (turn on Developer Mode)
Settings → Advanced → **Developer Mode** ON, then right‑click and **Copy ID** for:
- **guildId** — your server ID.
- **whitelistRoleId** — role that grants Minecraft access.
- **adminRoleId** — role allowed to approve/deny/revoke via commands.
- **logChannelId** — text channel for audit logs (optional).

## 5) Fill `config/argus.json`
Use the IDs from above plus your bot token. You can set them:
- By editing the file, or
- In-game: `/argus config set <field> <value>` (tab-complete shows fields), then `/argus reload`.

If you want your deny message to include a clickable invite, add `discordInviteUrl` with an invite link to your server.
