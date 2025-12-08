# Whitelist Behavior & Safety

- **Whitelist ON**: Argus requires Discord link + whitelist role. Legacy vanilla-whitelisted but unlinked players are kicked with a link token. Strangers get `applicationMessage` (or the default).
- **Whitelist OFF**: Everyone is allowed; OPs always bypass.
- **Linked but cache-denied**: one short Discord refresh is attempted; if it still fails, login is denied.
- **Post-join role check**:
  - If whitelist role is missing ⇒ kicked, access revoked, audit logged.
  - If user left the Discord guild ⇒ kicked, access revoked, audit logged.
  - If Discord is unreachable/timeout ⇒ no change; cache state is left intact.
- **Bans**: server bans still apply; Argus does not override them.
- **Dry-run mode**: set `enforcementEnabled=false` (default) to log what Argus would do without kicking/denying. Turn on once you’re ready to enforce.
- **Safety**: if Discord/config is unavailable, Argus stays cache-only and never blocks startup; cache writes keep a `.bak` and fall back to it on load failure.
