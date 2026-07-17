# ADR 0006: Local-development signing provider

- Status: accepted
- Date: 2026-07-17
- Owners: repository owner; signing-adapter engineering
- Supersedes: None
- Superseded by: None

## Context

Phase 4B needs real local cryptographic signing behind Phase 4A's provider-neutral boundary. JDK 25.0.2 SunEC signs and verifies Ed25519 natively, but an executable probe rejected `ECGenParameterSpec("secp256k1")` with `InvalidAlgorithmParameterException: Curve not supported: secp256k1 (1.3.132.0.10)`. Reimplementing curve/key/signature primitives in repository code would add security and maintenance risk, while Web3j or a chain SDK would add transaction concerns outside this slice.

The adapter is explicitly local-development infrastructure. Its keys are generated in memory only when the `local-signer` Spring profile is active, are discarded after the process session, and are not a production custody model.

## Decision

- Add one executable `adapters/signer-local` module that implements the existing `SignerPort` and `SigningKeyRegistry`.
- Pin `org.bouncycastle:bcprov-jdk18on:1.82` as the module's only cryptographic dependency. Bouncy Castle's official Java source is <https://www.bouncycastle.org/java.html>; the artifact uses the Bouncy Castle License (MIT-style), published at <https://www.bouncycastle.org/licence.html>.
- Use Bouncy Castle only for ephemeral secp256k1 key generation, exact-digest ECDSA, low-`s` normalization, compact signature encoding, recovery identity, and verification helpers inside the adapter.
- Use JDK-native Ed25519 for Solana exact-message signing.
- Seed ephemeral generation with JCA `SecureRandom`; read no key, seed, mnemonic, keystore, credential, or deterministic private fixture from any external source.
- Emit EVM signatures as 65 bytes `r || s || recovery-id`, with fixed unsigned 32-byte components, low `s`, and recovery ID in `0..3`. Emit Solana signatures as standard 64-byte Ed25519.
- Keep all provider types and private-key objects in the adapter. Do not export them through application/domain ports, install a global production provider, or introduce Web3j/another chain SDK.
- Compose the adapter only under explicit profile `local-signer`; default runtime has no signer or fallback.

## Alternatives considered

### JDK-only secp256k1

Rejected by the executable SunEC probe. JDK-native Ed25519 remains selected where it meets the contract.

### Hand-written secp256k1/ECDSA

Rejected because it would create custom cryptographic code and a larger review surface for no repository-specific invariant.

### Web3j or another chain SDK

Rejected because Phase 4B signs already-built material and must not construct, submit, or observe a transaction.

### Persistent local keystore or deterministic fixture seed

Rejected because restart must create a new local authority identity and stale pending requests must fail closed.

## Consequences

- The local signer can produce and verify real EVM/Solana signatures while remaining isolated and explicitly enabled.
- One third-party dependency is added and must stay confined to the adapter by Maven/`jdeps` checks.
- Java private-key objects and Bouncy Castle parameters may not guarantee physical memory zeroization; shutdown releases references and attempts supported destruction without claiming stronger erasure.
- Local signatures and metadata are synthetic development evidence only. This ADR selects no production HSM/MPC/custody provider and authorizes no public network or chain effect.
- Replacing the dependency, changing encoding, persisting keys, or selecting a production signer requires a later decision and focused evidence.

## Validation

The Phase 4B gate must prove exact-digest/no-rehash ECDSA verification, low-`s`, compact encoding/recovery, exact-message 64-byte Ed25519 verification, defensive copies, role/network/session fencing, restart identity change, replay without re-signing, inquiry after ambiguity, default-disabled/profile-only Spring composition, no key/evidence leakage, adapter-only dependency direction, and the complete offline Maven reactor.
