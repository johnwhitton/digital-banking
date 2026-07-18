# Plan a bounded change

Read `AGENTS.md`, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, and the applicable file in `docs/plans/active/` before proposing work.

Create or update an active plan that states:

- the user-visible outcome and explicit non-goals;
- the affected domain, application, and adapter boundaries;
- lifecycle, finality, idempotency, signer, reconciliation, and exact-money implications;
- tests that will fail before the change and pass afterward;
- documentation and ADR updates required for architectural claims;
- verification commands and rollback or containment steps.

Keep it under `docs/plans/active/` while work is open. On successful closeout, record the final status and move it to `docs/plans/completed/` before the action's commit. If an unresolved external or design blocker stops work, record the exact blocker and restart condition and move it to `docs/plans/blocked/`, creating that directory only when needed. Do not activate roadmap work until it is separately authorized.

Stop and surface any decision that changes authority, funds-at-risk, mainnet posture, or an accepted architecture decision.
