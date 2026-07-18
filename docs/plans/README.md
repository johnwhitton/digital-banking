# Execution plan lifecycle

Repository execution plans have three lifecycle locations:

- `active/`: work currently being executed and not yet closed;
- `completed/`: work whose acceptance gates passed and whose implementation or documentation was committed successfully; and
- `blocked/`: work stopped by an unresolved external or design blocker. Create this directory only when it has content.

## Rules

1. A newly authorized action begins with a restartable plan under `active/`.
2. The plan records scope, decisions, commands, bounded evidence, progress, and intentional deferrals while work proceeds.
3. On successful closeout, record the final status and move the plan to `completed/` before the implementation or documentation commit.
4. If work cannot close, record the exact blocker and restart condition and move the plan to `blocked/`; do not use `blocked` for merely planned or deferred work.
5. A roadmap item is not an active plan until the user authorizes that bounded action.
6. Completed plans are historical evidence. Do not routinely rewrite their commands or results; make only necessary link, metadata, or factual corrections and preserve the original context.

The owning plan remains subordinate to `AGENTS.md`, `SECURITY.md`, accepted ADRs, `docs/DESIGN.md`, and `docs/IMPLEMENTATION.md` as defined by repository instruction precedence.
