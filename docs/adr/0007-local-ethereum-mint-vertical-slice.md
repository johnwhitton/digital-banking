# ADR 0007: Local Ethereum mint vertical slice

- Status: accepted
- Date: 2026-07-17
- Owners: repository owner and reference-implementation maintainers
- Supersedes: None
- Superseded by: None

## Context

ADR 0002 selected Foundry and an isolated Web3j adapter but deliberately deferred concrete dependencies, contract authority, persistence, runtime configuration, and failure semantics until an executable slice needed them. Phases 3B, 4A, and 4B now provide the durable delivery identity, signing authority, and isolated local signer required to prove one real chain effect without bypassing those controls.

The first effect must remain smaller than the planned five-step transfer. It must process an already-accepted mint, preserve exact two-decimal quantity semantics, reserve a durable nonce before signing, survive response loss without blind resubmission, and derive blockchain finality from independently fetched native evidence. It must remain local-only and must not create a public chain, production custody, runtime deployment, or business-settlement claim.

## Decision

- Implement only a local-Anvil mint under `contracts/evm/` and `adapters/ethereum-web3j/`; do not wire it to the Phase 3C transfer aggregate.
- Pin Foundry 1.5.1, local Solidity 0.8.25, OpenZeppelin Contracts v5.6.1 at commit `5fd1781b1454fd1ef8e722282f86f9293cacf256` (MIT), and Web3j Core 4.14.0 (Apache-2.0).
- Use one non-upgradeable `LocalReferenceToken` based on OpenZeppelin ERC-20 and `AccessControl`. It has two decimals, a separate `MINTER_ROLE`, and no burn, pause, permit, denylist, fee, bridge, governance, or upgrade behavior.
- Keep deployment and role assignment in disposable test/development harnesses. Spring receives explicit contract and recipient addresses from trusted configuration and cannot deploy, administer, or upgrade the contract.
- Reuse the accepted token operation, Phase 3B delivery, Phase 4A signing service, and Phase 4B ephemeral signer. The application port carries provider-neutral prepared transaction/signature/submission/observation records; Web3j and Ethereum-native types remain adapter-owned.
- Derive the EVM address from the signer's non-secret public key. Persist the chain, signer/key metadata version, contract, recipient, exact amount/calldata, fee/gas bounds, nonce, unsigned bytes, and digest before signing. Persist verified signature evidence, signed bytes, and the precomputed transaction hash before submission.
- Reserve nonces in PostgreSQL by chain and signer under a row lock. Persist the finality-policy version and required confirmation count with the attempt. Fence submission by durable state before the RPC call. An unavailable pre-send readiness check is retryable without sending bytes; a lost response after submission starts is `AMBIGUOUS`. Redelivery inquires the same precomputed hash and never sends another transaction or reserves another nonce.
- Confirm a mint only after a separate observation client fetches a matching transaction and successful receipt, the exact zero-address `Transfer` event for the recipient and atomic amount, the retained confirmation count, and a canonical block-hash recheck. Persist bounded source, transaction-intent, receipt, and matched-log evidence. Advance only blockchain finality and the narrow technical operation lifecycle.
- Give the local-Ethereum worker a mint-only queue view so it does not claim accepted burn operations or transfer events that require other consumers.
- Compose the path only when both `local-ethereum` and `local-signer` profiles are active. Accept only uncredentialed loopback HTTP, require chain ID `31337`, and provide no default contract or recipient address. The default profile and public API remain unchanged.

## Alternatives considered

### Implement mint, transfer, and burn together

Rejected for this slice. It would mix three authority/effect models and the parent workflow before the first native attempt, ambiguity, and observation seam had executable evidence.

### Accept a private key or unlocked sender in Spring configuration

Rejected. It would bypass the Phase 4 signing boundary and turn a disposable local convention into an unsafe custody pattern.

### Use Web3j credentials and generated contract wrappers in the control plane

Rejected. Native SDK, transaction, wrapper, and RPC types belong in the adapter; the control plane composes application-owned ports only.

### Submit first and persist the transaction hash afterward

Rejected. Process or response loss would leave no stable inquiry identity and could cause a duplicate mint on redelivery.

### Treat a successful `eth_sendRawTransaction` response as finality

Rejected. A submission response is evidence only. Receipt status, transaction intent, exact event, confirmation, and canonicality must be observed before blockchain finality advances.

## Consequences

- The repository now contains one real local chain effect and one material chain SDK, both isolated behind existing authority and lifecycle boundaries.
- The V5 migration adds durable local-Ethereum nonce, immutable finality context, attempt, submission, and detailed observation evidence. Signer and RPC calls remain outside database transactions.
- A completed mint proves only the configured local blockchain-finality threshold and technical completion. Legal, customer-visible, accounting, transfer-parent, reconciliation, and settlement authorities remain unchanged.
- The local reference token and ephemeral signer are disposable development infrastructure. They are not staging forms of production contract administration or custody.
- Wallet transfer, burn, replacement/cancellation, longer-lived reorganization monitoring, transfer-parent integration, public networks, hosted RPC, persistent wallets, production deployment/admin, and production custody require later plans and decisions.

## Validation

```bash
(cd contracts/evm && forge test)
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl adapters/ethereum-web3j -am test
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl control-plane -am \
  -Dtest=DigitalBankingApplicationTests,HealthReadinessSmokeTests,LocalEthereumPropertiesTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
git diff --check
```

The acceptance evidence must include authorization failure, exact quantity/event, deterministic transaction encoding, wrong/malformed signer rejection, V1-V5 migration, concurrent nonce uniqueness, response loss after node acceptance recovered by the same hash without resubmission, duplicate delivery, revert/manual-review handling, local-only configuration rejection, and dependency-direction checks.
