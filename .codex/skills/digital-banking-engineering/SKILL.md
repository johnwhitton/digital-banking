---
name: digital-banking-engineering
description: Use when planning, implementing, reviewing, debugging, or documenting any substantial change in the Digital Banking Reference Implementation.
---

# Digital Banking Engineering

## Overview

Coordinate repository work from durable instructions through evidence-backed handoff. Keep regulated settlement authority, exactness, and native adapter boundaries intact.

## Workflow

1. Read `AGENTS.md`, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, relevant ADRs, and the active plan.
2. Run the Git/toolchain preflight from `AGENTS.md`; preserve user changes and keep Salus read-only.
3. Confirm the smallest bounded slice and its acceptance evidence. Update an active plan before substantial work.
4. Select every matching focused skill before editing:

| Change | Required local skill |
| --- | --- |
| Active-plan execution | `plan-execution` |
| Java, Maven, Spring, API, persistence | `java-spring-control-plane-change` |
| Ethereum, Solana, RPC, contract/program, observation | `chain-adapter-change` |
| Amount, lifecycle, idempotency, finality, signing, ambiguity, reconciliation | `financial-state-invariants` |
| Architecture, workflow, layout, capability, or status | `digital-banking-doc-sync` |

5. Work test-first in a domain-independent slice. Keep controllers and adapters thin; do not add unused SDKs or speculative modules.
6. Update living docs and the active plan with decisions, commands, results, deferrals, and status.
7. Run focused checks, the applicable full gate, stale-reference/secret checks, and Git diff/status checks before any completion claim.

## Non-negotiable result shape

A value-moving capability returns a durable operation identity and truthful lifecycle/evidence state. Never deliver a fake mint/burn success, a direct key-backed endpoint, or a transaction hash labeled as settlement - even for a demo. If durable semantics do not fit the approved slice, keep the capability absent and deliver only health, documentation, tests, or a non-value-moving simulation explicitly outside the business API.

## Quick reference

- Business truth: durable operation, policy, authorization, ledger, finality, and reconciliation state.
- External response: evidence only.
- Timeout: inquire and observe; do not blind-resubmit.
- Amounts: exact unit/scale; no `float` or `double`.
- Production authority: never application-held keys, mainnet, real funds, or compliance claims.

## Common mistakes

- Starting implementation before reading the active plan.
- Treating one local skill as a substitute for other matching reviews.
- Expanding both chain slices before proving the common domain lifecycle.
- Updating code without `README.md`, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, or plan synchronization.
- Reporting success from a narrow check while the broader acceptance gate is unrun.
