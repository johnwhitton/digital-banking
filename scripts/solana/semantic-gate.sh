#!/bin/sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/lib.sh"

[ "${1-}" = --yes ] || solana_die "semantic gate resets local Solana state; rerun exactly with semantic-gate.sh --yes"
umask 077

gate_passed=false
cleanup() {
    original_status=$?
    trap - 0
    cleanup_failed=false
    if ! (solana_require_safe_path "$SOLANA_RUNTIME_DIR"); then
        cleanup_failed=true
    elif (solana_validator_pid_alive); then
        if ! (solana_stop_validator); then
            cleanup_failed=true
            printf '%s\n' "Phase 7A Solana: validator cleanup failed; ownership metadata was preserved for inspection." >&2
        fi
    fi
    if [ "$cleanup_failed" = false ]; then
        for metadata_file in "$SOLANA_PID_FILE" "$SOLANA_IDENTITY_FILE" "$SOLANA_STARTING_FILE"; do
            solana_require_safe_path "$metadata_file"
        done
        rm -f -- "$SOLANA_PID_FILE" "$SOLANA_IDENTITY_FILE" "$SOLANA_STARTING_FILE"
    fi
    if [ "$gate_passed" = true ] && [ "$cleanup_failed" = false ]; then
        solana_write_state completed
    else
        solana_write_state failed
        [ "$original_status" -ne 0 ] || original_status=1
    fi
    exit "$original_status"
}
trap cleanup 0
trap 'exit 1' 1 2 15

expect_failure() {
    check_name=$1
    expected_pattern=$2
    shift 2
    failure_output=$SOLANA_RUNTIME_DIR/evidence/.$check_name.tmp
    solana_require_safe_path "$failure_output"
    rm -f -- "$failure_output"
    if "$@" >"$failure_output" 2>&1; then
        rm -f -- "$failure_output"
        solana_die "negative check unexpectedly succeeded: $check_name"
    fi
    if ! grep -E "$expected_pattern" "$failure_output" >/dev/null 2>&1; then
        solana_die "negative check failed with an unexpected classification: $check_name"
    fi
    rm -f -- "$failure_output"
}

rpc_value() {
    method=$1
    params=$2
    response=$(solana_rpc_request "$method" "$params")
    if printf '%s\n' "$response" | jq -e '.error != null' >/dev/null; then
        solana_die "RPC method failed: $method"
    fi
    printf '%s\n' "$response" | jq -c '.result'
}

token_amount() {
    account=$1
    method=$2
    params=$(jq -cn --arg account "$account" '[ $account, {commitment:"finalized"} ]')
    rpc_value "$method" "$params" | jq -er '.value.amount'
}

assert_amounts() {
    expected_supply=$1
    expected_user1=$2
    expected_user2=$3
    expected_redemption=$4
    [ "$(token_amount "$mint_address" getTokenSupply)" = "$expected_supply" ] \
        || solana_die "unexpected mint supply"
    [ "$(token_amount "$user1_ata" getTokenAccountBalance)" = "$expected_user1" ] \
        || solana_die "unexpected USER_1 balance"
    [ "$(token_amount "$user2_ata" getTokenAccountBalance)" = "$expected_user2" ] \
        || solana_die "unexpected USER_2 balance"
    [ "$(token_amount "$redemption_ata" getTokenAccountBalance)" = "$expected_redemption" ] \
        || solana_die "unexpected ADMIN redemption balance"
}

assert_wrong_mint_zero() {
    [ "$(token_amount "$wrong_mint_address" getTokenSupply)" = 0 ] \
        || solana_die "negative-fixture mint supply changed"
}

capture_blockhash() {
    latest=$(rpc_value getLatestBlockhash '[{"commitment":"finalized"}]')
    current_blockhash=$(printf '%s\n' "$latest" | jq -er '.value.blockhash')
    current_last_valid_block_height=$(printf '%s\n' "$latest" | jq -er '.value.lastValidBlockHeight')
}

wait_finalized() {
    signature=$1
    attempts=0
    while :; do
        params=$(jq -cn --arg signature "$signature" '[[ $signature ], {searchTransactionHistory:true}]')
        status=$(rpc_value getSignatureStatuses "$params" | jq -c '.value[0]')
        if [ "$status" != null ] && [ "$(printf '%s\n' "$status" | jq -r '.err')" != null ]; then
            solana_die "transaction failed: $signature"
        fi
        if [ "$status" != null ] \
            && [ "$(printf '%s\n' "$status" | jq -r '.confirmationStatus')" = finalized ]; then
            return 0
        fi
        attempts=$((attempts + 1))
        [ "$attempts" -lt 90 ] || solana_die "transaction did not finalize: $signature"
        sleep 1
    done
}

transaction_evidence() {
    signature=$1
    pre_submission_blockhash=$2
    expected_instruction=$3
    pre_submission_last_valid_block_height=$4
    params=$(jq -cn --arg signature "$signature" \
        '[ $signature, {commitment:"finalized",encoding:"jsonParsed",maxSupportedTransactionVersion:0} ]')
    attempts=0
    while :; do
        transaction=$(rpc_value getTransaction "$params")
        [ "$transaction" != null ] && break
        attempts=$((attempts + 1))
        [ "$attempts" -lt 30 ] || solana_die "finalized transaction evidence is unavailable: $signature"
        sleep 1
    done
    actual_signature=$(printf '%s\n' "$transaction" | jq -er '.transaction.signatures[0]')
    actual_blockhash=$(printf '%s\n' "$transaction" | jq -er '.transaction.message.recentBlockhash')
    instructions=$(printf '%s\n' "$transaction" | jq -c \
        --arg program "$SOLANA_TOKEN_PROGRAM" \
        '[.transaction.message.instructions[] | select(.programId == $program) | .parsed.type]')
    [ "$actual_signature" = "$signature" ] || solana_die "native signature identity mismatch"
    [ "$instructions" = "[\"$expected_instruction\"]" ] \
        || solana_die "expected exactly one $expected_instruction instruction (found $instructions)"
    atomic_amount=$(printf '%s\n' "$transaction" | jq -er \
        --arg program "$SOLANA_TOKEN_PROGRAM" \
        '.transaction.message.instructions[] | select(.programId == $program) | .parsed.info.tokenAmount.amount')
    decimals=$(printf '%s\n' "$transaction" | jq -er \
        --arg program "$SOLANA_TOKEN_PROGRAM" \
        '.transaction.message.instructions[] | select(.programId == $program) | .parsed.info.tokenAmount.decimals')
    [ "$atomic_amount" = 10000 ] || solana_die "transaction quantity is not 10000 base units"
    [ "$decimals" = 2 ] || solana_die "transaction decimals are not 2"
    slot=$(printf '%s\n' "$transaction" | jq -er '.slot')
    # Online spl-token resolves its own fresh blockhash. Persist the transaction's actual
    # identity and validity separately from the pre-submission RPC lifetime observation.
    validity_params=$(jq -cn --arg blockhash "$actual_blockhash" \
        '[ $blockhash, {commitment:"finalized"} ]')
    blockhash_valid=$(rpc_value isBlockhashValid "$validity_params" | jq -er '.value')
    [ "$blockhash_valid" = true ] || solana_die "transaction recent blockhash is not valid at observation"
    observed_block_height=$(rpc_value getBlockHeight '[{"commitment":"finalized"}]')
    jq -cn \
        --arg signature "$signature" \
        --arg transactionRecentBlockhash "$actual_blockhash" \
        --arg preSubmissionLatestBlockhash "$pre_submission_blockhash" \
        --arg instruction "$expected_instruction" \
        --argjson preSubmissionLastValidBlockHeight "$pre_submission_last_valid_block_height" \
        --argjson observedBlockHeight "$observed_block_height" \
        --argjson slot "$slot" \
        '{signature:$signature,transactionRecentBlockhash:$transactionRecentBlockhash,recentBlockhashValidAtObservation:true,observedBlockHeight:$observedBlockHeight,preSubmissionLatestBlockhash:$preSubmissionLatestBlockhash,preSubmissionLastValidBlockHeight:$preSubmissionLastValidBlockHeight,slot:$slot,commitment:"finalized",instruction:$instruction,atomicAmount:"10000",decimals:2}'
}

account_info() {
    address=$1
    params=$(jq -cn --arg address "$address" '[ $address, {commitment:"finalized",encoding:"jsonParsed"} ]')
    rpc_value getAccountInfo "$params" | jq -er '.value'
}

"$SOLANA_SCRIPT_DIR/reset.sh" --yes
"$SOLANA_SCRIPT_DIR/bootstrap.sh" --provision-only
solana_start_validator
solana_wait_ready
solana_record_identity
solana_write_state healthy

for directory in "$SOLANA_RUNTIME_DIR" "$SOLANA_RUNTIME_DIR/keys" "$SOLANA_RUNTIME_DIR/evidence"; do
    solana_require_mode "$directory" 700
done
for role in fee-payer admin-mint-authority admin-redemption-owner user-1 user-2 usdzelle-mint negative-wrong-mint; do
    solana_require_mode "$(solana_key_path "$role")" 600
done
solana_require_mode "$SOLANA_RUNTIME_DIR/public-addresses.json" 600
for runtime_file in "$SOLANA_PID_FILE" "$SOLANA_IDENTITY_FILE" \
    "$SOLANA_RUNTIME_DIR/validator.log" "$SOLANA_RUNTIME_DIR/state"; do
    solana_require_mode "$runtime_file" 600
done

fee_key=$(solana_key_path fee-payer)
mint_authority_key=$(solana_key_path admin-mint-authority)
redemption_key=$(solana_key_path admin-redemption-owner)
user1_key=$(solana_key_path user-1)
user2_key=$(solana_key_path user-2)
mint_key=$(solana_key_path usdzelle-mint)
wrong_mint_key=$(solana_key_path negative-wrong-mint)
fee_address=$(solana_address fee-payer)
mint_authority_address=$(solana_address admin-mint-authority)
redemption_address=$(solana_address admin-redemption-owner)
user1_address=$(solana_address user-1)
user2_address=$(solana_address user-2)
mint_address=$(solana_address usdzelle-mint)
wrong_mint_address=$(solana_address negative-wrong-mint)

"$SOLANA_CLI" --url "$SOLANA_RPC_URL" --output json \
    airdrop 10 "$fee_address" --keypair "$fee_key" >/dev/null

mint_creation=$("$SOLANA_SPL_TOKEN" --url "$SOLANA_RPC_URL" \
    --program-id "$SOLANA_TOKEN_PROGRAM" --fee-payer "$fee_key" --output json \
    create-token --decimals 2 --mint-authority "$mint_authority_address" "$mint_key")
[ "$(printf '%s\n' "$mint_creation" | jq -er '.commandOutput.address')" = "$mint_address" ] \
    || solana_die "mint address mismatch"
[ "$(printf '%s\n' "$mint_creation" | jq -er '.commandOutput.decimals')" = 2 ] \
    || solana_die "mint decimals mismatch"
wait_finalized "$(printf '%s\n' "$mint_creation" | jq -er '.commandOutput.transactionData.signature')"

wrong_mint_creation=$("$SOLANA_SPL_TOKEN" --url "$SOLANA_RPC_URL" \
    --program-id "$SOLANA_TOKEN_PROGRAM" --fee-payer "$fee_key" --output json \
    create-token --decimals 2 --mint-authority "$mint_authority_address" "$wrong_mint_key")
[ "$(printf '%s\n' "$wrong_mint_creation" | jq -er '.commandOutput.address')" = "$wrong_mint_address" ] \
    || solana_die "negative-fixture mint address mismatch"
wait_finalized "$(printf '%s\n' "$wrong_mint_creation" | jq -er '.commandOutput.transactionData.signature')"

for owner in "$redemption_address" "$user1_address" "$user2_address"; do
    account_creation=$("$SOLANA_SPL_TOKEN" --url "$SOLANA_RPC_URL" --program-id "$SOLANA_TOKEN_PROGRAM" \
        --fee-payer "$fee_key" --output json create-account --owner "$owner" "$mint_address")
    wait_finalized "$(printf '%s\n' "$account_creation" | jq -er '.signature')"
done

derive_ata() {
    owner=$1
    "$SOLANA_SPL_TOKEN" --url "$SOLANA_RPC_URL" --program-id "$SOLANA_TOKEN_PROGRAM" \
        address --verbose --output json-compact --token "$mint_address" --owner "$owner" \
        | jq -er '.associatedTokenAddress'
}
redemption_ata=$(derive_ata "$redemption_address")
user1_ata=$(derive_ata "$user1_address")
user2_ata=$(derive_ata "$user2_address")
[ "$(derive_ata "$redemption_address")" = "$redemption_ata" ] || solana_die "redemption ATA derivation changed"
[ "$(derive_ata "$user1_address")" = "$user1_ata" ] || solana_die "USER_1 ATA derivation changed"
[ "$(derive_ata "$user2_address")" = "$user2_ata" ] || solana_die "USER_2 ATA derivation changed"

mint_info=$(account_info "$mint_address")
[ "$(printf '%s\n' "$mint_info" | jq -er '.owner')" = "$SOLANA_TOKEN_PROGRAM" ] \
    || solana_die "mint is not owned by the original Token Program"
[ "$(printf '%s\n' "$mint_info" | jq -er '.data.parsed.info.decimals')" = 2 ] \
    || solana_die "mint decimals are not 2"
[ "$(printf '%s\n' "$mint_info" | jq -er '.data.parsed.info.mintAuthority')" = "$mint_authority_address" ] \
    || solana_die "mint authority mismatch"
[ "$(printf '%s\n' "$mint_info" | jq -r '.data.parsed.info.freezeAuthority')" = null ] \
    || solana_die "freeze authority must be absent"
wrong_mint_info=$(account_info "$wrong_mint_address")
[ "$(printf '%s\n' "$wrong_mint_info" | jq -er '.owner')" = "$SOLANA_TOKEN_PROGRAM" ] \
    || solana_die "negative-fixture mint is not owned by the original Token Program"
[ "$(printf '%s\n' "$wrong_mint_info" | jq -er '.data.parsed.info.decimals')" = 2 ] \
    || solana_die "negative-fixture mint decimals are not 2"

for tuple in "$redemption_ata:$redemption_address" "$user1_ata:$user1_address" "$user2_ata:$user2_address"; do
    ata=${tuple%%:*}
    owner=${tuple#*:}
    info=$(account_info "$ata")
    [ "$(printf '%s\n' "$info" | jq -er '.owner')" = "$SOLANA_TOKEN_PROGRAM" ] \
        || solana_die "ATA is not owned by the original Token Program"
    [ "$(printf '%s\n' "$info" | jq -er '.data.parsed.info.mint')" = "$mint_address" ] \
        || solana_die "ATA mint mismatch"
    [ "$(printf '%s\n' "$info" | jq -er '.data.parsed.info.owner')" = "$owner" ] \
        || solana_die "ATA wallet owner mismatch"
done

[ "$(account_info "$SOLANA_TOKEN_PROGRAM" | jq -er '.executable')" = true ] \
    || solana_die "original Token Program is not executable"
[ "$(account_info "$SOLANA_ATA_PROGRAM" | jq -er '.executable')" = true ] \
    || solana_die "Associated Token Account Program is not executable"
assert_amounts 0 0 0 0
assert_wrong_mint_zero

capture_blockhash
mint_blockhash=$current_blockhash
mint_last_valid=$current_last_valid_block_height
mint_output=$("$SOLANA_SPL_TOKEN" --url "$SOLANA_RPC_URL" --program-id "$SOLANA_TOKEN_PROGRAM" \
    --fee-payer "$fee_key" --output json mint \
    --mint-authority "$mint_authority_key" "$mint_address" 100 "$user1_ata")
mint_signature=$(printf '%s\n' "$mint_output" | jq -er '.signature')
wait_finalized "$mint_signature"
mint_transaction=$(transaction_evidence "$mint_signature" "$mint_blockhash" mintToChecked "$mint_last_valid")
assert_amounts 10000 10000 0 0

expect_failure unauthorized_mint 'owner does not match|custom program error: 0x4|Custom\(4\)' \
    "$SOLANA_SPL_TOKEN" --url "$SOLANA_RPC_URL" --program-id "$SOLANA_TOKEN_PROGRAM" \
    --fee-payer "$fee_key" --output json mint \
    --mint-authority "$user2_key" "$mint_address" 1 "$user2_ata"
expect_failure wrong_mint 'AccountInvalidMint|Source .* does not contain .* tokens' \
    "$SOLANA_SPL_TOKEN" --url "$SOLANA_RPC_URL" --program-id "$SOLANA_TOKEN_PROGRAM" \
    --fee-payer "$fee_key" --output json transfer --owner "$user1_key" --from "$user1_ata" \
    "$wrong_mint_address" 1 "$user2_ata"
expect_failure unauthorized_transfer 'owner does not match|custom program error: 0x4|Custom\(4\)' \
    "$SOLANA_SPL_TOKEN" --url "$SOLANA_RPC_URL" --program-id "$SOLANA_TOKEN_PROGRAM" \
    --fee-payer "$fee_key" --output json transfer \
    --owner "$user2_key" --from "$user1_ata" "$mint_address" 1 "$user2_ata"
expect_failure unauthorized_burn 'owner does not match|custom program error: 0x4|Custom\(4\)' \
    "$SOLANA_SPL_TOKEN" --url "$SOLANA_RPC_URL" --program-id "$SOLANA_TOKEN_PROGRAM" \
    --fee-payer "$fee_key" --output json burn \
    --owner "$redemption_key" "$user1_ata" 1
assert_amounts 10000 10000 0 0
assert_wrong_mint_zero

capture_blockhash
transfer_user2_blockhash=$current_blockhash
transfer_user2_last_valid=$current_last_valid_block_height
transfer_user2_output=$("$SOLANA_SPL_TOKEN" --url "$SOLANA_RPC_URL" --program-id "$SOLANA_TOKEN_PROGRAM" \
    --fee-payer "$fee_key" --output json transfer \
    --owner "$user1_key" --from "$user1_ata" "$mint_address" 100 "$user2_ata")
transfer_user2_signature=$(printf '%s\n' "$transfer_user2_output" | jq -er '.signature')
wait_finalized "$transfer_user2_signature"
transfer_user2_transaction=$(transaction_evidence "$transfer_user2_signature" "$transfer_user2_blockhash" transferChecked "$transfer_user2_last_valid")
assert_amounts 10000 0 10000 0

capture_blockhash
transfer_redemption_blockhash=$current_blockhash
transfer_redemption_last_valid=$current_last_valid_block_height
transfer_redemption_output=$("$SOLANA_SPL_TOKEN" --url "$SOLANA_RPC_URL" --program-id "$SOLANA_TOKEN_PROGRAM" \
    --fee-payer "$fee_key" --output json transfer \
    --owner "$user2_key" --from "$user2_ata" "$mint_address" 100 "$redemption_ata")
transfer_redemption_signature=$(printf '%s\n' "$transfer_redemption_output" | jq -er '.signature')
wait_finalized "$transfer_redemption_signature"
transfer_redemption_transaction=$(transaction_evidence "$transfer_redemption_signature" "$transfer_redemption_blockhash" transferChecked "$transfer_redemption_last_valid")
assert_amounts 10000 0 0 10000

capture_blockhash
burn_blockhash=$current_blockhash
burn_last_valid=$current_last_valid_block_height
burn_output=$("$SOLANA_SPL_TOKEN" --url "$SOLANA_RPC_URL" --program-id "$SOLANA_TOKEN_PROGRAM" \
    --fee-payer "$fee_key" --output json burn \
    --owner "$redemption_key" "$redemption_ata" 100)
burn_signature=$(printf '%s\n' "$burn_output" | jq -er '.signature')
wait_finalized "$burn_signature"
burn_transaction=$(transaction_evidence "$burn_signature" "$burn_blockhash" burnChecked "$burn_last_valid")
assert_amounts 0 0 0 0

genesis_hash=$("$SOLANA_CLI" --url "$SOLANA_RPC_URL" genesis-hash)
summary_tmp=$SOLANA_RUNTIME_DIR/evidence/summary.json.tmp
solana_require_safe_path "$summary_tmp"
solana_require_safe_path "$SOLANA_RUNTIME_DIR/evidence/summary.json"
rm -f -- "$summary_tmp"
jq -n \
    --arg genesisHash "$genesis_hash" \
    --arg mint "$mint_address" \
    --arg wrongMint "$wrong_mint_address" \
    --arg mintAuthority "$mint_authority_address" \
    --arg redemptionOwner "$redemption_address" \
    --arg user1 "$user1_address" \
    --arg user2 "$user2_address" \
    --arg redemptionAta "$redemption_ata" \
    --arg user1Ata "$user1_ata" \
    --arg user2Ata "$user2_ata" \
    --argjson mintTransaction "$mint_transaction" \
    --argjson transferUser2Transaction "$transfer_user2_transaction" \
    --argjson transferRedemptionTransaction "$transfer_redemption_transaction" \
    --argjson burnTransaction "$burn_transaction" \
    --arg tokenProgram "$SOLANA_TOKEN_PROGRAM" \
    --arg ataProgram "$SOLANA_ATA_PROGRAM" \
    '{schemaVersion:"phase7a-semantic-gate/v1",result:"passed",network:{kind:"local-test-validator",rpc:"http://127.0.0.1:8899",genesisHash:$genesisHash,commitment:"finalized"},toolchain:{agave:"4.1.2",splTokenCli:"5.6.1"},programs:{token:$tokenProgram,associatedTokenAccount:$ataProgram},asset:{symbol:"USDZELLE",mint:$mint,decimals:2,atomicQuantity:"10000",freezeAuthority:null},negativeFixtures:{wrongMint:$wrongMint,wrongMintFinalSupply:"0"},wallets:{mintAuthority:$mintAuthority,redemptionOwner:$redemptionOwner,user1:$user1,user2:$user2},associatedTokenAccounts:{redemption:$redemptionAta,user1:$user1Ata,user2:$user2Ata},negativeChecks:[{check:"unauthorized_mint",outcome:"rejected",classification:"mint_authority_mismatch"},{check:"wrong_mint",outcome:"rejected",classification:"valid_mint_account_mismatch"},{check:"unauthorized_transfer",outcome:"rejected",classification:"source_owner_mismatch"},{check:"unauthorized_burn",outcome:"rejected",classification:"burn_owner_mismatch"}],transactions:{mint:$mintTransaction,transferToUser2:$transferUser2Transaction,transferToRedemption:$transferRedemptionTransaction,burn:$burnTransaction},finalState:{supply:"0",user1:"0",user2:"0",redemption:"0"}}' \
    > "$summary_tmp"
mv "$summary_tmp" "$SOLANA_RUNTIME_DIR/evidence/summary.json"
chmod 600 "$SOLANA_RUNTIME_DIR/evidence/summary.json"
solana_require_mode "$SOLANA_RUNTIME_DIR/evidence/summary.json" 600

gate_passed=true
printf '%s\n' "Phase 7A semantic gate passed with exact 10000-base-unit mint, transfers, redemption custody, burn, finalized native evidence, and negative authority/wrong-mint checks."
