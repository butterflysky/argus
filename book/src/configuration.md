# Configuration Details

- **Cache-first login**: All decisions start from `argus_db.json`. If a linked user would be denied, Argus performs one short-timeout Discord role refresh before final decision.
- **Join live check**: After login, Argus does a live role check (short timeout, falls back to cache). If the whitelist role is missing, the player is kicked and cache/audit are updated.
- **Legacy vanilla-whitelisted**: Denied immediately with a link token (they never complete login).
- **OPs**: Always allowed. If Discord/config are unavailable, Argus stays cache-only and never blocks startup.
- **Bans**: Active bans deny with reason/time remaining; expired bans allow; permanent bans deny.
- **Audit**: Every cache mutation logs to console and Discord audit channel with names + IDs (link, approve/deny, warn/ban/unban, comments, role loss, first login, legacy kick, name/nick changes).
