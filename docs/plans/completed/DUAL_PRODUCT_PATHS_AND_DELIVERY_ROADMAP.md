# Dual Product Paths and Delivery Roadmap

**Status:** `completed`

**Authority:** Action Request 14, `AGENTS.md`, accepted ADRs, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, and `docs/TRANSFER_DEMO.md`.

**Baseline:** Clean synchronized `main` at `f75c493fbc9af4f89f83228b5f86631b64cc275c`; documentation and ADR only; exactly one commit named `docs: align product paths and delivery roadmap`; non-force push after a fresh remote fence.

## Scope

- Define settlement-only and user-held USDZELLE as distinct product paths with neutral roles, explicit ownership/custody boundaries, synthetic reserve concepts, and saga semantics.
- Make `docs/TRANSFER_DEMO.md` authoritative for Demo A and Demo B and make `docs/IMPLEMENTATION.md` authoritative for planned Phases 5B-8.
- Add ADR 0008 for the accepted product/custody/reserve/Ethereum-first decision and keep README concise.
- Preserve the verified Phase 5A boundary and all four PDF blobs; change no source, test, POM, dependency, migration, API/OpenAPI, contract, runtime configuration, key, `.env`, or Salus content.
- Create no placeholder future-phase plans, duplicate roadmap, demo script, or configured wallet/reserve/bank/chain implementation.

## Restartable checklist

- [x] Verify local, tracking, fetched, and live remote `main` equal the approved baseline; confirm clean `main` and approved remote.
- [x] Read governing instructions, living documents, plan lifecycle, relevant ADRs, and completed Phase 3C-5A evidence.
- [x] Confirm no accepted ADR contradicts the supplied product and delivery decisions.
- [x] Add ADR 0008 and update the ADR index/cross-references.
- [x] Reconcile README, design, demo specifications, and implementation roadmap.
- [x] Verify both paths/demos, neutral roles, custody modes, reserve invariant, saga/finality boundaries, current gaps, and planned Phase 5B-8 sequence across documents.
- [x] Run changed-document links, ADR/index, future-plan, scope, safety, PDF Git-metadata, and diff checks.
- [x] Attempt the single 60-second Graphify refresh and record success or `tooling_deferred` without retry.
- [x] Mark this plan complete and move it to `docs/plans/completed/`; leave `active/` with only its guide.
- [x] Prepare the exact documentation allowlist and re-fence live remote `main` at the approved baseline. The enclosing commit, non-force push, and synchronized-state evidence follow after this completed plan becomes part of that commit.

## Decisions and evidence

- The approved baseline records 414 passing Maven tests across seven modules and five passing Foundry tests at Phase 5A closeout. This documentation action does not rerun or reinterpret them.
- ADRs 0002/0007 require Ethereum first and preserve Web3j/Foundry/Anvil boundaries; ADR 0003 already requires native Solana semantics and a bounded Java-client gate; ADR 0006 keeps the present local signer session-ephemeral. Action Request 14 extends these decisions without superseding them.
- Phase 3C's current five-effect aggregate remains verified historical capability. Demo A is a future six-step settlement-only workflow that separates redemption transfer from ADMIN burn; no document may imply that Phase 3C already executes or durably orchestrates those six effects.
- Graphify is non-authoritative and was previously deferred. Direct authoritative document/ADR evidence governs this action.
- `tooling_deferred`: the single capped command `/opt/homebrew/bin/gtimeout 60 graphify update .` exited 1 because the local rebuild failed with `[Errno 1] Operation not permitted`. It was not retried. The approved-baseline versions of `graphify-out/GRAPH_REPORT.md`, `graphify-out/graph.json`, and `graphify-out/manifest.json` were explicitly restored; no attempt-created `.graphify_*` transient remained.

## Closeout evidence

- Focused assertions passed for both paths, both demos, Ethereum-before-Solana ordering, all twelve planned Phase 5B-8 entries, bounded README claims, and ADR 0008/index consistency.
- `git diff --check` passed before staging. Changed-file local-link and staged diff checks are repeated against the completed plan path in the final staging gate.
- Git metadata—not PDF content inspection—confirmed all four `docs/reference/*.pdf` paths remain their approved blobs with mode `100644`.
- A fresh `git fetch origin main` followed by local, tracking, fetched, and live-remote checks kept all four refs at `f75c493fbc9af4f89f83228b5f86631b64cc275c` before staging.
- No implementation build, test, review, web, PDF-content, or structured-file command ran because this action changes only Markdown documentation and an ADR.

## Deferrals

All planned Phase 5B-8 implementation, endpoint naming, production issuer/custodian/provider selection, reserve accounting policy, real assets/funds, public networks, legal/regulatory conclusions, yield, and revenue sharing require separately authorized work.
