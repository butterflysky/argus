# AI Agent Instructions for Argus

Argus is a Kotlin-based, multi-loader (Fabric + NeoForge) mod for Minecraft 1.21.10+. Keep changes aligned with `ARGUS_MASTER_SPEC.md`; current code already uses Javacord and shared :common logic across loaders.

**Quick Links**
- [AGENT_INSTRUCTIONS.md](AGENT_INSTRUCTIONS.md) – detailed workflow and QA steps
- [ARGUS_MASTER_SPEC.md](ARGUS_MASTER_SPEC.md) – current product specification to follow
- [.github/copilot-instructions.md](.github/copilot-instructions.md)
- [README.md](README.md)

## Project Direction (cheat sheet)
- Target Gradle multi-project: `:common` (shared logic, Discord/Javacord, cache), `:fabric` (Fabric hooks/commands), `:neoforge` (scaffold that reuses `:common`).
- Discord library: **Javacord** (spec choice; keep consistent unless explicitly changed).
- Java 21 / Kotlin 2.1.x; package namespace `dev.butterflysky.argus`. Prefer Kotlin for new work.
- Auth/Permissions rules from the spec:
  - Login checks read **only** from the local cache file `argus_db.json`; never block login on Discord I/O.
  - Before saving, rename existing cache to `.bak`; on load, fall back to `.bak` if the main file fails.
  - OPs bypass checks. Linked users require `hasAccess`; legacy whitelisted users get a temporary allow + kick with a link token; strangers are denied with the application message.
  - Audit mutations: update cache → log to console → send to Discord log channel. Track Discord name/nick changes.

## Issue Tracking with bd (beads)
- Use **bd for all work**; no markdown TODOs or side trackers.
- Typical flow:
  - Check ready items: `bd ready --json`
  - Create issues: `bd create "Title" -t bug|feature|task -p 0-4 --json`
  - Claim/update: `bd update <id> --status in_progress --json`
  - Link discoveries: `--deps discovered-from:<parent-id>`
  - Close: `bd close <id> --reason "Completed" --json`
- `.beads/issues.jsonl` must travel with code changes; `bd sync` flushes pending DB ↔ JSONL updates.

## Planning Docs
- Store any AI-generated plans/designs in `history/`. Keep the repo root clean. Avoid markdown TODO lists.

## Build & Run
- Multi-project now active: `./gradlew build`, `./gradlew :fabric:runServer`, `./gradlew :common:build`, `./gradlew :neoforge:build`.
- Logs: `run/logs/latest.log`; crash reports: `run/crash-reports/`; JVM errors: `run/hs_err_pid*.log`.

## Version Control
- Use `git` (beads hooks expect git, not jj).
  - Status: `git status -sb`
  - Stage: `git add -A`
  - Commit: `git commit -m "<type>(scope): short description"`
  - Push: `git push` (set upstream if needed)
- Commit message format:
  ```
  <type>(optional scope): <short description>

  - Optional detailed point 1
  - Optional detailed point 2

  <optional footer>
  ```
  Types: feat, fix, chore, refactor, docs, style, test, perf, ci, build, revert. Use present tense; max 50 chars description; scope is lowercase.

## Code Style Guidelines
- Kotlin objects for singletons; Java classes PascalCase, functions camelCase.
- Use Fabric's logger via `LoggerFactory.getLogger()`.
- Keep dependency versions pinned (see `gradle.properties`); when rebooting/adding deps, bump to latest supported versions first, then pin. Target JVM 21 for Java and Kotlin.
- Organize imports: stdlib first, then third-party.
- Prefer Kotlin `Result` for error flows where it improves clarity.

## Allowed Documentation Sites
- fabricmc.net/wiki and Fabric Loom docs
- neoforged.net docs
- javacord.org wiki/javadocs

For deeper process details, see `AGENT_INSTRUCTIONS.md`.
