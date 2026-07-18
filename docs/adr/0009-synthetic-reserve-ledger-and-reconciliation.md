# ADR 0009: Synthetic reserve ledger and reconciliation

- Status: accepted
- Date: 2026-07-18
- Owners: repository owner; reference-implementation maintainers
- Supersedes: None
- Superseded by: None

## Context

ADR 0008 requires a synthetic reserve and supply boundary before either local USDZELLE demonstration can be orchestrated. Phase 6A needs enough accounting authority to distinguish customer bank balances, reserve cash, fiat pending mint, circulating liability, redemption payable, redemption custody pending burn, and confirmed chain supply. Treating a mock-bank balance or chain receipt as the ledger would collapse external evidence into internal business truth.

The slice must support payout-before-burn and burn-before-payout after confirmed redemption custody, prevent duplicate evidence use under concurrency/restart, and report breaks without inventing entries to force reconciliation. It remains local reference software: no chart expansion, general ledger platform, production reserve asset, attestation, or accounting-finality claim is authorized.

## Decision

- Use a closed four-account POC chart:
  - `RESERVE_CASH_ASSET`, debit normal;
  - `FIAT_RECEIVED_PENDING_MINT_LIABILITY`, credit normal;
  - `USDZELLE_CIRCULATING_LIABILITY`, credit normal; and
  - `REDEMPTION_PAYABLE_LIABILITY`, credit normal.
- Keep `ADMIN_REDEMPTION_CUSTODY_PENDING_BURN`, `CONFIRMED_CHAIN_TOTAL_SUPPLY`, and `CONTROLLED_INVENTORY` as separate operational positions, not additional dollar balance-sheet accounts. Controlled inventory starts at zero and cannot be used as an unexplained balancing plug.
- Construct entries only through these trusted evidence transitions:
  - confirmed withdrawal: debit reserve cash, credit pending mint;
  - confirmed mint: debit pending mint, credit circulating;
  - confirmed redemption custody: debit circulating, credit redemption payable, and increase custody pending burn;
  - confirmed bank payout: debit redemption payable, credit reserve cash; and
  - confirmed burn: decrease custody pending burn and confirmed chain supply without another dollar journal.
- Require exactly one positive debit and credit of equal cents for every dollar journal. Persist journal, line, position, and evidence-consumption changes atomically. Posted history is append-only; corrections use a balanced entry linked to the original rather than mutation.
- Resolve authoritative durable bank or confirmed-chain evidence by identity and verify the retained type, outcome, native correlation, exact amount, asset, local network/contract, policy version, canonicality, finality, observation freshness, and observed supply where applicable. Requesters cannot submit arbitrary accounts, lines, directions, balances, success, finality, or supply.
- Consume one evidence identity once using database uniqueness. Exact command replay returns the original posting result; another posting or command digest conflicts.
- For the zero-equity local fixture, reconcile exact equality:

  ```text
  reserve_cash_asset
    = pending_mint_liability
    + circulating_usdzelle_liability
    + redemption_payable_liability

  confirmed_chain_total_supply
    = circulating_usdzelle_liability
    + admin_redemption_custody_pending_burn
    + controlled_inventory
  ```

  This is the executable Phase 6A realization of ADR 0008's broader conceptual reserve-coverage boundary; it does not supersede future policy choices for capital, over-collateralization, or production reserves.
- Persist explicit reconciliation outcomes: `RECONCILED`, `RESERVE_LEDGER_MISMATCH`, `CHAIN_SUPPLY_MISMATCH`, `EVIDENCE_INCOMPLETE`, and `UNSUPPORTED_OR_STALE_OBSERVATION`. A mismatch is evidence for review, never authority to mutate a balance.
- Do not orchestrate any withdrawal, posting, mint, custody transfer, payout, or burn in Phase 6A. Phase 6B owns workflow ordering and gating.

## Alternatives considered

### Derive internal truth from mock-bank balances and chain supply

Rejected. Those are separately observed external-system facts and cannot replace internal posting authority, idempotent consumption, or correction history.

### Add a generic configurable ledger or arbitrary posting API

Rejected. Phase 6A has a small closed present need. Runtime-configurable accounts/lines would expand authority, validation, and security surface without enabling either approved local flow more safely.

### Record a dollar journal for burn

Rejected. Redemption custody already reclassifies the dollar liability to redemption payable. Burn changes the custody and confirmed-supply positions; a second dollar entry would duplicate the economic transition.

### Require payout before burn, or burn before payout

Deferred to Phase 6B. The accounting primitives retain explicit states for either order after confirmed custody rather than embedding workflow policy in the ledger.

## Consequences

- The local POC gains a small auditable double-entry authority and explicit reserve/supply comparisons without claiming a complete general ledger or financial close.
- Bank mutation, accounting posting, and chain effect remain separate transactions and evidence identities. A successful bank or chain response does not automatically post accounting.
- The strict zero-equity equality exposes unsupported initial supply, missing evidence, and reserve/supply breaks instead of absorbing them through a plug.
- Reversals preserve journal history, but any compensating bank or chain effect remains a separate future authorized operation.
- Reconciliation derives its evidence set and complete/fresh classification from durable consumed bank and authoritative Phase 5 chain records. Callers cannot supply those classifications.
- The chain-side accounting table retains only a supply observation plus exact source identity; posting joins the authoritative operation, attempt, latest observation/event, and blockchain-finality rows. A shadow row without that provenance cannot create a posting.
- A correction is a one-time digest-bound reversing journal linked to its immutable original; neither entry is rewritten.
- Production accounting, real bank/reserve integration, capital policy, valuation, attestation, legal/customer/accounting finality, and orchestration remain outside this decision.

## Validation

- Pure tests cover every trusted posting, balanced/reversal construction, insufficient positions, both payout/burn orders, and every reconciliation outcome.
- Real PostgreSQL tests cover V8 from empty schema, row-lock concurrency, authoritative Phase 5 provenance, exact replay/conflict, one-time concurrent evidence consumption, durable linked reversal, caller-independent reconciliation, rollback, append-only enforcement, restart/read-back, and mismatch retention.
- Local-profile tests cover endpoint absence by default, distinct authorities, participant isolation, exact request shapes, redacted failures, OpenAPI conformance, and low-cardinality metrics.
- The Phase 6A plan records the final offline reactor, dependency-boundary, migration, document, security, and review evidence.
