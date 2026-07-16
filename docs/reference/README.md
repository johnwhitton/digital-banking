# Reference publications

These publications are contextual architecture inputs, not executable code specifications. [`docs/DESIGN.md`](../DESIGN.md), accepted [ADRs](../adr/README.md), API contracts, and tests govern implementation details.

Zelle is used only as a public case study. Neither the publications nor this repository claim to describe confidential or deployed Early Warning Services/Zelle architecture, vendors, data, controls, service levels, or implementation plans.

## Attachment status: blocked

The request attachment directory contained only `pasted-text.txt`. It did not contain either named PDF, so no locally built or similarly titled file is committed as a substitute. This follows the action request's explicit instruction not to invent replacements when the supplied attachment bytes are unavailable.

The two exact missing inputs are:

1. `zelle-digital-asset-settlement-executive-brief(1).pdf`
2. `stablecoin-settlement-reference-architecture(5).pdf`

When those exact files are supplied, copy them byte-for-byte to the normalized targets below, record source and destination SHA-256 values, and confirm binary equality with `cmp`. Do not regenerate, optimize, compress, annotate, or rewrite them.

## Digital Asset Settlement for Zelle

| Field | Value |
| --- | --- |
| Canonical title | *Digital Asset Settlement for Zelle: Executive Architecture Brief for Stablecoin and Cross-Border Settlement* |
| Author | John Whitton |
| Version | 1.0.0 |
| Publication date | 16 July 2026 |
| Requested attachment filename | `zelle-digital-asset-settlement-executive-brief(1).pdf` |
| Normalized repository target | `docs/reference/digital-asset-settlement-executive-brief.pdf` |
| SHA-256 | Blocked: exact source attachment unavailable |

Role: concise executive framing for the architecture thesis, governable decisions, chain evaluation, bounded pilot, custody roles, Java/native boundary, evidence-gated roadmap, and principal risks.

## Stablecoin settlement reference architecture

| Field | Value |
| --- | --- |
| Canonical title | *Designing a Stablecoin Settlement Platform for Existing Real-Time Payment Networks: A Reference Architecture Using Zelle as a Public Case Study* |
| Author | John Whitton |
| Version | 1.0.0 |
| Publication date | 13 July 2026 |
| Requested attachment filename | `stablecoin-settlement-reference-architecture(5).pdf` |
| Normalized repository target | `docs/reference/stablecoin-settlement-reference-architecture.pdf` |
| SHA-256 | Blocked: exact source attachment unavailable |

Role: detailed reference architecture for obligation ownership, layered trust boundaries, durable state, ledger and consistency, failure containment, four finalities, security and signing, Java/native boundaries, technology evaluation, delivery gates, and implementation-review appendices.

## Integrity verification after attachment delivery

Run both SHA-256 and binary comparison against each exact supplied source before accepting the files:

```bash
shasum -a 256 /path/to/exact/source.pdf docs/reference/normalized-target.pdf
cmp /path/to/exact/source.pdf docs/reference/normalized-target.pdf
```

Any checksum mismatch is a blocker, not a documentation edit.
