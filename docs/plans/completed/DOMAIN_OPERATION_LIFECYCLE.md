# Domain and Operation Lifecycle Plan

**Status:** `completed`

**Goal:** Implement the smallest plain-Java domain and application slice that accepts exact mint/burn commands idempotently, owns stable operation/attempt identities, guards the durable lifecycle, preserves four independent finalities, and exposes signer/chain capabilities through provider-neutral ports.

**Authority:** Action Request 02, `AGENTS.md`, `AUTONOMOUS_EXECUTION_POLICY.md`, `SECURITY.md`, accepted ADRs, `docs/DESIGN.md`, and `docs/IMPLEMENTATION.md`.

## Baseline and constraints

- The user explicitly accepted clean `main`/`origin/main` at `84b2ff350639f537adddd2fc1695e09bae5375b4` (`Add pdfs`) as the revised baseline, outside this action's two new commits.
- Work is authorized directly on `main` with at most the two specified commits and a non-force push after divergence checks.
- `domain` remains plain Java with only test-scoped JUnit; `application` depends on `domain`; `control-plane` depends on `application`.
- Do not add Spring behavior, HTTP APIs, persistence, Web3j, Solana SDKs, Solidity, Rust, Compose, public-network configuration, keys, or real-value behavior.
- Do not claim durable storage or external exactly-once effects. The application repository port defines an atomic acceptance contract that Phase 3 must implement durably.

## Concrete Phase 2 decisions

- `AssetUnit` owns asset ID, unit ID, version, scale, and maximum atomic units.
- `TokenQuantity` stores positive `BigInteger` atomic units. Canonical input is unsigned base-10 without leading zeros; an optional fractional component has at most the unit scale and no trailing zero. Scientific notation, explicit signs, zero, excess precision, and values above the configured maximum are rejected. Serialization removes insignificant fractional zeros and round-trips exactly.
- Operation and attempt IDs are canonical UUID value types generated before external interaction.
- Lifecycle transitions use an explicit allowlist and an expected aggregate version. Terminal lifecycle state is immutable; independent append-only finality evidence may continue after technical completion.
- Attempt IDs are unique and append-only. An authorized attempt must exist before signing/submission. No attempt may be added while submission is ambiguous; a later value-moving attempt requires an upstream policy decision proving retry safety.
- Blockchain, legal, customer-visible, and accounting finalities are distinct histories with independent authority, policy version, status, evidence references, and aggregate-monotonic recorded time. Blockchain finality must be `REACHED` before the corresponding lifecycle state or completion.
- Canonical command hashing uses NFC-normalized, well-formed Unicode, versioned length-prefixed UTF-8 fields, and SHA-256, independent of JSON property order. Malformed Unicode is also rejected before deriving the safe idempotency-key audit digest.
- Scoped idempotency is tenant/participant plus resource kind plus opaque key. The atomic repository contract stores canonicalization version with the digest. Same scope/key/version/hash replays the original operation; a different canonical identity conflicts and cannot create another operation.
- The aggregate retains a non-secret immutable acceptance context: participant scope, idempotency-key digest, request/canonicalization versions, command digest, and business correlation.
- Chain ports are capability-aware and separate prepare, submit-once, inquiry, and observation while carrying stable operation/attempt correlation. Signer requests bind exact bytes to a verified SHA-256 digest, identity, effect constraints, lifetime, policy, and evidence without private-key material.

## Ownership map

| Area | Module and package | Responsibility |
| --- | --- | --- |
| Exact quantities | `domain/.../asset` | Units, exact parsing, bounds, serialization, unit-safe arithmetic/comparison. |
| Operation aggregate | `domain/.../operation` | IDs, states, guarded transitions, attempt lineage, evidence, finalities. |
| Commands and hashing | `application/.../command` | Versioned mint/burn contracts, canonical bytes, digest, idempotency scope/key. |
| Use case and ports | `application/...` | Atomic acceptance/replay/conflict and provider-neutral repositories, policy, signer, chain, time, ID, and evidence contracts. |
| Composition | `control-plane` | Compile-time dependency on `application`; no new endpoint or Spring bean in this phase. |

## Ordered execution

### 1. Close the foundation gate

- [x] Normalize the two PDFs under `docs/reference/` without byte changes.
- [x] Review the source publications and record provenance, traceability, EVM/Solana decisions, and autonomous execution policy.
- [x] Run foundation documentation, PDF integrity, link, metadata, safety, Maven, and diff gates.
- [x] Commit only the aligned documentation and normalized publications as `docs: align chain architecture and close foundation` (`f22d329`).

### 2. RED: encode Phase 2 behavior before production code

- [x] Add `application` to the reactor and add only test-scoped JUnit dependencies needed to compile tests.
- [x] Write focused tests for canonical quantities, boundaries, unit mismatch, identifiers, every lifecycle transition, terminal immutability, ambiguity/attempt rules, finality independence, canonical hashing, replay, conflict, and scoped keys.
- [x] Run the focused test command and record the expected compile/test failures caused by missing production types.

RED command:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl domain,application -am test
```

### 3. GREEN: implement the minimum contracts and behavior

- [x] Implement exact domain types and aggregate guards without framework/native imports.
- [x] Implement versioned command canonicalization and SHA-256 hashing using the JDK.
- [x] Implement application port contracts and the smallest acceptance service; keep in-memory fakes test-only.
- [x] Make `control-plane` depend on `application` and enforce application-layer dependency exclusions.
- [x] Re-run focused tests until green; add no behavior not required by a failing test or an approved invariant.

GREEN commands:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl domain,application -am test
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl domain enforcer:enforce dependency:tree
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl application enforcer:enforce dependency:tree
```

### 4. Synchronize and verify

- [x] Update README, design, implementation status, and this evidence log to match executable behavior.
- [x] Run focused tests, the full clean reactor, dependency boundaries, no-framework/native-type scans, no-floating-point scans, Markdown links, metadata, secret/public-network scans, `git diff --check`, and full diff review.
- [x] Obtain an independent correctness review and resolve all Critical or Important findings.
- [x] Commit the verified Phase 2 slice as `feat: implement token operation domain lifecycle`.
- [x] Fetch, confirm safe non-divergence, push without force, verify remote SHA, and confirm a clean worktree.

## Restart and stop conditions

On restart, inspect `git status`, this checklist/evidence log, and the last Maven result before editing. Retain only verified evidence. Stop under `AUTONOMOUS_EXECUTION_POLICY.md` if work would require public networks, credentials, real value, history rewriting, destructive cleanup, a materially new dependency/vendor selection, or an architecture decision outside this bounded phase.

## Evidence log

| Gate | Command/evidence | Result |
| --- | --- | --- |
| Preflight | Git status, branch, remote, fetch, and source-commit inspection | `main` and freshly fetched `origin/main` matched `84b2ff350639f537adddd2fc1695e09bae5375b4`; the worktree was clean. |
| Publication integrity | SHA-256, source-blob `cmp`, `pdfinfo`, full render/contact-sheet inspection | Both PDFs are byte-identical to the `84b2ff3` blobs; all 132 pages were rendered and visually reviewed. |
| Architecture research | Source publications plus official Foundry, Solana, Sava, Circle, and repository evidence | Foundry/Web3j and native SPL Token/Sava-gate decisions recorded in ADRs 0002/0003; no code or dependency was consumed. |
| Skill/policy review | Repository skills, prompts, `AGENTS.md`, and autonomous policy | No conflicting implementation authority found; prompts now link the applicable plan and policy. |
| Foundation review | Two independent read-only diff reviews | PDF provenance, bytes, modes, links, and docs-only scope passed. Existing user approval resolved the revised-baseline concern. Review fixes aligned the current phase, changed README capability wording to prospective, unified precedence with host restrictions first and the autonomous policy below accepted architecture/plans, and removed an unsupported durability implication from the Phase 2 name. No Critical or Important finding remains. |
| Foundation verification | PDF hash/source-blob comparison; 37-link local Markdown scan; JSON/TOML parse; Ruby YAML/frontmatter validation; stale-claim, secret, public-network, and generated-artifact scans; `git diff --check`; `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw clean verify` | Both PDF hashes/modes and `cmp` checks passed; all local links and metadata passed; no unsafe/stale staged content found; reactor succeeded with 2 tests and 0 failures/errors. The official Python skill validator could not import PyYAML in either available runtime; an equivalent read-only Ruby validation passed all six unchanged skills. |
| Phase 2 RED | `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl domain,application -am test` | Expected failure at domain test compilation: the test-referenced exact quantity, identity, lifecycle, evidence/finality, command, port, and service types do not exist. Both module Enforcer rules reached before the failure and the domain rule passed. No assertion was weakened. |
| Phase 2 GREEN | `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl domain,application -am test` plus an independent binary-encoding digest calculation | Build success after review fixes: 246 domain tests and 18 application tests passed with both Enforcer rules green. The canonical fixture SHA-256 `64936676e367bfa9932e2be90a89f4c37052777b3fe186789e75d82a20574103` matched an independent big-endian length-prefix calculation. Subsequent RED/GREEN cycles added typed native-safe retry authorization; aggregate-monotonic time/input/identifier bounds; attempt/finality transition prerequisites; append-only finality rules; Unicode NFC and malformed-input rejection; versioned idempotency metadata and retained acceptance context; exact signer digest/effect/lifetime binding; stable chain correlation; rejected-payload safety; and diagnostic redaction. |
| Final verification/review | Focused and full Maven gates; exact dependency trees and module Enforcer rules; JAR/`jdeps` inspection; source-boundary, network/secret, link, metadata, PDF, diff, and status checks; two independent reviews with fix verification | Focused success: 246 domain + 18 application tests. Full `clean verify`: 266 tests, 0 failures/errors, four-module success, one exposed Actuator endpoint. Domain runtime dependency is empty; application runtime dependency is domain only; `jdeps` reports only `java.base` plus the intended application-to-domain edge. Both reviewers report no remaining Critical or Important finding. |
| Git closeout | Coherent second commit, fresh remote non-divergence check, non-force `main` push, remote-SHA comparison, clean status | Completed as the final action-request closeout; exact commit and remote SHA are reported in the handoff. |
