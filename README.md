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
| [Digital Banking Engineering Companion](https://github.com/johnwhitton/digital-banking/blob/main/docs/reference/digital-banking-engineering-companion.pdf)         | Volume II implementation and operations companion covering durable workflow, Java/Spring, wallets and signing, EVM, Solana, submission and observation, infrastructure, testing, performance, and delivery guidance. | Engineers, architects, security reviewers, operators, and technical decision makers.       | `~60-75 mins`                      |
| [Digital Banking Reference Implementation](https://github.com/johnwhitton/digital-banking/blob/main/docs/reference/digital-banking-reference-implementation.pdf)   | Volume III Reference Implementation.                                                                                                                                                                                 | Engineers, architects, security reviewers, operators, and technical decision makers.       | `~60-75 mins`                      |

The executive PDF states an 18-minute estimate. The detailed architecture contains approximately 37,700 extracted words and provides a guided reading path, so the table distinguishes a guided route from a complete technical read. The Engineering Companion is available as Volume II: a vendor-neutral implementation and operations companion, not production certification or a runnable implementation. Its code-status discussion is pinned to commit `e921fcb1877b46a6881437f46b1a6ebfa115ae58`; the live repository has advanced since that evidence snapshot, so use this README, the current [implementation plan](docs/IMPLEMENTATION.md), accepted [ADRs](docs/adr/README.md), source, and tests for current repository status. All four PDFs are immutable contextual inputs. The existing [reference index](docs/reference/README.md) retains its established provenance, checksum, metadata, and design-traceability records; [the engineering design](docs/DESIGN.md), accepted ADRs, versioned contracts, and executable tests govern implementation details.

## What This Demonstrates

- Java and Spring own the regulated control plane: authenticated business APIs, durable operations, policy coordination, workflow, persistence, reconciliation, and operational interfaces.
- Durable internal state owns business truth. A signature, transaction hash, RPC response, receipt, or commitment is evidence, not complete financial settlement.
- Exact quantities, stable operation/attempt identities, versioned idempotency, append-only evidence, four separate finalities, and ambiguous-effect recovery are explicit contracts.
- Chain SDKs, native transaction semantics, and signer/custody providers remain behind ports and adapters.
- Delivery proceeds in evidence-gated slices: prove the common lifecycle, worker/recovery, and signing authority before any local chain effect.

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
| Mint and burn operation lifecycle       | `verified` | Framework-free commands, canonical SHA-256 payloads, kind- and participant-scoped replay/conflict, lifecycle coordination, status lookup, and provider-neutral ports pass their existing gates. No signing provider or chain execution exists.                                                                                                                                       |
| Phase 3A durable acceptance and OpenAPI | `verified` | Explicit JDBC plus Flyway atomically records operation, hashed idempotency binding, audit evidence, four finalities, and one pending outbox event before HTTP 202. Real PostgreSQL tests cover rollback, concurrency, replay/conflict, restart, security, and read-back; the 302-test clean reactor gate passes.                                                                     |
| Phase 3B durable delivery worker        | `verified` | Framework-free delivery contracts plus an opt-in Spring worker durably claim PostgreSQL outbox work, fence leases, record attempts/outcomes, retry bounded failures, and recover expiry; the 335-test offline reactor passes. Phase 3C adds one transactional transfer-preparation handler, but no external business/chain effect is wired.                                          |
| Phase 3C transfer acceptance            | `verified` | A chain-neutral parent durably owns five ordered effects, server-resolved wallet context, exact amount/currency, scoped replay/conflict, PostgreSQL V3 persistence, transfer APIs, a synthetic mock-bank contract/adapter, and a transactional handler inbox that can prepare only the first withdrawal; the 364-test offline reactor passes. No bank or blockchain effect executes. |
| Phase 4A signing-authority boundary     | `implemented` | Framework-free request/attempt/key identities, distinct EVM-digest and Solana-message modes, policy/key checks, durable replay/conflict/ambiguity/inquiry, PostgreSQL V4 evidence persistence, and a test-only synthetic provider are implemented. No runtime signer, key material, public endpoint, native transaction, or chain effect exists. |

The current business API includes `POST /v1/token-operations/mints`, `POST /v1/token-operations/burns`, participant-scoped `GET /v1/token-operations/{operationId}`, `POST /v1/transfers`, and participant-scoped `GET /v1/transfers/{transferId}`. Durable transfer acceptance and effect planning are not bank movement, minting, token transfer, burning, deposit, settlement, or chain execution.

## Designed, Not Executable

- The durable provider-neutral signing boundary is implemented, but no HSM/MPC/custody signer, development signer, chain adapter, runtime composition, provider credential, or key material exists.
- Phase 3B delivery infrastructure exists and Phase 3C supplies a real transfer-accepted handler/inbox for one bounded internal preparation transition, but the default worker remains disabled and no external effect or broker publication exists.
- Independent observation, reconciliation, cases, and four-finality authority models are designed, but only the domain records and Phase 3A persistence foundation exist.
- Ethereum/Foundry/Web3j and native Solana/SPL Token approaches are accepted design directions; no contract, program, SDK, local chain, wallet, or deployment is present.
- The complete bank-to-bank workflow remains planned in the [target demonstration specification](docs/TRANSFER_DEMO.md); only parent acceptance, the five-effect plan, and first-withdrawal preparation are implemented.

## Target Demonstration

The planned [bank-to-bank stablecoin transfer demonstration](docs/TRANSFER_DEMO.md) is one durable parent workflow:

1. mock withdrawal from the sender's bank account;
2. mint the stablecoin to the sender's settlement wallet;
3. transfer the stablecoin to the recipient's settlement wallet;
4. burn the stablecoin from the recipient's settlement wallet; and
5. mock deposit to the recipient's bank account.

**Completing this flow is future work.** Phase 3C durably records the parent and all five planned effects and can transactionally prepare the first withdrawal. It does not call the mock-bank port at runtime, sign, submit to a chain, mint, transfer tokens, burn, deposit, observe, reconcile, or settle.

## Future Work

- **Transfer workflow execution:** authorize attempts and execute/inquire each bank or token effect through later adapter slices; preserve ambiguity and evidence gates before advancing.
- **HSM/MPC/custody signer implementations:** isolated local signer plus provider-neutral production authority integrations; raw production keys remain outside application memory.
- **Ethereum/Foundry/Web3j local vertical slice:** authorized mint, ERC-20 transfer, burn, deployment, observation, ambiguity/replacement, and recovery on Anvil.
- **Solana native-SVM/SPL Token local vertical slice:** mint/account setup, native mint/transfer/burn, lifetime/commitment, observation, and recovery on a local validator.
- **Independent observation and reconciliation:** versioned native evidence, provider disagreement, breaks/cases, and authorized append-only repair.
- **Integrated local environment and end-to-end tests:** complete five-step Ethereum and Solana demonstrations with restart, duplicate, timeout, and failure injection.
- **Hardening and publication-readiness evidence:** threat review, dependency/SBOM evidence, security review, runbooks, clean-room reproduction, and performance/failure budgets.

## On-Chain Development Approach

- **Ethereum/EVM:** Solidity contracts, when required, use Foundry (`forge`, `anvil`, `cast`, and Foundry scripts). Java integration uses Web3j inside its adapter; neither Web3j nor generated bindings enter the core domain.
- **Solana/SVM:** use native Solana semantics and the classic SPL Token Program for the initial path. Evaluate the Java client through the bounded gate in ADR 0003 before selecting a dependency.
- **Custom Solana execution:** add Rust with Anchor only when required business logic cannot safely use existing programs. No speculative Rust workspace is present.
- **Neon:** excluded from the baseline because the reference path prioritizes native SVM composability. Reconsideration requires a later ADR for a distinct EVM-compatibility requirement.
- **Sequencing:** complete worker/recovery and signing controls, prove one Ethereum vertical slice, then validate the same business contract against Solana's structurally different semantics.

Direct issuer-authority mint/burn and CCTP cross-chain burn/attestation/mint are separate workflows. CCTP is not the initial direct mint/burn mechanism.

## Repository map

```text
.
├── domain/                    # Plain Java domain boundary
├── application/               # Framework-free use cases and ports
├── adapters/
│   └── persistence-postgres/  # Explicit JDBC/Flyway operation, transfer, delivery, and signing evidence
├── control-plane/             # Spring APIs, reference route/wallet resolution, and opt-in worker
├── docs/
│   ├── DESIGN.md              # Canonical engineering architecture
│   ├── IMPLEMENTATION.md      # Living delivery plan and current state
│   ├── TRANSFER_DEMO.md       # Implemented acceptance mapping and remaining flow contract
│   ├── adr/                   # Accepted architectural decisions
│   ├── plans/active/          # Restartable execution plans and evidence
│   └── reference/             # Four immutable publications and index
├── .codex/                    # Project config, prompt templates, and skill sources
├── .agents/skills/            # Codex repository-skill discovery compatibility
├── graphify-out/              # Reviewed portable graph report, JSON, and manifest
├── AGENTS.md                  # Repository operating rules
├── AUTONOMOUS_EXECUTION_POLICY.md
└── SECURITY.md
```

Future executable slices may add bank, signer, and chain adapters plus `contracts/evm/`, conditional `programs/solana/`, and `integration-tests/`. They are planned paths, not current modules, and will not be created empty.

## Build and Inspect the Current Implementation

Prerequisites: JDK 25, Docker, and an internet connection for the first dependency/image resolution. On the bootstrap workstation, Homebrew's JDK is installed but not linked onto `PATH`. The verification suite starts the pinned PostgreSQL container automatically.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw --version
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw clean verify
```

To run the packaged application, provide a private/local PostgreSQL 17 database through environment variables. Flyway validates and migrates it at startup; there is no in-memory fallback.

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

## AI-assisted engineering

[AGENTS.md](AGENTS.md), [the autonomous-execution policy](AUTONOMOUS_EXECUTION_POLICY.md), architecture, ADRs, active plans, repository skills, and executable tests govern agent work. Third-party instructions and generated output never override them.

| Tool                                                   | Role and trigger                                                                                                                                                                         | Boundary                                                                                                                                                                                                                   |
| ------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [Graphify](https://github.com/safishamsi/graphify)     | Repository navigation: consult `graphify-out/` first for architecture, ownership, lifecycle, and cross-file questions, then verify against source.                                       | The tracked skill, hook, ignore policy, report, graph, and manifest are project integration. PDFs, secrets, caches, provider-backed extraction, Git hooks, and background services are excluded. Graph output is advisory. |
| [Ponytail](https://github.com/DietrichGebert/ponytail) | Simplicity pressure: invoke before adding a dependency, abstraction, wrapper, module, or speculative option; for explicit minimal/YAGNI work; and for the final over-engineering review. | Official user-installed Codex plugin only; never vendored. Its reviewed local hooks provide instructions and plugin-state bookkeeping, not evidence.                                                                       |
| [Superpowers](https://github.com/obra/superpowers)     | Engineering discipline: use the matching planning, TDD, debugging, review, and verification skill for substantial work.                                                                  | Official `openai-curated` user-installed plugin only; never vendored. Repository policy and focused financial/chain/Java skills take precedence.                                                                           |

Codex discovers canonical project skills under `.codex/skills/` through the relative `.agents/skills/` compatibility link. `.codex/prompts/` remains explicitly invoked, and active plans keep work restartable from repository evidence rather than chat history.

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

[The implementation plan](docs/IMPLEMENTATION.md) records the Phase 3A acceptance, Phase 3B worker/recovery, and Phase 3C transfer-acceptance slices and their limits. The next bounded recommendation is the provider-neutral signing-authority boundary, not chain deployment or end-to-end transfer execution.
