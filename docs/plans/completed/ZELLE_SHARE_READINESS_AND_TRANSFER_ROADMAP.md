# Zelle Share Readiness and Transfer Roadmap Execution Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use the repository `plan-execution` workflow task-by-task. Use `digital-banking-doc-sync` for every living-document change and `verification-before-completion` before commit or completion claims.

**Status:** `completed`

**Goal:** Make the repository reviewer-first for Zelle/Early Warning Services readers and specify a planned local bank-to-bank stablecoin transfer demonstration without adding executable capability.

**Architecture:** Keep the verified Phase 3A token-operation acceptance API unchanged. Add one proposed parent transfer resource and durable five-step workflow specification, then synchronize the reviewer landing page, canonical design, and delivery roadmap while preserving domain-owned state, narrow local transactions, signer isolation, independent observation, four finalities, and chain-native adapter boundaries.

**Tech stack:** Markdown, Mermaid, existing Java 25 / Maven 3.9.16 verification, Graphify 0.8.47 local navigation artifacts, and immutable PDF source publications.

**Authority:** Action Request 05, `AGENTS.md`, `SECURITY.md`, accepted ADRs, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, `AUTONOMOUS_EXECUTION_POLICY.md`, and executable tests. The source PDFs and Graphify output are contextual inputs, not implementation authority.

## Global constraints

- Work directly on clean synchronized `main` from approved baseline `e921fcb1877b46a6881437f46b1a6ebfa115ae58`.
- Create exactly one non-force commit named `docs: prepare Zelle review and transfer roadmap`, then push only if fetched `origin/main` still equals the baseline.
- Documentation and planning only: no product code, Maven dependency, database/API/worker/signer/adapter implementation, contract, program, chain process, runtime configuration, test implementation, or empty future module tree.
- Zelle remains a public case study. Make no affiliation, endorsement, confidential-architecture, selected-vendor, or announced-plan claim about Zelle or Early Warning Services.
- Preserve both PDFs byte-for-byte at mode `0644`; do not regenerate, optimize, rename, or replace them.
- The transfer target remains `planned`; the current API durably accepts and reads back mint/burn operation requests but does not process, sign, submit, mint, burn, transfer, reconcile, or settle.
- Phase 3B worker and recovery remains the next implementation dependency. Phase 3C may plan the chain-neutral transfer aggregate and mock-bank boundary; signing and external chain effects remain later phases.
- No BPM or workflow vendor is selected. A database-backed Java/Spring worker is the self-contained reference baseline unless a focused evidence spike and ADR accept another runtime.
- Ethereum and Solana remain separate local demonstrations and separate implementation slices. Ethereum stays first; Solana preserves native SVM/SPL Token semantics.
- No private key, seed phrase, credential, funded address, public-network default, real bank API, real funds, or production profile enablement may appear.

## Baseline and source evidence

| Gate | Command or evidence | Observed result |
| --- | --- | --- |
| Git authority | `git fetch origin`; branch, status, SHA, remote, and log inspection | Passed on 2026-07-16: clean `main`; local `HEAD` and fetched `origin/main` both `e921fcb1877b46a6881437f46b1a6ebfa115ae58`; approved SSH remote. |
| Graphify | Version check plus vocabulary-constrained query for architecture/documentation/capability/status/phase/workflow/evidence ownership | Graphify 0.8.47 used locally. It identified README, design, implementation, and active-plan ownership; direct source remains authoritative. Query memory is ignored and will not be committed. |
| Governance | Complete read of `AGENTS.md`, `SECURITY.md`, `AUTONOMOUS_EXECUTION_POLICY.md`, living docs, all accepted ADRs, and all plans under `docs/plans/active/` | Confirmed documentation-only authority, phase order, immutable references, and current Phase 3A limits. |
| Publications | `pdfinfo`, `pypdf` text extraction, representative page rendering, SHA-256, and mode inspection | Executive brief: 20 pages, explicit 18-minute estimate. Reference architecture: 112 pages, explicit guided reading path. Hashes match the required values and both modes are `0644`. Temporary extraction/rendering is outside the repository. |
| Current implementation | README/design/implementation/Phase 3A plan plus API source, OpenAPI, and test inventory | Phase 3A is verified durable acceptance/read-back only. Phase 3B processing, signers, chains, transfer orchestration, observation, and reconciliation are absent. |

## Decisions for this action

1. `README.md` becomes the reviewer landing page. It will lead with the exact `BACKGROUND READING - START HERE` table, then use compact `What This Demonstrates`, `Complete Now`, `Target Demonstration`, `Future Work`, repository map, quick start, and engineering-workflow sections.
2. `docs/TRANSFER_DEMO.md` is the single proposed POC contract. Other living docs summarize and link to it instead of duplicating the five-step specification.
3. The public transfer boundary is one asynchronous parent resource: `POST /v1/transfers` and `GET /v1/transfers/{transferId}`. The five child effects are internal orchestration, not five caller-owned commands.
4. The transfer is an asynchronous saga/workflow with narrow local transactions. Confirmed external effects are never rolled back destructively; compensation is a new authorized operation with its own evidence.
5. A provider-neutral process-manager boundary coordinates durable work but does not replace domain state, ledger, policy/approval, signer authority, observation, finality, or reconciliation.
6. The supplied job-description technology posture is labeled inference only: Java/Spring plus Oracle and Kafka/JMS/TIBCO EMS is plausible, but no Zelle/EWS implementation fact is claimed. Messaging transports are not BPM engines or financial-state authorities.
7. Phase 3B evaluates the database-backed worker and an approved enterprise BPM/durable-workflow platform against the same contracts. Camunda 8 and Temporal are representative evaluation candidates only; TIBCO BusinessWorks/BPM Enterprise, MuleSoft, and SAP integration products stay at integration/process boundaries unless organizational evidence and an ADR say otherwise.
8. No new ADR is created because this action selects no workflow vendor, issuer, signer/custody provider, chain provider, or production architecture. Accepted ADRs 0001-0004 remain unchanged.

## Intended repository changes

| Path | Action | Responsibility |
| --- | --- | --- |
| `README.md` | Modify | Reviewer-first landing page, exact background-reading table, evidence-backed present/future split, target demonstration, quick start, and retained AI workflow. |
| `docs/TRANSFER_DEMO.md` | Create | Planned transfer API, aggregate/workflow, five-step evidence and retry contract, BPM boundary, chain realization, key/configuration safety, and completion criteria. |
| `docs/DESIGN.md` | Modify | Planned transfer terminology, ownership, topology, API/configuration, workflow authority, compensation, security, unknowns, and deferrals. |
| `docs/IMPLEMENTATION.md` | Modify | Current Phase 3 status, Phase 3B evaluation boundary, Phase 3C transfer slice, expanded signer/chain/integration phases, and planned Volume II/III publications. |
| `docs/plans/completed/ZELLE_SHARE_READINESS_AND_TRANSFER_ROADMAP.md` | Create/update | Restartable scope, decisions, progress, exact commands, results, reviews, deferrals, and Git closeout. |
| `graphify-out/GRAPH_REPORT.md`, `graphify-out/graph.json`, `graphify-out/manifest.json` | Update only if the approved local Graphify workflow requires it | Keep tracked navigation artifacts synchronized with the final documentation corpus; exclude PDFs, query memory, caches, costs, reflections, HTML, secrets, and machine state. |

`docs/reference/README.md`, the two PDFs, accepted ADRs, product source, POMs, OpenAPI, configuration, and existing historical plans do not need content changes unless verification exposes a factual inconsistency that this action owns. The immutable reference index already records correct provenance and checksums.

## Ordered execution

### 1. Establish the plan and RED documentation baseline

- [x] Confirm the approved Git baseline and stop conditions.
- [x] Review Graphify navigation, governance, living docs, accepted ADRs, active plans, current source/tests, and both PDFs.
- [x] Create this active plan before editing other repository files.
- [x] Record focused RED assertions showing the background-reading heading, transfer specification, transfer links, planned transfer API, Phase 3C, and Volume II/III roadmap are absent at the baseline.

### 2. Build the reviewer-first landing page

- [x] Place the exact `## BACKGROUND READING - START HERE` section immediately after the title, description, safety notice, and public-case-study disclaimer.
- [x] Add the required four-column table with exact destinations, audience intent, and reading-time values.
- [x] Separate purpose, verified executable capability, scaffolded/designed boundaries, target demonstration, and future work without overstating durable acceptance.
- [x] Preserve the repository map, verified quick start, security direction, AI-assisted engineering workflow, and next-slice recommendation.
- [x] Link to `docs/TRANSFER_DEMO.md` and state prominently that its five-step flow is future work.

### 3. Specify the target transfer demonstration

- [x] Create the concise planned/local-only purpose, audience, and status boundary.
- [x] Specify `POST /v1/transfers` and `GET /v1/transfers/{transferId}`, exact request fields, HTTP 202/Location, safe status evidence, and allowlisted versioned local route selection.
- [x] Specify the parent/child identities, canonical idempotency, durable transitions/evidence, outbox/inbox recovery, ambiguity inquiry, independent finalities, and compensating-operation rules.
- [x] Add one small control-plane/source-bank/signer/chain/observer/destination-bank flow diagram.
- [x] Record the deployment-neutral workflow boundary, job-description inference, transport-versus-authority distinction, open Phase 3B choice, evaluation criteria, and ADR-before-dependency gate.
- [x] Define all five steps by owner/port, durable evidence, success, ambiguity/failure handling, and safe retry posture.
- [x] Preserve ADR 0002/0003 chain choices, future executable locations without creating them, local-key/configuration constraints, and separate Ethereum/Solana completion criteria.

### 4. Synchronize canonical design and roadmap

- [x] Update design goals/terminology and the `Transfer -> child bank effects/token operations -> chain attempts -> observations` relationship.
- [x] Add bank ports, settlement-wallet roles, route selection, process-manager boundary, saga/narrow-transaction semantics, planned transfer API/configuration, topology, security, compensation, unknowns, and deferrals; link to the detailed transfer spec.
- [x] Preserve the implemented mint/burn API and exact current boundary.
- [x] Update Phase 3B to compare two bounded worker/orchestrator approaches without selecting a vendor.
- [x] Add planned Phase 3C for the chain-neutral transfer aggregate, API/status, persistence, and mock-bank ports/adapters.
- [x] Expand Phases 4-8 for transfer signing, Ethereum mint/transfer/burn, native SPL mint/transfer/burn, observation/recovery, and two complete five-step local demonstrations while preserving dependency order.
- [x] Add planned Volume II and Volume III publication scope without creating placeholder publications.

### 5. Refresh navigation artifacts only as required

- [x] Run the approved local Graphify incremental workflow after authoritative docs settle.
- [x] Accept only reviewed portable changes to the tracked report, graph, and manifest; do not index PDFs or commit transient output.
- [x] Verify manifest hashes, graph endpoints/IDs, no absolute workstation paths, no secrets, and useful query coverage against direct source.

### 6. Validate and review before commit

- [x] Run exact-heading/table/required-term assertions and inspect Markdown rendering structure.
- [x] Verify every local Markdown link and the three background-reading destinations.
- [x] Re-run PDF hashes, modes, source-blob comparisons, and `git diff -- docs/reference`.
- [x] Run the JDK/Maven version check, offline clean reactor, and focused control-plane smoke tests.
- [x] Parse repository JSON/TOML/XML/YAML where applicable and run document-sync, stale-claim, Zelle-boundary, secret/key, public-network, generated-artifact, and portability scans.
- [x] Run `git diff --check`, verify the changed-file allowlist, and inspect the complete unstaged diff.
- [x] Run Ponytail full-mode simplicity review and remove duplicate roadmaps, vendor-comparison sprawl, speculative abstractions, and empty placeholders.
- [x] Obtain an independent read-only review for capability accuracy, public-case-study boundaries, key/configuration safety, distributed-atomicity implications, links, and phase order. Resolve every Critical and Important finding.
- [x] Record exact results and review dispositions in this plan.

### 7. Single commit and authorized push

- [x] Invoke `verification-before-completion` and rerun the applicable gates fresh within the user's post-review scope; retain the completed Maven/review evidence because no relevant source/build file changed.
- [x] Fetch `origin` and stop if `origin/main` is no longer `e921fcb1877b46a6881437f46b1a6ebfa115ae58`.
- [x] Stage only the reviewed allowlist; run `git diff --cached --check` and inspect the complete staged diff.
- [ ] Commit once as `docs: prepare Zelle review and transfer roadmap`.
- [ ] Push `main` to `origin/main` without force.
- [ ] Verify local `HEAD`, fetched `origin/main`, and remote `main` agree; confirm a clean synchronized worktree; record final SHA.

## Exact validation commands

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw --version
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl control-plane -am -Dtest=DigitalBankingApplicationTests,HealthReadinessSmokeTests -Dsurefire.failIfNoSpecifiedTests=false test
shasum -a 256 docs/reference/zelle-digital-asset-settlement-executive-brief.pdf docs/reference/stablecoin-settlement-reference-architecture.pdf
stat -f '%Sp %Lp %N' docs/reference/zelle-digital-asset-settlement-executive-brief.pdf docs/reference/stablecoin-settlement-reference-architecture.pdf
git diff --check
git status --short --branch
```

Additional read-only checks will use repository-installed or standard-library tools to validate Markdown links, required headings/table values, JSON/TOML/XML/YAML syntax, Graphify metadata, staged portability, source-PDF blob equality, stale/current capability language, Zelle/EWS disclaimers, secret-like assignments, key material, public-network defaults, and generated artifacts. Every exact command and observed result will be appended below before commit.

## Stop and restart conditions

On restart, inspect Git status, local/fetched remote SHAs, this checklist, the changed-file allowlist, and the latest evidence before editing. Stop for baseline or remote divergence, unexpected user changes, PDF byte/mode change, required product or dependency work, a new vendor/authority/production decision, public-network or credential handling, unresolved Critical review findings, a failing required gate, or scope expansion beyond documentation and planning.

## Evidence log

| Gate | Command/evidence | Result |
| --- | --- | --- |
| Preflight/source review | Baseline, Graphify, governance, ADR/plan, source/test, and PDF checks recorded above | Passed; documentation execution may proceed. |
| Documentation RED | Six `rg`/file assertions for the background heading, transfer spec/link/API, Phase 3C, and Volume II/III | Passed as RED: all six exited 1 at baseline because the approved documentation did not yet exist. |
| Documentation synchronization | Standard-library focused assertion matrix over README, transfer spec, design, implementation, plan, phase order, required terms, and absent future directories | Passed after correcting two case-sensitive test expectations; authoritative content did not need alteration for those assertion bugs. |
| Graphify refresh | Graphify 0.8.47 local incremental extraction, merge, clustering, diagnostics, and tracked-artifact review | Passed. The final post-review graph has 893 nodes, 2,348 edges, 4 hyperedges, and 75 labeled communities; diagnostics found zero dangling, missing, self-loop, duplicate, or collapsed edges. The 123-file portable manifest matches current hashes and excludes PDFs and `graphify-out/`. A graph path connects Phase 3B through Phase 3C and the five-step workflow to the transfer API. Only the tracked report, graph, and manifest remain changed. |
| Maven | `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw --version`; `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify`; focused control-plane smoke command | Passed: Maven 3.9.16 on Java 25.0.2; all five reactor modules `SUCCESS`; 302 tests with zero failures/errors/skips; focused `DigitalBankingApplicationTests,HealthReadinessSmokeTests` ran 3 tests with zero failures/errors/skips. Per user direction, these completed gates are not rerun without a relevant source/build change or failure. |
| Static/document/security | Focused assertion matrix; local Markdown link checker; three destination checks; JSON/TOML/XML/YAML and skill-frontmatter parsing; PDF checks; portability/secret/network/stale-project scans; `git diff --check` | Passed. The final focused matrix includes participant-scoped 404 and ADR-path authority; 57 local links across 19 Markdown files resolve. The portfolio destination returned HTTP 200; authenticated GitHub confirmed both required PDF paths on `main` (anonymous access returns 404 because the repository is private). Added lines contain no workstation paths, secret assignments, public-network defaults, or stale-project commands. |
| Publications | SHA-256, mode, original import-blob comparison, and `git diff -- docs/reference` | Passed. Executive brief `90b5e0b0ebaaae40dd43ce0adfdbfcd1d44a0cd9de45692428bfe7a990dbb6cd`; reference architecture `8a61ab83b427ef587d80edb59feb612a23a4af2497e7e0cda31a4ea30d201e77`; both mode `0644`; both byte-equal to the source import blobs; reference tree unchanged. |
| Ponytail | Full-mode final simplicity review over authoritative documentation and proposed paths | Passed: no dependency, wrapper, empty tree, duplicate scenario specification, selected-vendor claim, or speculative executable abstraction was added. Required summaries remain linked to the single detailed transfer specification. |
| Independent review | Read-only review against Action Request 05 plus remediation recheck | No Critical findings. Three Important findings (ADR path authority, participant-scoped transfer lookup, and Phase 3B traceability) plus one Minor closeout wording issue were fixed. The reviewer confirmed all resolved, no new Critical/Important issues, and a `Yes` merge verdict subject to remaining mechanical gates. |
| Git closeout | Pre-commit fetch plus exact stage/commit/push/remote synchronization | Pre-commit fetch passed: local `HEAD` and fetched `origin/main` remain `e921fcb1877b46a6881437f46b1a6ebfa115ae58`. The exact eight-file allowlist is staged; cached whitespace, product/build exclusion, Graphify/manifest, portability, secret, and untracked-file checks pass. Commit, push, and post-push verification remain. |

## Intentional deferrals

- Phase 3B worker/recovery implementation remains the recommended next bounded action.
- Phase 3C transfer implementation, mock banks, signers, chain adapters/contracts/programs, observation/reconciliation, integration environment, and end-to-end tests require focused active plans and later approvals.
- Workflow-platform selection requires organizational evidence, a focused spike, and an ADR.
- Volume II and Volume III remain planned publications; this action creates neither file.
- Production, legal, accounting, compliance, vendor, real-bank, public-network, and real-funds decisions remain outside this repository action.

## Closeout state

`in_progress` - authoritative documentation, Graphify synchronization, Maven/static evidence, Ponytail review, independent review, and exact staging are complete. The single commit, push, and remote SHA verification remain.
