# USDZELLE Local Demonstration Contract

## Purpose, authority, and current status

This document is the authoritative detailed specification for two local-only, non-production USDZELLE demonstrations. It is subordinate to accepted [ADRs](adr/README.md), the canonical [design](DESIGN.md), and executable tests; [the implementation roadmap](IMPLEMENTATION.md) owns phase status and dependencies.

**Status:** `partially implemented`. Phase 3C verifies durable acceptance of one five-effect transfer parent and first-withdrawal preparation. Phases 4A-4B verify durable signing authority plus a session-ephemeral local signer. Phase 5A verifies one separately accepted local-Anvil mint to one configured recipient with durable submission and observation evidence. Phase 5B adds the named, deterministic local owner/admin, bank-settlement, and segregated user-custody identities required by both paths. No bank effect, wallet transfer, redemption, burn execution, reserve record, complete parent saga, Solana adapter, or demonstration below is executable end to end.

Zelle is a public case study only. `USDZELLE` is a reference asset name and does not assert that Early Warning Services issues a stablecoin, accepts deposits, owns reserves, operates wallets, shares reserve income, or selected this architecture.

## Common product, API, and authority boundary

[ADR 0008](adr/0008-usdzelle-product-paths-ownership-custody-reserve-boundaries.md) supports two product paths:

- **Demo A — settlement-only fiat transfer:** customers hold fiat before and after payment; institutional wallets use USDZELLE only inside the settlement saga.
- **Demo B — user-held USDZELLE lifecycle:** a user can on-ramp, retain tokens, optionally transfer them, and redeem later. Receipt does not force redemption.

Economic ownership and private-key custody are independent. Compatible models are self-custody, segregated custodial wallets, and omnibus custody with an internal beneficial-balance ledger. Demo B selects segregated local custodial identities: the implemented `local-demo` signer can resolve named user-wallet identities, but no transfer or balance product consumes them yet. This is not self-custody and not production custody.

The currently implemented business API remains:

```text
POST /v1/transfers
GET  /v1/transfers/{transferId}
```

It accepts opaque source/destination synthetic bank references, exact amount/currency, an optional allowlisted logical network, and a scoped idempotency key. The server resolves participant scope, asset/unit, route, and institution-controlled wallet context. HTTP 202 means only durable parent/effect/outbox acceptance. The future on-ramp, wallet-transfer, redemption, and settlement-only commands remain distinct API-design decisions; no Boolean flags or unrestricted user-facing mint are implied.

## Demo A — settlement-only fiat transfer

### Customer outcome and exact target flow

User 1 sends `$100.00`; User 2 receives `$100.00`. Neither customer needs a blockchain wallet or blockchain signature. `ADMIN`, `BANK_1_SETTLEMENT`, `BANK_2_SETTLEMENT`, and `ADMIN_REDEMPTION` are institution- or custody-controlled signing roles.

```text
1. Mock Bank 1 debits User 1's bank account by $100.00
2. ADMIN mints 100.00 USDZELLE to BANK_1_SETTLEMENT
3. BANK_1_SETTLEMENT transfers 100.00 USDZELLE to BANK_2_SETTLEMENT
4. BANK_2_SETTLEMENT transfers 100.00 USDZELLE to ADMIN_REDEMPTION
5. Mock Bank 2 credits User 2's bank account by $100.00
6. ADMIN burns the redeemed 100.00 USDZELLE
```

The current Phase 3C five-effect parent does not already implement this future six-effect sequence. Phase 6C must add an explicit redemption-transfer child and ADMIN burn rather than hiding both behind the existing burn effect.

### Expected synthetic before/after example

| Record | Before | After successful Demo A |
| --- | ---: | ---: |
| User 1 mock-bank balance | `$100.00` | `$0.00` |
| User 2 mock-bank balance | `$0.00` | `$100.00` |
| `BANK_1_SETTLEMENT` | `0.00 USDZELLE` | `0.00 USDZELLE` |
| `BANK_2_SETTLEMENT` | `0.00 USDZELLE` | `0.00 USDZELLE` |
| `ADMIN_REDEMPTION` | `0.00 USDZELLE` | `0.00 USDZELLE` |
| USDZELLE total supply | `0.00 USDZELLE` | `0.00 USDZELLE` |

These are synthetic demonstration assertions, not real money movement or accounting statements.

### Evidence, failure, and replay contract

| Step | Required durable success evidence | Failure or ambiguity posture |
| --- | --- | --- |
| Bank 1 debit | Parent/effect/attempt IDs, exact amount, account-reference digest, idempotency identity, mock journal entry, independent inquiry result | Definitive rejection stops before mint; timeout is inquired by the same effect ID, never re-debited blindly |
| ADMIN mint | Child operation/attempt IDs, exact quantity, signer decision, nonce/native identity, successful receipt, exact mint event, canonical block/finality evidence | Rejection/no-effect may permit a policy-authorized new attempt; ambiguity uses the original transaction identity and blocks progression |
| Bank 1 to Bank 2 transfer | Source/destination wallet aliases, exact transfer intent, signer/native identities, receipt/event/canonicality evidence | Insufficient balance, revert, response loss, or observation disagreement holds the saga for inquiry/manual review |
| Bank 2 to ADMIN redemption transfer | Exact source/redemption destination, signer/native identities, independently confirmed receipt/event | Bank credit cannot begin until the configured receipt/finality gate passes |
| Bank 2 credit | Exact amount, account-reference digest, effect/attempt/idempotency identity, mock journal and inquiry evidence | Timeout remains ambiguous; compensation is a new authorized effect, never mutation of the original |
| ADMIN burn | Exact ADMIN-held quantity, burn authority decision, native identity, burn/supply event, canonicality and final supply reconciliation | No arbitrary burn from another wallet; unresolved burn blocks completion and creates manual review/recovery evidence |

An exact duplicate command or delivery returns the original durable parent/effect result. A changed payload under the same scope/key conflicts. The final assertion requires correlated bank balances, zero intermediate wallet balances, and zero net token supply for this `$100.00` example; it does not imply legal or accounting settlement.

### Current gaps

Runtime mock-bank debit/credit/inquiry, ERC-20 transfer, redemption receipt, ADMIN burn, six-effect orchestration, compensation execution, supply reconciliation, and a runnable environment are all planned. Phase 5B supplies the named settlement identities only; Phase 5A proves only the separate mint primitive.

## Demo B — user-held USDZELLE lifecycle

### Exact target lifecycle

```text
On-ramp
1. User requests conversion of $100.00 to USDZELLE
2. Mock bank debits or reserves $100.00
3. Synthetic reserve ledger confirms eligible backing
4. ADMIN authorizes and signs the mint
5. 100.00 USDZELLE is minted to USER_WALLET_1
6. User may retain the 100.00 USDZELLE balance

Optional wallet transfer
7. USER_WALLET_1 transfers an exact amount to USER_WALLET_2
8. USER_WALLET_2 may retain that balance; no redemption is forced

Redemption/off-ramp
9. User requests redemption
10. The user wallet transfers USDZELLE to ADMIN_REDEMPTION
11. Independent observation confirms receipt and required blockchain finality
12. Mock bank credits the user's bank account
13. ADMIN burns the redeemed USDZELLE
14. Reserve and token-supply records reconcile
```

On-ramp, wallet transfer, and redemption are separate durable parents with their own idempotency, child effects, attempts, evidence, and failure states. Mint and burn remain privileged child operations.

### Expected synthetic examples

| Point | Mock-bank / reserve state | Wallet and supply state |
| --- | --- | --- |
| Before on-ramp | User fiat `$100.00`; eligible reserve `0.00`; redeemable liability `0.00` | `USER_WALLET_1 = 0.00 USDZELLE`; supply `0.00` |
| After on-ramp | User fiat reduced/reserved by `$100.00`; confirmed eligible reserve and liability `100.00` | `USER_WALLET_1 = 100.00 USDZELLE`; supply `100.00` |
| After optional full transfer | Reserve and liability remain `100.00` | `USER_WALLET_1 = 0.00`; `USER_WALLET_2 = 100.00`; supply remains `100.00` |
| After later full redemption | User fiat credited `$100.00`; redeemable liability and encumbrance released to `0.00` after policy gates | User and redemption wallets `0.00`; supply `0.00` after ADMIN burn |

The user may stop after on-ramp or optional transfer and retain USDZELLE. The final redemption row is a later request, not an automatic consequence of receipt.

### Reserve, custody, and evidence contract

The synthetic reserve subsystem records confirmed deposit evidence, eligible/available/encumbered amounts, outstanding redeemable token liability, mint authorization evidence, redemption liability, payout evidence, reserve-release policy, total-supply observation, and reconciliation. It must preserve:

```text
confirmed eligible reserves >= outstanding redeemable USDZELLE supply
```

Demo B uses segregated local custodial wallet aliases. The explicit `local-demo` profile injects deterministic keys from the ignored mode-`0600` `.env.local-anvil` through process variables, derives addresses, and fails when the required expected addresses disagree. `ADMIN_REDEMPTION` aliases ADMIN without a duplicate key. Keys are never committed, logged, returned by APIs, or persisted in PostgreSQL. Production profiles prohibit raw-key configuration and require future secret-manager/workload-identity plus HSM/MPC/custody implementations of the existing signer port.

Success evidence includes parent/child IDs for bank debit/reserve, mint, optional transfer, redemption receipt, payout, burn, and reserve release; exact quantity/unit; policy/approval versions; signer decisions; native identities/events; independent observations; reserve/supply comparisons; and four separate finality histories. A timeout remains ambiguous and is inquired by stable identity before retry. A payout, burn, or reserve mismatch enters explicit incomplete/manual-review state.

### Current gaps

No user-held balance product, synthetic reserve ledger, runtime bank effect, generic transfer, redemption workflow, ADMIN burn, reserve/supply reconciliation, identity-provider profile, or public API for these operations exists. The named local registry and deterministic signer are custody prerequisites only; neither Phase 5A nor Phase 5B can be described as a reserve-backed on-ramp.

## Shared saga, evidence output, and finality rules

Neither demo is globally atomic. Individual PostgreSQL and blockchain transactions are atomic only within their own systems. Each parent saga preserves:

- idempotent commands and delivery identities;
- narrow local transactions with external calls outside them;
- stable parent, child, effect, and attempt identities;
- independently observed bank and chain effects;
- inquiry before retry after ambiguity;
- append-only policy, approval, transition, observation, and reconciliation evidence;
- explicit compensation or manual review rather than destructive rollback; and
- separate blockchain, accounting, legal, and customer-visible finality.

The eventual local evidence summary conceptually includes parent/child operation IDs, bank-ledger entries, wallet aliases and derived local addresses, native transaction hashes/signatures, confirmed contract/program events or instructions, reserve/supply reconciliation, and all four finality statuses. It must redact private keys, raw credentials, personal data, internal policy facts, and raw signed material.

## Ethereum-first and Solana-second realization

Ethereum completes the missing transfer, redemption/burn, bank/reserve, and orchestration phases first. Foundry/Anvil own native EVM execution and Web3j remains inside `adapters/ethereum-web3j/`. Phase 5A currently supports mint only; Phase 5B supplies local wallet custody without enabling chain execution.

Solana follows through the same provider-neutral business contracts after the Ethereum demonstrations. Its adapter must preserve native fee payer/authority, accounts, instructions, recent blockhash or durable nonce, signature, slot, commitment, expiry, and SPL Token evidence. ADR 0003 requires a bounded Java-client gate, classic SPL Token first, no Neon baseline, and Rust/Anchor only if existing programs cannot express required behavior.

The detailed dependency sequence and exit gates are in [`docs/IMPLEMENTATION.md`](IMPLEMENTATION.md). No future implementation plan exists until its phase is separately authorized.
