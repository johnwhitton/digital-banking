# Reference publications

These user-supplied publications are immutable contextual architecture and engineering inputs, not executable code specifications. [`docs/DESIGN.md`](../DESIGN.md), accepted [ADRs](../adr/README.md), versioned contracts, and tests govern implementation details.

Zelle is used only as a public case study. Neither the publications nor this repository claim to describe confidential or deployed Early Warning Services/Zelle architecture, vendors, data, controls, service levels, or implementation plans.

## Integrity and review status

The architecture and executive files were supplied by the user at the repository `docs/` root in commit `84b2ff350639f537adddd2fc1695e09bae5375b4` and moved without content modification on 16 July 2026. Their destination bytes compare equal to the source blobs in that commit. The Engineering Companion was committed directly at its indexed path in `cb1f1164bb1d604dbfbaec6727ed2f899231c812`, and the Reference Implementation was committed directly at its indexed path in `113c0f90bf21590501d0c62dab693176dedb195f`. All four committed files have non-executable repository mode `0644`.

| Publication | Repository file | Recorded integrity identity |
| --- | --- | --- |
| *Designing a Stablecoin Settlement Platform for Existing Real-Time Payment Networks* | [`stablecoin-settlement-reference-architecture.pdf`](stablecoin-settlement-reference-architecture.pdf) | `8a61ab83b427ef587d80edb59feb612a23a4af2497e7e0cda31a4ea30d201e77` |
| *Digital Asset Settlement for Zelle* | [`zelle-digital-asset-settlement-executive-brief.pdf`](zelle-digital-asset-settlement-executive-brief.pdf) | `90b5e0b0ebaaae40dd43ce0adfdbfcd1d44a0cd9de45692428bfe7a990dbb6cd` |
| *Digital Banking Engineering Companion* | [`digital-banking-engineering-companion.pdf`](digital-banking-engineering-companion.pdf) | `9448c01a27810a4d15d59c7bf8ef4e56246c5719abb4b9567f178dd2abec9223` |
| *Digital Banking Reference Implementation* | [`digital-banking-reference-implementation.pdf`](digital-banking-reference-implementation.pdf) | Not separately recorded; approved Git blob `b3b907c414a7c4a68741ad63bd2279d5ea007c6b` |

## Stablecoin settlement reference architecture

| Field | Value |
| --- | --- |
| Full title | *Designing a Stablecoin Settlement Platform for Existing Real-Time Payment Networks: A Reference Architecture Using Zelle as a Public Case Study* |
| Author | John Whitton |
| Version | 1.0.1 |
| Publication date | 16 July 2026 |
| Source | Supplied directly by the user |
| Review date | 16 July 2026 |
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
| Version | 1.0.1 |
| Publication date | 16 July 2026 |
| Source | Supplied directly by the user |
| Review date | 16 July 2026 |
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
| Version | 1.0.0 |
| Publication date | 2026-07-16 |
| Page count | 40 |
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
| Source | Committed directly by the user in `113c0f90bf21590501d0c62dab693176dedb195f` |
| Audience | Engineers, architects, security reviewers, operators, and technical decision makers. |
| Reading time | Approximately 60-75 minutes. |
| Purpose | Code companion mapping architecture to repository modules, APIs, database schema and migrations, implementation excerpts, local build/run/test flows, and the boundary between local demonstrations and production integrations. It is contextual guidance rather than current executable status authority. |

The live repository may advance beyond any evidence snapshot described in this volume. Use current source, tests, OpenAPI, accepted ADRs, `docs/DESIGN.md`, and `docs/IMPLEMENTATION.md` for implementation claims.

## Interpretation boundary

The publications support the durable control-plane, exactness, evidence, finality, signing, reconciliation, native-language, implementation, and operations boundaries. They do not certify this repository for production or select a chain, issuer, custody vendor, or production deployment. Repository decisions and current evidence gates are recorded separately in ADRs, living plans, source, and tests.
