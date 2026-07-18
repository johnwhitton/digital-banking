# Architecture Decision Records

Architecture Decision Records (ADRs) capture material decisions that have been accepted for this repository. They complement `docs/DESIGN.md`: the design describes the current system, while ADRs preserve why a consequential choice was made.

## Numbering and filenames

- Use four-digit, monotonically increasing numbers: `0001-short-decision-title.md`.
- Never reuse a number, including after a decision is rejected or superseded.
- Keep one material decision per ADR.

## Required format

```markdown
# ADR NNNN: Decision title

- Status: proposed | accepted | deprecated | superseded | rejected
- Date: YYYY-MM-DD
- Owners: accountable roles or named repository owner
- Supersedes: ADR NNNN (or None)
- Superseded by: ADR NNNN (or None)

## Context
What forces a decision now, including constraints and evidence.

## Decision
The accepted choice in testable terms.

## Alternatives considered
The credible alternatives and why they were not selected now.

## Consequences
Positive, negative, operational, migration, and reversal effects.

## Validation
Commands, tests, or future gates that prove the decision remains workable.
```

## Lifecycle and ownership

- `proposed` records are open for review and do not authorize implementation unless the active plan says otherwise.
- `accepted` records are current decisions and must agree with `docs/DESIGN.md` and `docs/IMPLEMENTATION.md`.
- `deprecated` means the decision is still present but should not be used for new work.
- `superseded` records remain immutable historical context and link to their replacement.
- `rejected` records preserve a considered choice that was not adopted.
- Superseding requires a new ADR; do not rewrite the old rationale as if the new decision was always true.
- The repository owner owns final decision acceptance. Named technical owners maintain evidence and propose updates.

Open questions remain in `docs/DESIGN.md` or `docs/IMPLEMENTATION.md` until evidence forces a decision. Avoid speculative ADR collections.

## Current records

- [ADR 0001: Maven reactor and module boundaries](0001-maven-reactor-and-module-boundaries.md) - accepted.
- [ADR 0002: EVM development with Foundry and Web3j](0002-evm-foundry-and-web3j.md) - accepted.
- [ADR 0003: Native Solana integration with SPL Token](0003-native-solana-spl-token.md) - accepted.
- [ADR 0004: PostgreSQL, explicit JDBC, Flyway, and atomic acceptance outbox](0004-postgresql-jdbc-flyway-atomic-outbox.md) - accepted.
- [ADR 0005: PostgreSQL operation delivery worker and lease recovery](0005-postgresql-operation-delivery-worker.md) - accepted.
- [ADR 0006: Local-development signing provider](0006-local-development-signing-provider.md) - accepted.
- [ADR 0007: Local Ethereum mint vertical slice](0007-local-ethereum-mint-vertical-slice.md) - accepted.
- [ADR 0008: USDZELLE product paths, ownership, custody, and reserve boundaries](0008-usdzelle-product-paths-ownership-custody-reserve-boundaries.md) - accepted.
- [ADR 0009: Synthetic reserve ledger and reconciliation](0009-synthetic-reserve-ledger-and-reconciliation.md) - accepted.
