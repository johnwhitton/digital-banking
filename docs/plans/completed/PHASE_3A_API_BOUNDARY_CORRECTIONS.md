# Phase 3A API Boundary Corrections Plan

**Status:** `completed`

**Goal:** Resolve implementation-standards audit findings I-01 and I-02 before Phase 3B by minimizing the participant response surface, making the OpenAPI contract executable and recursively checked, and distinguishing caller-owned validation failures from unexpected internal invariant failures.

## Baseline and scope

| Item | Evidence |
| --- | --- |
| Repository | `/Users/johnwhitton/dev/johnwhitton/digital-banking` |
| Branch | `main`; no branch or worktree created |
| Approved baseline | `f340545877c5cd80dbe5ff6a7b7b080b489d345f` |
| Local/fetched/live remote | Before editing, local `HEAD`, `origin/main`, fetched `FETCH_HEAD`, and live `refs/heads/main` all matched the approved baseline. |
| Worktree | Clean at preflight: `## main...origin/main`. |
| Remote | `git@github.com:johnwhitton/digital-banking.git`, the approved SSH form. |
| Graphify navigation | The existing 889-node / 2,325-link graph was queried read-only using repository vocabulary. It reached the participant-response, API-security, OpenAPI, and I-01/I-02 nodes. The temporary vocabulary file was removed and no query memory was retained. Direct source and governing documents remain authoritative. |
| Scope | Correct only I-01 and I-02. Phase 3B, migrations, POMs, dependencies, runtime configuration, PDFs, and unrelated refactoring remain out of scope. |

The audit findings match Action Request 07; no discrepancy or scope-expansion blocker was found.

## Exact audit findings

### I-01: participant status projection and nested OpenAPI conformance are incomplete

> **Evidence.** [`TokenOperationResponse.java`](../../../control-plane/src/main/java/io/github/johnwhitton/digitalbanking/controlplane/api/TokenOperationResponse.java) filters only evidence references by the `participant:` prefix (lines 77-82). It copies transition `actor` and `reason` verbatim (lines 101-115) and finality `authority` and `policyVersion` verbatim (lines 125-135). The authoritative OpenAPI includes all four fields in the participant response. [`TokenOperationResponseTest.java`](../../../control-plane/src/test/java/io/github/johnwhitton/digitalbanking/TokenOperationResponseTest.java) proves evidence filtering but does not prove that internal actor, reason, authority, or policy data is omitted. [`OpenApiContractTest.java`](../../../control-plane/src/test/java/io/github/johnwhitton/digitalbanking/OpenApiContractTest.java) compares only the top-level `TokenOperationResponse` record components; it does not recursively compare nested records or validate the accepted-response example. That example shows four `NOT_ASSESSED` records with `phase-3a-bootstrap`, `phase-3a-v1`, and the acceptance timestamp, while production acceptance constructs `not-assessed`, `none`, and `Instant.EPOCH`.

> **Bounded recommendation.** Before Phase 3B expands status, define a versioned participant-safe status projection. Remove these fields from the participant API or map them through explicit safe enums/allowlists; retain the full values only in durable internal evidence. Update the accepted example to match an executable response and add recursive nested-schema/example conformance plus negative tests using deliberately sensitive internal actor/reason/authority/policy values. This requires a separately approved code/test/OpenAPI action.

### I-02: broad `IllegalArgumentException` mapping misclassifies internal invariant failures as caller errors

> **Evidence.** [`ApiExceptionHandler.java`](../../../control-plane/src/main/java/io/github/johnwhitton/digitalbanking/controlplane/api/ApiExceptionHandler.java) maps every `IllegalArgumentException` to the stable 400 problem (lines 25-36). Caller validation uses this exception, but domain construction and PostgreSQL aggregate rehydration also throw it for invalid internal state. Existing API tests prove malformed/caller input and database-unavailable paths, not an invariant/mapping failure reached behind the transport boundary.

> **Bounded recommendation.** Introduce a specific caller-validation failure at the transport/application boundary, narrow the 400 handler to caller-owned failures, and map unexpected internal/invariant failures to a safe stable 5xx problem while preserving internal diagnostics. Add controller tests that distinguish invalid request data from a simulated internal aggregate/mapper failure, and synchronize OpenAPI. This requires a separately approved code/test/OpenAPI action.

## Relevant files

| Path | Planned responsibility |
| --- | --- |
| `application/src/main/java/io/github/johnwhitton/digitalbanking/application/InvalidRequestException.java` | Explicit caller-validation failure with the original validation cause. |
| `application/src/main/java/io/github/johnwhitton/digitalbanking/application/TokenOperationApplicationService.java` | Translate only quantity parsing and caller-owned command construction failures; do not catch catalog, lifecycle, repository, or mapping failures. |
| `control-plane/src/main/java/io/github/johnwhitton/digitalbanking/controlplane/api/TokenOperationController.java` | Translate only request-controlled operation-ID and idempotency-key parsing. Participant scope remains principal-derived. |
| `control-plane/src/main/java/io/github/johnwhitton/digitalbanking/controlplane/api/TokenOperationResponse.java` | Omit internal transition actor/reason and finality authority/policy strings. |
| `control-plane/src/main/java/io/github/johnwhitton/digitalbanking/controlplane/api/ApiExceptionHandler.java` | Keep explicit public failures stable; map unexpected `IllegalArgumentException` and `IllegalStateException` invariant failures to a safe internal-error problem and retain the original failure in server diagnostics. |
| `control-plane/src/main/resources/static/openapi/token-operations-v1.yaml` | Remove internal response fields, correct the executable acceptance example, and document HTTP 500. |
| `application/src/test/java/io/github/johnwhitton/digitalbanking/application/TokenOperationApplicationServiceTest.java` | Prove caller-owned quantity/command failures use the explicit classification without reaching persistence. |
| `control-plane/src/test/java/io/github/johnwhitton/digitalbanking/TokenOperationResponseTest.java` | Prove deliberately sensitive internal status strings are absent while safe evidence remains. |
| `control-plane/src/test/java/io/github/johnwhitton/digitalbanking/OpenApiContractTest.java` | Recursively compare nested record/schema fields, compare the accepted example to an executable response, and verify status/problem contracts. |
| `control-plane/src/test/java/io/github/johnwhitton/digitalbanking/TokenOperationApiFailureBoundaryTest.java` | Inject an application-boundary invariant failure and verify safe HTTP 500/redaction without production test hooks. |
| `control-plane/src/test/java/io/github/johnwhitton/digitalbanking/TokenOperationApiIntegrationTest.java` | Preserve acceptance, replay, conflict, validation, authority separation, participant isolation, safe 404, and read behavior. |
| `docs/reviews/PHASE_3A_IMPLEMENTATION_STANDARDS_REVIEW.md` | Record I-01/I-02 disposition and verification evidence. |
| `docs/IMPLEMENTATION.md` | Record the corrected Phase 3A boundary without claiming Phase 3B. |
| `graphify-out/{GRAPH_REPORT.md,graph.json,manifest.json}` | Refresh exactly once after final source/documentation changes; no HTML or transient artifacts. |

## Implementation decisions

1. Remove `actor`, `reason`, `authority`, and `policyVersion` from the participant API instead of creating speculative safe enums. The durable domain and persistence values remain unchanged for internal audit evidence.
2. Retain participant-safe transition/finality status, timestamps, attempt identity/lineage, business correlation, and allowlisted `participant:` evidence already required by the Phase 3A contract.
3. Add one application-level `InvalidRequestException`. Wrap only `TokenQuantity.parse` and mint/burn command construction in the application service, and only `OperationId.from` and `IdempotencyKey.of` in the controller. Catalog, lifecycle, repository, mapping, and response-projection failures stay outside these catches.
4. Narrow HTTP 400 handling to framework request failures plus `InvalidRequestException`. Map remaining `IllegalArgumentException` and `IllegalStateException` invariant failures to a stable `urn:digital-banking:problem:internal-error` HTTP 500 body with no reflected exception detail, while logging the original failure for server-side diagnostics.
5. Add no dependency, abstraction layer, public endpoint, production test hook, database change, or Phase 3B behavior.

## RED-GREEN evidence

| Finding | RED command and observed failure | GREEN command and observed result |
| --- | --- | --- |
| I-01 | `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl control-plane -am -Dtest=TokenOperationResponseTest,OpenApiContractTest,TokenOperationApiFailureBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false test` failed as intended: the sensitive actor remained visible; the documented 500 response was absent; and the accepted example differed from the executable initial finality records. | The combined focused gate below passed the response minimization, recursive nested schema/required-field comparison, complete executable-example equality, and documented-status/problem tests. |
| I-02 | `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl control-plane -am -Dtest=TokenOperationApplicationServiceTest,TokenOperationResponseTest,OpenApiContractTest,TokenOperationApiFailureBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false test` failed as intended because quantity validation returned raw `IllegalArgumentException`; the I-01 control-plane RED command returned HTTP 400 rather than 500 for the injected internal failure. After independent review identified the reachable persistence `IllegalStateException` path, `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl control-plane -am -Dtest=TokenOperationApiFailureBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false test` failed with three tests run and one error because that invariant failure was unhandled. | `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl control-plane -am -Dtest=TokenOperationApplicationServiceTest,TokenOperationResponseTest,OpenApiContractTest,TokenOperationApiFailureBoundaryTest,TokenOperationApiIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` passed 5 application tests and 20 control-plane tests with zero failures/errors/skips. It proves explicit caller validation, an unwrapped persistence failure, safe 500 redaction and retained diagnostics for both conventional invariant exception types, the real PostgreSQL API paths, authorities, request-controlled scope resistance, 404 equivalence, acceptance, replay, conflict, and read-back. |

README, `docs/DESIGN.md`, and accepted ADRs remain unchanged because this correction does not change repository purpose, capability topology, architectural invariants, or a material accepted decision. `docs/IMPLEMENTATION.md`, the audit disposition, and this action plan carry the implementation-status and verification evidence required for the bounded correction.

## Verification plan and evidence

Run focused tests for each RED-GREEN cycle, then one final full offline gate after all executable and documentation changes:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify
```

Also verify Enforcer/module direction, OpenAPI parsing and recursive conformance, security/participant isolation, problem redaction, local Markdown links, secrets/credentials, public-network defaults, immutable PDF hashes/source-blob equality, prohibited paths, ignored/generated artifacts, Graphify integrity/portability, `git diff --check`, exact staging, and `git diff --cached --check`.

| Gate | Evidence |
| --- | --- |
| Focused tests | Passed. The final focused command ran 25 selected tests across application/control-plane (5 + 20) with zero failures, errors, or skips. |
| Full offline Maven | Passed once on the final executable state. `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify` ran 307 tests (246 domain, 24 application, 14 real-PostgreSQL persistence, 23 control-plane) with zero failures, errors, or skips; all four Enforcer rules, compilation, Flyway migration/validation, packaging, and Boot repackaging passed. |
| Module and smoke gates | Passed. The explicit domain and application Enforcer commands passed; the application runtime tree remains domain-only with JUnit test-scoped. JDK 25 `jdeps --multi-release 25 -s` reports domain only `java.base`, application only domain/`java.base`, persistence only inward modules/JDK SQL/Spring JDBC and transactions, and control-plane only inward modules plus the approved Spring/Jakarta/Tomcat stack. The first direct checks established the required multi-release flag and that the `.jar.original` input must receive a temporary `.jar` suffix; the corrected checks passed and their `/private/tmp` extraction self-removed. The required context/readiness smoke command passed three tests. |
| Documentation/security/PDF/diff gates | Passed before the final Graphify export. Five POMs, two YAML resources, three current JSON artifacts, and the repository TOML parsed; 92 local links across 45 Markdown files resolve. Secret/key scans found only the Maven Wrapper's empty credential handling and documented environment-variable placeholders; no key file, private-key block, public-network default, production/test prohibited path, or scope-changing build/config/PDF diff was found. The one prescribed PDF pass confirmed hashes `8a61ab83...01e77` and `90b5e0b0...b6cd`, mode `0644`, byte equality to both `84b2ff3` source blobs, and no `docs/reference` baseline diff. `git diff --check` passed. |
| Final Graphify refresh | Prepared and reviewed for the single tracked export after this final plan edit: 14 approved changed inputs (10 Java/test and four contract/document files), 200 local AST nodes/628 links, and a compact in-session semantic fragment of 20 nodes/30 links/three hyperedges. Replace-on-re-extract produces a legitimate 920-node/2,396-link/67-community graph from the prior 889/2,325 graph; diagnostics report zero missing endpoints, dangling endpoints, self-loops, exact duplicates, or collapsed links. The final export, portable 129-file manifest, unchanged HTML/cost/machine-state hashes, and transient cleanup are mandatory pre-staging checks recorded in the handoff so this plan remains included in that one final manifest hash. |
| Ponytail final-diff review | Passed. The correction adds no dependency, layer, broad catch, speculative option, or unrelated refactor; one explicit application exception, two narrow validation translations, direct record-field removal, an explicit two-type invariant handler, and focused tests are the smallest adequate structure. |
| Independent review | Passed read-only. It found no Critical issue and one Important gap: reachable persistence rehydration `IllegalStateException` failures were not yet mapped to the safe internal-error boundary. The finding was verified with direct source evidence, reproduced RED, resolved with the explicit second invariant exception type, and covered by the final focused gate. Its two evidence advisories were incorporated here and in the audit report. |

## Review and Git closeout

- [x] Independently review cross-participant disclosure, authentication/authorities, safe 404, response minimization, OpenAPI/runtime agreement, failure classification/redaction, broad catches, regression risk, and scope.
- [x] Resolve every authorized Critical or Important review finding and record its disposition.
- [ ] Confirm live remote `main` remains at the approved baseline before staging/commit.
- [ ] Stage only the required production, test, OpenAPI, documentation, plan, and three reviewed Graphify artifacts.
- [ ] Inspect the complete staged diff and run `git diff --cached --check`.
- [ ] Create exactly one commit: `fix: correct token operation API boundaries`.
- [ ] Push non-force to `origin/main` and verify local `HEAD`, `origin/main`, and live remote `main` agree with a clean worktree.

## Intentional deferrals

- Phase 3B outbox polling/publication, worker/recovery behavior, messaging/workflow systems, transfers, external effects, signing, chain adapters, new migrations/dependencies, and production-readiness claims remain deferred.
- Advisory A-01, A-02, and A-03 are not part of this corrective action.
- Reference PDFs remain immutable and will receive only the prescribed byte-integrity check.

## Closeout state

`ready_for_commit` - authority, exact-baseline, RED-GREEN implementation, documentation, single full offline verification, focused integrity gates, Ponytail, and independent review are complete. The reviewed Graphify export follows this final evidence edit so its manifest includes the final plan bytes. Exact staging, the single commit, push, and post-push synchronization evidence are reported in the final handoff because embedding their generated SHA in this same single commit would be self-referential.
