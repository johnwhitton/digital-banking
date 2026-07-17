# Java/Spring Implementation Standards Audit Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use the repository `plan-execution` workflow task-by-task. Use `digital-banking-engineering`, `java-spring-control-plane-change`, `financial-state-invariants`, `digital-banking-doc-sync`, Graphify, Ponytail, and `verification-before-completion` at their documented checkpoints.

**Status:** `in_progress`

**Goal:** Codify repository-specific Java/Spring implementation standards and independently audit the completed Phase 3A baseline against them without changing executable behavior.

**Architecture:** Keep one detailed normative authority in `docs/IMPLEMENTATION_STANDARDS.md`, link and summarize it from `docs/IMPLEMENTATION.md`, and retain only its mandatory high-signal subset in `AGENTS.md`. Record the bounded source-and-test audit in one review report; keep code corrections and optional tooling as separately authorized follow-up actions.

**Tech stack:** Markdown, the existing Graphify 0.8.47 navigation artifacts, Java 25, Maven Wrapper 3.3.4 / Maven 3.9.16, Spring Boot 4.0.6, Spring Framework 7.0.x, Spring JDBC, Flyway, PostgreSQL/Testcontainers evidence, JDK `jdeps`, and standard repository validation tools.

**Authority:** Action Request 06, `AGENTS.md`, `SECURITY.md`, `AUTONOMOUS_EXECUTION_POLICY.md`, accepted ADRs, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, active plans, repository skills, source, and executable tests.

## Global constraints

- Work sequentially on clean synchronized `main` from exact approved baseline `cb5d4ad4c8e17e5a000448a271b330ed2f704fde`.
- Create exactly one non-force commit named `docs: define and audit Java implementation standards`.
- Documentation and audit only: do not modify production code, tests, POMs, migrations, OpenAPI, dependencies, runtime configuration, PDFs, or generated build output.
- Review every production Java source file and all relevant tests. Sampling is allowed only for repetitive fixture/setup bodies and must be disclosed.
- Use the current Graphify graph first for navigation, verify every conclusion against source, and refresh Graphify exactly once after the documentation is final.
- Do not generate Graphify HTML or accept query memory, caches, costs, reflections, interpreter/root files, or other untracked Graphify artifacts.
- Run the complete offline Maven verification once unless a relevant executable/build file changes or a failure requires another run.
- Do not inspect `~/.m2`, browse the web, or request network access for audit research or dependency resolution.
- Perform the prescribed immutable-PDF integrity check once; do not render, extract, optimize, regenerate, rename, or edit either publication.
- Record code corrections as bounded follow-up actions. Do not implement them in this action.
- Push only after a fresh remote-baseline check proves `origin/main` has not advanced; stop on divergence.
- Do not describe this audit as implementation-quality certification.

## Baseline evidence

| Item | Observed result |
| --- | --- |
| Repository | `/Users/johnwhitton/dev/johnwhitton/digital-banking` |
| Branch | `main` |
| Approved baseline | `cb5d4ad4c8e17e5a000448a271b330ed2f704fde` |
| Local/fetched/live remote | Local `HEAD`, `origin/main`, and live `refs/heads/main` all matched the approved baseline before editing. |
| Worktree | Clean at preflight: `## main...origin/main`. |
| Remote | `git@github.com:johnwhitton/digital-banking.git`, the approved SSH form. |
| Graphify navigation | Existing graph contains 893 nodes and 2,348 links. The vocabulary-constrained audit query reached module-boundary, lifecycle, PostgreSQL/JDBC, transaction, idempotency, outbox, security, and governance clusters; direct source remains authoritative. |
| User gate direction | The full verification must be offline and run once; no web, `~/.m2` inspection, or extra PDF review is authorized. |

Audit inventory: 53 production Java files / 3,345 physical lines and 15 test Java files / 2,688 physical lines across four modules; 10 production package names; five POMs; one 268-line Flyway migration; one 19-line runtime YAML file; and one 521-line OpenAPI contract. All production and test Java files were read in full; no fixture/setup sampling was used.

## Deliverables and ownership

| Path | Action | Responsibility |
| --- | --- | --- |
| `docs/IMPLEMENTATION_STANDARDS.md` | Create | Detailed normative authority for architecture, Java/domain modeling, readability, Spring/API/security, persistence/transactions, workflow/external effects, signing/chain adapters, testing, and evidence. |
| `docs/IMPLEMENTATION.md` | Modify | Concise standards link, phase-level application, and current audit reference without duplicating the detailed checklist. |
| `AGENTS.md` | Modify | Mandatory high-signal subset and explicit instruction to consult the standards for Java, Spring, persistence, API, workflow, signer, or chain changes. |
| `docs/reviews/PHASE_3A_IMPLEMENTATION_STANDARDS_REVIEW.md` | Create | Evidence-backed Phase 3A implementation audit, severity-calibrated findings, limitations, and ordered follow-up backlog. |
| `docs/plans/active/JAVA_SPRING_IMPLEMENTATION_STANDARDS_AUDIT.md` | Create/update | Restartable scope, inventory, methods, commands, findings, dispositions, validation evidence, review results, and final Git state. |
| `graphify-out/GRAPH_REPORT.md` | Refresh once | Reviewed portable navigation summary after final documentation changes. |
| `graphify-out/graph.json` | Refresh once | Reviewed portable graph after final documentation changes. |
| `graphify-out/manifest.json` | Refresh once | Relative, current corpus manifest after final documentation changes. |

`README.md`, `docs/DESIGN.md`, accepted ADRs, reference index/PDFs, product source, tests, POMs, migrations, OpenAPI, configuration, hooks, and skills remain unchanged unless a documentation-only inconsistency owned by this action requires escalation. The expected outcome is no change to those files except the three explicitly owned living/standards files above.

## Standards coverage contract

The detailed standards must cover, without maintaining three independent checklist copies:

1. inward dependency direction and framework/native-type isolation;
2. immutable and typed Java/domain modeling, exact quantities, defensive copies, exhaustive state handling, injected time/identity, explicit failure contracts, and useful public-port Javadoc;
3. cohesive source structure, package intent, and the roughly 500-line extraction-review / 800-line design-smell prompts;
4. constructor-injected, thin-boundary, stateless deny-by-default Spring/API/security behavior and stable safe problem responses;
5. explicit JDBC/Flyway/PostgreSQL mapping, forward-only migrations, database constraints, atomic acceptance/outbox, narrow transactions, canonical concurrency, and at-least-once transport truth;
6. durable intent/attempt identity, separate external-effect stages, bounded evidence-backed retry, ambiguity inquiry, and append-only compensation;
7. signer and native-chain isolation, exact signing bindings, native EVM/Solana semantics, and materially independent observation;
8. pure core tests, real PostgreSQL persistence tests, deterministic concurrency, rollback/restart recovery, whole-object reconstruction, synthetic fixtures, and evidence beyond a passing build; and
9. YAGNI rules requiring a present executable need and an ADR for material dependencies, stores, workflow runtimes, chains, signers, contracts/programs, or authority decisions.

## Audit method

### 1. Establish inventory and source map

- [x] Inventory every reactor module, Java package, production/test Java file, non-test line count, resource, migration, OpenAPI, and POM relevant to Phase 3A.
- [x] Record production-file size distribution and manually review every file above the standards prompts; do not infer a defect from size alone.
- [x] Use the existing Graphify graph only to prioritize cross-file paths, then read every production Java source directly.

### 2. Review architecture and inner modules

- [x] Inspect parent/module POMs and Enforcer configuration without modifying them.
- [x] Inspect every `domain` source and relevant test for framework leakage, exactness, immutability, defensive copies, state exhaustiveness, transition guards, time/ID control, exception semantics, and public-contract clarity.
- [x] Inspect every `application` source and relevant test for inward dependencies, provider-neutral ports, orchestration boundaries, primitive/string leakage, idempotency, accepted-command reconstruction, and infrastructure exception leakage.

### 3. Review PostgreSQL persistence

- [x] Inspect the Flyway migration, every persistence production source, and all persistence tests for constraints, parameterized SQL, explicit mapping, transaction isolation/scope, atomic acceptance/outbox, optimistic concurrency, replay/conflict behavior, timestamp precision, complete reconstruction, rollback, restart, and deterministic two-thread races.
- [x] Manually adjudicate every SQL/search lead, including `SELECT *`, concatenated SQL, mutation/deletion of history, unbounded quantities, implicit mapping, and external calls within transaction scope.

### 4. Review Spring/API/security

- [x] Inspect every control-plane production source and relevant test/resource for constructor injection, controller thinness, principal-derived participant scope, distinct authorities, deny-by-default configuration, response redaction, stable problems, HTTP 202-after-commit semantics, OpenAPI conformance, safe 404 behavior, and absence of credential/public-network defaults.
- [x] Compare runtime/API behavior with `SECURITY.md`, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, ADR 0004, and the authoritative OpenAPI resource.

### 5. Review tests and evidence quality

- [x] Review all tests establishing domain, application, persistence, API/security, OpenAPI, rollback, restart, concurrency, and module-boundary claims.
- [x] Assess behavior-first versus implementation-coupled assertions, whole-object reconstruction, permitted/forbidden paths, deterministic synchronization/timeouts, failure classification/redaction, and false-positive risks.
- [x] Disclose any sampling limited to repetitive fixture/setup code. No sampling was used.

### 6. Triage and findings

- [x] Run focused searches for framework/native leakage, mutable/setter patterns, `float`/`double`, `Instant.now`, random IDs, sleeps, broad exception handling, boolean/ambiguous optional parameters, default/wildcard state handling, unsafe byte-array exposure, field injection, reflection/Lombok, SQL risks, secret-like material, and public-network defaults.
- [x] Manually read and classify every reported concern before it enters the review.
- [x] Use only `Critical`, `Important`, and `Advisory` severity as defined by Action Request 06; do not report formatting preferences as defects.
- [x] Separate required corrections before Phase 3B, improvements alongside Phase 3B, future signer/chain standards that are not yet applicable, and optional tooling evaluations tied to observed risk.

## Review report contract

The completed report must include:

1. exact baseline and scope;
2. repository/module and source/test inventory;
3. methodology, direct-read coverage, sampling, and limitations;
4. standards checklist with `pass`, `partial`, `finding`, or `not_applicable`;
5. positive evidence and well-executed patterns;
6. severity-calibrated findings with file/line evidence, impact, and bounded recommendation;
7. file-size/complexity hotspots as review prompts;
8. test-quality and failure-path coverage;
9. dependency and framework-leakage assessment;
10. security, API, SQL, transaction, idempotency, outbox, and concurrency assessment;
11. documentation/implementation consistency; and
12. conclusion and dependency-ordered follow-up actions.

Severity meanings:

- `Critical`: potential financial-state, authorization, secret, data-loss, duplicate-effect, or unsafe external-effect defect.
- `Important`: material correctness, maintainability, test, boundary, recovery, or misleading-documentation problem that should be resolved before the affected next phase.
- `Advisory`: worthwhile improvement without a present correctness or safety failure.

## Validation plan

Run the full executable gate once, after source/test inspection and before completion claims:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl domain enforcer:enforce
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl application enforcer:enforce dependency:tree
```

Also run the repository's existing JDK `jdeps` boundary check using compiled outputs and ignored copied-runtime artifacts created by the offline build workflow; do not inspect `~/.m2`. Run focused standard-library checks for:

- every local Markdown link and required documentation link;
- JSON/TOML/XML/YAML/frontmatter syntax where applicable;
- Graphify version, manifest/hash coverage, graph endpoint/ID integrity, portability, and absence of HTML/transient artifacts;
- secret/credential/key material, public-network defaults, stale Salus/trading/runtime references, and generated artifacts;
- exact PDF SHA-256/mode/source-blob equality and an unchanged `docs/reference` tree, once only;
- production/test/POM/migration/OpenAPI/PDF/config unchanged from the approved baseline;
- `git diff --check`, `git diff --cached --check`, complete unstaged/staged diff review, and exact changed/staged allowlists.

## Review and closeout sequence

- [x] Draft and cross-check the detailed standards, living-document links, guidance subset, and audit report against the Action Request.
- [x] Run Ponytail full-mode review; remove duplicate checklists, speculative tooling mandates, and unsupported abstraction recommendations without weakening safety or evidence.
- [x] Obtain one independent read-only audit-quality review for missed Critical/Important findings, unsupported claims, severity calibration, source/test coverage, and standards completeness.
- [x] Resolve every Critical or Important documentation/review issue and record each disposition here.
- [x] Finalize documentation and plan evidence, then refresh Graphify exactly once without HTML and review only its three tracked artifacts.
- [x] Invoke `verification-before-completion`, run the remaining focused documentation/security/diff gates, and stage only the exact allowlist.
- [x] Confirm `origin/main` still equals `cb5d4ad4c8e17e5a000448a271b330ed2f704fde`; stop if it advanced.
- [ ] Commit once as `docs: define and audit Java implementation standards`.
- [ ] Push `main` without force and verify local `HEAD`, `origin/main`, and live remote `main` agree with a clean worktree.

## Evidence log

| Gate | Command/evidence | Result |
| --- | --- | --- |
| Git preflight | `pwd`; status/branch/HEAD/origin/live remote/remotes/log inspection | Passed before editing: correct repository, clean `main`, expected remote, and all three baseline SHAs equal `cb5d4ad4c8e17e5a000448a271b330ed2f704fde`. |
| Governance and workflow | Complete Action Request 06, repository guidance/security/policy, design/implementation, accepted ADRs, active plans, and matching skill reads | Passed; documentation-only scope, one-commit constraint, audit authority, validation commands, and stop conditions established. |
| Graphify navigation | Existing graph/version/inventory plus vocabulary-constrained BFS query | Existing 893-node/2,348-link graph reached the governing module, lifecycle, PostgreSQL, transaction, idempotency, outbox, and security clusters. One ignored query-memory file created by that navigation command was identified and removed before staging; it was excluded from extraction and is not retained. |
| Source/test inventory and audit | Direct read of all Java, POMs, migration, YAML, OpenAPI, governing docs, and manually adjudicated triage searches | Completed without sampling: 53 production / 15 test Java files. Report records 0 Critical, 2 Important, and 3 Advisory findings; no executable remediation was made. |
| Offline Maven and structural boundaries | Exact three required Maven commands; JDK 25.0.2 `jdeps -s` over compiled outputs and runtime jars extracted to `/private/tmp` from the already-built Boot archive | Passed. One offline `clean verify` ran all five reactor projects and 302 tests with 0 failures/errors/skips. Domain/application Enforcer passed; application tree contains domain at compile scope and JUnit only at test scope. Direct `jdeps` reports domain only `java.base`, application only domain/`java.base`, persistence only inward modules/JDK SQL/Spring JDBC+transactions, and control-plane only inward modules plus its approved Spring/Jakarta/Tomcat stack. The exact application command emitted the expected packaged-not-installed reactor POM warning but succeeded. Initial `jdeps --summary` failed because JDK 25 supports `-s`/`-summary`; `jdeps --help` established the syntax. A standalone offline dependency-copy attempt failed on packaged-but-not-installed reactor artifacts, so the successful boundary check used the Boot archive instead; no Maven gate was repeated and `~/.m2` was not inspected. |
| Documentation/static/PDF checks | Local Markdown checker; standards/report assertion matrix; JSON/TOML/XML/YAML and skill-frontmatter/compatibility parsing; secret/key/public-network/stale-project/generated-key-file scans; one prescribed PDF hash/mode/source-blob comparison | Passed. All 82 local links across 22 Markdown files resolve; required standards topics, 12 report sections, 0/2/3 finding counts, and governing links are present; structured source files and seven skill metadata/link pairs parse. Secret-assignment/key-file scans were empty; network and Salus matches were only existing repository/prohibition/provenance text. The one PDF pass confirmed destination hashes `90b5e0b0...b6cd` (Zelle brief) and `8a61ab83...01e77` (reference architecture), mode `0644`, and byte equality to both source blobs in `84b2ff3`; `docs/reference` is unchanged from the baseline. The unavailable PyYAML module in the Graphify environment was replaced by the standard Ruby YAML parser without installation or network access. |
| Ponytail and independent review | Full-mode simplification review plus one read-only audit-quality review and direct source verification of every suggestion | Passed. Ponytail found the required standards/report/plan detail justified and no speculative tool/dependency mandate. The independent reviewer found no new Critical or Important issue and confirmed I-01/I-02. Four report corrections were verified and incorporated: 79 annotated methods rather than a broad-search false positive of 80; nested OpenAPI/accepted-example drift under I-01; no-I/O acceptance-evidence semantics under A-02; and policy-version strings changing the modeling checklist to `partial`. Finding counts remain 0 Critical / 2 Important / 3 Advisory. |
| Graphify refresh | One low-level incremental detect/extract/merge/cluster/report pass over the five changed documentation files; graph diagnostics, manifest and source-path checks, tracked-artifact review, and pre/post HTML/cost hashes | Passed without HTML. The reviewed graph changed from 893 nodes / 2,348 links to 889 nodes / 2,325 links / 75 communities by replacing 21 stale nodes and 42 stale edges with 17 nodes and 19 edges for the five re-extracted documents. The four-node net reduction was explicitly verified as legitimate before using Graphify's supported forced export. All 126 approved corpus files remain in the relative manifest; no graph node references an absolute or `graphify-out` source path; all 2,325 edge endpoints are valid with zero missing, dangling, self-loop, duplicate, or collapsed edges. `graph.html` and `cost.json` hashes are unchanged, and the one query-memory file plus five new semantic-cache entries and all transient refresh files were removed rather than retained. |
| Git closeout | Live `git fetch origin main`, exact stage, one commit, push, and post-push synchronization | Pre-commit remote gate passed: local `HEAD`, fetched `origin/main`, and `FETCH_HEAD` still equal the approved baseline. The exact eight-file staged allowlist, cached diff whitespace gate, prohibited-path comparison, and zero-unstaged-change gate passed. The one commit, push, and post-push synchronization remain. |

## Stop and restart conditions

On restart, inspect branch/status/local/fetched remote SHAs, this checklist, changed-file allowlist, and the latest command evidence. Stop for baseline/remote divergence, unexpected user changes, a required production/test/build/contract/PDF edit, an unresolved Critical finding, an unavailable required offline dependency, Graphify data-egress/HTML behavior, PDF drift, destructive or credential handling, or scope expansion beyond documentation and audit.

## Intentional deferrals

- Every source/test/build/schema/API/config remediation identified by the audit requires a separate bounded action.
- Phase 3B worker/recovery implementation remains unimplemented and may begin only after required pre-Phase-3B audit corrections are reviewed and completed.
- Signer, Ethereum, Solana, contract/program, observation, reconciliation, public-network, and production standards remain normative future gates but executable implementation assessment is `not_applicable` where code is absent.
- Optional lint, formatter, static-analysis, architecture, mutation, or coverage tooling is evaluated only against a concrete observed risk; this action adds none.

## Closeout state

`ready_for_commit` - the full direct audit, standards/report drafting, one offline 302-test reactor gate, exact Enforcer/binary boundary commands, focused documentation/security/PDF checks, Ponytail review, one independent review, the single final Graphify refresh, live pre-commit remote-baseline gate, and exact staging are complete. The single commit, push, and post-push synchronization verification are intentionally reported in the final handoff because a commit cannot contain its own SHA.
