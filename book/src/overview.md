# What is Argus?

Argus links Discord roles to Minecraft access using a cache-first login flow and live role checks on join. It supports Fabric and NeoForge (Minecraft 1.21.10) and uses Javacord for Discord integration. Core rules:

- Cache-first login: if cache would deny a linked player, Argus does a one-shot Discord role refresh before deciding.
- Join live check: after login, a short-timeout Discord role check kicks if the whitelist role is missing and updates cache/audit.
- OPs always bypass. If Discord/config is unavailable, Argus stays cache-only and never blocks startup.
- Legacy vanilla-whitelisted but unlinked players are denied immediately with a link token message.

Artifacts
- `:common` shared logic and Javacord bot.
- `:fabric` Fabric hooks/commands.
- `:neoforge` NeoForge hooks/commands.
