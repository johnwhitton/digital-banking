---
name: digital-banking-doc-sync
description: Use when architecture, module layout, dependencies, commands, lifecycle behavior, security posture, delivery sequencing, workflow, or capability status changes in this repository.
---

# Digital Banking Document Synchronization

## Overview

Keep accepted implementation, architecture, status, and evidence consistent. Treat stale or overstated documentation as a defect in the same change.

## Document ownership

| Change | Update |
| --- | --- |
| Purpose, capability status, repository map, quick start, AI workflow | `README.md` |
| Boundaries, terminology, lifecycle, contracts, finality, security, topology | `docs/DESIGN.md` |
| Layout, versions, commands, phase status, sequencing, risks, next slice | `docs/IMPLEMENTATION.md` |
| Task progress, decisions, exact evidence, deferrals, closeout | Owning plan under `docs/plans/active/` during execution; move to `docs/plans/completed/` at successful closeout |
| Material accepted decision | New numbered ADR plus living-doc updates |
| Reference publication provenance | `docs/reference/README.md`; never modify the PDFs |
| Contributor process or local skills/hooks/prompts | `AGENTS.md`, README workflow section, and active plan |

## Workflow

1. Read the changed code/config and every current document that owns the same fact.
2. List facts that changed: names, versions, paths, commands, boundaries, statuses, risks, and evidence.
3. Update the smallest authoritative sections; link instead of duplicating long narratives.
4. Preserve decision history. Never rewrite an accepted ADR to make a new material choice look old; create a superseding ADR and cross-link both records.
5. If a normally relevant document does not change, record the concrete reason in the active plan or handoff.
6. Verify every current-state claim against files and fresh command output. Use `planned`, `scaffolded`, `implemented`, and `verified` exactly as README defines them.
7. Check relative links, filenames, commands, versions, terminology, placeholders, and stale product/source references.

## Evidence contract

- A quick-start command appears only after it has run successfully in the documented form.
- `verified` appears only with a fresh recorded gate.
- Future modules/capabilities are labeled planned and are not shown as existing paths.
- The reference PDFs retain their recorded SHA-256 values and byte identity.
- Zelle remains a public case study; no EWS/Zelle implementation claim appears.
- Salus may appear only as read-only bootstrap provenance, never as a runtime name, path, command, skill, or product description.

## Common mistakes

- Updating only README after changing architecture or delivery sequencing.
- Appending a contradictory note instead of editing the source-of-truth section.
- Changing an accepted ADR rather than superseding it.
- Marking a capability verified because a narrower unit test passed.
- Documenting a command that has not run, or hiding a tool failure.
