# Security Policy

## Research and reference status

This repository is non-production research and reference software. It is not approved for real funds, production settlement, regulated operations, or compliance reliance. It makes no warranty or claim of legal, regulatory, security, custody, or operational certification.

Do not use this repository with mainnet, public testnets, production RPC providers, production custody/HSM/MPC systems, or real-value accounts. Signing and chain integrations remain absent by design.

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

Any future local-development signer must be explicitly test-only, isolated from production paths, limited to deterministic local networks, and unable to load production credentials. Development keys are disposable fixtures, not a staging form of production authority.

Production-oriented signing designs must keep raw keys outside application memory and bind authorization evidence to the exact operation, attempt, chain, asset, destination, amount, fee/expiry bounds, policy version, approvals, and canonical transaction bytes or digest.

## Dependency and native-code review

- Pin and review direct dependencies; do not add a chain SDK, custody SDK, or cryptographic dependency until a tested slice uses it.
- Record material dependency or baseline changes in an ADR.
- Treat generated Web3j wrappers, Solidity contracts, Solana programs, and generated client bindings as security-sensitive reviewed source.
- Smart contracts/programs require native tests, authorization and failure-path review, admin/upgrade analysis, local-chain integration evidence, and an independent security review before any non-local use is even considered.
- Produce dependency/SBOM and vulnerability evidence during hardening; this foundation does not claim that gate has been completed.

Future threat models and security decisions belong in `docs/security/` when that body of work exists and in numbered ADRs under `docs/adr/`. Until then, unresolved security questions remain explicit in `docs/DESIGN.md` and `docs/IMPLEMENTATION.md`.
