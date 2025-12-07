# Testing & Playtest

Automated
- Unit tests under `common` cover cache, config, and Discord bridge logic.
- Smoke tasks: `./gradlew :fabric:smoke` and `:neoforge:smoke` (run via `check`) verify loaders build and basic wiring.
- CI runs `./gradlew check` on every push/PR and on releases.

Manual playtest (see scenarios below)
- Validate login gating with whitelist on/off, linked/unlinked users, role removal, and op bypass.
- Exercise Discord commands: apply/approve/deny, link/unlink, warnings/bans, comments, review, config set/get, reload.
- Confirm audit logging and cache mutations in both console and Discord audit channel.

Tips
- Start from a “clean” run directory per loader; copy baseline configs (ops.json, whitelist.json/server.properties when needed).
- Use short Discord timeouts to avoid blocking the server; check logs for any fallback-to-cache behavior.
