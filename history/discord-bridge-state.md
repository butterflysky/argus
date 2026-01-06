# Discord Bridge State Model

This document captures the Discord lifecycle state model for ArgusCore after the async
reload/start refactor, so we can reason about transitions without relying on scattered
flags.

## States

- STOPPED: Discord bridge is not running and can be started.
- STARTING: A start attempt is in flight on the Discord executor.
- STARTED: Discord bridge is running and available for role checks.
- STOPPING: A stop request is in flight; start results are ignored.
- DISABLED: Config is missing botToken/guildId; start is skipped by design.

## Transition Table

| Event | From | To | Notes |
| --- | --- | --- | --- |
| startDiscord (configured) | STOPPED, DISABLED | STARTING | Start scheduled on executor, new generation. |
| startDiscord (configured) | STARTING | STARTING | Returns existing future. |
| startDiscord (configured) | STARTED | STARTED | No-op, returns success. |
| startDiscord (disabled) | any | DISABLED | Skips start, completes success. |
| start success | STARTING | STARTED | Only if generation matches current. |
| start failure | STARTING | STOPPED | Only if generation matches current; logs warning. |
| stopDiscord | STARTING, STARTED, DISABLED | STOPPING | Invalidates in-flight start future; increments generation. |
| stop complete | STOPPING | STOPPED | Executed on executor after stop impl. |
| reloadConfig (async) | any | STOPPING -> STARTING | Runs initialize off-thread, stops, then starts if configured. |
| reloadConfig (async, disabled) | any | DISABLED | Stops then skips start; keeps started=false. |

## Invariants

- Only the Discord executor mutates the bridge itself; public methods schedule work and
  update state transitions atomically.
- A generation counter invalidates stale start completions when stop/reload supersede
  a start attempt.
- `discordPhase == STARTED` is the sole source of truth for “Discord up” unless
  overridden by tests.

## Notes for Review

- stopDiscord completes any in-flight start future with a failure to prevent hanging.
- start results are ignored if a stop or reload has superseded the start generation.
- Disabled config never sets STARTED; a later start attempt will still run when config
  becomes valid.
