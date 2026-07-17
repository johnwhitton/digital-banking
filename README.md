# Digital Banking Reference Implementation

Reference implementation for a regulated digital-asset settlement control plane. It is designed to demonstrate production-oriented boundaries, durable workflow, explicit evidence, and failure handling while remaining deliberately non-production software.

> **Safety boundary:** This repository is research and reference software. Do not use it with real funds, production credentials, mainnet accounts, public testnets, or production signing authority. Nothing here is a compliance, legal, security, or operational certification.

## Source publications

The architecture starts from two user-supplied publications:

1. [*Designing a Stablecoin Settlement Platform for Existing Real-Time Payment Networks*](docs/reference/stablecoin-settlement-reference-architecture.pdf) - detailed reference architecture using Zelle as a public case study.
2. [*Digital Asset Settlement for Zelle*](docs/reference/zelle-digital-asset-settlement-executive-brief.pdf) - executive architecture brief for stablecoin and cross-border settlement.

The PDFs are immutable contextual inputs. [The reference index](docs/reference/README.md) records their provenance, checksums, and design traceability. [The engineering design](docs/DESIGN.md), accepted [ADRs](docs/adr/README.md), versioned contracts, and executable tests govern implementation details.

Zelle appears only as a public case study. This repository does not represent Early Warning Services or Zelle production architecture, confidential information, selected vendors, or an announced implementation plan.

## Architectural thesis

- Java and Spring own the regulated control plane: business APIs, durable operations, policy coordination, workflow, persistence, reconciliation, and operational interfaces.
- The internal ledger and durable workflow own business truth. A transaction hash or successful RPC response is evidence, not complete financial settlement.
- Chain SDKs, transaction encodings, signing providers, and native network semantics remain behind explicit ports and adapters.
- Mint and burn are privileged asynchronous operations with exact quantity, idempotency, approval, attempt, evidence, and status semantics.
- Blockchain, legal, customer-visible, and accounting finality remain separate judgments.
- Ambiguous submission triggers inquiry and observation, never blind resubmission.

## Current capability

Status vocabulary:

- `planned` - described and sequenced, with no implementation claim.
- `scaffolded` - boundary or build structure exists but the capability is not delivered.
- `implemented` - executable behavior exists with focused tests.
- `verified` - the applicable repository validation gate has run successfully and is recorded.
- `blocked` - a named external input is missing, so the capability cannot pass its gate.

| Capability | Status | Evidence and limitation |
| --- | --- | --- |
| Foundation and source publications | `verified` | Both supplied PDFs are byte-verified under `docs/reference/`; architecture, repository policy, Maven reactor, and health/readiness application are synchronized. |
| Plain Java domain boundary | `verified` | Exact asset/unit quantities, stable IDs, guarded lifecycle, attempt lineage, append-only evidence, and four finalities pass the Phase 2 gate; `domain` has no runtime dependencies. |
| Spring control-plane application | `verified` | `control-plane` composes the durable PostgreSQL adapter, exposes health/readiness and three secured business resources, and serves one design-first OpenAPI contract. Business resources deny requests until a future identity adapter supplies an authenticated `ParticipantPrincipal`. |
| Mint and burn operation lifecycle | `verified` | Framework-free commands, canonical SHA-256 payloads, kind- and participant-scoped replay/conflict, lifecycle coordination, status lookup, and provider-neutral ports pass 269 domain/application tests. No signing or chain execution exists. |
| Phase 3A durable acceptance and OpenAPI | `verified` | Explicit JDBC plus Flyway atomically records operation, hashed idempotency binding, audit evidence, four finalities, and one pending outbox event before HTTP 202. Real PostgreSQL tests cover rollback, concurrency, replay/conflict, restart, security, and read-back; the 302-test clean reactor gate passes. |
| Phase 3B asynchronous worker | `planned` | No outbox polling, leasing, publication, retry, inbox, scheduler, or operation processing exists. A pending outbox row is not an execution claim. |
| HSM, MPC, or custody signing | `planned` | Only the provider-neutral authority boundary is designed; no signer implementation or key material exists. |
| Ethereum/Web3j adapter | `planned` | The later Ethereum-first slice uses Solidity/Foundry and Web3j only after its own gate. |
| Solana Java adapter | `planned` | The later native-SVM slice begins with SPL Token and a bounded Java-client evaluation. |
| Observation and reconciliation | `planned` | Independent observation and reconciliation are designed but not implemented. |

## On-chain development approach

- **Ethereum/EVM:** Solidity contracts, when required, use Foundry (`forge`, `anvil`, `cast`, and Foundry scripts). Java integration uses Web3j in an adapter; neither Web3j nor generated bindings enter the core domain.
- **Solana/SVM:** use native Solana semantics and the classic SPL Token Program for the initial Circle-USDC-aligned path. Evaluate Sava through a bounded dependency spike before selecting any Java SDK.
- **Custom Solana execution:** add Rust with Anchor only when required business logic cannot safely use existing programs. No speculative Rust workspace is present.
- **Neon:** excluded from the baseline because the reference path prioritizes native SVM composability. Reconsideration requires a later ADR for a distinct EVM-compatibility requirement.
- **Sequencing:** prove the common lifecycle, then one Ethereum vertical slice, then validate the same contracts against Solana's structurally different semantics.

Direct issuer-authority mint/burn and CCTP cross-chain burn/attestation/mint are separate workflows. CCTP is not the initial direct mint/burn mechanism.

## Repository map

```text
.
├── domain/                    # Plain Java domain boundary
├── application/               # Framework-free use cases and ports
├── adapters/
│   └── persistence-postgres/  # Explicit JDBC, Flyway schema, durable acceptance/outbox
├── control-plane/             # Spring Boot API, security, OpenAPI, and composition
├── docs/
│   ├── DESIGN.md              # Canonical engineering architecture
│   ├── IMPLEMENTATION.md      # Living delivery plan and current state
│   ├── adr/                   # Accepted architectural decisions
│   ├── plans/active/          # Restartable execution plans and evidence
│   └── reference/             # Immutable source publications and index
├── .codex/                    # Project config, prompt templates, and skill sources
├── .agents/skills/            # Codex repository-skill discovery compatibility
├── graphify-out/              # Reviewed portable graph report, JSON, and manifest
├── AGENTS.md                  # Repository operating rules
├── AUTONOMOUS_EXECUTION_POLICY.md
└── SECURITY.md
```

Future executable slices may add signer/chain adapters, `contracts/evm/`, `programs/solana/`, and `integration-tests/`. They are planned paths, not current modules, and will not be created empty.

## Local quick start

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

The response has status `UP`. The authoritative contract is served at `/openapi/token-operations-v1.yaml`. Business endpoints return 401 in the default repository configuration because no issuer, decoder, local user, password, or token is configured; integration tests inject fixture-only identities and authorities. Stop the application with `Ctrl-C`. No RPC URL, key, wallet, custody account, chain process, or public service is required.

## AI-assisted engineering

[AGENTS.md](AGENTS.md), [the autonomous-execution policy](AUTONOMOUS_EXECUTION_POLICY.md), architecture, ADRs, active plans, repository skills, and executable tests govern agent work. Third-party instructions and generated output never override them.

| Tool | Role and trigger | Boundary |
| --- | --- | --- |
| [Graphify](https://github.com/safishamsi/graphify) | Repository navigation: consult `graphify-out/` first for architecture, ownership, lifecycle, and cross-file questions, then verify against source. | The tracked skill, hook, ignore policy, report, graph, and manifest are project integration. PDFs, secrets, caches, provider-backed extraction, Git hooks, and background services are excluded. Graph output is advisory. |
| [Ponytail](https://github.com/DietrichGebert/ponytail) | Simplicity pressure: invoke before adding a dependency, abstraction, wrapper, module, or speculative option; for explicit minimal/YAGNI work; and for the final over-engineering review. | Official user-installed Codex plugin only; never vendored. Its reviewed local hooks provide instructions and plugin-state bookkeeping, not evidence. |
| [Superpowers](https://github.com/obra/superpowers) | Engineering discipline: use the matching planning, TDD, debugging, review, and verification skill for substantial work. | Official `openai-curated` user-installed plugin only; never vendored. Repository policy and focused financial/chain/Java skills take precedence. |

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

[The implementation plan](docs/IMPLEMENTATION.md) records the completed Phase 3A durable-acceptance slice and its limits. The next bounded recommendation is Phase 3B outbox worker/recovery mechanics, not signing or chain deployment.
