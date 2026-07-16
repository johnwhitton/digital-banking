# Repository prompt templates

These files are reviewable prompt templates for recurring repository work. Codex does not automatically load repository prompt directories, so copy the relevant template into a task or explicitly ask Codex to follow it.

The templates complement, but never override, [`AGENTS.md`](../../AGENTS.md), [`AUTONOMOUS_EXECUTION_POLICY.md`](../../AUTONOMOUS_EXECUTION_POLICY.md), the architecture in [`docs/DESIGN.md`](../../docs/DESIGN.md), and the applicable plan under [`docs/plans/active/`](../../docs/plans/active/).

| Template | Use |
| --- | --- |
| `plan-bounded-change.md` | Turn an approved change into a bounded execution plan. |
| `check-architecture-doc-sync.md` | Check whether code and architecture documentation still agree. |
| `review-mint-burn-lifecycle.md` | Review durable mint/burn workflow semantics. |
| `review-chain-adapter.md` | Review a chain adapter without leaking protocol details into the domain. |
| `verification-handoff.md` | Produce an evidence-backed completion handoff. |
