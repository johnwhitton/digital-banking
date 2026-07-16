---
name: financial-state-invariants
description: Use when changing or reviewing money/token quantities, mint/burn commands, idempotency, operation/attempt lifecycle, signing authority, finality, ambiguous effects, audit evidence, or reconciliation.
---

# Financial and State Invariants

## Overview

Review value-moving designs for exactness, stable identity, authorized transitions, truthful evidence, and recoverable ambiguity. Report violations and required tests without expanding the approved implementation phase.

## Review contract

| Invariant | Required shape |
| --- | --- |
| Exact quantity | Canonical decimal string at API; versioned asset/unit and scale; bounded atomic/minor integer internally; no implicit rounding or `float`/`double`. |
| Idempotency | Durable participant/resource scope, opaque key, canonicalization version, payload hash, and uniqueness. Same hash returns original; different hash conflicts. |
| Operation identity | One stable business `OperationId` across retries, timeouts, and cases. |
| Attempt identity | Server-issued `AttemptId` exists before signing/submission; native hash/signature is nullable evidence and may have lineage. |
| State transition | Append-only transition with expected/new aggregate version, actor/workload, reason, policy/config versions, time, and evidence references. |
| Ambiguous effect | Explicit `SUBMISSION_AMBIGUOUS`; inquire/observe original identity; no blind resubmission or new operation. |
| Signing authority | Typed request binds exact bytes/digest to operation, attempt, purpose, chain, asset, amount, destination, fee/expiry, policy, and approvals. Raw keys never enter the application. |
| Four finalities | Blockchain, legal settlement, customer-visible, and accounting records remain independent with authority, evidence, policy, and status; no `settled` Boolean. |
| Reconciliation | Join stable identities to independent evidence; preserve originals; create versioned breaks and authorized append-only repair. |
| Sensitive data | No personal/policy facts on-chain; opaque correlation only. No secrets or raw signed bytes in logs. |

## Review sequence

1. Name the business effect and all authorities that can permit, execute, observe, reconcile, repair, or declare each finality.
2. Trace identity from request through operation, attempts, signer, native evidence, observation, and reconciliation.
3. Trace exact quantity/unit/scale through API, canonical hash, persistence, signer bytes, native encoding, and evidence.
4. Enumerate every boundary response: accepted, definitively rejected, ambiguous, observed, conflicting, expired, and reconciled.
5. Confirm each retry is either the same idempotent read/request or a separately authorized attempt after proven native safety.
6. Produce prioritized findings and the narrow tests that fail before the fix. Do not prescribe database, framework, SDK, or module additions outside the active plan.

## Minimum test matrix

- Quantity: negative/zero policy, excess precision, non-canonical input, bounds/overflow, unit mismatch, exact round-trip.
- Idempotency: replay, payload conflict, restart, concurrency, canonicalization version.
- Lifecycle: every allowed and forbidden transition, optimistic version conflict, cancellation cutoff.
- Attempts: identity before native hash, ambiguous timeout, inquiry, authorized new attempt, EVM/Solana lineage differences.
- Signing: field/digest tampering, policy expiry, limit/allowlist/quorum rejection, evidence/redaction.
- Finality: independent advancement/order, evidence/authority mismatch, no implied cross-finality completion.
- Reconciliation: duplicate evidence, disagreement, append-only break, rerun, concurrent repair, preserved history.

## Red flags

- `amount` as JSON number, `double`, or unbounded `BigDecimal` without unit/scale rules.
- Cache-only idempotency, a new operation per retry, or transaction hash as primary identity.
- `settled`, `success`, or RPC acceptance standing in for business/finality truth.
- Timeout followed by immediate rebuild/resubmit.
- Signer receiving bytes without bound business/approval context.
- Reconciliation overwriting records or silently tolerating a difference.

Any red flag blocks a value-moving completion claim until its invariant and failing test are addressed.
