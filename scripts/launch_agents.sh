#!/usr/bin/env bash
# Spin up dedicated worktrees and tmux panes for multiple scoped Codex agents.
# Usage:
#   scripts/launch_agents.sh [role ...]
# Env:
#   CODEX_CMD  - command to start Codex CLI (default: codex)
#   CODEX_OPTS - extra flags passed to CODEX_CMD

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_ROLES=("tests" "docs" "ci")
ROLES=("$@")
[[ ${#ROLES[@]} -gt 0 ]] || ROLES=("${DEFAULT_ROLES[@]}")

CODEX_CMD="${CODEX_CMD:-codex}"
SESSION="argus-agents"

cd "$ROOT_DIR"

if ! command -v tmux >/dev/null; then
  echo "tmux is required to orchestrate agent sessions." >&2
  exit 1
fi

# Fresh session
tmux has-session -t "$SESSION" 2>/dev/null && tmux kill-session -t "$SESSION"
tmux new-session -d -s "$SESSION" -n orchestrator "cd '$ROOT_DIR' && clear; git status -sb; echo 'Orchestrator ready. Agents in other panes.'; exec bash"

for role in "${ROLES[@]}"; do
  worktree="../argus-${role}"
  branch="agents/${role}"

  # Create/update worktree per role
  if [[ ! -d "$worktree" ]]; then
    git worktree add -B "$branch" "$worktree"
  else
    git -C "$worktree" checkout -B "$branch" origin/main || true
  fi

  # Enforce local hooks inside worktree
  git -C "$worktree" config core.hooksPath .githooks

  # Start Codex in its own tmux window
  tmux new-window -t "$SESSION" -n "$role" "cd '$worktree' && echo \"Agent role: $role\" && $CODEX_CMD ${CODEX_OPTS:-} --prompt \"Agent role: $role\""
done

tmux select-window -t "$SESSION":1
tmux attach -t "$SESSION"
