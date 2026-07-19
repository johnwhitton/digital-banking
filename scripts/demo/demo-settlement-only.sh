#!/bin/sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/lib.sh"
demo_require_runtime
"$DEMO_SCRIPT_DIR/wait-ready.sh" >/dev/null

run_id=$(demo_read_secret "$DEMO_RUNTIME_DIR/run-id")
work=$DEMO_OUTPUT_DIR/settlement-$run_id
mkdir -p "$work"
chmod 700 "$work"
initial=$work/initial.json
final=$work/final.json
parent=$work/parent.json
demo_status_json > "$initial"
demo_assert_clean_initial_state "$initial"

request='{"amount":"100","currency":"USD","sourceBankAccountReference":"synthetic-bank:USER_1_BANK_ACCOUNT","destinationBankAccountReference":"synthetic-bank:USER_2_BANK_ACCOUNT","settlementNetwork":"ETHEREUM"}'
key=phase6d-settlement-$run_id
accepted=$(demo_curl POST "$DEMO_RUNTIME_DIR/sender-token" /v1/transfers "$request" "$key")
transfer_id=$(printf '%s' "$accepted" | jq -er '.transferId') \
    || demo_die "settlement acceptance response is malformed"
accepted=
demo_wait_parent "/v1/transfers/$transfer_id" "$parent" 600
demo_status_json > "$final"

demo_assert_status_value "$final" '.bankBalancesCents.USER_1_BANK_ACCOUNT' 0 'final sender bank cents'
demo_assert_status_value "$final" '.bankBalancesCents.USER_2_BANK_ACCOUNT' 10000 'final recipient bank cents'
for filter in \
    '.ledgerBalancesCents.RESERVE_CASH_ASSET' \
    '.ledgerBalancesCents.FIAT_RECEIVED_PENDING_MINT_LIABILITY' \
    '.ledgerBalancesCents.USDZELLE_CIRCULATING_LIABILITY' \
    '.ledgerBalancesCents.REDEMPTION_PAYABLE_LIABILITY' \
    '.operationalPositionsCents.ADMIN_REDEMPTION_CUSTODY_PENDING_BURN' \
    '.tokenBalancesAtomic.userOne' '.tokenBalancesAtomic.userTwo' \
    '.tokenBalancesAtomic.admin' '.tokenBalancesAtomic.totalSupply'; do
    demo_assert_status_value "$final" "$filter" 0 "final $filter"
done
for effect in withdrawals mints userTransfers custodyTransfers payouts burns; do
    demo_assert_status_value "$final" ".confirmedEffects.$effect" 1 "$effect count"
done
demo_assert_status_value "$final" '.latestSettlement.id' "$transfer_id" 'settlement identity'
demo_assert_status_value "$final" '.latestSettlement.status' COMPLETED 'settlement status'
demo_assert_status_value "$final" '.latestSettlement.reconciliation' RECONCILED 'settlement reconciliation'
demo_assert_status_value "$parent" '.settlementOrchestration.blockchainStatus' COMPLETED 'blockchain finality dimension'
demo_assert_status_value "$parent" '.settlementOrchestration.accountingStatus' COMPLETED 'accounting dimension'

replay=$(demo_curl POST "$DEMO_RUNTIME_DIR/sender-token" /v1/transfers "$request" "$key")
demo_assert_equal "$(printf '%s' "$replay" | jq -er '.transferId')" "$transfer_id" 'settlement replay identity'
replay=
demo_status_json > "$work/replay.json"
for effect in withdrawals mints userTransfers custodyTransfers payouts burns; do
    demo_assert_status_value "$work/replay.json" ".confirmedEffects.$effect" 1 "replayed $effect count"
done

jq -n --arg runId "$run_id" --arg transferId "$transfer_id" \
    '{demo:"settlement-only",runId:$runId,transferId:$transferId,effects:{withdrawal:1,mint:1,userTransfer:1,custodyTransfer:1,payout:1,burn:1},result:"RECONCILED",replay:"SAME_RESOURCE_NO_NEW_EFFECT"}' \
    > "$work/summary.json"
chmod 600 "$work"/*.json
printf 'Demo B passed: settlement %s, six exact confirmed effects, zeroed chain/accounting positions, RECONCILED. Redacted summary: %s\n' \
    "$transfer_id" "$work/summary.json"
