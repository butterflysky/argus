# What is Argus?

Argus links Discord roles to Minecraft server access. It keeps the login flow safe and fast by using a local cache and small, bounded Discord checks.

Highlights
- Cache-first login; one short Discord refresh only if cache would deny a linked player.
- Live role check after join; kicks if the whitelist role is gone and updates cache/audit.
- OPs always bypass. If Discord/config is down, Argus stays cache-only and never blocks startup.
- Legacy vanilla-whitelisted but unlinked players are denied immediately with a link token message.

Platform
- Minecraft 1.21.10
- Fabric and NeoForge loaders
- Discord via Javacord slash commands
