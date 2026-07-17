# ADR 0005: PostgreSQL operation delivery worker and lease recovery

- Status: accepted
- Date: 2026-07-17
- Owners: repository owner
- Supersedes: None
- Superseded by: None

## Context

ADR 0004 makes each accepted token operation and its `TokenOperationAccepted` outbox intent durable in one PostgreSQL transaction. It deliberately does not decide how work is claimed, dispatched, retried, or recovered after a process failure. Phase 3B needs the smallest executable delivery baseline that works with the existing reactor, preserves application ownership of the delivery contract, and proves concurrency and recovery behavior without introducing a broker, workflow product, or financial/chain effect.

Delivery cannot be exactly once across a handler transaction and the later outbox acknowledgement. A process can fail after a handler commits but before the worker records completion. The boundary therefore needs a stable delivery identity, lease fencing, explicit ambiguous outcomes, and handler-owned durable deduplication.

## Decision

- Keep delivery commands, explicit outcomes, retry policy, queue/handler ports, and the worker use case framework-free in `application`. PostgreSQL and Spring types do not enter those signatures.
- Add one forward-only Flyway `V2` migration. Extend `operation_outbox` with bounded delivery status, attempt count, opaque lease/worker identity, lease expiry, durable availability, safe outcome/failure code, manual-review reason, and completion/update timestamps. Retain an append-only `operation_delivery_attempt` row for every claim and outcome.
- Claim eligible rows in a short explicit `READ_COMMITTED` transaction using PostgreSQL `FOR UPDATE SKIP LOCKED`. A prior-unresolved-event guard prevents concurrent delivery for one operation while allowing different operations to proceed concurrently.
- Commit the claim and its attempt history before invoking the application handler. Never hold the claim lock or transaction during handler work.
- Fence acknowledgement, retry, and manual-review updates by event, lease, worker, status, and unexpired lease. An expired claim can be recovered under a new lease; a stale owner receives an explicit stale result and cannot overwrite it.
- Deliver at least once. `event_id` is the stable delivery identity. A real handler must commit that identity in the same local transaction as its bounded business effect and return `DUPLICATE` on redelivery. Phase 3B defines no unambiguous business transition, so production includes no inbox table, no no-op handler, and no fake financial effect; a test-only transactional inbox/effect proves the contract.
- Classify expected handler results as delivered, duplicate, retryable no-effect, terminal no-effect, or ambiguous acknowledgement. Unexpected handler exceptions are reported internally by class, persisted only as a stable safe code, and treated as ambiguous because an effect cannot be disproved.
- Use deterministic bounded exponential backoff. Lease, retry, and polling durations have a one-microsecond minimum so PostgreSQL timestamp canonicalization cannot collapse distinct instants. Durable retries return the outbox row to `PENDING`; terminal or exhausted outcomes retain the row and attempt evidence in `MANUAL_REVIEW`.
- Compose one Spring-managed, non-overlapping polling lifecycle in `control-plane`. It is disabled by default, has typed bounded configuration, requires an explicit worker identity and a real handler when enabled, and shuts down without interrupting in-flight work.
- Publish only low-cardinality Micrometer counts/gauges for eligible work, active leases, oldest eligible age, claims, deliveries, duplicates, retries, manual review, recovered leases, stale updates, and suppressed overlap. No operation, participant, idempotency, address, payload, or exception value is a metric label.

## Alternatives considered

### Kafka, JMS, or another broker

Rejected for this slice because the existing PostgreSQL outbox already supplies the required local durable intent, and a transport would not remove the need for consumer deduplication, business-state authority, ambiguity handling, or recovery evidence.

### Enterprise BPM or durable-workflow platform

Deferred. No approved organizational platform or evidence spike exists. A later ADR may introduce one behind the same application contracts, but it must not become the domain state, ledger, policy, signer, observation, or reconciliation authority.

### In-memory queue, scheduler state, or deduplication set

Rejected because process restart would lose correctness state and could either lose work or repeat a business effect.

### Hold a database transaction during handler execution

Rejected because long-lived locks couple business/external latency to queue availability and still cannot make a future external effect atomic with PostgreSQL.

### No-op production handler or exactly-once claim

Rejected. A no-op acknowledgement would misstate processing, and lease exclusion does not make the handler/outbox boundary exactly once.

## Consequences

- PostgreSQL queue semantics are an intentional adapter concern; the domain and application remain framework/database-neutral.
- The same delivery may reach a handler more than once after lease expiry or acknowledgement loss. Correct consumer deduplication is mandatory before any real business effect is wired.
- Rows and attempt history are retained after success, retry, lease recovery, stale acknowledgement, terminal failure, or exhaustion. Safe codes and review reasons are durable; raw exception text and payloads are not.
- The runtime remains one Spring Boot process plus private/local PostgreSQL. The worker adds no endpoint, credential, public network, signer, chain adapter, bank adapter, transfer workflow, broker, or workflow product.
- With the default disabled configuration, Phase 3A API/readiness behavior is unchanged. Enabling without a real handler fails startup rather than falsely consuming work.
- This decision establishes delivery infrastructure only. It does not progress a token-operation lifecycle, construct/sign/submit a transaction, mint, burn, transfer, observe, reconcile, settle, or establish production readiness.

## Validation

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl application -am test
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl adapters/persistence-postgres -am test
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl control-plane -am test
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify
```

The delivery gate must keep deterministic application policy/outcome tests, real PostgreSQL empty migration, two-worker exclusion, short claim transactions, lease expiry/replacement, stale-owner fencing, durable retry timing, same-operation ordering, cross-operation concurrency, rollback/restart, post-handler/pre-ack deduplication, safe evidence, conditional Spring startup/lifecycle/metrics, and existing API/readiness/security behavior green.
