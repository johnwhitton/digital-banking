# ADR 0004: PostgreSQL, explicit JDBC, Flyway, and atomic acceptance outbox

- Status: accepted
- Date: 2026-07-16
- Owners: repository owner
- Supersedes: None
- Superseded by: None

## Context

Phase 3A must prove that an authorized mint or burn request is durably accepted before HTTP 202, remains stable across restart, and cannot create a second operation or future external-effect identity under duplicate or conflicting concurrency. The current application port already defines atomic acceptance, but Phase 2 intentionally supplied no database implementation.

The operation aggregate has explicit state, optimistic version, ordered transitions and attempts, four independent finality histories, and evidence references. Those structures must remain constrained and queryable rather than hidden in an opaque serialized aggregate. Acceptance also needs a pending event for a later worker without publishing or performing an external effect in the request transaction.

## Decision

- Use PostgreSQL as the only behavioral database for this slice and pin integration tests to `postgres:17.10-alpine3.23`.
- Add one `adapters/persistence-postgres` module. It depends inward on `application`/`domain`; `control-plane` composes it.
- Use Spring JDBC `JdbcClient` and explicit SQL/mapping with HikariCP. Do not use JPA/Hibernate, Spring Data, R2DBC, jOOQ, a second persistence abstraction, or an embedded behavioral database.
- Use one deterministic, forward-only Flyway migration for normalized operation, idempotency, transition/evidence, attempt, finality/evidence, and outbox tables. Keep Flyway clean disabled in application configuration.
- Resolve scoped idempotency races with PostgreSQL uniqueness and `INSERT ... ON CONFLICT DO NOTHING`. Scope includes tenant, participant, resource, operation kind, and the SHA-256 digest of the opaque key; the raw key is not stored.
- Set the acceptance `TransactionTemplate` explicitly to `READ_COMMITTED` so connection-pool defaults cannot alter the visibility algorithm. Canonical operation timestamps are truncated to PostgreSQL microsecond precision before first persistence so original and replay representations are stable.
- In one local transaction, persist the idempotency binding, immutable operation acceptance context, requested aggregate/version, initial audit/evidence, four initial finality records, and one versioned pending `TokenOperationAccepted` outbox record. Return HTTP 202 only after this transaction commits.
- Store exact quantities as positive bounded `NUMERIC(512,0)` atomic units plus the versioned asset/unit definition. Rehydrate aggregates by replaying normalized ordered records through domain guards.
- The outbox row is a durable request for later processing, not proof of processing, minting, burning, publication, settlement, or exactly-once external effect. Polling, leasing, publication, retries, and consumers remain Phase 3B.
- Use Testcontainers with real PostgreSQL for migration, constraint, rollback, restart, and retry-free two-thread concurrency evidence. No H2-style substitute is permitted.

## Alternatives considered

### JPA/Hibernate or Spring Data

Rejected for this slice because the idempotency race, append-only histories, optimistic update, and atomic outbox boundary need visible SQL and database constraints. An ORM would add mapping behavior without reducing the required transactional reasoning.

### R2DBC, jOOQ, or another persistence abstraction

Rejected because the control plane is imperative Spring MVC and the approved slice needs one small explicit adapter. A reactive stack or SQL DSL would add a second programming model or dependency without changing the invariants.

### Embedded or in-memory behavioral database

Rejected because PostgreSQL conflict, constraint, transaction, numeric, JSONB, and concurrent visibility behavior are part of the acceptance proof.

### Opaque JSON or Java-serialized aggregate

Rejected because stable identity, exact quantity, lifecycle version, attempt lineage, finality history, and audit ordering must remain independently constrained and queryable.

### Publish directly from the acceptance transaction

Rejected because a broker or external side effect cannot participate safely in the local database commit. The pending outbox record preserves atomic intent while deferring delivery and recovery mechanics.

## Consequences

- The API now requires a reachable PostgreSQL database at startup; there is no in-memory durable fallback.
- PostgreSQL behavior is an intentional adapter dependency, while domain and application signatures remain framework/database-neutral.
- The normalized schema and explicit mapper are more verbose than opaque serialization, but transactions, constraints, audit ordering, and evolution remain reviewable.
- A database commit proves only durable local acceptance. A later phase must define worker leasing, publication, inbox/deduplication, recovery, and transition rules before any asynchronous processing claim.
- Testcontainers requires Docker and the pinned image. Reusable/cloud containers and public database endpoints remain disabled.

## Validation

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl adapters/persistence-postgres -am test
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl control-plane -am test
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw clean verify
```

The adapter suite must keep migration/schema/index, explicit transaction isolation, exact quantity, aggregate replay including attempt lineage, optimistic conflict, append-only ordering, cardinality, replay/conflict, duplicate/conflicting concurrency, forced rollback, outbox content/uniqueness, participant isolation, and restart read-back green against the pinned PostgreSQL image. The control-plane suite must keep 202-after-commit, byte-stable replay, safe 503, participant-scoped GET, security, evidence filtering, and OpenAPI behavior green.
