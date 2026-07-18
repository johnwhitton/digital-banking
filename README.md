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
- Delivery proceeds in evidence-gated slices: the common lifecycle, worker/recovery, and signing authority now support one bounded local-Ethereum mint effect without implying the complete transfer workflow.

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
| Spring control-plane application        | `verified` | `control-plane` composes the durable PostgreSQL adapter, exposes health/readiness plus token-operation and transfer resources, and serves one design-first OpenAPI contract. Business resources deny requests until a future identity adapter supplies an authenticated `ParticipantPrincipal`.                                                                                      |
| Mint and burn operation lifecycle       | `verified` | Framework-free commands, canonical SHA-256 payloads, kind- and participant-scoped replay/conflict, lifecycle coordination, status lookup, and provider-neutral ports pass their existing gates. Phase 5A connects only accepted mints to the explicit local-Ethereum profile; burns remain acceptance-only.                                                                          |
| Phase 3A durable acceptance and OpenAPI | `verified` | Explicit JDBC plus Flyway atomically records operation, hashed idempotency binding, audit evidence, four finalities, and one pending outbox event before HTTP 202. Real PostgreSQL tests cover rollback, concurrency, replay/conflict, restart, security, and read-back; its historical closeout gate recorded 302 tests.                                                                     |
| Phase 3B durable delivery worker        | `verified` | Framework-free delivery contracts plus an opt-in Spring worker durably claim PostgreSQL outbox work, fence leases, record attempts/outcomes, retry bounded failures, and recover expiry; its historical closeout gate recorded 335 tests. Phase 3C adds one transactional transfer-preparation handler, but no external business/chain effect is wired.                                          |
| Phase 3C transfer acceptance            | `verified` | A chain-neutral parent durably owns five ordered effects, server-resolved wallet context, exact amount/currency, scoped replay/conflict, PostgreSQL V3 persistence, transfer APIs, a synthetic mock-bank contract/adapter, and a transactional handler inbox that can prepare only the first withdrawal; its historical closeout gate recorded 364 tests. No bank or blockchain effect executes. |
| Phase 4 signing boundary               | `verified` | Phase 4A supplies durable authority, replay/conflict, ambiguity/inquiry, and PostgreSQL V4 evidence. Phase 4B adds one explicitly enabled `local-signer` profile with session-only secp256k1 and Ed25519 keys, exact-material signatures, and no public endpoint or chain effect. Production custody remains absent. |
| Phase 5A local Ethereum mint           | `verified` | One explicit `local-ethereum` + `local-signer` path processes an accepted mint on Anvil through a durable nonce/attempt fence, exact EIP-1559 signing, submit-once ambiguity recovery, and independent receipt/event/canonicality observation. It advances only technical operation state and blockchain finality. |
| Phase 5B configured local custody      | `verified` | The opt-in `local-demo` profile requires ten deterministic, server-owned EVM identities behind one immutable provider-neutral registry. Keys are supplied only from ignored local process configuration, addresses and stable public versions are derived from those keys, purpose/address/version mismatches fail closed, and no API or chain effect is added. |

The current full offline reactor result is **426 passing Maven tests across seven modules**. The unchanged Foundry contract suite last recorded **five passing tests** in the Phase 5A closeout and was not rerun because Phase 5B changes no Solidity or chain effect. The current business API includes `POST /v1/token-operations/mints`, `POST /v1/token-operations/burns`, participant-scoped `GET /v1/token-operations/{operationId}`, `POST /v1/transfers`, and participant-scoped `GET /v1/transfers/{transferId}`. Durable transfer acceptance and effect planning are not bank movement, minting, token transfer, burning, deposit, settlement, or chain execution.

## Designed, Not Executable

- Burn requests are durably accepted but are not executed on Ethereum, and wallet-to-wallet Ethereum transfer is not implemented.
- The settlement-only bank-to-bank parent flow is not orchestrated end to end. Phase 3C records its existing five-effect aggregate and first-withdrawal preparation; the future six-step Demo A adds a distinct redemption transfer before ADMIN burn, and mock-bank effects are not executed at runtime.
- Default business endpoints have no runtime identity provider and therefore deny access.
- The durable provider-neutral signing boundary, session-ephemeral signer, and configured local-custody signer are limited to their explicit local profiles; no production HSM, MPC, secret-manager, or custody signer exists.
- No public network, hosted RPC provider, API key, dynamic wallet-management service, or production deployment exists. Solana remains planned.
- The Anvil mint establishes only narrow technical operation state and blockchain finality; it does not establish accounting, legal, or customer-visible finality.

## Product paths and delivery roadmap

The reference architecture supports two distinct USDZELLE outcomes without conflating economic ownership with signing custody:

| Path | Customer outcome | On-chain holders/signers | Current state | Target |
| --- | --- | --- | --- | --- |
| Settlement-only | User sends and receives fiat; USDZELLE is an institutional settlement asset inside the saga | `ADMIN` plus bank settlement and redemption wallets | Phase 3C parent/effect acceptance, local mint, and named local wallet/signing identities are implemented; remaining effects and orchestration are planned | Demo A on Ethereum, then Solana |
| User-held USDZELLE | User can acquire, hold, optionally transfer, and later redeem USDZELLE | `ADMIN` plus segregated custodial user wallets in the local POC | Local mint and segregated local custody identities are implemented; reserve, transfer, redemption, and burn are planned | Demo B on Ethereum, then Solana |

```text
Implemented now: domain + durable API/worker + transfer parent + signing + local Ethereum mint + configured local custody
Next: Ethereum transfer -> Ethereum redemption/burn
Then: synthetic reserve/mock banks -> both Ethereum demos
After Ethereum: native Solana parity -> both Solana demos
Finally: code/security/share-readiness review
```

The authoritative [design](docs/DESIGN.md) owns architecture and custody/reserve boundaries; the [implementation roadmap](docs/IMPLEMENTATION.md) owns phase status and completed-plan links; the [demo contract](docs/TRANSFER_DEMO.md) specifies Demo A and Demo B; and the [plan lifecycle](docs/plans/README.md) governs authorization and closeout. No future phase is active until separately authorized.

The `local-demo` profile now composes an immutable startup registry for `CONTRACT_OWNER`/`CONTRACT_DEPLOYER`, `ADMIN`/`ADMIN_REDEMPTION`, four bank-settlement wallets, and four user wallets. It does not persist keys, dynamically manage wallets, activate the Phase 5A chain path, or execute a transfer. The local Phase 5A mint continues to use the separate session-ephemeral signer. The reserve subsystem, executable wallet transfer, Ethereum burn, runtime bank effect, complete parent orchestration, Docker Compose/demo script, Solana adapter, production custody, public networks/RPC, real funds or reserve assets, yield, revenue sharing, and accounting/legal/customer-finality claims remain absent.

## On-Chain Development Approach

- **Ethereum/EVM:** Solidity contracts, when required, use Foundry (`forge`, `anvil`, `cast`, and Foundry scripts). Java integration uses Web3j inside its adapter; neither Web3j nor generated bindings enter the core domain.
- **Solana/SVM:** use native Solana semantics and the classic SPL Token Program for the initial path. Evaluate the Java client through the bounded gate in ADR 0003 before selecting a dependency.
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
│   ├── persistence-postgres/  # Explicit JDBC/Flyway operation, transfer, delivery, and signing evidence
│   ├── signer-local/          # Explicit-profile, in-memory local-development signing only
│   └── ethereum-web3j/        # Isolated local-Anvil mint construction, submission, and observation
├── contracts/evm/             # Foundry project and minimal role-gated local reference token
├── control-plane/             # Spring APIs plus explicit worker/signer/local-Ethereum composition
├── docs/
│   ├── DESIGN.md              # Canonical engineering architecture
│   ├── IMPLEMENTATION.md      # Living delivery plan and current state
│   ├── TRANSFER_DEMO.md       # Implemented acceptance mapping and remaining flow contract
│   ├── adr/                   # Accepted architectural decisions
│   ├── plans/
│   │   ├── README.md          # Authoritative active/completed/blocked lifecycle
│   │   ├── active/            # Currently executing plans; empty except its directory guide
│   │   └── completed/         # Closed plans and historical execution evidence
│   └── reference/             # Four immutable publications and index
├── .codex/                    # Project config, prompt templates, and skill sources
├── .agents/skills/            # Codex repository-skill discovery compatibility
├── graphify-out/              # Reviewed portable graph report, JSON, and manifest
├── AGENTS.md                  # Repository operating rules
├── AUTONOMOUS_EXECUTION_POLICY.md
└── SECURITY.md
```

Future executable slices may add a runtime bank adapter, the accepted conditional Solana paths, and broader integration orchestration. They remain planned and will not be created empty.

## Build and Inspect the Current Implementation

Prerequisites: JDK 25, Docker, Foundry 1.5.1, and local Solidity 0.8.25; the first dependency/image resolution also needs an internet connection. On the bootstrap workstation, Homebrew's JDK is installed but not linked onto `PATH`. `./mvnw` is the repository-pinned Maven Wrapper. Maven/Testcontainers verification starts disposable PostgreSQL in Docker automatically, and the Ethereum integration suite starts a disposable Anvil process.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw --version
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw clean verify
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

`local-demo` accepts only chain ID `31337`, derives and validates every address, fails when a required identity is incomplete, and is mutually exclusive with `local-signer`. It creates no RPC client, contract action, transaction, business endpoint, or automatic Phase 5A composition. These environment variables are acceptable only for this local reference profile; production custody requires workload identity/secret management plus HSM, MPC, or custody infrastructure behind the same signer port.

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

[The implementation plan](docs/IMPLEMENTATION.md) records the Phase 3 acceptance slices, Phase 4 signing controls, bounded Phase 5A local mint, Phase 5B configured local custody, and separately authorized future phases with their limits. The next bounded recommendation is Phase 5C Ethereum generic wallet transfer—not end-to-end orchestration, burn, or production custody.
