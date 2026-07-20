# Digital Banking v1.0.0 Dual-Chain POC

**Tag:** `digital-banking-v1.0.0-dual-chain-poc`

**Final commit:** the exact commit resolved by that annotated tag

This prerelease packages two disposable local demonstrations over the same
durable Java/PostgreSQL control plane:

- **Demo A — user-held:** synthetic bank withdrawal, exact mint to USER_1,
  hold, transfer to redemption custody, payout, exact burn, and reconciliation.
- **Demo B — settlement-only:** sender acquisition, exact USER_1-to-USER_2
  wallet transfer, forced recipient `AUTO_REDEEM`, payout, burn, and
  reconciliation across six durable effects.

The Ethereum profile uses PostgreSQL, digest-pinned Dockerized Anvil, a deployed
minimal Solidity reference token, and Web3j. The Solana profile uses PostgreSQL,
private host-native Agave, a standard classic-SPL mint, and Sava; it deploys no
custom Rust program. Only one profile runs at a time.

## Verified local behavior

- Exact `USD 100.00` / `10,000` atomic-unit behavior on both chains.
- Idempotent replay without duplicate bank or native effects.
- Inquiry after ambiguous submission; no blind resubmission.
- Durable restart recovery and chain-specific safe replacement rules.
- Payout-before-burn, one-time evidence consumption, balanced synthetic
  accounting, and final reconciliation against PostgreSQL and native evidence.
- Offline Maven: `538` discovered, `536` executed and passed, `0` failed or
  errored, and `2` intentional skips. Foundry: `9` passed with no failure or
  skip. Maven Enforcer and compiled-class Java 25 `jdeps` boundaries passed.
- Clean Ethereum and Solana Demo A, Demo B, replay, and restart-recovery gates
  passed. Each chain proved exact `10000` atomic-unit/cents behavior, no
  duplicate confirmed effect, final reconciliation, and safe environment stop.

Exact per-module counts, commands, workflow identities, and final positions are
recorded in the [Phase 8B plan](../plans/completed/PHASE_8B_FINAL_REVIEW_AND_DUAL_CHAIN_POC_RELEASE.md)
and [final review](../reviews/PHASE_8B_FINAL_REFERENCE_REVIEW.md).

Start with the [POC walkthrough](../DEMO_WALKTHROUGH.md) and repository
[quick start](../../README.md#quick-start), then use the
[Ethereum](../runbooks/LOCAL_ETHEREUM_DEMO.md) or
[Solana](../runbooks/LOCAL_SOLANA_DEMO.md) runbook for exact prerequisites,
state ownership, recovery, diagnostics, and teardown.

## Publications and limits

The committed Volume I and Executive Brief are the published v1.0.5
Ethereum-aligned snapshots. Solana publication alignment remains deferred; the
current source, ADRs, OpenAPI, tests, and release evidence govern current
implementation claims.

This is non-production reference software for local synthetic data and local
chains only. It is not real banking, real reserve backing, audited accounting,
legal/customer finality, compliance certification, public-chain deployment,
production custody, or an announced Zelle/Early Warning implementation. Future
production identity, HSM/MPC/custody, bank, accounting-close, HA/DR, monitoring,
performance, public-network, and publication work requires separate authority.
