# Playtest (for developers)

Headless tests cover these flows, but if you want to spot-check manually:
- Unconfigured Discord: server starts; login uses cache only.
- Application flow: `/whitelist apply` → list & approve/deny via buttons.
- Ban/warn/comment/review: ensure visibility and user-facing `/whitelist my`.
- Legacy unlinked: vanilla-whitelisted user is denied with link token.
- Role removal: login allowed via cache, kicked on join when role missing.
- Cache deny with live refresh: cache says no, Discord role says yes → login refresh allows.
- Link token reuse: reconnect quickly; same token is reused.
