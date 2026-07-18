# Repository Status and Plan Archival

**Status:** `completed`

**Authority:** Action Request 13B, `AGENTS.md`, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, and the repository-local planning/documentation workflows.

**Baseline:** Clean synchronized `main` at `7fd2a6f267b87ed90d15ef980b166866d3872fe3`; documentation and workflow only; exactly one commit named `docs: reconcile repository status and archive plans`; non-force push after a fresh remote fence.

## Scope and constraints

- Reconcile the reviewer landing page, implementation status, publication index, plan links, and plan-lifecycle guidance with the verified Phase 2 through Phase 5A repository state.
- Archive the 13 completed plans named by Action Request 13B without rewriting their historical evidence; make only minimal status and link corrections.
- Keep production code, tests, POMs, dependencies, migrations, API/OpenAPI, runtime configuration, PDFs, and Salus untouched.
- Do not run Maven, Foundry, Anvil, PostgreSQL, Docker, Testcontainers, `jdeps`, an independent review, or a separate simplification cycle.
- Attempt at most one bounded Graphify refresh after the documentation diff is stable. Generate no HTML or query output.

## Restartable checklist

- [x] Confirm local, tracking, fetched, and live remote `main` equal the approved baseline and the worktree is clean.
- [x] Inspect the named plans and confirm their work is closed in repository history; identify stale snapshot status labels requiring minimal correction.
- [x] Create the authoritative plan lifecycle index and empty-active-directory marker.
- [x] Move the 13 completed plans to `docs/plans/completed/` and repair literal links.
- [x] Reconcile README, implementation status, reference index, repository guidance, and applicable planning workflows.
- [x] Verify the four publication blobs and modes from Git metadata only.
- [x] Run changed-document links, structured metadata, stale-reference, scope, security/network/path, and diff checks.
- [x] Attempt the single bounded Graphify refresh and record its result or authorized deferral.
- [x] Mark this plan complete and move it to `docs/plans/completed/` before staging.
- [ ] Stage the exact allowlist, re-fence the remote, commit once, push non-force, and verify clean synchronization.

## Evidence and decisions

- Preflight passed on 2026-07-17: branch `main`; local `HEAD`, `origin/main`, fetched `main`, and live remote `main` all resolved to `7fd2a6f267b87ed90d15ef980b166866d3872fe3`; approved SSH remote; clean worktree.
- The listed plans contain completed implementation and validation evidence. Labels left at `in_progress`, `ready_for_commit`, `ready_to_commit`, `ready_for_graphify_and_git_closeout`, or `ready_for_git_closeout` are pre-commit snapshots, not open work; Action Request 13B and the succeeding Git history authorize minimal status correction to `completed`.
- Existing completed-plan command/evidence records remain historical and are not refreshed merely because their path changes.
- `README.md` now presents the five requested reading entries, the verified Phase 2 through Phase 5A state, the current 414-Maven/five-Foundry gate, the complete limitation set, the plan map, and the PostgreSQL/Maven Wrapper/JAR runtime distinction. `docs/IMPLEMENTATION.md`, `docs/reference/README.md`, repository guidance, planning skills, and prompts are synchronized. `docs/DESIGN.md`, `docs/TRANSFER_DEMO.md`, and ADRs required no edits because their current boundary statements and links were already accurate.
- One Git-metadata-only comparison confirmed all four publications retain their approved-baseline blobs and mode `100644`; no PDF was opened, hashed, rendered, extracted, rewritten, or staged.
- Focused pre-closeout checks passed: no completed-plan Markdown link targets `plans/active/`; local links resolve for every changed Markdown file; skill frontmatter has one name and description; the README contains the required state/evidence/limitations; and `git diff --check` is clean. Final changed/staged scope and security/path checks are repeated at the Git fence.
- **Graphify — `tooling_deferred`:** the single authorized `/opt/homebrew/bin/gtimeout 60 graphify update .` attempt exited 1 immediately because the local rebuild returned `[Errno 1] Operation not permitted`. It produced no tracked diff or `.graphify_*` transient. There was no retry, query, HTML, manual reconstruction, or semantic labeling. `graphify-out/GRAPH_REPORT.md`, `graphify-out/graph.json`, and `graphify-out/manifest.json` were explicitly restored from the approved baseline and remain non-authoritative navigation data.

## Deferrals

- Future Ethereum transfer/burn, full five-effect orchestration, mock-bank runtime effects, production identity/custody/networking, Solana, and non-blockchain finality authorities remain separately authorized work. None is activated by this documentation action.

## Git closeout boundary

This plan is marked complete and archived before the one documentation commit as required by Action Request 13B. Exact staging, the live remote fence, commit/push SHAs, and clean synchronization are necessarily observed after this file's committed snapshot and belong in the final handoff; they do not reopen the completed documentation acceptance.
