#!/bin/sh

set -eu

SOLANA_SCRIPT_DIR=$(CDPATH= cd -- "${SOLANA_SCRIPT_DIR_OVERRIDE:-$(dirname -- "$0")}" && pwd -P)
SOLANA_ROOT=$(CDPATH= cd -- "$SOLANA_SCRIPT_DIR/../.." && pwd -P)
SOLANA_TOOLS_DIR=$SOLANA_ROOT/.solana-tools
SOLANA_RUNTIME_DIR=${LOCAL_SOLANA_RUNTIME_ROOT:-$SOLANA_ROOT/.solana-runtime}
SOLANA_RESET_LEDGER=${LOCAL_SOLANA_RESET_LEDGER:-true}
SOLANA_RPC_URL=http://127.0.0.1:8899
SOLANA_AGAVE_VERSION=4.1.2
SOLANA_SPL_TOKEN_VERSION=5.6.1
SOLANA_TOKEN_PROGRAM=TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA
SOLANA_ATA_PROGRAM=ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL
SOLANA_CLI=$SOLANA_TOOLS_DIR/agave/bin/solana
SOLANA_KEYGEN=$SOLANA_TOOLS_DIR/agave/bin/solana-keygen
SOLANA_TEST_VALIDATOR=$SOLANA_TOOLS_DIR/agave/bin/solana-test-validator
SOLANA_SPL_TOKEN=$SOLANA_TOOLS_DIR/spl-token/bin/spl-token
SOLANA_PID_FILE=$SOLANA_RUNTIME_DIR/validator.pid
SOLANA_IDENTITY_FILE=$SOLANA_RUNTIME_DIR/validator.identity
SOLANA_STARTING_FILE=$SOLANA_RUNTIME_DIR/validator.starting

case "$SOLANA_ROOT" in
    ""|/) printf '%s\n' "Refusing unresolved repository root." >&2; exit 1 ;;
esac
for solana_scoped_path in "$SOLANA_TOOLS_DIR" "$SOLANA_RUNTIME_DIR"; do
    case "$solana_scoped_path" in
        "$SOLANA_ROOT"/*) ;;
        *) printf '%s\n' "Refusing path outside the repository: $solana_scoped_path" >&2; exit 1 ;;
    esac
done
unset solana_scoped_path
case "$SOLANA_RUNTIME_DIR" in
    "$SOLANA_ROOT"/.solana-runtime|"$SOLANA_ROOT"/.demo-runtime/solana/validator) ;;
    *) printf '%s\n' "Refusing unapproved Solana runtime root: $SOLANA_RUNTIME_DIR" >&2; exit 1 ;;
esac
case "$SOLANA_RESET_LEDGER" in
    true|false) ;;
    *) printf '%s\n' "LOCAL_SOLANA_RESET_LEDGER must be true or false" >&2; exit 1 ;;
esac

solana_die() {
    printf 'Phase 7A Solana: %s\n' "$1" >&2
    exit 1
}

solana_mode() {
    if stat -f '%Lp' "$1" >/dev/null 2>&1; then
        stat -f '%Lp' "$1"
    else
        stat -c '%a' "$1"
    fi
}

solana_require_mode() {
    actual=$(solana_mode "$1")
    [ "$actual" = "$2" ] || solana_die "$1 must have mode $2 (found $actual)"
}

solana_require_safe_path() {
    scoped_path=$1
    case "$scoped_path" in
        "$SOLANA_ROOT"/*) ;;
        *) solana_die "path is outside the repository: $scoped_path" ;;
    esac
    [ ! -L "$scoped_path" ] || solana_die "refusing symlink path: $scoped_path"
    scoped_parent=$(dirname -- "$scoped_path")
    [ -d "$scoped_parent" ] || solana_die "parent directory does not exist: $scoped_parent"
    physical_parent=$(CDPATH= cd -- "$scoped_parent" && pwd -P)
    [ "$physical_parent" = "$scoped_parent" ] \
        || solana_die "refusing path through a symlinked parent: $scoped_path"
    if [ -d "$scoped_path" ]; then
        physical_path=$(CDPATH= cd -- "$scoped_path" && pwd -P)
        [ "$physical_path" = "$scoped_path" ] \
            || solana_die "refusing non-canonical directory: $scoped_path"
    fi
}

solana_prepare_directory() {
    directory=$1
    mode=$2
    solana_require_safe_path "$directory"
    [ ! -e "$directory" ] || [ -d "$directory" ] \
        || solana_die "expected a directory: $directory"
    mkdir -p "$directory"
    solana_require_safe_path "$directory"
    chmod "$mode" "$directory"
    solana_require_mode "$directory" "$mode"
}

solana_require_host_tools() {
    [ "$(uname -s)" = Darwin ] || solana_die "native fallback supports only the approved Darwin host"
    [ "$(uname -m)" = arm64 ] || solana_die "native fallback supports only the approved arm64 host"
    [ "$(id -u)" != 0 ] || solana_die "run as a non-root host user"
    for command_name in cargo curl jq rustc shasum tar; do
        command -v "$command_name" >/dev/null 2>&1 || solana_die "$command_name is required"
    done
    cargo --version | grep -F 'cargo 1.96.0 (30a34c682 2026-05-25)' >/dev/null \
        || solana_die "the approved Cargo 1.96.0 toolchain is required"
    rustc --version | grep -F 'rustc 1.96.0 (ac68faa20 2026-05-25)' >/dev/null \
        || solana_die "the approved Rust 1.96.0 toolchain is required"
}

solana_require_native_tools() {
    for native_tool in "$SOLANA_CLI" "$SOLANA_KEYGEN" "$SOLANA_TEST_VALIDATOR" "$SOLANA_SPL_TOKEN"; do
        solana_require_safe_path "$native_tool"
    done
    [ -x "$SOLANA_CLI" ] || solana_die "Agave $SOLANA_AGAVE_VERSION is not bootstrapped"
    [ -x "$SOLANA_KEYGEN" ] || solana_die "solana-keygen is not bootstrapped"
    [ -x "$SOLANA_TEST_VALIDATOR" ] || solana_die "solana-test-validator is not bootstrapped"
    [ -x "$SOLANA_SPL_TOKEN" ] || solana_die "spl-token $SOLANA_SPL_TOKEN_VERSION is not bootstrapped"
}

solana_rpc() {
    payload=$1
    curl --fail --silent --show-error \
        --header 'content-type: application/json' \
        --data "$payload" "$SOLANA_RPC_URL"
}

solana_rpc_request() {
    method=$1
    params=${2:-'[]'}
    payload=$(jq -cn --arg method "$method" --argjson params "$params" \
        '{jsonrpc:"2.0",id:1,method:$method,params:$params}')
    solana_rpc "$payload"
}

solana_write_state() {
    state=$1
    [ -d "$SOLANA_RUNTIME_DIR" ] || return 0
    solana_require_safe_path "$SOLANA_RUNTIME_DIR"
    solana_require_safe_path "$SOLANA_RUNTIME_DIR/state"
    umask 077
    rm -f -- "$SOLANA_RUNTIME_DIR/state"
    printf '%s\n' "$state" > "$SOLANA_RUNTIME_DIR/state"
    chmod 600 "$SOLANA_RUNTIME_DIR/state"
    solana_require_mode "$SOLANA_RUNTIME_DIR/state" 600
}

solana_validator_pid_alive() {
    [ -d "$SOLANA_RUNTIME_DIR" ] || return 1
    solana_require_safe_path "$SOLANA_RUNTIME_DIR"
    solana_require_safe_path "$SOLANA_PID_FILE"
    [ -f "$SOLANA_PID_FILE" ] || return 1
    validator_pid=$(sed -n '1p' "$SOLANA_PID_FILE")
    case "$validator_pid" in
        ''|*[!0-9]*) return 1 ;;
    esac
    kill -0 "$validator_pid" >/dev/null 2>&1 || return 1
}

solana_validator_pid_matches() {
    solana_validator_pid_alive || return 1
    validator_pid=$(sed -n '1p' "$SOLANA_PID_FILE")
    validator_command=$(ps -ww -p "$validator_pid" -o command= 2>/dev/null || true)
    reset_argument=
    [ "$SOLANA_RESET_LEDGER" = false ] || reset_argument=' --reset'
    expected_command="$SOLANA_TEST_VALIDATOR --ledger $SOLANA_RUNTIME_DIR/ledger$reset_argument --bind-address 127.0.0.1 --rpc-port 8899 --faucet-port 9900 --quiet"
    [ "$validator_command" = "$expected_command" ]
}

solana_validator_running() {
    solana_validator_pid_matches || return 1
    solana_require_safe_path "$SOLANA_IDENTITY_FILE"
    [ -f "$SOLANA_IDENTITY_FILE" ] || return 1
    expected_identity=$(sed -n '1p' "$SOLANA_IDENTITY_FILE")
    actual_identity=$(solana_rpc_request getIdentity 2>/dev/null | jq -er '.result.identity' 2>/dev/null || true)
    [ -n "$expected_identity" ] && [ "$actual_identity" = "$expected_identity" ]
}

solana_stop_validator() {
    if ! solana_validator_pid_alive; then
        return 0
    fi
    solana_validator_pid_matches \
        || solana_die "refusing to signal a PID that is not the scoped validator command"
    validator_pid=$(sed -n '1p' "$SOLANA_PID_FILE")
    if [ -f "$SOLANA_IDENTITY_FILE" ]; then
        solana_validator_running \
            || solana_die "refusing to signal a validator without matching RPC identity"
    else
        solana_require_safe_path "$SOLANA_STARTING_FILE"
        [ -f "$SOLANA_STARTING_FILE" ] \
            || solana_die "refusing to signal a validator without startup ownership metadata"
        starting_pid=$(sed -n '1p' "$SOLANA_STARTING_FILE" 2>/dev/null || true)
        [ "$starting_pid" = "$validator_pid" ] \
            || solana_die "refusing to signal a validator without identity or startup ownership"
    fi
    kill "$validator_pid"
    attempts=0
    while kill -0 "$validator_pid" >/dev/null 2>&1; do
        attempts=$((attempts + 1))
        [ "$attempts" -lt 30 ] || solana_die "validator process $validator_pid did not stop"
        sleep 1
    done
}

solana_wait_ready() {
    attempts=0
    until "$SOLANA_CLI" --url "$SOLANA_RPC_URL" cluster-version >/dev/null 2>&1; do
        attempts=$((attempts + 1))
        if [ "$attempts" -ge 90 ]; then
            solana_write_state failed
            solana_die "validator did not become ready; inspect .solana-runtime/validator.log"
        fi
        sleep 1
    done
}

solana_start_validator() {
    if solana_validator_running; then
        return 0
    fi
    solana_validator_pid_alive \
        && solana_die "refusing to replace an existing live validator PID"
    solana_require_safe_path "$SOLANA_RUNTIME_DIR"
    for validator_path in "$SOLANA_RUNTIME_DIR/ledger" "$SOLANA_RUNTIME_DIR/validator.log" \
        "$SOLANA_PID_FILE" "$SOLANA_IDENTITY_FILE" "$SOLANA_STARTING_FILE"; do
        solana_require_safe_path "$validator_path"
    done
    umask 077
    if [ "$SOLANA_RESET_LEDGER" = true ]; then
        rm -rf -- "$SOLANA_RUNTIME_DIR/ledger"
    fi
    rm -f -- "$SOLANA_RUNTIME_DIR/validator.log" "$SOLANA_PID_FILE" \
        "$SOLANA_IDENTITY_FILE" "$SOLANA_STARTING_FILE"
    : > "$SOLANA_RUNTIME_DIR/validator.log"
    chmod 600 "$SOLANA_RUNTIME_DIR/validator.log"
    if [ "$SOLANA_RESET_LEDGER" = true ]; then
        nohup "$SOLANA_TEST_VALIDATOR" \
            --ledger "$SOLANA_RUNTIME_DIR/ledger" \
            --reset \
            --bind-address 127.0.0.1 \
            --rpc-port 8899 \
            --faucet-port 9900 \
            --quiet \
            >"$SOLANA_RUNTIME_DIR/validator.log" 2>&1 &
    else
        nohup "$SOLANA_TEST_VALIDATOR" \
            --ledger "$SOLANA_RUNTIME_DIR/ledger" \
            --bind-address 127.0.0.1 \
            --rpc-port 8899 \
            --faucet-port 9900 \
            --quiet \
            >"$SOLANA_RUNTIME_DIR/validator.log" 2>&1 &
    fi
    printf '%s\n' "$!" > "$SOLANA_PID_FILE"
    printf '%s\n' "$!" > "$SOLANA_STARTING_FILE"
    chmod 600 "$SOLANA_PID_FILE"
    chmod 600 "$SOLANA_STARTING_FILE"
}

solana_record_identity() {
    identity_tmp=$SOLANA_IDENTITY_FILE.tmp
    identity_response_tmp=$SOLANA_IDENTITY_FILE.response.tmp
    solana_require_safe_path "$SOLANA_IDENTITY_FILE"
    solana_require_safe_path "$identity_tmp"
    solana_require_safe_path "$identity_response_tmp"
    rm -f -- "$SOLANA_IDENTITY_FILE" "$identity_tmp" "$identity_response_tmp"
    if ! solana_rpc_request getIdentity > "$identity_response_tmp"; then
        rm -f -- "$identity_response_tmp"
        return 1
    fi
    if ! jq -er '.result.identity' "$identity_response_tmp" > "$identity_tmp"; then
        rm -f -- "$identity_tmp" "$identity_response_tmp"
        return 1
    fi
    rm -f -- "$identity_response_tmp"
    chmod 600 "$identity_tmp"
    mv "$identity_tmp" "$SOLANA_IDENTITY_FILE"
    rm -f -- "$SOLANA_STARTING_FILE"
}

solana_key_path() {
    printf '%s/keys/%s.json\n' "$SOLANA_RUNTIME_DIR" "$1"
}

solana_address() {
    "$SOLANA_KEYGEN" pubkey "$(solana_key_path "$1")"
}
