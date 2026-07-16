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
| Plain Java domain boundary | `scaffolded` | `domain` has no runtime dependencies and Maven Enforcer rejects framework, transport, persistence, and chain SDK dependencies. |
| Spring control-plane application | `verified` | `control-plane` starts a Spring context and exposes only Actuator health/readiness. |
| Mint and burn operation lifecycle | `planned` | Phase 2 adds exact quantities, lifecycle, idempotency, application ports, and pure tests; no value-moving endpoint exists. |
| Durable persistence and OpenAPI | `planned` | No database, migration, outbox, worker, or business API contract exists yet. |
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
├── control-plane/             # Spring Boot composition and health/readiness
├── docs/
│   ├── DESIGN.md              # Canonical engineering architecture
│   ├── IMPLEMENTATION.md      # Living delivery plan and current state
│   ├── adr/                   # Accepted architectural decisions
│   ├── plans/active/          # Restartable execution plans and evidence
│   └── reference/             # Immutable source publications and index
├── .codex/                    # Project config, prompt templates, and skill sources
├── .agents/skills/            # Codex repository-skill discovery compatibility
├── AGENTS.md                  # Repository operating rules
├── AUTONOMOUS_EXECUTION_POLICY.md
└── SECURITY.md
```

Future executable slices may add `application/`, `adapters/`, `contracts/evm/`, `programs/solana/`, and `integration-tests/`. They are planned paths, not current modules, and will not be created empty.

## Local quick start

Prerequisites: JDK 25 and an internet connection on the first Maven-wrapper run. On the bootstrap workstation, Homebrew's JDK is installed but not linked onto `PATH`.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw --version
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw clean verify
```

Start the application in terminal 1:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk /opt/homebrew/opt/openjdk/bin/java \
  -jar control-plane/target/digital-banking-control-plane-0.1.0-SNAPSHOT.jar
```

Check readiness in terminal 2:

```bash
curl --fail --silent http://localhost:8080/actuator/health/readiness
```

The response has status `UP`. Stop the application with `Ctrl-C`. No RPC URL, database, key, wallet, custody account, or chain process is required.

## AI-assisted engineering

- [AGENTS.md](AGENTS.md) defines architecture, Git safety, testing, documentation, and handoff rules.
- [The autonomous-execution policy](AUTONOMOUS_EXECUTION_POLICY.md) distinguishes reversible implementation autonomy from decisions requiring user authority.
- Repository-local skills encode repeated workflows for plans, Java/Spring changes, chain adapters, financial invariants, and documentation synchronization.
- `.codex/prompts/` contains explicitly invoked templates; tests, build rules, diff review, and recorded validation determine correctness.
- Active plans make work restartable from the repository rather than chat history.

Codex discovers compatibility entries under `.agents/skills/`; canonical sources remain under `.codex/skills/`.

## Security and delivery direction

Never commit private keys, seed phrases, tokens, RPC credentials, HSM/custody credentials, funded addresses, or environment files. Defaults and tests must remain local-only. See [SECURITY.md](SECURITY.md).

[The implementation plan](docs/IMPLEMENTATION.md) proves the common domain and durable lifecycle before either chain adapter. The next bounded phase after the lifecycle remains a durable API and persistence slice, not chain deployment.
