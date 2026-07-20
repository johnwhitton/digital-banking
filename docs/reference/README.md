# Reference publications

These user-supplied publications are immutable contextual architecture and engineering inputs, not executable code specifications. [`docs/DESIGN.md`](../DESIGN.md), accepted [ADRs](../adr/README.md), versioned contracts, and tests govern implementation details.

Zelle is used only as a public case study. Neither the publications nor this repository claim to describe confidential or deployed Early Warning Services/Zelle architecture, vendors, data, controls, service levels, or implementation plans.

## Integrity and review status

PDF-only commit `87f8aadf9f2b520c40631cd236eb0a5d91417e95`
synchronized Volume I and the Executive Brief to the published v1.0.5 Ethereum
alignment release. It is not implementation evidence. The executable Solana
baseline immediately before it is
`173ebcbb002cacad479c7ced4361106e7c6f21dc`.

The v1.0.5 source authority is the immutable prep tag
`digital-banking-v1.0.5-ethereum`, which resolves to publication commit
`d367698c9aa1396c5d87102e9bab975982501192`. Release records were reconciled at
`484d6635c705f3e43a7835111930e615109c2938`. The prep worktree and mutable build
directories are not provenance evidence. The two changed repository PDFs have
the same Git blob as both the tag's canonical `dist` output and primary review
copy. Volumes II and III retain their separately governed tagged blobs. All four
committed files have non-executable Git mode `100644`.

| Publication | Version / pages | Bytes | Git blob | SHA-256 |
| --- | --- | ---: | --- | --- |
| [*Designing a Stablecoin Settlement Platform for Existing Real-Time Payment Networks*](stablecoin-settlement-reference-architecture.pdf) | 1.0.5 / 152 | 4,963,702 | `515d621b6d024eb6d40d7258564b1436c6738892` | `a2a451d2830b1aef2dc6efe47110322be0e76f508d99feeaf5c61e6c82b1777b` |
| [*Digital Asset Settlement for Zelle*](zelle-digital-asset-settlement-executive-brief.pdf) | 1.0.5 / 27 | 1,358,915 | `c3d1a431ebb5870a9c6f0f790e7e9bdb55954699` | `96f5b8301406328b6e896a7d8dc0903d472cb07bdcc81157254c8d6e2af8aad1` |
| [*Digital Banking Engineering Companion*](digital-banking-engineering-companion.pdf) | 1.1.0 / 46 | 373,792 | `2b36c73cd47953bfd690b6db2e7d4317b8fff04d` | `9448c01a27810a4d15d59c7bf8ef4e56246c5719abb4b9567f178dd2abec9223` |
| [*Digital Banking Reference Implementation*](digital-banking-reference-implementation.pdf) | 1.0.0 / 45 | 345,440 | `b3b907c414a7c4a68741ad63bd2279d5ea007c6b` | `d7b5aa6218df3d1ef6c7cddd6e9c1e05bf6da25cbdbed670b7eccabd7cba8ca3` |

## Stablecoin settlement reference architecture

| Field | Value |
| --- | --- |
| Full title | *Designing a Stablecoin Settlement Platform for Existing Real-Time Payment Networks: A Reference Architecture Using Zelle as a Public Case Study* |
| Author | John Whitton |
| Version | 1.0.5 |
| Publication date | 18 July 2026 |
| Page count | 152 portrait US Letter pages |
| Source | `digital-banking-v1.0.5-ethereum:architecture/digital-banking/volume1/dist/stablecoin-settlement-reference-architecture.pdf` |
| Publication commit | `d367698c9aa1396c5d87102e9bab975982501192` |
| Repository sync | PDF-only commit `87f8aadf9f2b520c40631cd236eb0a5d91417e95` |
| Evidence snapshot | `johnwhitton/digital-banking@f744fe619de0d6cf1fc295cd4116e880aa00d803` (local Ethereum POC) |
| Purpose | Detailed reference architecture for obligation ownership, layered trust boundaries, authoritative ledger/state, failure containment, four finalities, signing, Java/native boundaries, technology evaluation, and evidence-gated delivery. |

Design traceability:

- `docs/DESIGN.md` sections 3-6 use the publication's system-of-record, layered authority, and Java control-plane boundaries.
- Sections 7-12 use its durable state machine, exact identity/evidence, ambiguity, adapter, and signer-control requirements.
- Sections 16-21 use its transaction boundary, reconciliation, four-finality, error, security, audit, and recovery treatment.
- Sections 22-24 use its evidence-gated topology, decisions/unknowns discipline, and publication traceability method.

## Executive architecture brief

| Field | Value |
| --- | --- |
| Full title | *Digital Asset Settlement for Zelle: Executive Architecture Brief for Stablecoin and Cross-Border Settlement* |
| Author | John Whitton |
| Version | 1.0.5 |
| Publication date | 18 July 2026 |
| Page count | 27 portrait US Letter pages |
| Source | `digital-banking-v1.0.5-ethereum:architecture/digital-banking/executive/zelle/dist/zelle-digital-asset-settlement-executive-brief.pdf` |
| Publication commit | `d367698c9aa1396c5d87102e9bab975982501192` |
| Repository sync | PDF-only commit `87f8aadf9f2b520c40631cd236eb0a5d91417e95` |
| Evidence snapshot | `johnwhitton/digital-banking@f744fe619de0d6cf1fc295cd4116e880aa00d803` (local Ethereum POC) |
| Purpose | Executive framing for the control-plane thesis, governable decisions, bounded pilot, custody roles, Java/native boundary, independent finalities, evidence-first roadmap, and principal risks. |

Design traceability:

- `docs/DESIGN.md` sections 1-5 use the brief's public-case-study boundary, regulated control plane, and separation of business truth from the external rail.
- Sections 11-14 use its adapter isolation, native semantic preservation, controlled signing, and Java/native execution boundary.
- Sections 18-20 use its four independent finalities, ambiguity/duplicate prevention, evidence retention, and failure containment.
- `docs/IMPLEMENTATION.md` uses its recommendation to expand evidence before scope and to change one asset/network/authority dimension at a time.

## Digital Banking Engineering Companion

| Field | Value |
| --- | --- |
| Full title | *Digital Banking Engineering Companion* |
| Series position | Volume II of the Digital Banking Engineering Series |
| Author | John Whitton |
| Version | 1.1.0 |
| Publication date | 2026-07-16 |
| Page count | 46 |
| Git blob | `2b36c73cd47953bfd690b6db2e7d4317b8fff04d` |
| SHA-256 | `9448c01a27810a4d15d59c7bf8ef4e56246c5719abb4b9567f178dd2abec9223` |
| Evidence snapshot | Repository commit `e921fcb1877b46a6881437f46b1a6ebfa115ae58` |
| Source | Committed directly by the user in `cb1f1164bb1d604dbfbaec6727ed2f899231c812` |
| Audience | Engineers, architects, security reviewers, operators, and technical decision makers. |
| Reading time | Approximately 60-75 minutes. |
| Purpose | Vendor-neutral implementation and operations guidance for durable workflow, Java/Spring, wallets and signing, EVM, Solana, submission and observation, infrastructure, testing, performance, and delivery. It is not production certification or a runnable implementation. |

The live repository has advanced since the companion's evidence snapshot. Use the current repository README, implementation plan, accepted ADRs, source, and tests for current implementation status.

## Digital Banking Reference Implementation

| Field | Value |
| --- | --- |
| Full title | *Digital Banking Reference Implementation* |
| Series position | Volume III of the Digital Banking Engineering Series |
| Author | John Whitton |
| Version | 1.0.0 |
| Page count | 45 |
| Git blob | `b3b907c414a7c4a68741ad63bd2279d5ea007c6b` |
| SHA-256 | `d7b5aa6218df3d1ef6c7cddd6e9c1e05bf6da25cbdbed670b7eccabd7cba8ca3` |
| Source | Committed directly by the user in `113c0f90bf21590501d0c62dab693176dedb195f` |
| Audience | Engineers, architects, security reviewers, operators, and technical decision makers. |
| Reading time | Approximately 60-75 minutes. |
| Purpose | Code companion mapping architecture to repository modules, APIs, database schema and migrations, implementation excerpts, local build/run/test flows, and the boundary between local demonstrations and production integrations. It is contextual guidance rather than current executable status authority. |

The live repository may advance beyond any evidence snapshot described in this volume. Use current source, tests, OpenAPI, accepted ADRs, `docs/DESIGN.md`, and `docs/IMPLEMENTATION.md` for implementation claims.

## Publication snapshot boundary

Volume I and the Executive Brief v1.0.5 align to the local Ethereum evidence
snapshot named above. They do not describe the later local Solana implementation
completed at `173ebcbb002cacad479c7ced4361106e7c6f21dc`. Publication snapshots may
lag the live implementation. A future Solana publication alignment will be a
separately versioned publication and will be synchronized only after it is
published; no such release is claimed here.

## Interpretation boundary

The publications support the durable control-plane, exactness, evidence, finality, signing, reconciliation, native-language, implementation, and operations boundaries. They do not certify this repository for production or select a chain, issuer, custody vendor, or production deployment. Repository decisions and current evidence gates are recorded separately in ADRs, living plans, source, and tests.
