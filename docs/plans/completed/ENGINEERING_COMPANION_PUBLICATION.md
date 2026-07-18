# Engineering Companion Publication Plan

**Status:** `completed`

**Goal:** Publish the committed *Digital Banking Engineering Companion* through the repository's navigation and publication-status documentation without changing the PDF or executable repository state.

## Baseline and verified publication

| Item | Evidence |
| --- | --- |
| Branch and baseline | Clean `main` at `cb1f1164bb1d604dbfbaec6727ed2f899231c812`; local `HEAD`, `origin/main`, fetched `FETCH_HEAD`, and live remote `main` matched before editing. |
| User PDF commit | `cb1f1164bb1d604dbfbaec6727ed2f899231c812` (`Add engineering companion`); retained without amend, replacement, or rewrite. |
| Repository file | `docs/reference/digital-banking-engineering-companion.pdf`; committed non-executable mode `100644` (`0644`). |
| Identity | *Digital Banking Engineering Companion*, Volume II of the Digital Banking Engineering Series, version `1.0.0`, published `2026-07-16`, John Whitton. |
| Integrity | SHA-256 `f535a9e2a7b80737c90423218594f277fc4e49f6be8b1453d9bfe79c1d2733e4`; 40 pages; approximately 15,061 words (15,065 with `pypdf` whitespace extraction). |
| Evidence boundary | The publication pins implementation discussion to `e921fcb1877b46a6881437f46b1a6ebfa115ae58`; current repository status remains governed by the current README, implementation plan, accepted ADRs, source, and tests. |
| Existing references | The architecture and executive PDFs retain their recorded hashes, `100644` modes, and byte equality to their `84b2ff3` source blobs. |

The title page and extracted text confirm that Volume II is vendor-neutral implementation and operations guidance, not production certification or a runnable implementation. `docs/DESIGN.md` and the accepted ADRs require no change because the publication does not alter architecture, executable capability, or an accepted decision.

## Authorized changes

- `README.md`: add the required fourth Background Reading row, publish the Volume II/evidence-snapshot boundary, remove Volume II from future work, preserve Volume III as planned, and make the reference-directory map accurate.
- `docs/reference/README.md`: index all three PDFs and record the companion filename, purpose, pages, hash, version, publication date, and evidence-snapshot commit.
- `docs/IMPLEMENTATION.md`: replace the stale Volume II future-publication claim with published status while retaining Volume III scope and planned status.
- `graphify-out/{GRAPH_REPORT.md,graph.json,manifest.json}`: refresh once after final documentation edits because the tracked manifest must remain current; do not generate HTML or retain query/cache artifacts.

No PDF, executable source, test, OpenAPI contract, migration, POM, dependency, runtime configuration, Phase 3 behavior, Volume III artifact, or Salus content is authorized.

## Validation and closeout

- [x] Validate links in the three changed publication documents, including the exact GitHub PDF URL and Volume II/III wording.
- [x] Inspect the complete diff, confirm no PDF or executable file changed, and run `git diff --check`.
- [x] Refresh and review only the three tracked Graphify artifacts once, with no HTML or retained transient artifacts.
- [ ] Confirm remote `main` remains at the approved baseline; stage the exact allowlist with no PDF or executable file; run `git diff --cached --check`; create exactly one `docs: publish engineering companion` commit and push non-force.
- [ ] Verify the final clean worktree and equality of local `HEAD`, `origin/main`, `FETCH_HEAD`, and live remote `main`.

The Maven reactor is intentionally not rerun: this action changes documentation and reviewed Graphify navigation artifacts only, and the request explicitly substitutes focused documentation validation unless an executable/build file changes.

## Closeout evidence

The generated commit SHA, push result, and post-push synchronization evidence are reported in the final handoff because they cannot be embedded in the commit that creates them.
