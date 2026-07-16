# ADR 0003: Native Solana integration with SPL Token

- Status: accepted
- Date: 2026-07-16
- Owners: repository owner; control-plane and Solana adapter engineering
- Supersedes: None
- Superseded by: None

## Context

The Solana slice must preserve accounts, instructions, authorities, blockhash lifetime, signatures, slots, commitment, and expiry without flattening them into EVM semantics. The baseline needs a native-SVM direction and must decide whether ordinary token mint/burn requires a custom program.

Official Solana documentation reviewed 2026-07-16 states that a token mint is an account owned by the Token Program and can be created without deploying another program. It documents Rust/Anchor for custom programs and notes reduced composability outside Neon's EVM environment. Current Solana Java documentation identifies Sava as a community SDK, but that is evidence for a bounded evaluation, not dependency acceptance.

Circle's official documentation currently lists native USDC and EURC identifiers on Solana. Those public identifiers are not secrets, but they are volatile network references and confer no authority to use public clusters. This repository records the official source link rather than hard-coding addresses or endpoints.

## Decision

- Target Solana natively; Neon is excluded from the baseline.
- Use the classic SPL Token Program for the initial Circle-USDC-aligned reference path. Token-2022 requires a later ADR tied to a needed extension.
- Do not create a custom Solana program merely to create a mint, mint tokens, burn tokens, or manage token accounts.
- Evaluate Sava through a bounded Phase 6 spike before accepting it. The spike must verify maintenance, Apache-2.0 licensing, dependency distribution, Java 25 compatibility, deterministic transaction construction, classic SPL Token support, local-validator compatibility, blockhash lifetime and commitment queries, and testability.
- Add `adapters/solana-java/` only after the client gate passes.
- Add `programs/solana/` only if required business logic cannot safely use an existing program. If that threshold is met, use pinned Rust and Anchor toolchains with native tests and a separate authority/upgrade decision.
- Use only a local validator in executable tests. No public testnet, mainnet, real asset, or production authority is authorized.

## Alternatives considered

### Neon EVM

Rejected for the baseline. It can ease Solidity reuse but adds a distinct runtime/proxy/operator model and reduces native Solana composability. Reconsideration requires a later ADR for a requirement whose actual goal is EVM compatibility on Solana.

### Custom Rust/Anchor token program now

Rejected. Existing SPL Token behavior covers ordinary mint/burn and token-account operations. A custom program adds upgrade, authority, audit, deployment, and client obligations without a current business requirement.

### Accept Sava immediately

Rejected. Official listing and active releases make it a leading candidate, but dependency availability, Java 25 behavior, SPL instruction coverage, native lifetime semantics, and local-validator testing still require executable evidence.

### Token-2022 by default

Deferred. Extensions can materially change token semantics and authority. The classic program is the narrower Circle-USDC-aligned baseline.

## Consequences

- The first Solana slice uses native accounts/instructions and existing token-program behavior.
- No Solana SDK, Rust workspace, Neon dependency, endpoint, or mint address enters Phase 2.
- Java-client selection remains an explicit evidence gate.
- Direct authority mint/burn remains separate from CCTP's cross-chain burn, off-chain attestation, and destination mint workflow.

## Reference disposition

| Source | Purpose | License/provenance | Current use |
| --- | --- | --- | --- |
| <https://solana.com/docs/clients/community/java> | Official Solana community-Java guidance identifying Sava | Solana documentation | Candidate evidence only. |
| <https://github.com/sava-software/sava> | Candidate Java SDK | Apache-2.0 | Evaluated only; no dependency accepted or code consumed. |
| <https://github.com/solana-program/token> | Canonical SPL Token program and clients | Apache-2.0 | Semantic reference only; no code consumed. |
| <https://github.com/circlefin/solana-cctp-contracts> | Circle CCTP Rust/Anchor programs | Apache-2.0 | CCTP reference only; not core USDC token implementation. |
| <https://github.com/circlefin/circle-cctp-crosschain-transfer> | Cross-chain example flow | Apache-2.0 | Example reference only; not a custody or production template. |
| <https://github.com/circlefin/skills> | AI-assisted Circle engineering reference | Apache-2.0 plus stated developer terms | Reference only; no skill or code consumed. |
| <https://developers.circle.com/stablecoins/usdc-contract-addresses> | Current official USDC network identifiers | Circle documentation | Reviewed 2026-07-16; address not copied into configuration. |
| <https://developers.circle.com/stablecoins/eurc-contract-addresses> | Current official EURC network identifiers | Circle documentation | Reviewed 2026-07-16; address not copied into configuration. |
| <https://developers.circle.com/cctp/references/technical-guide> | CCTP burn/attestation/mint semantics | Circle documentation | Workflow distinction only. |

Any later consumption must pin an exact commit/tag or dependency version and record license/provenance and the specific ideas or code used.

## Validation

Phase 6 cannot accept a Java client until its local spike proves the decision criteria above. The eventual adapter must test deterministic instructions/accounts, authority binding, recent-blockhash expiry, same-signed-transaction resubmission versus new-blockhash attempt lineage, commitment progression/regression, duplicate/ambiguous submission, independent inquiry/observation, and local-validator failure behavior.
