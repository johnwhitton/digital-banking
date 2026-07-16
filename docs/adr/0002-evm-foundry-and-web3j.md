# ADR 0002: EVM development with Foundry and Web3j

- Status: accepted
- Date: 2026-07-16
- Owners: repository owner; control-plane and EVM adapter engineering
- Supersedes: None
- Superseded by: None

## Context

The repository needs an explicit EVM development approach before the Ethereum vertical slice. The Java control plane must preserve native EVM identity, nonce, replacement, receipt, log, confirmation, and reorganization semantics without making Solidity or JSON-RPC types part of the domain.

The supplied publications establish Solidity as the EVM-native language, Web3j as a credible Java integration boundary, and the need to isolate chain execution from business truth. They do not select a contract toolchain. Foundry's official documentation, reviewed 2026-07-16, defines `forge` for contract build/test/script workflows, `anvil` for a local Ethereum node, and `cast` for RPC and diagnostic interaction.

## Decision

- Write EVM contracts in Solidity only when an owning vertical slice requires on-chain enforcement.
- Use Foundry as the repository's sole EVM-native toolchain: `forge` for build/test, `anvil` for deterministic local execution, `cast` for diagnostics, and Foundry scripts for deployment fixtures.
- Add the Foundry project under `contracts/evm/` only when the Ethereum slice begins.
- Use Web3j in `adapters/ethereum-web3j/`; generated bindings and native types remain inside the adapter.
- Implement the Ethereum slice before Solana so the shared lifecycle receives one real adapter before a second native model tests its limits.
- Keep all defaults local-only. No fork, public RPC, testnet, mainnet, deployment, or funded account is authorized by this ADR.

## Alternatives considered

### Hardhat

Circle's `stablecoin-evm` repository uses Hardhat and also includes Foundry configuration. It is valuable reference material, not a reason to introduce Node/Yarn and a second EVM toolchain here. One native toolchain reduces duplicate configuration and review surface.

### Java-only EVM development

Rejected. Java owns orchestration, but Solidity owns EVM execution. Reimplementing contract behavior in Java would not test the deployed execution environment.

### No EVM decision until implementation

Rejected because toolchain and repository-layout ambiguity would make Phase 5 planning non-repeatable. This ADR selects the approach without creating speculative files or dependencies.

## Consequences

- Future EVM work has one native build/test/local-node workflow.
- Solidity and Foundry remain absent until Phase 5.
- Web3j enters only the adapter module and cannot cross the application port.
- Circle contract code may inform concepts only after a recorded license/provenance review; no source is copied by this decision.

## Reference disposition

| Source | Purpose | License/provenance | Current use |
| --- | --- | --- | --- |
| <https://getfoundry.sh/> | Official Foundry tool responsibilities and local workflow | Foundry project documentation | Decision evidence only; no toolchain installed or pinned yet. |
| <https://github.com/circlefin/stablecoin-evm> | Stablecoin contract, authority, pause, upgrade, and mint/burn concepts | Apache-2.0 | Reviewed as reference only; no code consumed, so no commit/tag pin is required yet. |

Any later code consumption must record the exact repository URL, license review, pinned commit or tag, and copied or adapted ideas.

## Validation

Phase 5 must add and pass:

```bash
forge fmt --check
forge build
forge test
```

It must also prove deterministic Web3j encoding, stable attempt identity, nonce/replacement lineage, ambiguous submission inquiry, independent receipt/log observation, confirmation/canonicality policy, and local-Anvil failures before the ADR is considered executable.
