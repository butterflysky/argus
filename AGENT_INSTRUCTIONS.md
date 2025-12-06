# Detailed Agent Instructions for Argus Development

These instructions match the current Argus direction in `ARGUS_MASTER_SPEC.md`: a Kotlin-first, multi-loader (Fabric + NeoForge) mod targeting Minecraft 1.21+. The present code is legacy Fabric/JDA; prefer changes that move us toward the new architecture.

## Project Layout (target state)
- `:common` – shared logic, cache/database (`argus_db.json` + `.bak`), Javacord bot, configuration.
- `:fabric` – Fabric entrypoint, login/connection event wiring, commands, vanilla whitelist bridge.
- `:neoforge` – scaffold module reusing `:common`, minimal platform glue.
- Resources live under each module's `src/main/resources` (e.g., `fabric/src/main/resources/fabric.mod.json`). Java/Kotlin sources stay under `src/main/java|kotlin/dev/butterflysky/argus/...`.

## Workflow Expectations
- **BD-first:** run `bd ready --json`; create and claim issues with `--json`; link discoveries via `discovered-from:<parent-id>`; keep `.beads/issues.jsonl` in commits. Run `bd sync` before ending a session.
- **Planning docs:** if you need design/plan files, place them in `history/`.
- **Small commits:** use `git status -sb` often and commit logically scoped changes with the agreed message format.
- **Version control:** use git (not jj); beads hooks, auto-flush, and sync features rely on git commands running.

## Coding Standards (spec-driven)
- Language/targets: Java 21, Kotlin 2.1.x, package `dev.butterflysky.argus`.
- Discord: prefer **Javacord** inside `:common`.
- Login safety: all login decisions read from the local cache only—no Discord/network calls on the login thread.
- Cache safety: before writing `argus_db.json`, rename the existing file to `.bak`; on load, fall back to `.bak` if needed.
- Permission gate: OPs bypass; linked users need `hasAccess`; legacy whitelisted users may enter once and are kicked with a link token; strangers are denied with the application message.
- Audit/identity: after cache mutation, log to console and to the Discord log channel; track Discord name/nick changes and record them.
- Logging: use Fabric `LoggerFactory.getLogger()`. Prefer Kotlin `Result` when it clarifies error handling.
- Dependency policy: when (re)bootstrapping, bump to the latest supported dependency versions, then pin; future upgrades are explicit and intentional.

## Build & Test
- Toolchain: JDK 21, Gradle wrapper, Loom (Vineflower configured).
- Commands:
  - Build all: `./gradlew build`
  - Module builds: `./gradlew :common:build`, `./gradlew :fabric:build`, `./gradlew :neoforge:build`
  - Run dev server (Fabric): `./gradlew :fabric:runServer`
- Run `./gradlew build` before committing code changes when code is modified.

## File Organization and Conventions
- Shared logic belongs in `:common`; platform specifics stay in their module.
- Keep config/DB paths stable (`argus_db.json` in the config folder).
- Resources: `fabric.mod.json` in `fabric/src/main/resources`; mirror NeoForge metadata when added.
- Imports: stdlib first, then third-party. Keep dependency versions pinned from `gradle.properties`.

## Session Closeout
- Update issues (`bd update/close ... --json`) and run `bd sync`.
- If code changed: run `./gradlew test` (and `./gradlew build` when appropriate).
- Verify clean state: `git status -sb`.
- Stage with `git add -A`, then commit with `git commit -m "<type>(scope): short description"`; include `.beads/issues.jsonl` when it changed.
- Push with `git push` (set upstream if needed). Git hooks from beads require using git rather than jj.

## Quick References
- Check ready work: `bd ready --json`
- Create issue: `bd create "Title" -t task -p 1 --json`
- Claim: `bd update <id> --status in_progress --json`
- Logs: `run/logs/latest.log`; crashes: `run/crash-reports/`; JVM errors: `run/hs_err_pid*.log`

When unsure, raise a bd issue to clarify, then continue. Keep efforts focused on moving the codebase toward the multi-loader Argus described in `ARGUS_MASTER_SPEC.md`.
