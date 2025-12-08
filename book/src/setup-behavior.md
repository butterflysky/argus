# Whitelist Behavior & Safety

- **Whitelist ON**: Argus requires Discord link + whitelist role. Legacy vanilla-whitelisted but unlinked players are kicked with a link token. Strangers get `applicationMessage`.
- **Whitelist OFF**: Everyone is allowed; OPs always bypass.
- **Linked but cache-denied**: one short Discord refresh is attempted; if it still fails, login is denied.
- **Post-join role check**: after join, Argus verifies the whitelist role; if missing, player is kicked and cache/audit are updated.
- **Bans**: server bans still apply; Argus does not override them.
- **Safety**: if Discord/config is unavailable, Argus stays cache-only and never blocks startup; cache writes keep a `.bak` and fall back to it on load failure.
