## Coverage push (2025-12-09)

BD links: argus-atc (70%+ coverage), argus-jb5 (merge jacoco), argus-6ik (README badge), argus-cm5 (git hygiene).

### Goals
- Make JaCoCo part of the default workflow (root + subprojects).
- Drive :common line coverage to ≥70% with focused unit tests; stretch higher if time allows.
- Surface the current coverage figure in `README.md` (badge or textual metric).
- Keep work in small, clean commits per argus-cm5.

### Test scope (initial pass)
- Login/whitelist flows: cache-first path, live role reconciliation, legacy whitelist kick, ban handling, first-allow audit, join-time refresh and invite messaging, OP bypass.
- Cache persistence: save/backup rotation, .bak fallback on load, SaveScheduler flush.
- Link tokens: issue/consume/list behavior, reissue same-UUID token, expiry cleanup.
- Config helpers: update/get sample values, isConfigured guardrails.
- Small utilities: ApplicationsPaginator, LoginIntrospection reflection helpers (via lightweight stubs).

### Tactics
- Use `@TempDir` to isolate config/cache files; set `argus.config.path` per test.
- Stub Discord via `setDiscordStartedOverride`/`setRoleCheckOverride`; avoid Mojang HTTP by not exercising `submitApplication`.
- Capture audit lines with `AuditLogger.configure` in tests where messaging/logging matters.
- Flush async saves with `CacheStore.flushSaves` before assertions on disk content.

### Exit criteria
- `./gradlew test jacocoRootReport` passes locally.
- JaCoCo XML shows ≥70% line coverage for :common (checked via report summary).
- README lists coverage metric; instructions added for maintaining clean commits.

