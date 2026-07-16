# Digital Banking Reference Implementation

Reference implementation for a regulated digital-asset settlement control plane. The project is intended to demonstrate production-oriented boundaries, durable workflow, explicit evidence, and failure handling while remaining deliberately non-production software.

> **Safety boundary:** This repository is research and reference software. Do not use it with real funds, production credentials, mainnet accounts, or production signing authority. Nothing here is a compliance, legal, security, or operational certification.

## Source publications: attachment blocker

The action names two source publications, but the exact attachment bundle contains neither PDF. No similarly titled local file is committed as a substitute:

1. *Digital Asset Settlement for Zelle: Executive Architecture Brief for Stablecoin and Cross-Border Settlement* - requested as `zelle-digital-asset-settlement-executive-brief(1).pdf`.
2. *Designing a Stablecoin Settlement Platform for Existing Real-Time Payment Networks: A Reference Architecture Using Zelle as a Public Case Study* - requested as `stablecoin-settlement-reference-architecture(5).pdf`.

Zelle is used only as a public case study in those publications. This repository does not represent Early Warning Services or Zelle production architecture, confidential information, selected vendors, or an announced implementation plan. The codebase is organization-neutral and is named **Digital Banking Reference Implementation**.

The PDFs are contextual architecture inputs. [The engineering design](docs/DESIGN.md), accepted [architecture decision records](docs/adr/README.md), future API contracts, and executable tests govern implementation details. The [reference index](docs/reference/README.md) records canonical metadata, intended normalized paths, and the exact missing-input blocker.

## Architectural thesis

- Java and Spring own the regulated control plane: business APIs, durable operations, policy coordination, workflow, persistence, reconciliation, and operational interfaces.
- The internal ledger and durable workflow own business truth. A transaction hash or successful RPC response is evidence, not complete financial settlement.
- Chain SDKs, transaction encodings, signing providers, and native network semantics remain behind explicit ports and adapters.
- External effects are observed independently and reconciled using stable operation, attempt, and native evidence identities.
- Mint and burn are privileged asynchronous operations with idempotency, policy, approval, attempt, evidence, and status - never thin wrappers around application-held keys.
- Blockchain, legal, customer-visible, and accounting finality remain separate judgments.

## Current capability

Status vocabulary:

- `planned` - described and sequenced, with no implementation claim.
- `scaffolded` - boundary or build structure exists but the capability is not delivered.
- `implemented` - executable behavior exists with focused tests.
- `verified` - the applicable repository validation gate has been run successfully and recorded.
- `blocked` - a named external input is missing, so the capability cannot pass its gate.

| Capability | Status | Evidence and limitation |
| --- | --- | --- |
| Architecture and repository policy | `verified` | `AGENTS.md`, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, ADR 0001, and the active bootstrap plan are checked in and cross-linked. |
| Exact source publication attachments | `blocked` | The request bundle contains neither named PDF; no substitute is committed. See `docs/reference/README.md`. |
| Plain Java domain boundary | `scaffolded` | The `domain` Maven module has no runtime dependencies and an Enforcer rule rejects Spring, chain SDK, HTTP, and persistence dependencies. Domain behavior begins in the next slice. |
| Spring control-plane application | `verified` | The `control-plane` module starts a Spring context and exposes only Actuator health/readiness; tests run through the committed Maven wrapper. |
| Mint and burn operation lifecycle | `planned` | No mint or burn business endpoint exists in this bootstrap. |
| Durable persistence and OpenAPI | `planned` | No database, migration, outbox, or business API contract is present yet. |
| HSM, MPC, or custody signing | `planned` | Only the provider-neutral boundary is designed; there is no signing implementation or key material. |
| Ethereum/Web3j adapter | `planned` | Web3j is intentionally absent until a tested Ethereum vertical slice. |
| Solana Java/Rust adapter | `planned` | Solana SDKs and Rust programs are intentionally absent until a tested Solana vertical slice. |
| Observation and reconciliation | `planned` | Independent observation contracts are designed but not implemented. |
| Docker Compose and end-to-end environment | `planned` | Compose is not introduced in this foundation action. |

## Repository map

```text
.
├── domain/                    # Plain Java domain boundary; behavior follows in Action Request 02
├── control-plane/             # Spring Boot entry point and health/readiness tests
├── docs/
│   ├── DESIGN.md              # Canonical engineering architecture
│   ├── IMPLEMENTATION.md      # Living delivery plan and current state
│   ├── adr/                   # Accepted architectural decisions
│   ├── plans/active/          # Restartable execution plans and evidence
│   └── reference/             # Publication index; exact PDF attachments are blocked
├── .codex/                    # Project config, prompt templates, and workflow skill sources
├── .agents/skills/            # Native Codex repository-skill discovery compatibility
├── AGENTS.md                  # Repository operating rules for human and AI contributors
└── SECURITY.md                # Research-use and secret-handling policy
```

Future slices may add adapter, contract/program, persistence, deployment, and integration-test areas only when each has executable purpose and a recorded decision. Empty speculative trees are intentionally absent.

## Local quick start

Prerequisites: JDK 25 and an internet connection on the first Maven-wrapper run. On the bootstrap workstation, Homebrew's JDK was installed but not linked onto `PATH`, so the following exact commands were validated.

Build in either terminal:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw --version
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw clean verify
```

Start the application in terminal 1:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk /opt/homebrew/opt/openjdk/bin/java \
  -jar control-plane/target/digital-banking-control-plane-0.1.0-SNAPSHOT.jar
```

After the application reports that it started, check readiness in terminal 2:

```bash
curl --fail --silent http://localhost:8080/actuator/health/readiness
```

The final command returns a JSON document with status `UP`. Stop the application with `Ctrl-C`. On another workstation, set `JAVA_HOME` to that system's JDK 25 location.

No RPC URL, database, key, wallet, custody account, or chain process is required for this bootstrap.

## AI-assisted engineering

AI-assisted changes are governed like any other engineering work:

- `AGENTS.md` defines durable architecture, Git safety, testing, documentation, and handoff rules.
- Repository-local skills encode repeated workflows for plans, Java/Spring changes, chain adapters, financial invariants, and documentation synchronization.
- `.codex/prompts/` contains explicitly invoked prompt templates. Codex CLI 0.142.1 does not auto-load repository prompt directories, so these files are shared templates rather than hidden automation.
- `.codex/hooks.json` is valid but intentionally enables no command hooks. The Salus source hook depended on a user-specific absolute Graphify path and was not portable or necessary here.
- Active plans record decisions, acceptance gates, commands, and evidence so work can restart from the repository rather than chat history.
- Tests, build rules, diff review, and recorded validation determine correctness. Unreviewed AI output is not evidence.

Codex natively discovers repository skills under `.agents/skills/`. This repository keeps the requested skill sources under `.codex/skills/` and exposes them through `.agents/skills/` compatibility links. Invoke a matching skill by naming it in the task (for example, `Use digital-banking-doc-sync`) or by asking for work matching its trigger; always confirm the project is trusted before relying on project config or skills.

## Security

- Never commit private keys, seed phrases, API tokens, RPC credentials, HSM credentials, custody credentials, real addresses associated with funds, or environment files.
- Never point defaults, tests, or examples at mainnet or public testnets.
- Never use this repository for real funds or production settlement.
- Treat development signing, when later introduced, as isolated test-only authority with no upgrade path to production.
- Report suspected secret exposure and follow the response steps in [SECURITY.md](SECURITY.md).

## Delivery direction

[The implementation plan](docs/IMPLEMENTATION.md) sequences the work so the common domain and durable operation lifecycle are proved before either chain adapter. The recommended next slice is exact quantities, mint/burn commands, idempotency, operation and attempt identity, lifecycle transitions, chain and signer ports, and pure tests.
