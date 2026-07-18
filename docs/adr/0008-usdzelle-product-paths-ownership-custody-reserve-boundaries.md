# ADR 0008: USDZELLE product paths, ownership, custody, and reserve boundaries

- Status: accepted
- Date: 2026-07-17
- Owners: repository owner; reference-implementation maintainers
- Supersedes: None
- Superseded by: None

## Context

The verified repository proves durable acceptance, delivery recovery, signing authority, and one local Ethereum mint, but it does not yet define one coherent delivery sequence for two different product outcomes: institutional stablecoin settlement between fiat endpoints and a user-held stablecoin lifecycle. Conflating those outcomes would blur customer ownership, signing custody, reserve liability, redemption, and finality.

The repository also needs a chain sequence. ADRs 0002 and 0007 establish the first Ethereum mint slice, while ADR 0003 requires a native Solana mapping and a bounded Java-client gate. The common business contracts must remain provider-neutral without pretending that EVM and SVM mechanisms are identical.

Zelle is only a public case study. This decision cannot assign stablecoin issuance, deposits, reserves, custody, revenue, or production operations to Early Warning Services or any other named organization.

## Decision

- Support two distinct USDZELLE product paths:
  - **settlement-only:** customers hold fiat before and after payment; `ADMIN` and bank/custody-controlled settlement wallets use USDZELLE only inside a six-effect durable settlement saga; customers need no blockchain wallet or signature;
  - **user-held:** a user may on-ramp, retain USDZELLE, optionally transfer it, and redeem later. On-ramp, wallet transfer, and redemption are separate durable operations, and receipt never forces redemption.
- Treat economic ownership and private-key custody as independent dimensions. Compatible custody modes are self-custody, segregated custodial wallets, and omnibus custody with an internal beneficial-balance ledger.
- Use **segregated local custodial identities** for the local user-held proof. A future explicitly authorized configured local signer may sign for named `ADMIN`, bank-settlement, redemption, and user-wallet identities. This is neither self-custody nor a production custody design.
- Keep chain adapters indifferent to local, self-custodied, bank/custodied, HSM, MPC, or custody-provider signing. They consume the existing provider-neutral signing contract and never receive business authority or production raw keys.
- Use neutral roles: `ISSUER`/`ADMIN`, `RESERVE_CUSTODIAN`, `DISTRIBUTOR_BANK`, `BANK_SETTLEMENT_WALLET`, `CUSTODY_PROVIDER`, `USER`/`USER_WALLET`, and `ADMIN_REDEMPTION`.
- Require explicit user-held on-ramp, wallet-transfer, redemption/off-ramp, reserve, supply, and reconciliation boundaries. A user deposit request is not an unrestricted mint command; privileged mint and burn are authorized child effects.
- Model synthetic reserve evidence for the POC and preserve the conceptual invariant `confirmed eligible reserves >= outstanding redeemable USDZELLE supply`. This selects no real reserve account, asset, investment, yield, revenue-sharing model, or accounting/legal conclusion.
- Treat both demonstrations as durable sagas: narrow local transactions, idempotent commands, stable operation/attempt identities, independent observation, inquiry before retry, append-only evidence, explicit compensation/manual review, and separate blockchain, accounting, legal, and customer-visible finality.
- Deliver Ethereum first through Phases 5B-6D, then map the same business contracts to native Solana semantics in Phases 7A-7D. Do not implement both chain adapters simultaneously or erase chain-specific identity, lifetime, authority, evidence, and finality semantics.

## Alternatives considered

### One combined deposit/transfer/redemption API

Rejected. Boolean modes would obscure distinct authority, reserve, retry, and customer-outcome contracts.

### Settlement-only product model

Rejected as the sole model because it cannot represent a user retaining or transferring USDZELLE.

### User-held model only

Rejected as the sole model because institutional settlement does not require customer blockchain wallets or signatures.

### Self-custody or omnibus custody for the first local proof

Deferred. Both remain compatible, but segregated local custodial identities are the smallest model that exercises named wallet resolution and the existing signer boundary without adding an external wallet or beneficial-balance ledger first.

### Solana first or parallel Ethereum/Solana delivery

Rejected. Ethereum Phase 5A is already verified; completing its missing wallet, burn, reserve, bank, and orchestration seams before native Solana mapping provides one concrete business contract without a lowest-common-denominator multi-chain design.

## Consequences

- The public architecture distinguishes customer outcome, token ownership, signing custody, reserve liability, and chain realization.
- The future wallet registry and configured local signer need role/purpose/version/authority controls and local-only secret injection; no key or `.env` is authorized by this ADR.
- The user-held path requires a synthetic reserve and supply model before it can be demonstrated end to end.
- Phase 3C remains verified for its existing five-effect acceptance/preparation boundary; it does not become the future six-step settlement-only orchestration by documentation.
- Self-custody, omnibus custody, production custody providers, commercial reserve-income sharing, and exact public endpoints remain separately authorized decisions.
- This is a reference-architecture decision, not a claim about Zelle or Early Warning Services production plans, legal roles, reserve model, or endorsement.

## Validation

- `README.md`, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, and `docs/TRANSFER_DEMO.md` must consistently distinguish both paths, both demos, current Phase 5A evidence, and the Ethereum-first/Solana-second sequence.
- Every future Phase 5B-8 entry remains `planned`, names its dependency and exit gate, and has no execution-plan link until separately authorized.
- Future executable gates must prove exact quantities, idempotent replay, stable attempt identity, ambiguity inquiry, least-authority signing, independent observation, reserve/supply reconciliation, restart recovery, and four distinct finalities without public networks or real funds.
