---
name: plan-execution
description: Use when implementing or continuing work owned by an active plan under docs/plans/active in this repository.
---

# Plan Execution

## Overview

Execute an approved active plan in dependency-ready, reviewable slices. Keep the plan sufficient for another contributor to restart without chat history.

## Workflow

1. Read `AGENTS.md`, the complete active plan, its linked design/ADR sources, and current Git status.
2. Reconcile checkboxes and evidence against the actual files and command output. Never trust status prose alone.
3. Choose the smallest unchecked slice whose dependencies are satisfied and whose acceptance can be reviewed independently. State why that slice is next.
4. Activate every focused skill matching the slice. Follow test-first behavior for code and the repository's per-skill validation for workflow files.
5. Implement only that slice. Preserve unrelated changes and do not combine refactoring, behavior, chain families, or speculative future modules.
6. Run the slice's focused acceptance command. Record exact command, observed result, decisions, and any limitation in the plan immediately.
7. Synchronize affected `README.md`, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, and ADRs using `digital-banking-doc-sync`.
8. Re-read the plan, select the next dependency-ready slice, and repeat.
9. Before closeout, run the full acceptance gate, review the complete diff, resolve every required checkbox, and record final Git/remote evidence.

## Evidence states

| Observation | Plan treatment |
| --- | --- |
| Check passed with fresh output | Mark complete and record command/result. |
| Optional check failed | Record failure, impact, and why work may continue; never hide it. |
| Required check failed | Leave open; debug before proceeding. |
| Tool/input unavailable | Record exact blocker and safest next step. |
| Work intentionally deferred | Name scope, reason, owner/trigger, and destination phase. |
| File/doc unchanged | Record why no synchronization was required. |

## Completion contract

Do not mark the plan complete because implementation exists or time is short. Completion requires every required deliverable, fresh validation, consistent living docs, reviewed diff, and explicit closeout state. A failed or blocked item remains visible.

Handoff includes the completed slice, remaining slices, decisions, exact validation, blockers/deferrals, and current branch/status.

## Common mistakes

- Executing all unchecked tasks before a review checkpoint.
- Updating the plan only at the end from memory.
- Checking a box based on another agent's report without inspecting files/output.
- Treating an optional failure as no evidence.
- Closing implementation while docs or acceptance claims remain stale.
