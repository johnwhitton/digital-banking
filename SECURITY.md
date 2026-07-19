# Security Policy

## Research and reference status

This repository is non-production research and reference software. It is not approved for real funds, production settlement, regulated operations, or compliance reliance. It makes no warranty or claim of legal, regulatory, security, custody, or operational certification.

Do not use this repository with mainnet, public testnets, production RPC providers, production custody/HSM/MPC systems, or real-value accounts. Production signing remains absent. Chain integration is limited to explicit local-Anvil mint, user-transfer, and redemption-custody/burn paths, and the only cryptographic signers are the two explicit modes of the isolated local-development adapter described below.

## Durable API boundary

- The default application configures no identity provider, token decoder, local user, password, static bearer token, issuer, or JWK endpoint. Health/readiness and the OpenAPI description are anonymous; business resources deny by default until an identity adapter supplies a validated participant principal and authority.
- Participant scope comes from the authenticated principal, never request JSON or an ad hoc tenant header. Cross-participant and unknown operation IDs have indistinguishable not-found responses.
- Raw idempotency keys are restricted to 1–128 visible US-ASCII characters, hashed before persistence, excluded from API responses, and redacted by their application value type. Stable problems do not echo request values, SQL, provider details, class names, or stack traces.
- Participant-facing evidence is deny-by-default: only opaque references with the explicit `participant:` prefix are returned. Internal command digests, authorization evidence, transition/finality evidence, and provider details remain omitted.
- PostgreSQL credentials and endpoints are runtime configuration only. No public database, embedded durable fallback, or committed credential is provided.
- A pending outbox record proves only durable local acceptance. It does not prove processing, signing, submission, minting, burning, reconciliation, settlement, or exactly-once external effect.

## Reporting a vulnerability or accidental secret

Report security concerns privately to the repository owner. Do not open a public issue containing exploit details, credentials, keys, personal data, or funded addresses.

If a credential or secret is suspected:

1. Stop using and copying it; do not paste it into chat, logs, issues, or tests.
2. Notify the repository owner through a private channel.
3. Revoke or rotate the credential in its owning system; Git removal alone is not revocation.
4. Remove the material from the working tree and, with owner approval, address any published history.
5. Review logs and dependent systems for use, then record the response without retaining the secret.

Never commit private keys, seed phrases, API tokens, RPC credentials, HSM credentials, custody credentials, keystores, `.env` files, or real funded addresses.

## Development signing

The `local-signer` Spring profile is an explicit development-only exception to the production custody model. When enabled, its isolated adapter generates one secp256k1 key and one Ed25519 key in process memory using secure randomness. It reads no private key, seed, mnemonic, keystore, credential, environment secret, API value, configuration value, repository file, or database row. Its aliases, key versions, public-key fingerprints, roles, and logical networks are non-secret session metadata; private objects never leave the adapter.

The default profile creates no local signer and generates no key. A local signer restart creates new aliases and versions; a pending request bound to the prior session fails closed into manual review rather than using a replacement key. A completed Phase 4A result replays durably without re-signing. EVM signing accepts only an exact 32-byte digest and returns low-`s` secp256k1 evidence; Solana signing accepts only the exact bounded serialized message and returns a 64-byte Ed25519 signature. Neither mode constructs or submits a transaction.

Shutdown releases key references and attempts provider-supported destruction, but Java/provider objects do not prove physical memory zeroization. No stronger erasure claim is made. Local keys and signatures are disposable development evidence, not a staging form of production authority.

Production-oriented signing designs must keep raw keys outside application memory and bind authorization evidence to the exact operation, attempt, chain, asset, destination, amount, fee/expiry bounds, policy version, approvals, and canonical transaction bytes or digest.

### Configured local-demo custody

The separate `local-demo` profile is an explicitly local POC exception for deterministic EVM identities on chain `31337`. It requires `CONTRACT_OWNER`/`CONTRACT_DEPLOYER`, `ADMIN`/`ADMIN_REDEMPTION`, four bank-settlement wallets, and four segregated user-custody wallets. The immutable registry exposes only stable aliases, ownership categories, normalized derived addresses, provider-neutral key references, public metadata/key versions, allowed purposes, network, and enabled status. A request must retain the exact authorized alias, purpose, derived source address, local network, and metadata version; another bank/user wallet or a rotated key fails closed. Outstanding work bound to replaced material enters the existing manual-review boundary rather than signing with the replacement.

The committed [`.env.example`](.env.example) contains blank private-key placeholders and only the approved public Anvil address map. The actual `.env.local-anvil` is a user-authorized, ignored, mode-`0600` local file containing publicly known Anvil development fixtures; it must never be staged, committed, logged, copied to reports, funded with real value, or used on a public network. `.env`, `.env.*`, and secret variants remain ignored while `.env.example` is intentionally tracked. Java does not read `.env` automatically: a local shell may use `set -a; source .env.local-anvil; set +a` to supply process variables without echoing them.

Private scalars accept only canonical 32-byte hexadecimal secp256k1 values with an optional lowercase `0x` prefix. Startup derives the address and compares the expected local address; missing, malformed, zero, out-of-range, crossed, or mismatched configuration prevents readiness with a redacted diagnostic. Configuration diagnostics and serialization redact the key value, key property objects are cleared after signer construction where practical, and neither PostgreSQL nor HTTP receives raw keys. Java/provider objects still do not prove physical memory zeroization.

`local-demo` and `local-signer` are mutually exclusive. The default profile instantiates neither signer, and `local-demo` alone enables no chain adapter, RPC, deployment, transfer, burn, or public signing endpoint. Combined with `local-ethereum`, Phase 5D permits only a configured user source to transfer an exact accepted quantity to `ADMIN_REDEMPTION`, then permits only ADMIN with `BURN_AUTHORITY` to burn its own exact balance after confirmed custody evidence is consumed once. Phases 6B-6C may invoke those same bounded effects only through immutable server-owned accepted context and ordered parent workflows. Process-environment secrets are acceptable only for this bounded local profile. A production implementation must use workload identity and a secret manager plus HSM, MPC, or qualified custody behind the provider-neutral signer port; raw application configuration is prohibited.

### Synthetic local banking and accounting

Phase 6A adds synthetic bank and reserve/liability behavior only under `local-demo`; the default profile has no fixture accounts, bank controllers, or accounting beans. The local HTTP boundary uses separate debit, credit, and read authorities, derives participant scope from the authenticated principal, returns an equivalent safe 404 for missing and cross-participant resources, and accepts only exact USD amount/currency plus the scoped idempotency header. It exposes no seed, arbitrary balance, posting, account-selection, direction, evidence, status, failure-injection, or reserve-adjustment control.

Fixture names are synthetic identifiers and contain no real customer name, account/routing number, telephone number, email address, credential, or funded address. Responses and low-cardinality metrics omit participant, account, operation, evidence, idempotency, and amount values. Exact replay is hash-bound; an ambiguous committed effect is inquired by its stable operation identity and is not repeated. PostgreSQL row locking and uniqueness prevent overdraft, lost updates, and duplicate effect records.

The internal four-account POC ledger is append-only and accepts only trusted closed posting commands backed by durable bank or confirmed-chain evidence. Chain supply pointers are joined to the authoritative Phase 5 operation, attempt, observation/event, and blockchain-finality rows before amount, identity, asset, local network/contract, policy, canonicality, finality, age, and observed supply can be accepted. A pointer alone cannot post. Evidence is consumed once in the posting transaction; reconciliation derives its complete/fresh assessment from the durable consumed sources rather than caller claims. Digest-bound corrections append one linked reversing journal without mutating the original. Callers cannot assert success or finality. Evidence substitution/reuse, unbalanced journals, unsupported/stale observations, and reserve/supply mismatch fail closed or produce explicit reconciliation results; no balance is rewritten to hide a break. These records are reference evidence, not audited statements, proof of reserves, an attestation, or legal/accounting finality.

### User-held workflow boundary

Phase 6B is active only under `local-demo,local-ethereum` and adds no default-profile bean or credential. Acquisition and redemption require separate `usdzelle:acquire` and `usdzelle:redeem` authorities; participant-safe lookup requires `usdzelle:read`. Requests contain only canonical positive amount, `USD`, one participant-configured synthetic bank reference, optional `ETHEREUM`, and the scoped idempotency header. Unknown fields are rejected. The caller cannot choose a wallet, ADMIN identity, signer, key, contract, RPC URL, asset/unit, policy, child identity, evidence, step, status, payout result, or reconciliation outcome.

Acceptance atomically persists the hashed scoped idempotency identity, exact cents/token quantity, participant, bank, opaque user/ADMIN wallet identities and public metadata versions, loopback network/contract, policy versions, ordered steps, history, and outbox event. Exact replay returns that retained context. Every later parent or mint/burn child effect revalidates the immutable accepted server context and fails closed if the configured bank, wallet, contract, route, or policy versions have rotated. A delivery advances at most one durable boundary and re-reads authoritative child state. Retained chain evidence is rechecked against its original canonical block and exact balances/supply before accounting consumes it. Ambiguous bank or chain work is retained for inquiry rather than blind repetition, and exhausted workflow delivery atomically projects manual review onto the parent rather than leaving a participant-visible active workflow.

Redemption is structurally payout-before-burn: confirmed user-to-ADMIN custody and its one-time accounting posting precede payout; the one-time payout and payout posting precede burn acceptance. A crash, ambiguous burn, rejection, or delayed observation after payout cannot issue a second deposit and leaves the paid quantity in the explicit ADMIN-custody-pending-burn position for recovery or manual review. Workflow completion requires durable reconciliation; it does not assert legal, customer-visible, or production accounting finality.

### Settlement-only transfer boundary

Phase 6C is active only under `local-demo,local-ethereum` and adds no request field or authority. The existing transfer create/read authorities and principal-derived sender scope still apply. A sender can name only the exact amount/currency, its configured source synthetic account, a destination synthetic account reference, and optional `ETHEREUM`; it cannot identify the destination participant, choose either wallet, disable recipient redemption, select ADMIN, or control a child, signer, key, policy, evidence, state, or reconciliation result. A server-owned versioned instruction maps the destination account to a distinct enabled recipient with `AUTO_REDEEM`. Participant-scoped GET remains source-owned and exposes only minimized boundary and bank/blockchain/accounting/reconciliation statuses—never the recipient identity, wallet aliases, child identities, native evidence, or internal policy context.

Acceptance atomically retains the V3 transfer, V10 settlement companion, one outbox event, exact cents/base units, both immutable instruction snapshots, opaque wallet identities and public metadata versions, ADMIN, local network/contract, policies, ordered boundaries, and digests. Exact replay returns that stored context without re-resolving the command. Delivery revalidates the accepted server authority, advances at most one boundary, and re-reads the authoritative Phase 6B/5C child rather than trusting a caller or copying child truth. A child identity conflict, changed authority context, exhausted delivery, unresolved ambiguity, no-effect outcome after partial progression, or reconciliation mismatch fails closed into explicit no-effect/manual-review evidence. There is no automatic compensation, reversal, refund, second payout, or claim of distributed atomicity.

## Local Ethereum boundary

The Phase 5A path activates only when both `local-signer` and `local-ethereum` profiles are selected. The Phase 5C-5D path instead requires `local-demo` plus `local-ethereum`. Configuration accepts only uncredentialed loopback HTTP, hard-requires local chain ID `31337`, and supplies no default contract or mint recipient address. The runtime never deploys a contract or assigns roles. Its minimal non-upgradeable reference token exposes standard ERC-20 behavior plus separately authorized `MINTER_ROLE` mint and `BURNER_ROLE` own-balance burn; it deliberately omits arbitrary-holder burn, pause, permit, denylist, fee, bridge, governance, and upgrade features.

Web3j and Ethereum-native types remain in the isolated adapter. PostgreSQL records the nonce, immutable transaction context, finality-policy version, and confirmation threshold before signing; it records the expected transaction hash before submission and append-only source/transaction/receipt/event observation evidence afterward. A readiness failure before the submission fence proves no bytes were sent and is retryable; a lost response after submission starts is ambiguous and must be inquired by the same hash. No path authorizes blind resubmission after ambiguity. Mint success requires the exact zero-address-to-recipient event; transfer success requires the exact source-to-destination event; burn success requires the exact ADMIN-to-zero event plus block-bound ADMIN-balance and total-supply deltas. Each also requires a matching transaction, successful receipt, retained confirmation policy, and canonical block recheck. That evidence advances only blockchain finality and technical operation state—not legal, customer-visible, accounting, payout, reserve, or settlement state.

The real-chain integration test starts Anvil with a random mnemonic, discards its output, uses unlocked disposable development accounts only for deployment/role fixtures, and funds a random session signer. No private key, mnemonic, funded address, RPC credential, signed transaction, or generated Foundry artifact is committed. This design is not suitable for staging or production use.

## Dependency and native-code review

- Pin and review direct dependencies; do not add a chain SDK, custody SDK, or cryptographic dependency until a tested slice uses it.
- Record material dependency or baseline changes in an ADR.
- Treat generated Web3j wrappers, Solidity contracts, Solana programs, and generated client bindings as security-sensitive reviewed source.
- Smart contracts/programs require native tests, authorization and failure-path review, admin/upgrade analysis, local-chain integration evidence, and an independent security review before any non-local use is even considered.
- Produce dependency/SBOM and vulnerability evidence during hardening; this foundation does not claim that gate has been completed.

Future threat models and security decisions belong in `docs/security/` when that body of work exists and in numbered ADRs under `docs/adr/`. Until then, unresolved security questions remain explicit in `docs/DESIGN.md` and `docs/IMPLEMENTATION.md`.
