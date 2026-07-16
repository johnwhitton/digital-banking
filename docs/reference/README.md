# Reference publications

These user-supplied publications are immutable contextual architecture inputs, not executable code specifications. [`docs/DESIGN.md`](../DESIGN.md), accepted [ADRs](../adr/README.md), versioned contracts, and tests govern implementation details.

Zelle is used only as a public case study. Neither the publications nor this repository claim to describe confidential or deployed Early Warning Services/Zelle architecture, vendors, data, controls, service levels, or implementation plans.

## Integrity and review status

Both files were supplied by the user at the repository `docs/` root in commit `84b2ff350639f537adddd2fc1695e09bae5375b4` and moved without content modification on 16 July 2026. The destination bytes compare equal to the source blobs in that commit. Both committed files have non-executable mode `0644`.

| Publication | Repository file | SHA-256 |
| --- | --- | --- |
| *Designing a Stablecoin Settlement Platform for Existing Real-Time Payment Networks* | [`stablecoin-settlement-reference-architecture.pdf`](stablecoin-settlement-reference-architecture.pdf) | `8a61ab83b427ef587d80edb59feb612a23a4af2497e7e0cda31a4ea30d201e77` |
| *Digital Asset Settlement for Zelle* | [`zelle-digital-asset-settlement-executive-brief.pdf`](zelle-digital-asset-settlement-executive-brief.pdf) | `90b5e0b0ebaaae40dd43ce0adfdbfcd1d44a0cd9de45692428bfe7a990dbb6cd` |

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

## Interpretation boundary

The publications support the durable control-plane, exactness, evidence, finality, signing, reconciliation, and native-language boundaries. They do not select Foundry, Sava, Neon, CCTP, a chain, issuer, custody vendor, or production deployment. Those repository decisions and evidence gates are recorded separately in ADRs and living plans.
