# Digital Banking Reference Implementation

Reference implementation for a regulated digital-asset settlement control plane. It is designed to demonstrate production-oriented boundaries, durable workflow, explicit evidence, and failure handling while remaining deliberately non-production software.

> **Safety boundary:** This repository is research and reference software. Do not use it with real funds, production credentials, mainnet accounts, public testnets, or production signing authority. Nothing here is a compliance, legal, security, or operational certification.

> **Public-case-study boundary:** Zelle appears only in the supplied publications as a public case study. This repository does not represent Early Warning Services or Zelle production architecture, confidential information, selected vendors, endorsement, or an announced implementation plan.

## BACKGROUND READING - START HERE

| Document                                                                                                                                                           | Description                                                                                                                                                                                                          | Audience                                                                                   | Reading Time                       |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ | ---------------------------------- |
| [Architecture Slides and John Whitton's Portfolio](https://zelle.johnwhitton.com)                                                                                  | Fast visual introduction to the architecture, project, and author.                                                                                                                                                   | Hiring managers, interviewers, engineering leaders, and architects.                        | `5 mins`                           |
| [Zelle Executive Briefing](https://github.com/johnwhitton/digital-banking/blob/main/docs/reference/zelle-digital-asset-settlement-executive-brief.pdf)             | Executive architecture brief for stablecoin and cross-border settlement.                                                                                                                                             | Executives, hiring managers, principal engineers, product, risk, and architecture leaders. | `15-18 mins`                       |
| [Digital Banking Reference Architecture](https://github.com/johnwhitton/digital-banking/blob/main/docs/reference/stablecoin-settlement-reference-architecture.pdf) | Detailed 112-page reference architecture covering control-plane boundaries, ledger and workflow, signing, native chain integration, finality, reconciliation, security, and delivery.                                | Architects, principal engineers, security, operations, risk, and implementation teams.     | `~30 mins guided / ~180 mins full` |
| [Digital Banking Engineering Companion — Volume II](https://github.com/johnwhitton/digital-banking/blob/main/docs/reference/digital-banking-engineering-companion.pdf)         | Implementation and operations handbook covering durable workflow, Java/Spring, wallets and signing, EVM, Solana, infrastructure, testing, deployment, observability, performance, and runbooks. | Engineers, architects, security reviewers, operators, and technical decision makers.       | `~60-75 mins`                      |
| [Digital Banking Reference Implementation — Volume III](https://github.com/johnwhitton/digital-banking/blob/main/docs/reference/digital-banking-reference-implementation.pdf)   | Code companion mapping architecture to repository modules, APIs, database schema and migrations, implementation excerpts, local build/run/test flows, and the boundary between local demonstrations and production integrations. | Engineers, architects, security reviewers, operators, and technical decision makers.       | `~60-75 mins`                      |

The executive PDF states an 18-minute estimate. The detailed architecture contains approximately 37,700 extracted words and provides a guided reading path, so the table distinguishes a guided route from a complete technical read. Volumes II and III are contextual implementation/code companions, not production certification or runnable releases. A publication's evidence snapshot can lag the live repository; current source, tests, OpenAPI, accepted [ADRs](docs/adr/README.md), [the engineering design](docs/DESIGN.md), and [the implementation plan](docs/IMPLEMENTATION.md) govern current implementation claims. All four PDFs are immutable contextual reference inputs, and their provenance and metadata remain in the [reference index](docs/reference/README.md).

## What This Demonstrates

- Java and Spring own the regulated control plane: authenticated business APIs, durable operations, policy coordination, workflow, persistence, reconciliation, and operational interfaces.
- Durable internal state owns business truth. A signature, transaction hash, RPC response, receipt, or commitment is evidence, not complete financial settlement.
- Exact quantities, stable operation/attempt identities, versioned idempotency, append-only evidence, four separate finalities, and ambiguous-effect recovery are explicit contracts.
- Chain SDKs, native transaction semantics, and signer/custody providers remain behind ports and adapters.
- Delivery proceeds in evidence-gated slices: the common lifecycle, worker/recovery, signing authority, local-Ethereum effects, and independently executable synthetic-bank/accounting primitives do not imply a complete product workflow.

## Complete Now

Status vocabulary:

- `planned` - described and sequenced, with no implementation claim.
- `scaffolded` - boundary or build structure exists but the capability is not delivered.
- `implemented` - executable behavior exists with focused tests.
- `verified` - the applicable repository validation gate has run successfully and is recorded.
- `blocked` - a named external input is missing, so the capability cannot pass its gate.

| Capability                              | Status     | Evidence and limitation                                                                                                                                                                                                                                                                                                                                                              |
| --------------------------------------- | ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Foundation and source publications      | `verified` | All four reference PDFs are tracked under `docs/reference/`; architecture, repository policy, Maven reactor, and health/readiness application are synchronized.                                                                                                                                                                                                                     |
| Plain Java domain boundary              | `verified` | Exact asset/unit quantities, stable IDs, guarded lifecycle, attempt lineage, append-only evidence, and four finalities pass the Phase 2 gate; `domain` has no runtime dependencies.                                                                                                                                                                                                  |
| Spring control-plane application        | `verified` | `control-plane` composes the durable PostgreSQL adapter, exposes health/readiness plus token-operation, transfer, and explicit-profile local resources, and serves design-first OpenAPI contracts. Business resources deny requests until a future identity adapter supplies an authenticated `ParticipantPrincipal`.                                                                                      |
| Mint and burn operation lifecycle       | `verified` | Framework-free commands, canonical SHA-256 payloads, kind- and participant-scoped replay/conflict, lifecycle coordination, status lookup, and provider-neutral ports pass their existing gates. Phase 5A connects accepted mints to its explicit local profile; Phase 5D connects accepted burns only after exact local redemption-custody evidence. |
| Phase 3A durable acceptance and OpenAPI | `verified` | Explicit JDBC plus Flyway atomically records operation, hashed idempotency binding, audit evidence, four finalities, and one pending outbox event before HTTP 202. Real PostgreSQL tests cover rollback, concurrency, replay/conflict, restart, security, and read-back; its historical closeout gate recorded 302 tests.                                                                     |
| Phase 3B durable delivery worker        | `verified` | Framework-free delivery contracts plus an opt-in Spring worker durably claim PostgreSQL outbox work, fence leases, record attempts/outcomes, retry bounded failures, and recover expiry; its historical closeout gate recorded 335 tests. Phase 3C adds one transactional transfer-preparation handler, but no external business/chain effect is wired.                                          |
| Phase 3C transfer acceptance            | `verified` | A chain-neutral parent durably owns five ordered effects, server-resolved wallet context, exact amount/currency, scoped replay/conflict, PostgreSQL V3 persistence, transfer APIs, a synthetic mock-bank contract/adapter, and a transactional handler inbox that can prepare only the first withdrawal; its historical closeout gate recorded 364 tests. No bank or blockchain effect executes. |
| Phase 4 signing boundary               | `verified` | Phase 4A supplies durable authority, replay/conflict, ambiguity/inquiry, and PostgreSQL V4 evidence. Phase 4B adds one explicitly enabled `local-signer` profile with session-only secp256k1 and Ed25519 keys, exact-material signatures, and no public endpoint or chain effect. Production custody remains absent. |
| Phase 5A local Ethereum mint           | `verified` | One explicit `local-ethereum` + `local-signer` path processes an accepted mint on Anvil through a durable nonce/attempt fence, exact EIP-1559 signing, submit-once ambiguity recovery, and independent receipt/event/canonicality observation. It advances only technical operation state and blockchain finality. |
| Phase 5B configured local custody      | `verified` | The opt-in `local-demo` profile requires ten deterministic, server-owned EVM identities behind one immutable provider-neutral registry. Keys are supplied only from ignored local process configuration, addresses and stable public versions are derived from those keys, purpose/address/version mismatches fail closed, and no API or chain effect is added. |
| Phase 5C local Ethereum wallet transfer | `verified` | One internal, standalone `USER_WALLET_1` to `USER_WALLET_2` transfer uses exact atomic units, server-resolved custody identities, source-only authorization, per-source durable nonces, submit-once ambiguity recovery, and independent transaction/event/canonicality observation on Anvil. No endpoint or parent-saga behavior is added. |
| Phase 5D local redemption and burn      | `verified` | One accepted burn creates or resumes an exact server-resolved user-to-`ADMIN_REDEMPTION` custody transfer, consumes its independently confirmed evidence once, then signs an ADMIN own-balance burn. Block-bound balance/supply evidence and response-loss recovery are durable; no payout, reserve release, or parent saga is added. |
| Phase 6A synthetic finance primitives   | `verified` | The `local-demo` profile adds durable exact-USD synthetic withdrawals, deposits, inquiry, participant isolation, and distinct debit/credit/read authorities. A closed four-account double-entry ledger consumes trusted bank/chain evidence once and durably reports reserve-ledger, chain-supply, incomplete-evidence, and stale-observation breaks. No effect is automatically orchestrated. |
| Phase 6B user-held workflows            | `verified` | The combined `local-demo,local-ethereum` profile adds participant-scoped acquisition and redemption parents with exact amounts, immutable server-resolved bank/wallet/policy context, V9 persistence, one-step durable recovery, payout-before-burn, and final reconciliation. The consolidated PostgreSQL+Anvil proof and full offline repository gate are green. |
| Phase 6C settlement-only orchestration  | `verified` | The existing transfer API can now accept one configured local route from `USER_1`'s synthetic bank account to `USER_2`'s account. A V10 companion durably orders sender acquisition, exact `USER_WALLET_1` to `USER_WALLET_2` transfer, recipient `AUTO_REDEEM`, and final reconciliation while callers remain unable to select wallets, ADMIN, child identities, policies, or outcomes. The consolidated PostgreSQL/Anvil proof, stable-diff reviews, and full offline repository gate are green. |
| Phase 6D reproducible Ethereum demos    | `verified` | One digest-pinned, loopback-only Compose environment starts PostgreSQL, Anvil, deterministic contract deployment, and the packaged non-root control plane. API-driven user-held and settlement-only commands assert exact cents/atomic units, six bounded effects, payout-before-burn, reconciliation, replay, durable restart recovery, and scoped teardown. The stable-diff reviews, runtime gates, and 503-test offline reactor are green. |
| Phase 7A native Solana semantic gate    | `verified` | Pinned repository-local Agave 4.1.2 and SPL Token CLI 5.6.1 run a loopback-only native validator proof. Classic SPL checked mint/transfer/redemption/burn instructions move exactly 10,000 base units at two decimals through canonical associated accounts, classify rejected authority and valid-second-mint/account cases, retain finalized signature/blockhash/slot evidence, and finish at zero supply/balances. No Java adapter, API, custom program, or public network is added. |
| Phase 7B local Solana mint              | `verified` | The unchanged participant-scoped mint API can route through an explicit `local-solana` profile to an isolated Sava 25.8.0 adapter. One exact classic-SPL mint uses a canonical ATA, ordered fee-payer and mint-authority Ed25519 signatures, durable recent-blockhash/submission evidence, response-loss inquiry, restart recovery, and finalized exact supply/balance observation. Transfer, redemption, burn, demos, public clusters, and production custody remain absent. |
| Phase 7C local Solana wallet transfer   | `verified` | One internal `USER_WALLET_1` to `USER_WALLET_2` classic-SPL `TransferChecked` reuses the provider-neutral transfer boundary, ordered fee-payer/source-owner Ed25519 signing, V12 durable attempt and observation state, response-loss inquiry, restart recovery, and finalized exact source/destination/supply evidence. No public endpoint, redemption, burn, product orchestration, or demo is added. |
| Phase 7D local Solana redemption/burn   | `verified` | One accepted burn creates or resumes an internal server-resolved USER_1-to-`ADMIN_REDEMPTION` classic-SPL `TransferChecked`, consumes its exact finalized evidence once, then executes one ADMIN-owner `BurnChecked` through separate durable attempts and ordered fee-payer/owner signatures. Response loss, restart, blockhash-expiry replacement within the same burn lineage, replay, and exact zero supply/balances are proven locally; no endpoint, payout/accounting parent, product orchestration, or demo is added. |
| Phase 7E Solana product orchestration   | `verified` | The existing Phase 6B acquisition/redemption parents and Phase 6C settlement-only companion can select the server-registered `SOLANA` route under `local-demo,local-solana`. They reuse the Phase 7B–7D effects, immutable server-resolved wallet/signer context, one PostgreSQL worker, finalized chain evidence, payout-before-burn, and existing participant-safe APIs. The consolidated PostgreSQL/Agave product-path gate and final offline reactor are green. |
| Phase 7F reproducible Solana demos      | `verified` | Host-native Agave 4.1.2 and packaged Java 25.0.2 use the approved cached PostgreSQL digest and loopback-only ports. Demo A user-held, explicit reset, Demo B settlement-only, replay, restart recovery, scoped teardown, both stable-diff reviews, and the 538-test offline reactor are green. |

The Phase 7E opt-in PostgreSQL/Agave gate executes both existing product meanings through the Solana path: exact user-held acquisition/replay/redemption and one settlement-only sender-acquisition/transfer/recipient-`AUTO_REDEEM` composition. It finishes with reconciled bank/accounting state and zero token supply and balances. Real-validator execution remains disabled in the ordinary reactor. The final reactor discovered 538 tests, executed 536 successfully, and skipped only the separately gated Phase 7B and Phase 7E native-validator tests. The current Foundry contract suite remains at **nine tests** from Phase 5D; no Solidity file changed. No endpoint was added: the public contracts still accept exact amount/currency, synthetic bank references, and an allowlisted network while wallet identities, key aliases, native authorities, policy, and route versions remain server-resolved. These are synthetic local workflows, not production banking endpoints.

Phase 7F ran Demo A, one explicit scoped reset, Demo B, and one durable restart proof against private host-native Agave and the dedicated PostgreSQL project. The final offline reactor retained the same 538 discovered / 536 executed test count with only those two opt-in native gates skipped.

## Designed, Not Executable

- Phase 5C transfer and Phase 5D redemption-custody/burn remain separately authoritative internal local-Anvil primitives. Phase 6B composes the configured user-held acquisition/redemption path; Phase 6C reuses those children only for one server-registered settlement-only route. Neither is a production balance product, bank integration, reserve, or custody system.
- Phase 6C does not rewrite Phase 3C's historical five-effect aggregate or implement arbitrary institutional routing. Its V10 companion and the Phase 6D environment prove the settlement-only economic outcome with segregated local custody aliases and forced recipient `AUTO_REDEEM`; broader bank-settlement-wallet routing and compensation execution remain absent.
- Default business endpoints have no runtime identity provider and therefore deny access.
- The durable provider-neutral signing boundary, session-ephemeral signer, and configured local-custody signer are limited to their explicit local profiles; no production HSM, MPC, secret-manager, or custody signer exists.
- No public network, hosted RPC provider, API key, dynamic wallet-management service, or production deployment exists. Phase 7F packages the existing local Solana composition only for a private disposable validator.
- The Anvil mint establishes only narrow technical operation state and blockchain finality; it does not establish accounting, legal, or customer-visible finality.

## Product paths and delivery roadmap

The reference architecture supports two distinct USDZELLE outcomes without conflating economic ownership with signing custody:

| Path | Customer outcome | On-chain holders/signers | Current state | Target |
| --- | --- | --- | --- | --- |
| Settlement-only | User sends and receives fiat; USDZELLE is transient inside the saga | The bounded local proof uses `ADMIN` plus two server-owned segregated custody aliases; the target model also permits institutional settlement wallets | Phase 7F packages Demo B through native local Solana with exact six-effect/replay/recovery assertions | Arbitrary institutional routing remains future work |
| User-held USDZELLE | User can acquire, hold, optionally transfer, and later redeem USDZELLE | `ADMIN` plus segregated custodial user wallets in the local POC | Phase 7F packages Demo A acquisition/hold/redemption, replay, payout-before-burn, reconciliation, and recovery through native local Solana | Production identity, custody, reserve, and wallet products remain future work |

```text
Implemented now: domain + durable API/worker + transfer parents + signing + local Ethereum effects + configured local custody + synthetic finance + user-held and settlement-only workflows + reproducible local Ethereum and Solana demos + native Solana semantic gate + local Solana mint, wallet-transfer, redemption-custody, burn, and product-path orchestration
Next: final code/security/recovery/API/share-readiness review (Phase 8)
```

The authoritative [design](docs/DESIGN.md) owns architecture and custody/reserve boundaries; the [implementation roadmap](docs/IMPLEMENTATION.md) owns phase status and completed-plan links; the [demo contract](docs/TRANSFER_DEMO.md) specifies Demo A and Demo B; and the [plan lifecycle](docs/plans/README.md) governs authorization and closeout. No future phase is active until separately authorized.

The `local-demo` profile composes the immutable wallet registry plus four synthetic bank identities, two configured participant accounts, and the internal reserve/liability services. Its separate local bank contract remains at `/local/v1/mock-banks/openapi.yaml`; withdrawals, deposits, account reads, and operation inquiry require distinct `local-bank:debit`, `local-bank:credit`, and `local-bank:read` authorities. Combined with `local-ethereum`, it exposes the Phase 6B acquisition/redemption contract at `/openapi/usdzelle-workflows-v1.yaml` and enables the registered Phase 6C route behind the existing transfer contract. `USER_1` is the authorized sender; the server resolves `USER_2` as the distinct `AUTO_REDEEM` recipient. The fixtures and ledger are synthetic POC evidence—not real funds, reserve assets, audited statements, attestation, or legal/customer/accounting finality.

## On-Chain Development Approach

- **Ethereum/EVM:** Solidity contracts, when required, use Foundry (`forge`, `anvil`, `cast`, and Foundry scripts). Java integration uses Web3j inside its adapter; neither Web3j nor generated bindings enter the core domain.
- **Solana/SVM:** native Solana semantics and the classic SPL Token Program govern the initial path. Sava Core/RPC 25.8.0 passed the Java 25 compatibility gate and remains isolated in `adapters/solana-sava`; no Sava type enters the domain or application contract.
- **Custom Solana execution:** add Rust with Anchor only when required business logic cannot safely use existing programs. No speculative Rust workspace is present.
- **Neon:** excluded from the baseline because the reference path prioritizes native SVM composability. Reconsideration requires a later ADR for a distinct EVM-compatibility requirement.
- **Sequencing:** extend the proven Ethereum mint boundary only through separately approved effects, then validate the shared business contract against Solana's structurally different semantics.

Direct issuer-authority mint/burn and CCTP cross-chain burn/attestation/mint are separate workflows. CCTP is not the initial direct mint/burn mechanism.

## Repository map

```text
.
├── domain/                    # Plain Java domain boundary
├── application/               # Framework-free use cases and ports
├── adapters/
│   ├── persistence-postgres/  # Explicit JDBC/Flyway workflow, chain, synthetic-bank, and accounting evidence
│   ├── signer-local/          # Explicit-profile, in-memory local-development signing only
│   ├── ethereum-web3j/        # Isolated local-Anvil mint construction, submission, and observation
│   └── solana-sava/           # Isolated local-validator SPL mint, submission, and observation
├── contracts/evm/             # Foundry project and minimal role-gated local reference token
├── control-plane/             # Spring APIs plus explicit worker/signer/local-chain composition
├── docker/demo/               # Fixed local genesis and aggregate demo-only runtime configuration
├── scripts/demo/              # Ethereum commands plus the host-native Solana demo subdirectory
├── scripts/solana/            # Pinned native tooling, semantic gate, status, stop, and scoped reset
├── docs/
│   ├── DESIGN.md              # Canonical engineering architecture
│   ├── IMPLEMENTATION.md      # Living delivery plan and current state
│   ├── TRANSFER_DEMO.md       # Implemented acceptance mapping and remaining flow contract
│   ├── runbooks/              # Reproducible local operator procedures
│   ├── adr/                   # Accepted architectural decisions
│   ├── plans/
│   │   ├── README.md          # Authoritative active/completed/blocked lifecycle
│   │   ├── active/            # Currently executing plans
│   │   └── completed/         # Closed plans and historical execution evidence
│   └── reference/             # Four immutable publications and index
├── .codex/                    # Project config, prompt templates, and skill sources
├── .agents/skills/            # Codex repository-skill discovery compatibility
├── graphify-out/              # Reviewed portable graph report, JSON, and manifest
├── compose.yaml               # Digest-pinned loopback-only Phase 6D environment
├── compose.solana-demo.yaml   # PostgreSQL-only Phase 7F project; Agave/Java remain host-native
├── Dockerfile                 # Minimal packaged non-root control-plane runtime
├── AGENTS.md                  # Repository operating rules
├── AUTONOMOUS_EXECUTION_POLICY.md
└── SECURITY.md
```

Future executable slices begin with the Phase 8 final reference review; production-oriented integrations remain separately authorized future work and will not be created empty.

## Build and Inspect the Current Implementation

Prerequisites: JDK 25, Docker, Foundry 1.5.1, and local Solidity 0.8.25; the first dependency/image resolution also needs an internet connection. On the bootstrap workstation, Homebrew's JDK is installed but not linked onto `PATH`. `./mvnw` is the repository-pinned Maven Wrapper. Maven/Testcontainers verification starts disposable PostgreSQL in Docker automatically, and the Ethereum integration suite starts a disposable Anvil process.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw --version
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw clean verify
```

For the bounded local demonstrations, prepare the ignored mode-`0600` `.env.local-anvil` from [`.env.example`](.env.example), ensure the three approved digest-pinned images are cached, then use the safe commands below. Demo A is the user-held acquisition/hold/redemption lifecycle; reset is explicit before Demo B, the settlement-only six-effect transfer. `reset.sh --yes` irreversibly removes only the named Phase 6D database/chain volumes and ignored runtime evidence.

```bash
scripts/demo/start.sh
scripts/demo/demo-user-held.sh
scripts/demo/reset.sh --yes
scripts/demo/start.sh
scripts/demo/demo-settlement-only.sh
scripts/demo/stop.sh
```

The [local Ethereum demo runbook](docs/runbooks/LOCAL_ETHEREUM_DEMO.md) records prerequisites, immutable image identities and licenses, exact assertions, restart recovery, troubleshooting, preserved-state behavior, and destructive teardown.

For the corresponding private local Solana demonstrations, use the already
provisioned pinned Agave/SPL tools, Java 25.0.2, and cached PostgreSQL image.
Demo A remains user-held and Demo B remains settlement-only:

```bash
scripts/demo/solana/start.sh
scripts/demo/solana/demo-user-held.sh
scripts/demo/solana/reset.sh --yes
scripts/demo/solana/start.sh
scripts/demo/solana/demo-settlement-only.sh
scripts/demo/solana/stop.sh
```

The [local Solana demo runbook](docs/runbooks/LOCAL_SOLANA_DEMO.md) records the
host-native topology, exact identities/ports, assertions, restart procedure,
safe diagnostics, and destructive teardown boundary.

The native Solana tooling uses the approved Apple Silicon Agave archive and repository-local SPL Token CLI build; it does not change Compose. The Phase 7A semantic command rejects redirected scoped paths, resets only ignored `.solana-runtime/`, runs the exact local mint/transfer/redemption/burn proof, retains sanitized evidence, and stops only its exactly identified validator. The public-only fixture supplies both configured user owners plus ADMIN redemption for opt-in Java primitive and product-path gates; the Java adapter still accepts only loopback RPC and explicit server-owned identities. See the [native Solana script guide](scripts/solana/README.md) for immutable artifact identities, prerequisites, profile inputs, and cleanup boundaries.

```bash
scripts/solana/bootstrap.sh
scripts/solana/status.sh --json
scripts/solana/phase7b-fixture.sh
scripts/solana/semantic-gate.sh --yes
scripts/solana/reset.sh --yes
```

After Maven builds the packaged JAR, run it with `java -jar`. Running the application does not start PostgreSQL: the operator must provide an existing private/local PostgreSQL 17 server and inject its credentials through environment variables or another secret mechanism. Flyway validates and migrates that database at startup; it neither creates the PostgreSQL server nor provides an in-memory fallback. No credentials are committed.

```bash
SPRING_DATASOURCE_URL="${DB_URL}" \
SPRING_DATASOURCE_USERNAME="${DB_USERNAME}" \
SPRING_DATASOURCE_PASSWORD="${DB_PASSWORD}" \
JAVA_HOME=/opt/homebrew/opt/openjdk /opt/homebrew/opt/openjdk/bin/java \
  -jar control-plane/target/digital-banking-control-plane-0.1.0-SNAPSHOT.jar
```

Check readiness in terminal 2:

```bash
curl --fail --silent http://localhost:8080/actuator/health/readiness
```

The response has status `UP`. Inspect the authoritative contract at `/openapi/token-operations-v1.yaml` and the current implementation evidence in [`docs/IMPLEMENTATION.md`](docs/IMPLEMENTATION.md). Business endpoints return 401 in the default repository configuration because no issuer, decoder, local user, password, or token is configured; integration tests inject fixture-only identities and authorities. Stop the application with `Ctrl-C`. No RPC URL, key, wallet, custody account, chain process, or public service is required.

For isolated local signing, add the explicit `local-signer` Spring profile to the same private/local PostgreSQL launch. The profile generates one ephemeral secp256k1 key and one ephemeral Ed25519 key in process memory, emits a development-only warning, and wires the internal Phase 4A service. It reads no key, seed, mnemonic, keystore, or credential; restart creates new aliases and key versions. There is no public signing endpoint.

```bash
SPRING_PROFILES_ACTIVE=local-signer \
SPRING_DATASOURCE_URL="${DB_URL}" \
SPRING_DATASOURCE_USERNAME="${DB_USERNAME}" \
SPRING_DATASOURCE_PASSWORD="${DB_PASSWORD}" \
JAVA_HOME=/opt/homebrew/opt/openjdk /opt/homebrew/opt/openjdk/bin/java \
  -jar control-plane/target/digital-banking-control-plane-0.1.0-SNAPSHOT.jar
```

For deterministic local wallet identities, copy the safe variable names and public Anvil address map from [`.env.example`](.env.example) into the ignored `.env.local-anvil`, fill it only with the approved public Anvil development fixtures, and retain mode `0600`. Java does not load `.env` files automatically; export the values through the shell without echoing them:

```bash
chmod 600 .env.local-anvil
set -a
source .env.local-anvil
set +a
SPRING_PROFILES_ACTIVE=local-demo \
SPRING_DATASOURCE_URL="${DB_URL}" \
SPRING_DATASOURCE_USERNAME="${DB_USERNAME}" \
SPRING_DATASOURCE_PASSWORD="${DB_PASSWORD}" \
JAVA_HOME=/opt/homebrew/opt/openjdk /opt/homebrew/opt/openjdk/bin/java \
  -jar control-plane/target/digital-banking-control-plane-0.1.0-SNAPSHOT.jar
```

`local-demo` alone creates no RPC client or chain effect; it enables only the profile-isolated synthetic-bank API, configured local registry, and internal accounting services described above. Combined with `local-ethereum`, it retains the Phase 5C/5D handlers, Phase 6B acquisition/redemption parents, and Phase 6C registered route. Combined instead with mutually exclusive `local-solana`, Phase 7E routes those same parents to the server-owned classic-SPL mint, transfer, redemption-custody, and burn executors. Parent acceptance persists exact participant, bank, instruction, asset/unit, network, contract, wallet alias/address/version, and policy context before returning 202; delivery advances one durable boundary at a time. Callers cannot select a wallet or key alias, the recipient remains server-resolved under `AUTO_REDEEM`, and redemption cannot dispatch burn before one-time payout and payout-accounting complete. Local key files are acceptable only for this reference profile; production custody requires workload identity/secret management plus HSM, MPC, or custody infrastructure behind the same signer port.

The self-contained Phase 5A proof is the Ethereum adapter integration suite. It starts a random-session Anvil node, deploys and configures the local reference token through unlocked development RPC accounts, uses the ephemeral signer for mint authorization, and tears everything down without a committed private key:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl adapters/ethereum-web3j -am test
```

Standalone runtime composition requires both explicit profiles, a loopback Anvil endpoint on chain `31337`, and operator-provisioned local contract and recipient addresses. The profile rejects public or credentialed RPC URLs and does not deploy contracts, expose a new API, or supply default addresses:

```bash
SPRING_PROFILES_ACTIVE=local-signer,local-ethereum \
LOCAL_ETHEREUM_RPC_URL=http://127.0.0.1:8545 \
LOCAL_ETHEREUM_CONTRACT_ADDRESS="${LOCAL_TOKEN_ADDRESS}" \
LOCAL_ETHEREUM_RECIPIENT_ADDRESS="${LOCAL_RECIPIENT_ADDRESS}" \
SPRING_DATASOURCE_URL="${DB_URL}" \
SPRING_DATASOURCE_USERNAME="${DB_USERNAME}" \
SPRING_DATASOURCE_PASSWORD="${DB_PASSWORD}" \
JAVA_HOME=/opt/homebrew/opt/openjdk /opt/homebrew/opt/openjdk/bin/java \
  -jar control-plane/target/digital-banking-control-plane-0.1.0-SNAPSHOT.jar
```

## AI-assisted engineering

[AGENTS.md](AGENTS.md), [the autonomous-execution policy](AUTONOMOUS_EXECUTION_POLICY.md), architecture, ADRs, the [plan lifecycle](docs/plans/README.md), repository skills, and executable tests govern agent work. Third-party instructions and generated output never override them.

| Tool                                                   | Role and trigger                                                                                                                                                                         | Boundary                                                                                                                                                                                                                   |
| ------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [Graphify](https://github.com/safishamsi/graphify)     | Repository navigation: consult `graphify-out/` first for architecture, ownership, lifecycle, and cross-file questions, then verify against source.                                       | The tracked skill, hook, ignore policy, report, graph, and manifest are project integration. PDFs, secrets, caches, provider-backed extraction, Git hooks, and background services are excluded. Graph output is advisory. |
| [Ponytail](https://github.com/DietrichGebert/ponytail) | Simplicity pressure: invoke before adding a dependency, abstraction, wrapper, module, or speculative option; for explicit minimal/YAGNI work; and for the final over-engineering review. | Official user-installed Codex plugin only; never vendored. Its reviewed local hooks provide instructions and plugin-state bookkeeping, not evidence.                                                                       |
| [Superpowers](https://github.com/obra/superpowers)     | Engineering discipline: use the matching planning, TDD, debugging, review, and verification skill for substantial work.                                                                  | Official `openai-curated` user-installed plugin only; never vendored. Repository policy and focused financial/chain/Java skills take precedence.                                                                           |

Codex discovers canonical project skills under `.codex/skills/` through the relative `.agents/skills/` compatibility link. `.codex/prompts/` remains explicitly invoked, and execution plans keep work restartable from repository evidence rather than chat history.

### Setup, update, verification, and trust

The repository pins its reviewed Graphify integration to `graphifyy` 0.8.47. Install the executable without optional PDF or provider extras; the project skill and hook are already committed:

```bash
uv tool install "graphifyy==0.8.47"
graphify --version
test -f .agents/skills/graphify/SKILL.md
jq -e '.hooks.PreToolUse[] | select(.matcher == "^Bash$")' .codex/hooks.json
```

Do not update Graphify implicitly. After reviewing a proposed canonical release, replace `REVIEWED_VERSION` in `uv tool upgrade "graphifyy==REVIEWED_VERSION"`, rerun `graphify install --project --platform codex`, and review/reconcile every generated change, especially hook portability, instruction precedence, ignore rules, license notice, and graph artifacts.

Install the two user-level plugins from their official marketplaces, then start a new Codex task so bundled skills are loaded:

```bash
codex plugin marketplace add DietrichGebert/ponytail
codex plugin add ponytail@ponytail
codex plugin add superpowers@openai-curated
codex plugin list
```

For an update, run `codex plugin marketplace upgrade ponytail`, inspect the resolved canonical checkout and plugin hooks, then rerun the applicable `codex plugin add` command. Do the same provenance/manifest review before accepting a refreshed OpenAI-curated Superpowers version.

In the new trusted-repository task, run `/hooks`, inspect the project Graphify definition and Ponytail's three lifecycle definitions, and trust only those exact reviewed definitions. Repeat that review whenever a hook definition or plugin checkout changes. Never use a trust bypass.

The committed Graphify hook is project-scoped, resolves the reviewed executable from `PATH`, exits cleanly when Graphify is absent, propagates a present executable's failure, and calls the local-only no-op `hook-check` provided by 0.8.47. It contains no Salus or user/workstation absolute path, unlike the rejected Salus-style definition reviewed during setup.

Graph queries, reports, plugin advice, and agent suggestions are navigation aids, not implementation evidence. Confirm every material claim against authoritative source, tests, build rules, and recorded validation; never use AI output to authorize financial state changes, signing, external submission, finality, reconciliation, or production operation.

## Security and delivery direction

Never commit private keys, seed phrases, tokens, RPC credentials, HSM/custody credentials, funded addresses, or environment files. Defaults and tests must remain local-only. See [SECURITY.md](SECURITY.md).

[The implementation plan](docs/IMPLEMENTATION.md) records the Phase 3 acceptance slices, Phase 4 signing controls, bounded Phase 5 local effects, Phase 6 synthetic finance/workflow/demonstration slices, Phase 7A's native semantic gate, Phase 7B-7D's local Solana primitives, Phase 7E's reuse of the existing product parents on local Solana, and Phase 7F's reproducible local Solana demonstrations. The next bounded recommendation is the Phase 8 final reference review—not a public network or production-custody rollout.
