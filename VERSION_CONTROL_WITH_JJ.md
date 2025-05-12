Jujutsu (jj) Quick Start for LLM Agents

You are to use the Jujutsu (jj) version control system for all code changes. Do NOT use git add, git commit, git rebase, or other direct Git modification commands. jj operates on top of Git but provides its own workflow.

# Core Concepts & Workflow:

    ## Working Copy (@) is Live:
        Any changes you make to files are immediately part of the current commit (@). There is no staging area (no git add).
        To see changes in @: jj diff
        To see current status: jj status

    ## Describing Work (jj describe):
        This command edits the description (commit message) of the current commit (@).
        Use jj describe (opens editor) or jj describe -m "<your message>"
        Confirm strictly to Commit Message Format section instructions.

    ## Creating a New Commit (jj new):
        This command "saves" the current state of @ (making it a distinct, immutable-by-default commit) and creates a new, empty commit which becomes the new @. This is how you "make a commit" in jj.
        Use jj new frequently after describing a logical unit of work.
        You can provide an initial message for the new commit: jj new -m "fix(ui): resolve button alignment" (still follow the format described in "Commit Message Format" section below).
        Typical cycle: Make changes -> jj describe (to update current @'s message) -> jj new (to save @ and start a new one).
        jj commit is shorthand for jj describe && jj new

    ## Editing an Existing Commit's Content (jj edit <commit_hash>):
        To modify the files within an older commit: jj edit <commit_hash>
        This makes <commit_hash> the current commit (@). Make your file changes.
        If needed, update its description: jj describe
        jj automatically rebases any descendant commits.
        To return to your previous work or start something new, you might use jj edit <another_commit> or jj new on top of the edited commit.

    ## Viewing History:
        jj log: Shows commit history.
        jj obslog: Shows the "operation log" for commits, useful for understanding how commits have changed.

    ## Conflict Resolution:
        Conflicts can occur due to jj's automatic rebasing (e.g., after jj edit or when integrating external Git changes via jj git fetch then rebasing).
        jj status will indicate conflicts.
        To resolve:
            Identify conflicted files (they will contain <<<<<<<, =======, >>>>>>> markers).
            Edit these files to fix the conflicts. Save them.
            The working copy now contains the resolved state. jj might prompt you to run jj squash or jj resolve. Follow these prompts. jj diff should be empty for the resolved paths once done.

# Key Instructions for LLM:

    Primary Loop: Modify files, use jj describe (with correct format) to set the message for the current work in @, then use jj new to finalize that commit and start a new one.
    Commit Messages: Strictly adhere to the format described in "Commit Message Format" section
    No git add/git commit: All file changes are live in @. jj new is your "commit" action.
    Frequent Commits: Use jj new often to create a granular history.

# Commit Message Format

```
<type>(optional scope): <short description>

- Optional detailed point 1
- Optional detailed point 2

<optional footer>
```
- Type: must be one of feat, fix, chore, refactor, docs, style, test, perf, ci, build, revert
- Scope: lowercase term indicating affected area (e.g., api, auth, ui)
- Description: lowercase, no ending punctuation, 50 chars max
- Use present tense (e.g., "add feature" not "added feature")
