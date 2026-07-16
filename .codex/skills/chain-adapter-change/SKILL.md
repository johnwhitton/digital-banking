---
name: chain-adapter-change
description: Use when designing, implementing, or reviewing Ethereum, Solana, RPC, transaction, contract/program, submission, observation, finality, or reconciliation adapter changes.
---

# Chain Adapter Change

## Overview

Keep chain-native technology behind adapters without erasing native identity, lifetime, replacement, evidence, or finality semantics.

## Required lifecycle seam

Use a common business-facing lifecycle, not `send(): settled`:

1. `capabilities(routeVersion)` - declare supported operation/evidence/retry characteristics.
2. `prepare(operation, attempt)` - build deterministic canonical unsigned bytes/digest and redacted build evidence.
3. Sign through the independent `Signer` port, binding the exact operation/attempt and constraints.
4. `submitOnce(signedAttempt)` - submit the exact signed bytes once and classify accepted, definitively rejected, or ambiguous.
5. `inquire(attemptIdentity)` - locate the original effect and establish route-specific retry safety.
6. `observe(nativeIdentity, policyVersion)` - gather normalized status plus versioned native evidence through a materially independent read path.

Keep SDK/RPC models and native fields inside the adapter. Core records retain stable operation/attempt identity, opaque native identity, evidence schema/version/reference, observed effect, source, time, and policy result.

## Native semantic checklist

| Ethereum/EVM | Solana |
| --- | --- |
| Chain ID, sender, nonce reservation | Cluster, fee payer/authority |
| Exact signed transaction and hash | Exact message/instructions/accounts and signature |
| Replacement lineage and fee policy | Recent blockhash/last-valid height or durable nonce |
| Receipt status/logs and revert evidence | Instruction/program error and log evidence |
| Block number/hash, confirmations, canonicality, reorg | Slot, commitment progression/regression, transaction lifetime |

Never map confirmation and commitment into an undocumented shared meaning. The adapter maps native evidence to a versioned policy decision while preserving the native record for inquiry and reconciliation.

## Workflow

1. Verify the common domain/lifecycle and signing phases are already accepted. Build one chain slice at a time.
2. Record SDK, contract/program, authority, local-chain, and native retry/finality decisions in an ADR before dependency expansion.
3. Use `financial-state-invariants` for identity, amount, ambiguity, finality, and reconciliation review.
4. Write deterministic encoding/native-lifetime failure tests before adapter implementation.
5. Use Web3j only in the Ethereum adapter; use the selected maintained Java SDK only in the Solana adapter. Add Solidity/Rust only for required on-chain enforcement.
6. Test against Anvil/equivalent or a local Solana validator; never default to public networks or credentials.
7. Prove response loss, inquiry, duplicate handling, canonicality/commitment change, independent observation, and reconciliation before verification.

## Retry and observation rules

- Timeout means ambiguous; persist and inquire before another attempt.
- EVM replacement is related by sender/nonce and explicit replacement policy.
- Solana same-signed-transaction resend within lifetime differs from building with a new blockhash.
- A new attempt never creates a new business operation.
- Submit-provider success is not independent observation and never business settlement.

## Common mistakes

- Putting nonce/blockhash/receipt/commitment or SDK types in domain DTOs.
- Returning one Boolean for submission, execution, and four finalities.
- Building fake adapters for both chains before one native slice proves the port.
- Reusing the submit node as the sole observer.
- Adding a custom contract/program where an existing audited program suffices.
