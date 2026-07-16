# Autonomous Execution Policy

## Purpose and precedence

This policy defines how Codex may execute an approved engineering phase in the Digital Banking Reference Implementation. It does not grant deployment, financial, credential, network, or business authority.

Precedence, highest first:

1. host, tool, sandbox, and organization restrictions;
2. explicit current user instructions and the approved action request;
3. [`AGENTS.md`](AGENTS.md) and [`SECURITY.md`](SECURITY.md);
4. accepted ADRs, [`docs/DESIGN.md`](docs/DESIGN.md), [`docs/IMPLEMENTATION.md`](docs/IMPLEMENTATION.md), and the active plan; and
5. this policy.

Host permission and user intent are separate. A technically permitted action is not automatically authorized.

## Authority retained by the user

The user owns:

- architecture and module-boundary changes outside an approved phase;
- signing, custody, administrative, policy, approval, and recovery authority;
- security, audit, reconciliation, and evidence standards;
- asset, chain, provider, deployment, network, corridor, and funds-at-risk decisions; and
- phase approval, scope expansion, release, and production-readiness claims.

## Autonomous actions inside an approved phase

Codex may make reversible implementation choices that remain within the approved outcome and accepted architecture. It may:

- add or revise tests before implementation and make the smallest code changes that satisfy them;
- update owning documentation, ADRs, active plans, and validation evidence;
- choose names, package layout, private implementation details, and test fakes that do not create a new authority or dependency decision;
- fix Critical and Important correctness, security-boundary, or invariant findings within scope; and
- record Minor findings and bounded deferrals without expanding the phase.

Prefer one coherent commit per completed feature or phase, not a commit per edit. Push only when the action request explicitly authorizes it and every applicable gate passes.

## Mandatory escalation

Stop and ask the user when **any** of these conditions applies:

- an architecture, authority, security, finality, reconciliation, or evidence standard would change;
- work requires material scope expansion or contradicts current instructions or accepted decisions;
- a Critical finding remains unresolved;
- an operation is destructive, history-rewriting, irreversible, or would discard unexpected user work;
- an external side effect, release, tag, deployment, public-network call, or real-funds operation is required;
- production credentials, private keys, seed phrases, HSM/MPC/custody authority, or secret-bearing material would be handled;
- a contract or program would be irreversibly deployed or an administrative authority changed;
- a choice requires business, legal, compliance, treasury, security, accounting, or customer authority; or
- the remote branch advances or repository safety checks no longer match the approved baseline.

No force push, history rewrite, destructive cleanup, secret handling, public-chain deployment, public-testnet execution, mainnet execution, or real-funds operation is authorized.

## Git and isolation

Direct work on a clean, synchronized `main` is allowed for the current early phase only when the action request explicitly says so. Use a worktree or additional branch only when the user requests parallel isolation or a later task materially benefits from it.

Never absorb unrelated user work into a commit. Re-fetch before an authorized push, stop on divergence, and never force-push.

## Completion gate

Completion requires all of the following:

- fresh focused and full build/test evidence;
- review of the actual diff and staged tree;
- synchronized README, design, implementation status, ADRs, and active plans;
- resolved Critical and Important findings within scope;
- no generated artifacts, secrets, public-network defaults, or unexpected changes staged;
- a clean working tree after an authorized commit and push; and
- a handoff stating starting/final commits, commands and results, decisions, deferrals, remote state, and the next bounded phase.

Permission to complete one phase does not authorize the next phase.
