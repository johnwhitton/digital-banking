# Executive Brief v1.0.6 synchronization and release reconciliation

**Status:** `completed`

## Objective

Synchronize the living publication metadata with the user-committed Executive
Brief v1.0.6, preserve the immutable Phase 8B release, and publish one
documentation-only v1.0.1 POC patch release.

## Authority and boundaries

- Approved starting commit: `645290c9df0fc67cc45aa473228e4ac53cdc55f6`.
- Prior Phase 8B release commit: `16f7fc12b768e0c9debd6326931f8c79f5850a53`.
- Executable implementation evidence: `173ebcbb002cacad479c7ced4361106e7c6f21dc`.
- Publication authority: prep commits
  `9d459e777961527467f8fdf04959c0790ec91568` and
  `e070c9509bf65d777034f2d3570bf964db42ab86`, plus immutable tag
  `digital-banking-v1.0.6-solana`.
- Documentation and release metadata only. No executable file, PDF byte,
  dependency, API, migration, configuration, script, prep file, or Salus file
  may change.
- Maven, Foundry, containers, demos, native validators, Graphify, PDF
  rendering/extraction, and independent review are not applicable and must not
  run.

## Preflight evidence

- `main`, local `HEAD`, local `main`, `origin/main`, fresh `FETCH_HEAD`, and
  live remote `main` all resolved to the approved starting commit; the worktree
  and index were clean.
- `origin` is `git@github.com:johnwhitton/digital-banking.git`.
- The prior-release-to-baseline diff changed only
  `docs/reference/zelle-digital-asset-settlement-executive-brief.pdf`, with Git
  mode `100644`.
- The Executive Brief is 1,372,799 bytes, Git blob
  `5c9a83061e03a603fd641187661ee06608c44bc9`, and SHA-256
  `a7a06a38a1de51d807d249e580f8882a2510b8196b24000893ef9c92015e023d`.
- The other three publication blobs equal their prior-release identities.
- Both prep commits are ancestors of pushed prep `origin/main`; the remote prep
  tag resolves to the release-metadata commit. Immutable prep Git objects bind
  version 1.0.6, 27 portrait US Letter pages, the PDF hash above, source-archive
  SHA-256 `e9f0a1cd3e916ae383739ed4291c1ab12a48242c786f9ffdb2ce4122fbe06284`,
  and the implementation-evidence commit.
- Existing local/remote tag `digital-banking-v1.0.0-dual-chain-poc` resolves to
  the prior Phase 8B release commit. No local, remote, or GitHub v1.0.1 patch
  tag/release exists, so release decision Case B applies.
- Existing GitHub authentication is valid.

## Intended files

- `README.md`
- `docs/reference/README.md`
- `docs/IMPLEMENTATION.md`
- `docs/releases/DIGITAL_BANKING_V1_0_1_DUAL_CHAIN_POC.md`
- this plan, moved to `docs/plans/completed/` at closeout

`docs/releases/DIGITAL_BANKING_V1_0_0_DUAL_CHAIN_POC.md` remains unchanged as
historical v1.0.0 evidence. `docs/DESIGN.md` and the ADRs remain unchanged
because this action makes no architecture, authority, lifecycle, API, or
executable-behavior decision.

## Validation and release sequence

1. Reconcile publication versions, pages, hashes, provenance, and unchanged
   Volume I-III boundaries across the intended documents.
2. Check changed-document local Markdown links, unsupported claims, absolute
   local paths, PDF blobs/modes, and `git diff --check`.
3. Record results here, move this plan to `completed/`, stage only the five
   authorized documentation paths, inspect the exact staged diff, and run
   `git diff --cached --check`.
4. Confirm live remote `main` has not advanced, commit exactly
   `docs: publish Solana-aligned executive brief`, and push non-force.
5. Create and push annotated tag `digital-banking-v1.0.1-dual-chain-poc`, then
   create the matching GitHub prerelease from the committed patch notes.
6. Verify clean local/tracking/fetched/live remote equality, remote tag target,
   GitHub prerelease state, and unchanged publication blobs.

## Progress

- [x] Baseline, remote, clean-worktree, and exact one-PDF commit preflight.
- [x] Immutable PDF hash/blob/mode and unchanged-Volume checks.
- [x] Read-only prep commit/tag/provenance reconciliation.
- [x] Existing release/tag reconciliation; Case B selected.
- [x] Documentation synchronization.
- [x] Focused documentation, claim, PDF-identity, and worktree validation.
- [x] Final documentation diff and allowlist review.
- [x] Plan closeout and move to `completed/` before the single action commit.

## Closeout evidence

- Current publication sections agree on Executive Brief v1.0.6 at 27 pages,
  unchanged Volume I v1.0.5, Volume II v1.1.0, and Volume III v1.0.0. Historical
  Phase 8A and v1.0.0 release evidence remains intentionally unchanged.
- All local Markdown targets in the five changed documents resolve.
- The Executive Brief still has the expected SHA-256, byte size, Git blob, and
  Git mode. `git diff --name-only -- 'docs/reference/*.pdf'` is empty, and the
  other three PDF blobs still match the prior Phase 8B release commit.
- Focused scans found no absolute workstation path or new unsupported
  production, public-network, real-bank, reserve-attestation, confidential
  Zelle, or fresh-test claim.
- `git diff --check` passes. The final five-path staging allowlist,
  `git diff --cached --check`, remote-advance fence, commit, push, tag, GitHub
  prerelease, and synchronization checks follow this committed plan snapshot so
  the action can retain exactly one new documentation commit.

## Deferrals

Broader Solana alignment of Volume I, Volume II, or Volume III remains
separately governed publication work. Production identity, custody, banking,
accounting close, public-network operation, and readiness remain outside this
documentation-only action.
