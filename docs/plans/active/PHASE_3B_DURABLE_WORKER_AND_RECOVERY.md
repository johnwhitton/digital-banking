# Phase 3B Durable Worker and Delivery Recovery Plan

**Status:** `ready_for_graphify_and_git_closeout`

**Goal:** Add the smallest PostgreSQL-backed, crash-recoverable delivery worker that durably claims accepted token-operation outbox work, invokes an application-owned handler at least once, records safe outcomes, retries bounded transient or ambiguous acknowledgement failures, and reclaims expired leases without performing a financial or chain effect.

**Authority:** Action Request 09, `AGENTS.md`, `SECURITY.md`, `docs/IMPLEMENTATION_STANDARDS.md`, accepted ADRs, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, `docs/TRANSFER_DEMO.md`, the Phase 3A standards review, and the completed Phase 3A/API-boundary plans.

## Baseline and scope

| Item | Evidence |
| --- | --- |
| Repository | `/Users/johnwhitton/dev/johnwhitton/digital-banking` |
| Branch | Direct work on `main`; no branch or worktree. |
| Approved Action Request 08 baseline | `a58ca0ad6834ab53d80a0431e97d12c25b4d6d64` (`docs: publish engineering companion`). |
| Local/fetched/live remote | Before editing, local `HEAD`, `origin/main`, fetched `FETCH_HEAD`, and live `refs/heads/main` all matched the approved baseline. |
| Worktree | Clean at preflight: `## main...origin/main`. |
| Required predecessor history | `65a75cbb9182bc554145ae61a082f63d4dc19fa4` and user publication commit `cb1f1164bb1d604dbfbaec6727ed2f899231c812` are retained in history. |
| Remote | `git@github.com:johnwhitton/digital-banking.git`, an approved form. |
| Graphify navigation | Reviewed Graphify 0.8.47 graph after direct `rg`. The constrained query connected Phase 3B deferral to ADR 0004, the atomic acceptance outbox, application boundaries, and retry authorization. Direct source remains authoritative; temporary vocabulary/query memory was removed. |
| Engineering Companion | A user-supplied replacement arrived during closeout, as anticipated, and the user then explicitly authorized staging it with this action. Its checksum index is synchronized; it remains contextual publication evidence rather than implementation authority. |

This action adds only delivery infrastructure for the existing `TokenOperationAccepted` outbox intent. It does not create a token-operation lifecycle transition, transaction attempt, signer or chain call, mint, burn, transfer, bank effect, observation, reconciliation, or settlement behavior. No broker, BPM/workflow product, new module, new dependency, endpoint, OpenAPI change, public network, or credential is authorized. The only publication change is the later user-authorized Engineering Companion replacement and its checksum-index update; the other two PDFs remain unchanged.

## Design checkpoint

The current roadmap required the Phase 3B plan to evaluate the database worker and an approved enterprise workflow platform. The design and transfer specification already identify the database-backed Java/Spring worker as the self-contained reference baseline. Action Request 09 now selects that baseline and explicitly forbids a broker or workflow-platform dependency. This is not a material business-transition, broker-topology, or module-boundary discrepancy; it closes the previously open implementation choice while preserving a later ADR/evidence gate for any enterprise platform.

ADR 0004 governs PostgreSQL, JDBC, Flyway, atomic acceptance, and the pending outbox but deliberately defers claiming, leasing, retry, recovery, and consumer behavior. Because the implementation standards require an accepted decision for a material workflow runtime, this action adds one focused ADR for the PostgreSQL queue/lease baseline.

## Current outbox and required forward evolution

Migration `V1__create_token_operation_persistence.sql` creates one `operation_outbox` row for each accepted operation with stable event/operation identity, one event type/version, versioned JSON payload, `PENDING` status, and created/available timestamps. Its status constraint permits only `PENDING`; it has no lease identity, worker identity, attempt count, retry schedule beyond `available_at`, delivery outcome, or history.

Add one forward-only `V2` migration; never edit `V1`. Evolve `operation_outbox` with:

- `PENDING`, `IN_PROGRESS`, `DELIVERED`, and `MANUAL_REVIEW` delivery dispositions;
- opaque lease and bounded worker identities, lease expiry, durable claim-attempt count, safe last-outcome/failure code, delivered/updated timestamps, and shape constraints;
- an eligible-work index ordered by disposition/availability/lease expiry; and
- an append-only `operation_delivery_attempt` record for every lease with claim, expiry/recovery, acknowledgement, retry, duplicate, terminal, and exhaustion evidence.

No generic production inbox or fake business-effect table is added because this slice defines no business transition. Durable consumer deduplication is proved by a deterministic integration-test handler whose test-only inbox identity and bounded effect are committed in one PostgreSQL transaction. A future real consumer owns its inbox and business effect in the same local transaction.

## Architecture and file map

### Application-owned delivery contract

Create focused framework-free application types under `application/.../delivery/`:

- stable delivery/worker/lease identities and a `TokenOperationAccepted` delivery command derived from durable event/operation identity;
- an explicit sealed delivery outcome covering delivered, duplicate/already applied, retryable no-effect failure, terminal no-effect failure, and ambiguous acknowledgement;
- an `OperationDeliveryQueue` port for bounded claim, lease-guarded acknowledgement/reschedule/manual-review, and queue measurements;
- an `OperationDeliveryHandler` port whose implementation owns idempotent consumer behavior;
- a bounded exponential retry policy using injected time; and
- an `OperationDeliveryWorker` use case that commits claims before calling the handler, prevents overlapping polls, invokes no handler inside a database transaction, redelivers ambiguity, and records only safe failure codes.

Application tests use deterministic fakes, an injected clock, and barriers/latches where concurrency is exercised. No Spring, JDBC, serializer, provider, or native-chain type enters an application signature.

### PostgreSQL adapter

Create `PostgresOperationDeliveryQueue` beside the existing repository rather than extending the 864-line `PostgresOperationRepository`. It uses explicit parameterized JDBC and short `READ_COMMITTED` transactions:

1. select eligible rows with `FOR UPDATE SKIP LOCKED` and an ordering guard that prevents concurrent unresolved work for the same operation;
2. close expired attempt history, assign a fresh lease, increment the claim attempt, and insert attempt history before commit;
3. return the committed claim to the application worker;
4. compare event/lease/worker identity and unexpired lease for acknowledge, retry, or manual-review updates; and
5. retain every row and history record after success, retry exhaustion, or recovery.

The handler runs only after the claim method returns. Lease update failure is an explicit stale-owner result, not an exception that can overwrite a replacement lease.

### Spring runtime and observability

Add typed `digital-banking.delivery-worker` configuration and a conditional worker composition in `control-plane`:

- disabled by default;
- enabling requires a real `OperationDeliveryHandler` bean and explicit worker identity;
- bounded batch size, one non-overlapping poll per process, duration-based lease/poll/backoff values, and bounded attempts;
- Spring-managed scheduling/lifecycle with explicit start/stop and no ad hoc thread or business-code sleep; and
- existing Micrometer/Actuator only, with low-cardinality gauges/counters for eligible work, active leases, oldest eligible age, claims, successes, duplicates/redeliveries, retries, exhaustion/manual review, and lease recovery.

When disabled, existing startup/readiness behavior remains unchanged. When explicitly enabled without a handler, startup fails rather than silently acknowledging work.

## Test-first slices

### 1. Application RED/GREEN

- [x] Add pure tests for explicit outcomes, retry/backoff bounds, retry exhaustion, ambiguous acknowledgement redelivery, safe exception classification, stale lease handling, and overlapping-poll suppression.
- [x] Run the focused application tests and capture expected compilation failures for absent delivery types.
- [x] Implement the minimum application contracts/policy/worker and rerun green.

### 2. Migration and PostgreSQL RED/GREEN

- [x] Add migration assertions and real-PostgreSQL tests before production SQL for two-worker claim exclusion, commit-before-handler visibility, successful acknowledgement, expired-lease reclaim, stale-owner rejection, durable retry time, attempt exhaustion, cross-operation concurrency, same-operation ordering, rollback, restart, and safe outcome persistence.
- [x] Add a deterministic test-only transactional inbox/effect handler proving post-handler/pre-ack redelivery returns the original durable result without repeating the effect.
- [x] Run the focused adapter tests and capture missing `V2`/queue failures.
- [x] Add the forward migration and minimum JDBC adapter; rerun green.

### 3. Spring runtime and metrics RED/GREEN

- [x] Add focused context/config/lifecycle/metric tests proving disabled-by-default startup, failure when enabled without a handler, bounded typed configuration, explicit stoppability, safe metrics, and unchanged health/readiness/API behavior.
- [x] Capture the expected missing-runtime failures, add minimum conditional composition/lifecycle/metrics, and rerun green.

### 4. Documentation and decisions

- [x] Add the focused worker ADR and ADR-index entry.
- [x] Update `README.md`, `docs/DESIGN.md`, and `docs/IMPLEMENTATION.md` with the implemented infrastructure boundary, at-least-once/lease/recovery semantics, measurements, and explicit non-effects.
- [x] Update this plan after every slice with exact RED/GREEN commands, observed results, decisions, limitations, and deferrals.
- [x] Leave `docs/IMPLEMENTATION_STANDARDS.md`, `docs/TRANSFER_DEMO.md`, OpenAPI, and the existing migration unchanged. No implementation evidence required a PDF edit; during closeout the user separately supplied and explicitly authorized the updated Engineering Companion plus its checksum-index correction.

## Mandatory acceptance evidence

Focused development commands:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl application -am test
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl adapters/persistence-postgres -am test
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl control-plane -am test
```

After final code and documentation changes, run the complete offline reactor exactly once unless a relevant file changes or a failure requires a rerun:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify
```

Then run the applicable Enforcer/dependency-direction, `jdeps`, Flyway-empty-migration, focused PostgreSQL failure-window, control-plane readiness/API, structured-file/skill/hook, Markdown/local-link, security/redaction/public-network/portability, generated-artifact, PDF hash/mode, and diff gates. Do not inspect `~/.m2`, request network access, render/extract PDFs, or rerun completed gates without a relevant change/failure.

Refresh only tracked `graphify-out/GRAPH_REPORT.md`, `graph.json`, and `manifest.json` once after final source/documentation changes. Do not generate HTML or retain vocabulary, query, cache, cost, reflection, or machine-state artifacts.

## Independent review and completion

- [x] Run Ponytail on the final diff for accidental wrappers, speculative abstractions/configuration, or dependencies.
- [x] Obtain one independent read-only review focused on duplicate/lost work, lease/stale-owner races, lock/transaction scope, retry/exhaustion, ambiguous acknowledgement/deduplication, ordering/concurrency, restart/rollback, precision, sensitive leakage, scheduling bounds, dependency direction, claims, and scope.
- [x] Resolve every in-scope Critical or Important finding; stop on material expansion.
- [x] Invoke verification-before-completion and record fresh executable, dependency, documentation, integrity, and diff evidence here.
- [ ] Confirm live remote `main` still equals the approved baseline; stage only authorized Phase 3B source, tests, `V2`, docs/ADR/plan, and reviewed Graphify artifacts.
- [ ] Inspect the complete staged diff, run `git diff --cached --check`, create exactly one `feat: add durable operation delivery worker` commit, push non-force, and verify clean synchronized local/fetched/live remote state.

## Stop conditions and deferrals

Stop for baseline divergence, unavailable pinned PostgreSQL/Docker, flaky timing-based concurrency, an unavoidable broker/workflow/chain/signer dependency, a required business lifecycle transition not already authorized, unresolved data-loss/security/duplicate-effect risk, public-network/credential requirement, material scope expansion, or remote advance before push.

Deferred: any real handler business transition, policy/approval consumption, chain attempt, signing, submission, mint, burn, transfer, bank effect, broker/BPM selection, external-effect retry, observation, reconciliation, settlement, enterprise HA/DR, and production-readiness claim. The current and any future Engineering Companion remains publication evidence, not live implementation authority.

## Evidence log

| Gate | Command/evidence | Result |
| --- | --- | --- |
| Preflight | Fetch, branch/status/SHA/remote/log/live-remote inspection | Passed at `a58ca0ad6834ab53d80a0431e97d12c25b4d6d64`; clean synchronized `main`; required predecessor commits retained. |
| Authority/navigation | Governing docs/plans/review, direct `rg`, Graphify 0.8.47 constrained query, current source/migration/POM inspection | No material discrepancy. Existing graph reached the correct Phase 3A/ADR/application seams; direct source established the separate delivery adapter and forward-migration need. Temporary Graphify artifacts were removed. |
| Application RED/GREEN | `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl application -am -Dtest=DeliveryRetryPolicyTest,OperationDeliveryWorkerTest -Dsurefire.failIfNoSpecifiedTests=false test` | RED failed at test compilation only on the intentionally absent delivery policy, queue, handler/outcome, worker, and result types. The first GREEN compile exposed a record factory/accessor name collision; the next exposed one omitted final-field assignment. Both root causes were corrected without behavior expansion. Fresh GREEN ran 9 focused application tests with zero failures/errors/skips, including retry, exhaustion, terminal no-effect, ambiguity, stale ownership, overlap, and the one-microsecond precision floor; both inner Enforcer gates passed. |
| PostgreSQL RED/GREEN | `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl adapters/persistence-postgres -am -Dtest=PostgresOperationRepositoryTest,PostgresOperationDeliveryQueueTest -Dsurefire.failIfNoSpecifiedTests=false test` | RED first failed at test compilation on the intentionally absent queue adapter. Initial GREEN runs then exposed, in sequence, the existing outbox insert's missing mandatory `updated_at`, a noncanonical test quantity, PostgreSQL's inability to infer `Instant` parameter types, and one later-event fixture whose availability preceded creation. Each root cause was corrected narrowly. The final combined run passed 28 tests (14 acceptance regressions plus 14 delivery/lease/recovery tests), zero failures/errors/skips; all inner Enforcer gates passed. Delivery behavior remains in a separate adapter; the 864-line acceptance repository received only the required one-column V2 insert compatibility update, so no mechanical split was justified. |
| Control-plane RED/GREEN | `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl control-plane -am -Dtest=DeliveryWorkerConfigurationTest,DeliveryWorkerMetricsTest -Dsurefire.failIfNoSpecifiedTests=false test`; then the focused existing control-plane/API selection | RED failed at test compilation only on the intentionally absent typed properties, conditional configuration, lifecycle, and metrics types. GREEN passed 5 focused worker tests. The follow-on selection passed all 28 control-plane tests with zero failures/errors/skips, including startup/readiness, mint/burn, replay/conflict, participant isolation, OpenAPI, and redacted error boundaries; all inner Enforcer gates passed. |
| Documentation/decision | `README.md`, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, ADR 0005/index, `docs/reference/README.md`, and this plan | Synchronized the implemented at-least-once infrastructure and explicit non-effects. Existing implementation standards already govern every new rule, `TRANSFER_DEMO.md` remains a planned subordinate capability contract, the HTTP/OpenAPI boundary did not change, and V1 is immutable. The user supplied and explicitly authorized the updated Engineering Companion during closeout; only its recorded checksum changed with it. |
| Independent review | One read-only reviewer against the full Action Request 09 failure-window checklist | No Critical or Important findings. Both Moderate evidence issues were accepted and resolved: typed/application/adapter durations now reject values below PostgreSQL's one-microsecond precision, and real PostgreSQL tests now use concurrent same-operation claims plus fresh pools across retry and delivered acknowledgement. Residual limits are intentional and documented: a long handler can outlive its lease, and any future production handler must transactionally deduplicate its effect. |
| Focused final gates | Isolated JDK 25 `jdeps -s`; real-PostgreSQL failure-window selections; control-plane readiness/API selection; 99 local Markdown-link checks; YAML/OpenAPI, five-POM XML, repository TOML, hook JSON/matcher, and repository-skill/discovery validation; private-key/public-network/generated-artifact scans; `git diff --check` | Passed for the Action Request 09 source and documentation state. Dependency summaries remain inward and approved; no secret material, runtime public-network configuration, generated build output, new dependency, or formatting error was found. Production additions remain below the repository's extraction-review threshold. |
| Full offline reactor | `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify` | Passed once on the final executable state: 335 tests (246 domain, 33 application, 28 real-PostgreSQL persistence, 28 control-plane), zero failures/errors/skips. All four Enforcer rules, clean compilation, two-migration empty-schema Flyway validation/migration, JAR packaging, and Spring Boot repackaging passed. No relevant source or build file changed afterward, so the full build was not repeated. |
| PDF integrity/scope | One prescribed SHA-256/mode pass over `docs/reference/*.pdf`; focused reference diff | The settlement and Zelle publications match their recorded hashes and mode. The user-supplied Engineering Companion is `9448c01a27810a4d15d59c7bf8ef4e56246c5719abb4b9567f178dd2abec9223`; its checksum index now matches. Local Unix mode is `0600`, while Git correctly records the staged non-executable path as `100644`. No render, extraction, metadata inspection, or repeated PDF validation was performed. |
| Documentation/Graphify/Ponytail | Final plan/link/diff checks, Ponytail full review, then the prescribed incremental Graphify refresh | Ponytail found no dependency, wrapper, new module, or speculative abstraction to remove: the 392-line JDBC queue is cohesive and avoids growing the existing 864-line acceptance repository. The refresh completed for the executable/doc state, then the user's explicit Companion-staging instruction required a bounded update for this plan and the checksum index only. No HTML, cost, query, vocabulary, or retained temporary/cache output is authorized. Exact final Graphify counts and integrity results belong in the closeout handoff rather than a self-invalidating post-refresh plan edit. |
| Git closeout | Pending | Pending. |
