# Argus

Argus keeps your Minecraft server tied to your Discord: every player must be linked to a Discord user who holds your whitelist role.

Core abilities
- **Account linking**: `/link` flow issues short-lived tokens to connect Minecraft and Discord identities; OPs are prompted too (but always allowed).
- **Whitelist enforcement**: cache-first login, with a one-time Discord refresh only when cache would deny a linked player; legacy vanilla-whitelisted-but-unlinked are kicked with a link token.
- **Role drift detection**: post-join live role check removes/denies access if the whitelist role is missing.
- **Applications queue**: `/whitelist apply` lets Discord members request access with Minecraft name validation; admins can list/approve/deny from Discord.
- **Moderator notes & history**: warnings, bans, unbans, comments, approvals/denials, first-seen, legacy kicks, role loss, and name/nick changes are all logged.
- **Audit logging**: every cache mutation and key event logs to console and (optionally) a Discord audit channel.
- **Safety**: if Discord/config is unavailable, Argus stays cache-only and never blocks startup; cache writes keep a `.bak`.

Platform
- Minecraft 1.21.10
- Fabric and NeoForge loaders
- Discord via Javacord slash commands
