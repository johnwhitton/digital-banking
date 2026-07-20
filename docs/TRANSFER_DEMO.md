# USDZELLE Local Demonstration Contract

## Purpose, authority, and current status

This document is the authoritative detailed specification for two local-only, non-production USDZELLE demonstrations. It is subordinate to accepted [ADRs](adr/README.md), the canonical [design](DESIGN.md), and executable tests; [the implementation roadmap](IMPLEMENTATION.md) owns phase status and dependencies.

**Status:** `implemented locally`. Phase 3C verifies durable acceptance of one five-effect transfer parent and first-withdrawal preparation. Phases 4A-4B verify durable signing authority plus a session-ephemeral local signer. Phases 5A-5D verify separate local-Anvil mint, configured custody, user transfer, redemption custody, and ADMIN burn primitives. Phase 6A adds exact-USD synthetic bank and reserve/liability accounting primitives. Phase 6B verifies separate local acquisition and redemption parents for the configured participant, with server-owned custody, payout-before-burn, and reconciliation. Phase 6C verifies one registered settlement-only route by composing those authoritative children behind the existing transfer API. Phase 6D packages the two paths as reproducible API-driven local Ethereum demonstrations with exact assertions, restart recovery, and scoped teardown. Phase 7A verifies the native classic-SPL mint/transfer/redemption/burn semantics needed by both paths; Phases 7B-7D add exact durable Java local-Solana primitives. Phase 7E routes the same Phase 6B/6C parents through those Solana effects with immutable server-resolved authority, exact finalized evidence, replay/recovery, payout-before-burn, and reconciliation. Arbitrary institutional routing, packaged Solana demonstrations, and a public wallet-transfer API remain absent.

Zelle is a public case study only. `USDZELLE` is a reference asset name and does not assert that Early Warning Services issues a stablecoin, accepts deposits, owns reserves, operates wallets, shares reserve income, or selected this architecture.

## Common product, API, and authority boundary

[ADR 0008](adr/0008-usdzelle-product-paths-ownership-custody-reserve-boundaries.md) supports two product paths:

- **Demo A — user-held USDZELLE lifecycle:** a user can on-ramp, retain tokens, optionally transfer them, and redeem later. Receipt does not force redemption.
- **Demo B — settlement-only fiat transfer:** customers hold fiat before and after payment; institutional wallets use USDZELLE only inside the settlement saga.

Economic ownership and private-key custody are independent. Compatible models are self-custody, segregated custodial wallets, and omnibus custody with an internal beneficial-balance ledger. Demo B selects segregated local custodial identities: the internal Phase 5C primitive consumes two named `local-demo` user-wallet identities, but no balance product or public API exposes it. This is not self-custody and not production custody.

The default-profile business API remains:

```text
POST /v1/transfers
GET  /v1/transfers/{transferId}
```

It accepts opaque source/destination synthetic bank references, exact amount/currency, an optional allowlisted logical network, and a scoped idempotency key. The server resolves participant scope, asset/unit, route, and institution-controlled wallet context. HTTP 202 means only durable parent/effect/outbox acceptance.

Under `local-demo,local-ethereum`, Phase 6C recognizes only the versioned `USER_1` sender-acquisition instruction and the distinct `USER_2` recipient `AUTO_REDEEM` instruction. The caller still supplies no participant, wallet, ADMIN, signer, policy, step, child, evidence, state, or outcome field. The existing response may include minimized sender-acquisition, user-transfer, recipient-redemption, bank, blockchain, accounting, and reconciliation statuses; it does not expose recipient identity, wallet aliases, child identities, native evidence, or internal policy.

Under the combined `local-demo,local-ethereum` profiles, Phase 6B separately exposes:

- `POST /v1/usdzelle/acquisitions` and participant-scoped `GET /v1/usdzelle/acquisitions/{workflowId}`; and
- `POST /v1/usdzelle/redemptions` and participant-scoped `GET /v1/usdzelle/redemptions/{workflowId}`.

Each create request contains only exact amount/currency, the configured participant-owned synthetic bank reference, optional `ETHEREUM`, and the idempotency header. Distinct acquire/redeem/read authorities apply. The server resolves every wallet, ADMIN, asset/unit, contract, signer, policy, step, and child identity; no Boolean flags or unrestricted user-facing mint/burn authority is implied. HTTP 202 is durable workflow acceptance, not completion or financial settlement.

Separately, `local-demo` serves a local-only mock-bank OpenAPI and withdrawal, deposit, account-read, and operation-inquiry resources under `/local/v1/mock-banks`. They require distinct local authorities and execute only synthetic account effects. They are not public product commands and do not trigger reserve postings or chain operations.

Phase 6D's additional `local-demo-environment` profile exists only inside the documented Compose topology. Bootstrap generates distinct sender and operator bearer tokens in ignored mode-restricted files. The sender receives only the existing configured acquisition/redemption and transfer create/read authorities. The operator receives only the aggregate `GET /local/v1/demo/status` and separate `/local/v1/demo/openapi.yaml` resource; it cannot mutate workflow, bank, accounting, chain, fixture, or reset state. The projection is fixed to synthetic demo evidence and excludes keys, tokens, raw transactions, signatures, arbitrary participant lookup, unrestricted journals, and internal policy facts. See the [local Ethereum runbook](runbooks/LOCAL_ETHEREUM_DEMO.md).

## Demo B — settlement-only fiat transfer

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

Phase 3C's five-effect parent remains unchanged and does not itself implement this six-effect sequence. Phase 6C adds a V10 companion rather than relabeling the V3 effects. Its bounded local proof uses the already governed segregated custody aliases: sender acquisition mints to `USER_WALLET_1`, an exact Phase 5C transfer moves the full quantity to `USER_WALLET_2`, the registered recipient instruction immediately invokes the Phase 6B `AUTO_REDEEM` path to `ADMIN_REDEMPTION`, and final reconciliation closes the parent. This proves the settlement-only customer outcome—sender fiat decreases, recipient fiat increases, no recipient token retention—without claiming arbitrary bank-settlement-wallet routing.

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

### Remaining boundaries

Phase 6C provides durable parent/child correlation, ordered execution, ambiguity/manual-review projection, and final reconciliation for one hard-bounded local route. Phase 6D Demo B executes that route from a clean named local environment, asserts the six exact confirmed effects and zeroed intermediate positions, and verifies replay and restart behavior. It does not provide arbitrary registered-route administration, the broader institutional bank-settlement-wallet realization, automatic compensation/refund/reversal, or production operator commands. The synthetic proof is not real bank integration, an audited reserve, legal/accounting/customer finality, or production settlement.

## Demo A — user-held USDZELLE lifecycle

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

On-ramp, wallet transfer, and redemption are separate durable operations with their own idempotency, child effects, attempts, evidence, and failure states. Phase 6B implements the on-ramp and redemption parents for the configured user; Phase 5C remains a separate internal optional-transfer primitive. Mint and burn remain privileged child operations.

### Expected synthetic examples

| Point | Mock-bank / reserve state | Wallet and supply state |
| --- | --- | --- |
| Before on-ramp | User fiat `$100.00`; eligible reserve `0.00`; redeemable liability `0.00` | `USER_WALLET_1 = 0.00 USDZELLE`; supply `0.00` |
| After on-ramp | User fiat reduced/reserved by `$100.00`; confirmed eligible reserve and liability `100.00` | `USER_WALLET_1 = 100.00 USDZELLE`; supply `100.00` |
| After optional full transfer | Reserve and liability remain `100.00` | `USER_WALLET_1 = 0.00`; `USER_WALLET_2 = 100.00`; supply remains `100.00` |
| After later full redemption | User fiat credited `$100.00`; redeemable liability and encumbrance released to `0.00` after policy gates | User and redemption wallets `0.00`; supply `0.00` after ADMIN burn |

The user may stop after on-ramp or optional transfer and retain USDZELLE. The final redemption row is a later request, not an automatic consequence of receipt.

### Reserve, custody, and evidence contract

Phase 6A supplies the POC's closed four-account ledger, explicit custody/supply positions, one-time evidence consumption, and reconciliation results. For its zero-equity fixture it preserves:

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

Controlled inventory defaults to zero and is not a balancing plug. Confirmed withdrawal, mint, redemption custody, payout, and burn evidence remain independently authoritative posting inputs. Phase 6B supplies stable parent/child correlation and selects payout-before-burn: custody and custody accounting must complete before payout, while the one-time payout and payout accounting must complete before burn acceptance. Burn delay or ambiguity retains the explicit paid ADMIN-custody-pending-burn position and cannot trigger another payout.

Demo B uses segregated local custodial wallet aliases. The explicit `local-demo` profile injects deterministic keys from the ignored mode-`0600` `.env.local-anvil` through process variables, derives addresses, and fails when the required expected addresses disagree. `ADMIN_REDEMPTION` aliases ADMIN without a duplicate key. Keys are never committed, logged, returned by APIs, or persisted in PostgreSQL. Production profiles prohibit raw-key configuration and require future secret-manager/workload-identity plus HSM/MPC/custody implementations of the existing signer port.

Success evidence includes parent/child IDs for bank debit/reserve, mint, optional transfer, redemption receipt, payout, burn, and reserve release; exact quantity/unit; policy/approval versions; signer decisions; native identities/events; independent observations; reserve/supply comparisons; and four separate finality histories. A timeout remains ambiguous and is inquired by stable identity before retry. A payout, burn, or reserve mismatch enters explicit incomplete/manual-review state.

### Remaining boundaries

Phase 6D Demo A executes the local acquisition and redemption workflow for one configured participant, asserts the intermediate held-token/reserve/liability state, payout-before-burn, zeroed final positions, reconciliation, and exact replay. There is still no production user-held balance product, production identity-provider profile, self-custody path, reserve attestation/release workflow, public wallet-transfer API, or automatic compensation. The synthetic fixture cannot be described as a real reserve-backed product or production service.

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

The durable internal evidence includes parent/child operation IDs, bank-ledger entries, wallet aliases and derived local addresses, native transaction identities/signatures, confirmed contract events, reserve/supply reconciliation, and separate finality statuses. Phase 6C's participant response deliberately exposes only minimized dimension statuses. Phase 6D's local operator projection exposes only fixed aggregate positions, bounded counts, latest workflow identities/statuses, payout-before-burn ordering, local contract/network identity, and reconciliation; it redacts private keys, raw credentials, personal data, internal policy facts, native evidence bodies, and raw signed material.

## Ethereum-first and Solana-second realization

Ethereum completes orchestration phases first. Foundry/Anvil own native EVM execution and Web3j remains inside `adapters/ethereum-web3j/`. Phases 5A-6A supply bounded chain/custody/bank/accounting effects, Phase 6B composes the user-held workflow core, Phase 6C composes one settlement-only route, and Phase 6D makes both demonstrations operator-executable from a trusted clean checkout with the approved local prerequisites.

Phase 7A proves the shared local semantic substrate with pinned native Agave/SPL tooling: classic checked instructions, canonical associated accounts, exact 10,000-base-unit movement at two decimals, classified authority and valid-second-mint/account rejection, actual transaction signatures/recent blockhashes/slots, validity-window observations, finalized commitment, and zero end state.

Phase 7B then proves mint parity through the same provider-neutral operation and signing contracts. Sava 25.8.0 is isolated behind the adapter; the server selects distinct fee-payer and mint-authority roles; two ordered Ed25519 signatures bind one exact legacy message; and V11 retains cluster/program/account context, recent blockhash lifetime, stable transaction signature, submit fence, inquiry, and finalized exact supply/balance evidence. The response-loss/restart gate produces one native mint and advances only blockchain finality. Neither user-held nor settlement-only parent selects Solana yet.

Phase 7C proves wallet-transfer parity through the provider-neutral internal transfer boundary. The server selects USER_1 and USER_2 from immutable local configuration; callers cannot override wallets or signing authority. One classic-SPL `TransferChecked` moves exact `10000` atomic units through ordered fee-payer and USER_1 source-owner signatures. V12 retains source/destination account context plus source, destination, and unchanged-supply observations; response loss is resolved by inquiry after restart without a second submission. This primitive is not yet selected by either product parent and advances only blockchain finality.

Phase 7D proves redemption-custody and ADMIN-burn parity without composing either product parent. The existing exact burn command causes the server to resolve USER_1 and ADMIN, create or resume one internal classic-SPL `TransferChecked`, and wait for exact finalized transaction, account, indexed-balance, unchanged-supply, cluster, wallet-registry-version, policy, and blockchain-finality evidence. V13 consumes that custody evidence once for the stable burn identity. One classic-SPL `BurnChecked` then requires fee-payer plus ADMIN-owner signatures and equal exact ADMIN-balance and supply decrements. Response loss at either effect is inquired by its retained signature across restart; proven pre-submission expiry may create only a parented native replacement under the same burn identity without consuming custody again, and replay cannot create a second transfer or burn. Only blockchain finality advances.

Phase 7E composes the user-held and settlement-only parents from the separately verified native primitives without creating parallel workflows. `local-demo,local-solana` retains the existing public request boundary while the server selects the registered route, user/ADMIN wallet identities, fee payer, mint/transfer/burn authorities, programs, cluster, and policy versions. V14 gives each worker exclusive chain ownership, binds accounting to exact finalized Solana observations, and retains one immutable workflow/step/effect/child evidence identity so replay cannot adopt a later observation. The consolidated PostgreSQL/Agave gate completes acquisition/replay/redemption and settlement-only `AUTO_REDEEM`, including payout-before-burn, restart recovery, reconciliation, and zero final supply/balances. Phase 7F may package both full Solana demonstrations. It must preserve native accounts, owners, instructions, signatures, slots, commitment, expiry, and SPL Token evidence rather than relabeling Ethereum receipts. ADRs 0003 and 0010 retain classic SPL Token first, no Neon baseline, exact atomic conversion outside the CLI decimal parser, and Rust/Anchor only if existing programs cannot express required behavior.
