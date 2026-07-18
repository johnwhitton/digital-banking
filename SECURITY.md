# Security Policy

## Research and reference status

This repository is non-production research and reference software. It is not approved for real funds, production settlement, regulated operations, or compliance reliance. It makes no warranty or claim of legal, regulatory, security, custody, or operational certification.

Do not use this repository with mainnet, public testnets, production RPC providers, production custody/HSM/MPC systems, or real-value accounts. Production signing remains absent. The only chain integration is the explicit local-Anvil mint path, and the only cryptographic signers are the two explicit modes of the isolated local-development adapter described below.

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

`local-demo` and `local-signer` are mutually exclusive. The default profile instantiates neither signer, and `local-demo` alone enables no chain adapter, RPC, deployment, transfer, burn, or public signing endpoint. Process-environment secrets are acceptable only for this bounded local profile. A production implementation must use workload identity and a secret manager plus HSM, MPC, or qualified custody behind the provider-neutral signer port; raw application configuration is prohibited.

## Local Ethereum boundary

The Phase 5A path activates only when both `local-signer` and `local-ethereum` profiles are selected. Configuration accepts only uncredentialed loopback HTTP, hard-requires local chain ID `31337`, and supplies no default contract or recipient address. The runtime never deploys a contract or assigns roles. Its minimal reference token is non-upgradeable and exposes only standard ERC-20 behavior plus a separately authorized mint; it deliberately omits burn, pause, permit, denylist, fee, bridge, governance, and upgrade features.

Web3j and Ethereum-native types remain in the isolated adapter. PostgreSQL records the nonce, immutable transaction context, finality-policy version, and confirmation threshold before signing; it records the expected transaction hash before submission and append-only source/transaction/receipt/event observation evidence afterward. A readiness failure before the submission fence proves no bytes were sent and is retryable; a lost response after submission starts is ambiguous and must be inquired by the same hash. Neither path authorizes a second mint attempt. Success requires a matching transaction, successful receipt, exact zero-address `Transfer` mint event, the retained confirmation policy, and a canonical block recheck. That evidence advances only blockchain finality and technical operation state—not legal, customer-visible, accounting, transfer, or settlement state.

The real-chain integration test starts Anvil with a random mnemonic, discards its output, uses unlocked disposable development accounts only for deployment/role fixtures, and funds a random session signer. No private key, mnemonic, funded address, RPC credential, signed transaction, or generated Foundry artifact is committed. This design is not suitable for staging or production use.

## Dependency and native-code review

- Pin and review direct dependencies; do not add a chain SDK, custody SDK, or cryptographic dependency until a tested slice uses it.
- Record material dependency or baseline changes in an ADR.
- Treat generated Web3j wrappers, Solidity contracts, Solana programs, and generated client bindings as security-sensitive reviewed source.
- Smart contracts/programs require native tests, authorization and failure-path review, admin/upgrade analysis, local-chain integration evidence, and an independent security review before any non-local use is even considered.
- Produce dependency/SBOM and vulnerability evidence during hardening; this foundation does not claim that gate has been completed.

Future threat models and security decisions belong in `docs/security/` when that body of work exists and in numbered ADRs under `docs/adr/`. Until then, unresolved security questions remain explicit in `docs/DESIGN.md` and `docs/IMPLEMENTATION.md`.
