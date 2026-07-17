# Digital Banking Repository Guidance

## Purpose and phase

This repository is the **Digital Banking Reference Implementation**: non-production reference software for a regulated digital-asset settlement control plane. The foundation and plain-Java domain/operation lifecycle are verified. No later implementation phase is active or authorized; Phase 3 durable API and persistence is the next planned slice. The repository does not expose mint/burn APIs or implement persistence, signing, chain adapters, or financial settlement.

Zelle appears only in the supplied publications as a public case study. Never describe this repository as Early Warning Services or Zelle production architecture, confidential information, a selected vendor stack, or an announced implementation plan.

## Instruction precedence and sources of truth

Follow instructions in this order:

1. The user's current request.
2. Host, tool, sandbox, and organization restrictions.
3. The closest applicable `AGENTS.md` and `SECURITY.md`.
4. Accepted ADRs under `docs/adr/`.
5. `docs/DESIGN.md` for architecture and invariants.
6. `docs/IMPLEMENTATION.md` for current state and delivery order.
7. The active plan under `docs/plans/active/`.
8. `AUTONOMOUS_EXECUTION_POLICY.md` for bounded execution authority.
9. Repository-local skills when their trigger matches.

Chat history is not a source of truth. Put durable decisions, evidence, and deferrals in the repository.

The source PDFs under `docs/reference/` are immutable contextual architecture inputs, not code specifications. Their verified checksums and traceability are recorded in `docs/reference/README.md`. `docs/DESIGN.md`, accepted ADRs, API contracts, and executable tests govern implementation details. Never edit, regenerate, optimize, or substitute the publication files.

## Required preflight before editing

Before changing files:

1. Run `pwd` and confirm the repository root.
2. Run `git status --short --branch`, `git branch --show-current`, `git rev-parse HEAD`, `git remote -v`, and `git log -5 --oneline --decorate`.
3. Read this file, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, the relevant ADRs, and the active plan.
4. Identify pre-existing user changes and do not overwrite or reformat them.
5. Confirm the exact validation command for the planned change.
6. For substantial work, create or update an active plan before implementation.

If unrelated changes overlap the target files and cannot be preserved safely, stop and report the collision.

## Git and worktree safety

- Preserve all user changes and unrelated history.
- Never use destructive reset, checkout, restore, clean, force-push, or history rewriting without explicit authorization.
- Do not commit generated caches, build outputs, temporary evidence, IDE state, credentials, or environment files.
- Verify `origin` is `https://github.com/johnwhitton/digital-banking` or `git@github.com:johnwhitton/digital-banking.git` before pushing.
- Fetch or inspect the live remote before pushing. Never force-push. Stop on divergence, branch protection, or unexpected remote history.
- Keep commits coherent and review the full staged diff.

The Salus repository at `/Users/johnwhitton/dev/jincubator/salus`, including all of its worktrees, is read-only reference material. Never edit, stage, commit, or push anything there. Do not copy Salus caches, artifacts, secrets, environment files, generated evidence, Git metadata, project names, trading behavior, or runtime commands.

## Architectural invariants

- **Java owns the regulated control plane.** Spring coordinates business APIs, durable operations, policy, workflow, persistence, reconciliation, and operations.
- **Business truth is durable and internal.** A transaction hash, signature, RPC response, receipt, or commitment is evidence, not complete financial truth.
- **Chain technology stays behind adapters.** Web3j, Solana SDK types, RPC models, encodings, addresses, nonces, blockhashes, instructions, accounts, slots, and receipts do not become the core domain model.
- **Native execution uses native ecosystems.** Use Solidity only for required EVM enforcement and Rust only for required Solana programs. Do not force chain-native execution into Java.
- **Mint and burn are privileged asynchronous operations.** They require durable identity, idempotency, policy, approval, attempts, evidence, and status. Never create a thin key-backed endpoint.
- **Signing is separate authority.** Application code owns no production raw keys. HSM, MPC, qualified custody, and isolated local development signing implement a provider-neutral signer port.
- **Finalities remain distinct.** Never replace blockchain, legal, customer-visible, and accounting finality with one `settled` Boolean.
- **Ambiguous effects are first-class.** After a timeout or lost response, inquire by stable identity and gather evidence before any resubmission.
- **Amounts are exact.** Never use `float` or `double` for money or token quantities. Define unit, scale, rounding, bounds, overflow behavior, and serialization.
- **External effects are reconcilable.** Persist stable operation and attempt identities plus sufficient native evidence for independent observation.
- **Sensitive data stays off-chain.** Put no personal, case, sanctions, fraud, or policy data on-chain; use opaque correlation identifiers when necessary.
- **This implementation is non-production.** No mainnet, public testnet, real funds, production credentials, or compliance claims.

## Language and module boundaries

### Java and Spring

- `domain` is plain Java. It must not depend on Spring, HTTP, persistence, Web3j, a Solana SDK, or provider libraries.
- Domain types express business identity, lifecycle, exact quantities, policy/evidence references, and invariant-enforcing transitions.
- Application services orchestrate ports; adapters translate external protocols.
- `control-plane` is the Spring composition root. Keep controllers thin, transactions explicit, and business decisions out of annotations and framework callbacks.
- No business endpoint may report success without durable state and evidence semantics.

### Solidity and EVM

- Add Solidity only in an approved Ethereum slice and only for on-chain enforcement that cannot remain off-chain.
- Web3j stays in an Ethereum adapter module. Nonce reservation, replacement, chain ID, receipt/log, block, confirmation, and canonicality semantics remain adapter-owned and explicitly tested.

### Rust and Solana

- Add Rust only after an ADR chooses a native Solana program requirement and establishes Anchor or native Solana commands.
- Java client integration stays in a Solana adapter. Recent blockhash lifetime, durable nonce choice, instructions, accounts/PDAs, signatures, slots, commitment, and expiry remain adapter-owned and explicitly tested.
- Do not copy generic Rust commands from another repository before a real workspace exists.

### Dependency direction

Allowed direction:

```text
control-plane/adapters -> application ports -> domain
```

The domain never depends outward. Chain adapters may depend on core ports and their native SDK, but never on another chain adapter. Signing adapters implement the signer port and do not authorize business policy independently. Observation paths do not reuse mutable submit-provider truth as their only evidence source.

## Exact money, token quantity, and idempotency rules

- Canonical API quantities are decimal strings plus an asset/unit identifier, never binary floating-point JSON numbers.
- Convert to integer atomic/minor units only after validating the configured scale. Reject excess precision unless an explicitly approved conversion policy names a rounding mode; value-moving operations default to no rounding.
- Define maximum magnitude before persistence or native encoding; fail on overflow rather than truncate.
- Scope idempotency keys by participant/tenant and operation resource.
- Hash a versioned canonical payload. The same scope/key/hash returns the original durable operation; the same scope/key with a different hash is a conflict.
- Operation IDs are stable business identities. Attempt IDs identify each authorized external-effect attempt. Never generate a new operation to retry an ambiguous effect.

## Signing, secrets, and networks

- Never commit a private key, seed phrase, API token, RPC credential, HSM credential, custody credential, real funded address, `.env`, keystore, or secret-bearing test output.
- Examples use obvious non-secret placeholders only; prefer no secret field at all.
- Production key material never enters application memory, logs, exceptions, fixtures, or traces.
- A signing request binds exact canonical bytes/digest to operation, attempt, purpose, chain, asset, amount, destination, fee/expiry bounds, policy version, and approval evidence.
- Default configuration must not reference mainnet, Base mainnet, Solana mainnet, a public testnet, or a production provider.
- If a suspected secret appears, stop, avoid repeating it, notify the owner, remove it safely, rotate/revoke it outside the repository, and document the response without preserving the secret.

## Testing expectations

Use test-first development for behavior changes: write the focused failing test, observe the expected failure, add the smallest implementation, then run the focused and broader suites.

| Change type | Minimum evidence |
| --- | --- |
| Domain invariant or lifecycle | Pure unit/property tests for valid and invalid transitions, boundaries, exactness, duplicate behavior, and serialization. |
| Spring API | Contract test, validation/error test, idempotency/concurrency test, transaction-boundary test, and context test. |
| Persistence | Migration test, uniqueness/concurrency test, rollback test, and restart/recovery test. |
| Signer adapter | Exact-byte binding, allowlist/limit, rejection, evidence, timeout, and no-key-leakage tests. |
| Chain adapter | Deterministic encoding, native identity/lifetime semantics, duplicate/ambiguous submission, observation, canonicality/commitment, and local-chain failure tests. |
| Solidity/Rust program | Native formatter/linter/tests, authorization failure tests, upgrade/admin analysis, and local-chain integration evidence. |
| Documentation/config | Link/path/metadata validation, stale-reference search, and command dry runs where commands are enabled. |

Do not use mocks when a small real value object, repository, local process, or deterministic fake proves behavior more directly.

## Documentation synchronization

- Update `README.md` when purpose, current capability, repository map, quick start, or contributor workflow changes.
- Update `docs/DESIGN.md` when boundaries, terminology, lifecycle, contracts, finality, evidence, security, or topology changes.
- Update `docs/IMPLEMENTATION.md` when layout, dependencies, validation commands, status, sequencing, risks, or next-slice recommendations change.
- Update the active plan during substantial work with decisions, progress, commands, results, deferrals, and closeout state.
- Create an ADR for a material accepted decision with alternatives and consequences. Do not create speculative ADRs for unresolved questions.
- If a relevant living document does not change, record why in the active plan or final handoff.

## Repository-local skills

Use a repository-local skill when its trigger matches:

- `digital-banking-engineering` for any substantial repository work.
- `plan-execution` when executing an active plan.
- `digital-banking-doc-sync` when code, architecture, workflow, or status changes.
- `java-spring-control-plane-change` for Java, Maven, Spring, API, or persistence work.
- `chain-adapter-change` for Ethereum, Solana, native contract/program, RPC, observation, or adapter work.
- `financial-state-invariants` for amount, idempotency, lifecycle, finality, signing authority, ambiguity, or reconciliation changes.

Skills supplement this file and the living docs; they do not override them. Codex CLI 0.142.1 discovers the compatibility entries under `.agents/skills/`; canonical requested sources remain under `.codex/skills/`.

## Plans, ADRs, and scope

- Substantial features, multi-module changes, persistence/workflow changes, chain slices, signer integration, and architectural refactors require an active plan.
- Keep plans under `docs/plans/active/`; do not create parallel plan trees.
- A plan must be restartable: scope, files, dependencies, acceptance, validation, decisions, progress, and deferrals must be explicit.
- One chain vertical slice follows the common domain/lifecycle proof. Do not implement Ethereum and Solana simultaneously to avoid validating the common model.

## Generated files

- Commit the Maven wrapper scripts and properties because they are source-controlled build entry points.
- Do not hand-edit generated wrapper scripts; regenerate with the recorded Maven Wrapper version.
- Do not commit `target/`, local JDKs, dependency caches, extracted PDF text/images, test reports, IDE files, Graphify output, or generated chain bindings unless an approved slice defines them as reviewed source artifacts.
- Keep the supplied PDFs byte-identical; never regenerate or optimize them.

## Completion gate

Run the narrowest focused checks first, then all applicable repository checks. Before claiming completion for the current foundation, run and inspect:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw --version
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw clean verify
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl control-plane -am -Dtest=DigitalBankingApplicationTests,HealthReadinessSmokeTests -Dsurefire.failIfNoSpecifiedTests=false test
git diff --check
git status --short --branch
```

Also validate PDF hashes/copies, Markdown links, skill metadata and referenced paths, `hooks.json`, stale Salus/trading/obsolete-command references, and secret-like material. If a tool or check is unavailable, record the exact command, failure, impact, and safest next step.

Never claim a test, build, link, hook, metadata, or security check passed without fresh observed output.

## Final handoff format

Report:

1. starting and final branch/SHA;
2. verified remote and push result;
3. intended files/modules created;
4. PDF source/destination names and checksum equality;
5. Salus asset disposition;
6. architecture/build decisions;
7. exact validation commands and results;
8. blockers and intentional deferrals;
9. final `git status --short --branch`; and
10. the next bounded vertical slice.
