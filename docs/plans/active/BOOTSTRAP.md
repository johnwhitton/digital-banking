# Digital Banking Reference Implementation Bootstrap Plan

> **Execution rule:** Work through this checklist in order, preserve evidence in this file, and create one coherent final commit only after every unblocked acceptance check passes.

**Goal:** Establish the repository policy, architecture, AI-assisted engineering workflow, publication-input provenance, and smallest verified Java/Spring application foundation for later durable mint/burn vertical slices; keep missing exact source attachments explicit rather than substituting them.

**Architecture:** Use a Maven reactor with a dependency-free `domain` module and a Spring Boot `control-plane` module. The application exposes only Actuator health probes. Documentation owns the future operation lifecycle and adapter contracts; no chain SDK, signing implementation, persistence, business endpoint, or false settlement behavior is introduced in this action.

**Tech stack:** OpenJDK 25, Apache Maven 3.9.16 through Maven Wrapper 3.3.4, Spring Boot 4.0.6 / Spring Framework 7.0.x, JUnit through Spring Boot test starters, Markdown, and repository-local Codex guidance.

## Global constraints

- Product name: **Digital Banking Reference Implementation**.
- Zelle is a public case study only; make no claim about confidential or deployed EWS/Zelle architecture.
- The Java domain remains independent of Spring, Web3j, Solana SDKs, HTTP, persistence, and provider models.
- Mint/burn remain privileged, asynchronous, durable operations; do not create business endpoints in this action.
- A chain response or transaction hash is evidence, not business settlement truth.
- Preserve chain, legal, customer-visible, and accounting finality as distinct judgments.
- Use exact decimal/integer quantities only; never binary floating point for money or token amounts.
- Keep signing behind a provider-neutral authority boundary; never commit keys or credentials.
- Do not configure mainnet, public testnets, real value, or production credentials.
- Treat `/Users/johnwhitton/dev/jincubator/salus` and its worktrees as read-only.
- Do not add Web3j, a Solana SDK, Solidity, Rust, Compose, persistence, or OpenAPI until a slice uses and tests it.
- Use the single commit message `chore: bootstrap digital banking reference implementation` after final validation.

## Baseline evidence

| Item | Observation |
| --- | --- |
| Target | `/Users/johnwhitton/dev/johnwhitton/digital-banking` |
| Starting branch | `main` |
| Starting SHA | `2e7ec1f0fe700de3b7072b7995371b4727b2c535` |
| Worktree | Clean; only tracked file was the initial `README.md` |
| Remote | `git@github.com:johnwhitton/digital-banking.git` (approved SSH equivalent) |
| Live remote HEAD | `main` at `2e7ec1f0fe700de3b7072b7995371b4727b2c535` |
| Java | Homebrew OpenJDK `25.0.2`, available at `/opt/homebrew/opt/openjdk` but not linked onto `PATH` |
| Build tools | Maven and Gradle absent from `PATH`; Docker 28.5.1 and Compose 2.40.3 available |
| Codex | `codex-cli 0.142.1`; project config and hooks supported for trusted repositories |
| Attachments | Request bundle contains only `pasted-text.txt`; neither exact named PDF is available, and locally built lookalikes are not accepted as substitutes |
| Salus | Clean `main` at `fd9ffaf0d569ebaa232575d143e00488d31a2974`; inspected read-only |

## Salus `.codex` asset disposition

| Source asset | Disposition | Bootstrap result and reason |
| --- | --- | --- |
| `.codex/config.toml` | replace | Remove `approval_policy = "never"` and machine/worktree trust paths; keep only supported, repository-portable settings. |
| `.codex/hooks.json` | replace | Do not retain the absolute-path Graphify command; use a valid empty hook set until a portable deterministic enforcement hook has a concrete need. |
| `behavior-parity` | omit | This is a new implementation, not a legacy behavior migration; financial/state invariants receive a focused replacement skill. |
| `execplan-execution` | adapt | Create `plan-execution` using this repository's active-plan, validation, and documentation rules. |
| `graphify` | omit | The source is byte-identical to the globally installed skill and depends on external tooling; duplicating it would add a stale, non-portable repo copy. |
| `modularization` | omit | Generic guidance is available globally and is not needed for the minimal bootstrap; module boundaries are enforced directly. |
| `salus-doc-sync` | adapt | Create `digital-banking-doc-sync` for `README.md`, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, ADRs, and active plans. |
| `salus-engineering` | adapt | Create `digital-banking-engineering` as the repository workflow coordinator. |
| `salus-external-publications` | omit | Publication authoring and Salus disclosure rules are outside this implementation repository's scope. |
| `salus-migration-slice` | omit | No repository migration workflow exists. |
| `salus-rust-change` | replace | Create focused Java/Spring and chain-adapter skills; defer a Solana-program Rust skill until a real Rust/Anchor or native Solana workspace exists. |
| New focused skill | replace | Create `financial-state-invariants` to review exact quantities, idempotency, attempt identity, finalities, ambiguous effects, and reconciliation. |

## Task 1: Repository policy and architecture documents

**Files:**

- Replace: `README.md`
- Create: `AGENTS.md`
- Create: `SECURITY.md`
- Create: `docs/DESIGN.md`
- Create: `docs/IMPLEMENTATION.md`
- Create: `docs/adr/README.md`
- Create: `docs/adr/0001-maven-reactor-and-module-boundaries.md`
- Create: `docs/reference/README.md`

- [x] Write the repository purpose, current capability table, evidence disclaimer, repository map, security boundary, and verified quick start.
- [x] Define the implementable architecture, operation terminology, durable lifecycle, identities, exact quantities, ports/adapters, native chain differences, signer authority, persistence/reconciliation, finalities, security, observability, and deferred decisions.
- [x] Record Maven and the two-module reactor as an accepted bootstrap decision; do not create speculative ADRs.
- [x] Record the nine evidence-gated delivery phases and recommend the domain/operation-lifecycle slice next.
- [x] Check Markdown for placeholders, broken relative links, stale Salus runtime language, and claims unsupported by code or tests.

## Task 2: Repository-local Codex operating model

**Files:**

- Create: `.codex/config.toml`
- Create: `.codex/hooks.json`
- Create: `.codex/prompts/*.md`
- Create: `.codex/skills/*/SKILL.md`
- Create: `.agents/skills/*` discovery links or wrappers if required by the installed Codex version

- [x] Establish a failing baseline scenario before each new skill, then author and forward-test that skill before creating the next one.
- [x] Create the six retained skills: `digital-banking-engineering`, `digital-banking-doc-sync`, `plan-execution`, `java-spring-control-plane-change`, `chain-adapter-change`, and `financial-state-invariants`.
- [x] Validate YAML metadata, trigger descriptions, referenced paths, and repository-correct commands for every skill.
- [x] Create reusable prompt templates for bounded planning, doc synchronization, mint/burn lifecycle review, chain semantic leakage review, and verification handoff.
- [x] Document that `.codex/prompts` is a checked-in template library, not an auto-loaded prompt directory in Codex CLI 0.142.1.
- [x] Keep hooks empty and valid; document why the Salus absolute-path Graphify hook was not enabled.

## Task 3: Minimal Java/Spring foundation (TDD)

**Files:**

- Create: `pom.xml`
- Create: `.mvn/wrapper/maven-wrapper.properties`
- Generate: `mvnw`, `mvnw.cmd`
- Create: `domain/pom.xml`
- Create: `domain/src/main/java/io/github/johnwhitton/digitalbanking/domain/package-info.java`
- Create: `control-plane/pom.xml`
- Create: `control-plane/src/test/java/io/github/johnwhitton/digitalbanking/DigitalBankingApplicationTests.java`
- Create: `control-plane/src/test/java/io/github/johnwhitton/digitalbanking/HealthReadinessSmokeTests.java`
- Create: `control-plane/src/main/java/io/github/johnwhitton/digitalbanking/DigitalBankingApplication.java`
- Create: `control-plane/src/main/resources/application.yaml`

- [x] Create the Maven reactor and wrapper configuration, pin Maven 3.9.16, and include its distribution checksum.
- [x] Add a Maven Enforcer rule in `domain` banning Spring, Web3j, Solana SDK, HTTP, and persistence dependencies.
- [x] Write the application-context and readiness smoke tests before the application class.
- [x] Run the targeted tests and observe the expected failure because the application class is absent.
- [x] Add only the `@SpringBootApplication` entry point and safe health/readiness configuration.
- [x] Re-run targeted tests and the full reactor until green.
- [x] Confirm no controller or business endpoint exists and that only Actuator health is exposed.

The intended production entry point is:

```java
@SpringBootApplication
public class DigitalBankingApplication {
    public static void main(String[] args) {
        SpringApplication.run(DigitalBankingApplication.class, args);
    }
}
```

The readiness test must call `/actuator/health/readiness` and assert HTTP 200 plus status `UP`; it must not simulate mint or burn behavior.

## Task 4: Immutable reference publications

**Files:**

- Create by byte-for-byte copy: `docs/reference/digital-asset-settlement-executive-brief.pdf`
- Create by byte-for-byte copy: `docs/reference/stablecoin-settlement-reference-architecture.pdf`

- [ ] Copy the exact supplied attachments without rewriting or optimizing them. **Blocked:** neither named PDF exists in the request attachment directory.
- [x] Record requested attachment filenames, canonical metadata, roles, normalized targets, and the exact missing-input blocker in `docs/reference/README.md`.
- [ ] Compare each exact source and destination with both SHA-256 and `cmp`. **Blocked:** source attachments unavailable.

## Task 5: Repository hygiene and acceptance validation

**Files:**

- Create: `.editorconfig`
- Create: `.gitattributes`
- Create: `.gitignore`
- Update: this plan with exact evidence and closeout state

- [x] Run Git/worktree and live remote verification.
- [ ] Run PDF source/copy SHA-256 and byte comparisons. **Blocked:** exact source attachments unavailable.
- [x] Run `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw --version`.
- [x] Run `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw clean verify`.
- [x] Run the targeted Spring context and readiness smoke tests.
- [x] Validate Markdown links and repository-local skill metadata/references.
- [x] Validate `hooks.json`; no hook dry run is required when the hook set is empty.
- [x] Search for stale Salus paths/names, trading/arbitrage language, obsolete commands, secret-like assignments, environment files, raw-key material, and mainnet configuration.
- [x] Run `git diff --check` and `git status --short --branch`; the staged diff check passed and status contains only the intended bootstrap allowlist.
- [x] Review the complete diff and requirement checklist; independent review findings were corrected and rechecked.
- [ ] Commit with the required message.
- [ ] Re-check live remote divergence and push without force only if the approved remote is still safe.

## Evidence log

This section is updated as commands are run. A command is never marked successful without fresh observed output.

| Check | Command | Result |
| --- | --- | --- |
| Preflight | `git status --short --branch` and related Git inspection | Clean `main`; complete details recorded above. |
| Live remote | `git ls-remote --symref origin HEAD` | `origin/HEAD` is `main` at the starting SHA. |
| PDF discovery | attachment search plus local-context review | Exact attachment bundle lacks both named PDFs. Locally built lookalikes were rejected as substitutes; PDF copy/hash/`cmp` gates remain blocked. |
| Java baseline | `/opt/homebrew/opt/openjdk/bin/java -version` | OpenJDK 25.0.2. |
| Skill TDD | six read-only baseline scenarios followed by six forward tests | Every baseline exposed the intended workflow gap; every completed skill produced repository-correct guidance in its forward test. |
| Skill packages | `quick_validate.py` for all six skills | All six valid; YAML metadata and referenced repository paths also checked. |
| Codex discovery | `codex debug prompt-input ...` | Config parsed; all six repository skills appeared in model-visible input through `.agents/skills`. CLI 0.142.1 does not support combining `--strict-config` with `debug`, so the supported read-only debug path was used. |
| Spring red | focused Maven test command before the application class | Expected failure: two tests errored only with `ClassNotFoundException` for `DigitalBankingApplication`. |
| Spring green | `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl control-plane -am -Dtest=DigitalBankingApplicationTests,HealthReadinessSmokeTests -Dsurefire.failIfNoSpecifiedTests=false test` | Build success; 2 tests, 0 failures/errors; readiness returned HTTP 200 and `UP`. |
| Clean reactor | `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw clean verify` | Build success on Java 25.0.2; Enforcer passed; 2 Spring tests passed; executable JAR produced. |
| Domain boundary | `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl domain enforcer:enforce dependency:tree` | Direct Enforcer rule passed; dependency tree contains only the domain artifact and no dependencies. |
| Wrapper | `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw --version` | Apache Maven 3.9.16, Java 25.0.2; distribution URL and SHA-256 are pinned. |
| Runtime smoke | executable JAR plus `curl --fail --silent http://localhost:8080/actuator/health/readiness` | Application started on port 8080, exposed one Actuator endpoint, returned `{"status":"UP"}`, and shut down gracefully. |
| Documentation/tooling | Markdown link checker; JSON/TOML/YAML checks; hooks parse; official skill validators | 16 local links resolved; six skill packages valid; empty hooks valid; no TODO/TBD or dead repository paths. The only `/path/to` example is the explicitly blocked future PDF integrity command. |
| Safety sweeps | targeted `rg` and file searches | No runtime Salus/trading coupling, network/mainnet config, secret-like assignments, high-risk tokens, or environment/key files. Salus appears only in policy/provenance. |
| Independent review | read-only full-diff review against the action request and `AGENTS.md` | Plan evidence, prompt semantics, README status/quickstart, HTTP dependency guard, and unintended recorder findings were resolved; exact PDFs remain the sole blocker. |
| Staged tree | `git diff --cached --check`, `git status --short --branch`, staged stat/summary | Diff check passed; 44 intended files staged; symlink and wrapper executable modes correct; no unstaged or ignored residuals. |

## Closeout state

`partial_complete` - all unblocked bootstrap deliverables and acceptance checks are complete, staged, and independently reviewed. The only blocked deliverables are the two exact supplied PDF copies and their source/destination hash/`cmp` evidence. The tree is ready for the required single commit and safe push after the final live-remote check; those post-freeze Git results belong in the final handoff.
