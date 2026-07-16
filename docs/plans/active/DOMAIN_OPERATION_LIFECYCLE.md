# Domain and Operation Lifecycle Plan

**Status:** `in_progress`

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
- Lifecycle transitions use an explicit allowlist and an expected aggregate version. Terminal operations are immutable.
- Attempt IDs are unique and append-only. No attempt may be added while submission is ambiguous; a later value-moving attempt requires an upstream policy decision proving retry safety.
- Blockchain, legal, customer-visible, and accounting finalities are distinct records with independent authority, policy version, status, timestamp, and evidence references.
- Canonical command hashing uses a versioned, length-prefixed UTF-8 field encoding and SHA-256, independent of JSON property order.
- Scoped idempotency is tenant/participant plus resource kind plus opaque key. Same scope/key/hash replays the original operation; a different hash conflicts and cannot create another operation.
- Chain ports are capability-aware and separate prepare, submit-once, inquiry, and observation. Signer ports bind an exact digest and return references/decisions without private-key material.

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
- [ ] Commit only the aligned documentation and normalized publications as `docs: align chain architecture and close foundation`.

### 2. RED: encode Phase 2 behavior before production code

- [ ] Add `application` to the reactor and add only test-scoped JUnit dependencies needed to compile tests.
- [ ] Write focused tests for canonical quantities, boundaries, unit mismatch, identifiers, every lifecycle transition, terminal immutability, ambiguity/attempt rules, finality independence, canonical hashing, replay, conflict, and scoped keys.
- [ ] Run the focused test command and record the expected compile/test failures caused by missing production types.

RED command:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl domain,application -am test
```

### 3. GREEN: implement the minimum contracts and behavior

- [ ] Implement exact domain types and aggregate guards without framework/native imports.
- [ ] Implement versioned command canonicalization and SHA-256 hashing using the JDK.
- [ ] Implement application port contracts and the smallest acceptance service; keep in-memory fakes test-only.
- [ ] Make `control-plane` depend on `application` and enforce application-layer dependency exclusions.
- [ ] Re-run focused tests until green; add no behavior not required by a failing test or an approved invariant.

GREEN commands:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl domain,application -am test
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl domain,application -am enforcer:enforce dependency:tree
```

### 4. Synchronize and verify

- [ ] Update README, design, implementation status, and this evidence log to match executable behavior.
- [ ] Run focused tests, the full clean reactor, dependency boundaries, no-framework/native-type scans, no-floating-point scans, Markdown links, metadata, secret/public-network scans, `git diff --check`, and full diff review.
- [ ] Obtain an independent correctness review and resolve all Critical or Important findings.
- [ ] Commit the verified Phase 2 slice as `feat: implement token operation domain lifecycle`.
- [ ] Fetch, confirm safe non-divergence, push without force, verify remote SHA, and confirm a clean worktree.

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
| Phase 2 RED | Pending | Pending. |
| Phase 2 GREEN | Pending | Pending. |
| Final verification/review | Pending | Pending. |
| Git closeout | Pending | Pending. |
