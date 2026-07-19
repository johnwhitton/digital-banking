#!/bin/sh
set -eu

env_file=/run/secrets/local-anvil.env
runtime=${DEMO_RUNTIME_DIR:-/runtime}
rpc=${ETH_RPC_URL:-http://anvil:8545}

fail() {
    printf 'Contract bootstrap: %s\n' "$1" >&2
    exit 1
}

env_value() {
    awk -F= -v wanted="$1" '
        $1 == wanted { if (found) exit 2; print substr($0, index($0, "=") + 1); found=1 }
        END { if (!found) exit 1 }
    ' "$env_file"
}

normalize() {
    printf '%s' "$1" | tr '[:upper:]' '[:lower:]'
}

verify_key_map() {
    for role in CONTRACT_OWNER ADMIN BANK_1 BANK_2 BANK_3 BANK_4 USER_WALLET_1 USER_WALLET_2 USER_WALLET_3 USER_WALLET_4; do
        key=$(env_value "LOCAL_DEMO_${role}_PRIVATE_KEY") || fail "missing key mapping"
        expected=$(env_value "LOCAL_DEMO_${role}_EXPECTED_ADDRESS") || fail "missing address mapping"
        [ "${#key}" -eq 66 ] || fail "malformed key mapping"
        case "$key" in 0x*) ;; *) fail "malformed key mapping" ;; esac
        key_hex=${key#0x}
        case "$key_hex" in *[!0-9a-fA-F]*) fail "malformed key mapping" ;; esac
        key_hex=
        key=
        case "$expected" in 0x????????????????????????????????????????) ;; *) fail "malformed address mapping" ;; esac
    done
}

has_role() {
    result=$(cast call "$contract" 'hasRole(bytes32,address)(bool)' "$1" "$2" \
        --rpc-url "$rpc" 2>/dev/null) || return 1
    [ "$result" = true ]
}

verify_contract_base() {
    chain=$(cast chain-id --rpc-url "$rpc" 2>/dev/null) || fail "chain is unavailable"
    [ "$chain" = 31337 ] || fail "chain ID mismatch"
    code=$(cast code "$contract" --rpc-url "$rpc" 2>/dev/null) || fail "bytecode inquiry failed"
    [ "$code" != 0x ] && [ "${#code}" -gt 10 ] || fail "deployed bytecode is absent"
    name=$(cast call "$contract" 'name()(string)' --rpc-url "$rpc" 2>/dev/null | tr -d '"')
    symbol=$(cast call "$contract" 'symbol()(string)' --rpc-url "$rpc" 2>/dev/null | tr -d '"')
    decimals=$(cast call "$contract" 'decimals()(uint8)' --rpc-url "$rpc" 2>/dev/null)
    [ "$name" = 'Local USD Stable' ] || fail "token name mismatch"
    [ "$symbol" = LUSD ] || fail "token symbol mismatch"
    [ "$decimals" = 2 ] || fail "token decimals mismatch"
    zero_role=0x$(printf '%064d' 0)
    has_role "$zero_role" "$owner_address" || fail "default administrator mismatch"
}

verify_contract() {
    verify_contract_base
    has_role "$minter_role" "$admin_address" || fail "ADMIN minter role mismatch"
    has_role "$burner_role" "$admin_address" || fail "ADMIN burner role mismatch"
}

grant_roles() {
    if ! has_role "$minter_role" "$admin_address"; then
        cast send "$contract" 'grantRole(bytes32,address)' \
            "$minter_role" "$admin_address" --rpc-url "$rpc" --unlocked \
            --from "$owner_address" >/dev/null 2>&1 \
            || fail "MINTER role grant failed"
    fi
    if ! has_role "$burner_role" "$admin_address"; then
        cast send "$contract" 'grantRole(bytes32,address)' \
            "$burner_role" "$admin_address" --rpc-url "$rpc" --unlocked \
            --from "$owner_address" >/dev/null 2>&1 \
            || fail "BURNER role grant failed"
    fi
}

write_metadata() {
    temporary_metadata=$(mktemp "$runtime/deployment.env.XXXXXX")
    chmod 600 "$temporary_metadata"
    {
        printf 'LOCAL_ETHEREUM_CONTRACT_ADDRESS=%s\n' "$contract"
        printf 'LOCAL_DEMO_CHAIN_ID=31337\n'
        printf 'LOCAL_DEMO_NETWORK=LOCAL_ANVIL\n'
    } > "$temporary_metadata"
    mv "$temporary_metadata" "$metadata"
    chmod 600 "$metadata"
}

[ -f "$env_file" ] || fail "ignored local key configuration is unavailable"
[ -d "$runtime" ] || fail "runtime directory is unavailable"
verify_key_map
owner_address=$(env_value LOCAL_DEMO_CONTRACT_OWNER_EXPECTED_ADDRESS) || fail "owner address is unavailable"
admin_address=$(env_value LOCAL_DEMO_ADMIN_EXPECTED_ADDRESS) || fail "ADMIN address is unavailable"
owner_address=$(normalize "$owner_address")
admin_address=$(normalize "$admin_address")
minter_role=$(cast keccak MINTER_ROLE)
burner_role=$(cast keccak BURNER_ROLE)
expected_contract=$(cast compute-address "$owner_address" --nonce 0 2>/dev/null \
    | awk 'NF { print $NF }' | tail -1) \
    || fail "deterministic contract address calculation failed"
expected_contract=$(normalize "$expected_contract")
case "$expected_contract" in 0x????????????????????????????????????????) ;; *) fail "deterministic contract address is invalid" ;; esac

metadata=$runtime/deployment.env
if [ -s "$metadata" ]; then
    contract=$(awk -F= '$1 == "LOCAL_ETHEREUM_CONTRACT_ADDRESS" { print $2 }' "$metadata")
    case "$contract" in 0x????????????????????????????????????????) ;; *) fail "deployment metadata is malformed" ;; esac
    contract=$(normalize "$contract")
    [ "$contract" = "$expected_contract" ] || fail "deployment address is not deterministic"
    verify_contract
    printf '%s\n' "Existing local contract deployment verified."
    exit 0
fi

existing_code=$(cast code "$expected_contract" --rpc-url "$rpc" 2>/dev/null) \
    || fail "deterministic contract inquiry failed"
if [ "$existing_code" != 0x ]; then
    contract=$expected_contract
    verify_contract_base
    grant_roles
    verify_contract
    write_metadata
    printf '%s\n' "Recovered and verified the deterministic local contract deployment."
    exit 0
fi
owner_nonce=$(cast nonce "$owner_address" --rpc-url "$rpc" 2>/dev/null) \
    || fail "owner nonce inquiry failed"
case "$owner_nonce" in 0|0x0|0x00) ;; *) fail "owner nonce advanced without the deterministic contract" ;; esac

artifact=/contracts/out/LocalReferenceToken.sol/LocalReferenceToken.json
[ -s "$artifact" ] || fail "offline contract artifact is unavailable"
bytecode=$(sed -n \
    's/.*"bytecode":{"object":"\(0x[0-9a-fA-F]*\)".*/\1/p' "$artifact")
case "$bytecode" in 0x????????*) ;; *) fail "offline contract artifact is malformed" ;; esac
constructor=$(cast abi-encode 'constructor(address)' "$owner_address") \
    || fail "constructor encoding failed"
creation=$bytecode${constructor#0x}
bytecode=
constructor=
receipt=$(cast send --rpc-url "$rpc" --unlocked --from "$owner_address" --json \
    --create "$creation" 2>/dev/null) \
    || fail "contract deployment failed"
creation=
contract=$(printf '%s\n' "$receipt" | sed -n \
    's/.*"contractAddress"[[:space:]]*:[[:space:]]*"\(0x[0-9a-fA-F]*\)".*/\1/p' | tail -1)
receipt=
case "$contract" in 0x????????????????????????????????????????) ;; *) fail "contract deployment result was malformed" ;; esac
contract=$(normalize "$contract")
[ "$contract" = "$expected_contract" ] || fail "contract deployed at an unexpected address"

verify_contract_base
grant_roles
verify_contract
write_metadata
printf '%s\n' "Local contract deployed and roles/metadata verified."
