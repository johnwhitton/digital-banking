# Phase 8A — Share-Ready Demo Documentation

## Status

Complete on `main` from approved baseline
`87f8aadf9f2b520c40631cd236eb0a5d91417e95`. The implementation-evidence
commit is `173ebcbb002cacad479c7ced4361106e7c6f21dc`; the intervening baseline
commit changes only the two v1.0.5 publication PDFs.

## Scope

Make the verified local Ethereum and Solana demonstrations understandable and
runnable from the repository without changing product behavior. Add one
share-ready walkthrough, improve README navigation, reconcile publication
metadata/provenance, and clarify the two existing runbooks and living docs.

This action changes documentation only. It does not change or run Java, Maven,
Foundry, Docker, Anvil, Agave, scripts, configuration, APIs, migrations,
dependencies, tests, or PDFs.

## Publication reconciliation

The bounded pre-edit pass used immutable Git objects, not the concurrently
changing prep worktree:

- prep release tag `digital-banking-v1.0.5-ethereum` resolves to publication
  commit `d367698c9aa1396c5d87102e9bab975982501192`;
- release records were read at
  `484d6635c705f3e43a7835111930e615109c2938`;
- Volume I is v1.0.5, 152 pages, 4,963,702 bytes, SHA-256
  `a2a451d2830b1aef2dc6efe47110322be0e76f508d99feeaf5c61e6c82b1777b`;
- the Executive Brief is v1.0.5, 27 pages, 1,358,915 bytes, SHA-256
  `96f5b8301406328b6e896a7d8dc0903d472cb07bdcc81157254c8d6e2af8aad1`;
- each changed repository PDF has the same Git blob as both the tag's canonical
  build output and primary review copy;
- the unchanged Engineering Companion remains v1.1.0, 46 pages, SHA-256
  `9448c01a27810a4d15d59c7bf8ef4e56246c5719abb4b9567f178dd2abec9223`;
- the unchanged Reference Implementation remains v1.0.0, 45 pages, SHA-256
  `d7b5aa6218df3d1ef6c7cddd6e9c1e05bf6da25cbdbed670b7eccabd7cba8ca3`.

The v1.0.5 publications describe the earlier local Ethereum evidence snapshot
`f744fe619de0d6cf1fc295cd4116e880aa00d803`; they do not claim to include the
later Solana implementation. Current repository capability evidence remains at
`173ebcbb002cacad479c7ced4361106e7c6f21dc` and its descendants. A future
Solana publication alignment is a separately versioned publication action.

## Authorized files

- `README.md`
- `docs/DEMO_WALKTHROUGH.md`
- `docs/DESIGN.md`
- `docs/IMPLEMENTATION.md`
- `docs/TRANSFER_DEMO.md`
- `docs/reference/README.md`
- `docs/runbooks/LOCAL_ETHEREUM_DEMO.md`
- `docs/runbooks/LOCAL_SOLANA_DEMO.md`
- this completed plan

## Acceptance and validation

- Publication versions, titles, page counts, hashes, bytes, Git blobs, release
  records, and provenance agree before authoring.
- README gives a compact start-here path and hands readers to the environment
  runbooks without overstating production readiness.
- The walkthrough explains Demo A (user-held acquisition, hold, redemption) and
  Demo B (settlement-only transfer), exact quantities, server-resolved custody,
  PostgreSQL business truth, native-chain evidence, synthetic accounting, and
  the absence of a global transaction.
- Topology and both workflow sequences are expressed as GitHub-renderable
  Mermaid diagrams.
- Runbooks state what start creates, where local state lives, what stop/reset do,
  and how to stop the mutually exclusive environment before switching.
- Changed-document links, navigation anchors, Mermaid fences, exact demo labels,
  quantities, account/posting names, stale metadata, absolute paths, unsafe
  claims, `git diff --check`, final diff, and the exact staging allowlist pass.
- Graphify is `not_applicable`: this focused documentation workflow does not
  require a graph refresh, and no manual reconstruction or transient artifact is
  authorized.
- No build, native-chain, container, demo, PDF regeneration, or repeated broad
  review is run.

## Progress

- [x] Verified local, tracking, fetched, and live remote `main` at the approved
  baseline with a clean worktree.
- [x] Confirmed the baseline delta from the implementation-evidence commit is
  exactly the two publication PDFs.
- [x] Reconciled all four PDFs against immutable release evidence.
- [x] Completed the authorized documentation edits and one focused stable-diff
  accuracy/minimalism review.
- [x] Ran the focused documentation and final-diff checks.
- [x] Staged the exact nine-file documentation allowlist for the single
  non-force closeout commit; commit, push, and synchronization evidence belongs
  to the final handoff.

## Validation evidence

- Four-PDF metadata and immutable Git-object provenance: **PASS**. The two
  v1.0.5 repository PDFs equal the release tag's canonical and primary-review
  blobs; the separately governed Volume II/III blobs also agree.
- Changed-document local links: **PASS** across all nine final documents.
- Mermaid source: **PASS** with one topology flowchart, two sequence diagrams,
  and balanced fences; no renderer or binary artifact was added.
- README navigation and explicit Ethereum/Solana handoff: **PASS**.
- Demo labels, exact `USD 100.00` / `10,000` quantities, PostgreSQL authority,
  custom-Solidity versus standard-classic-SPL realization, and no-custom-Rust
  statement: **PASS**.
- Exact four-account names and debit/credit mapping against ADR 0009/design:
  **PASS**.
- Current publication metadata, workstation-path additions, safe-claim review,
  PDF/executable diff, active-plan directory, and authorized worktree list:
  **PASS**.
- `git diff --check`: **PASS**.
- Ponytail full-mode stable-diff review: **PASS**; no removable abstraction,
  dependency, binary artifact, or speculative documentation was introduced.
- Graphify: **not_applicable**. No refresh, HTML, reconstruction, transient, or
  tracked Graphify change was made.
- Maven, Foundry, Docker, Anvil, Agave, demos, and PDF regeneration/review were
  not run because this action changes documentation only.

## Deferrals

Phase 8B retains the broader final architecture, security, recovery, API, and
publication-alignment review. It may review Solana publication alignment, but it
must not retroactively rewrite the immutable v1.0.5 Ethereum release record.
