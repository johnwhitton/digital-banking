#!/bin/sh

set -eu

DEMO_SCRIPT_DIR=$(CDPATH= cd -- "${DEMO_SCRIPT_DIR_OVERRIDE:-$(dirname -- "$0")}" && pwd -P)
DEMO_ROOT=$(CDPATH= cd -- "$DEMO_SCRIPT_DIR/../.." && pwd -P)
DEMO_PROJECT=digital-banking-phase6d
DEMO_RUNTIME_DIR=$DEMO_ROOT/.demo-runtime
DEMO_OUTPUT_DIR=$DEMO_RUNTIME_DIR/output
DEMO_COMPOSE_ENV=$DEMO_RUNTIME_DIR/compose.env
DEMO_API_URL=${LOCAL_DEMO_API_URL:-http://127.0.0.1:8080}
DEMO_RPC_URL=${LOCAL_ETHEREUM_RPC_URL:-http://127.0.0.1:8545}

case "$DEMO_ROOT" in
    ""|/) printf '%s\n' "Refusing unresolved demo repository root." >&2; exit 1 ;;
esac

demo_die() {
    printf 'Phase 6D demo: %s\n' "$1" >&2
    exit 1
}

demo_mode() {
    if stat -f '%Lp' "$1" >/dev/null 2>&1; then
        stat -f '%Lp' "$1"
    else
        stat -c '%a' "$1"
    fi
}

demo_require_mode() {
    actual=$(demo_mode "$1")
    [ "$actual" = "$2" ] || demo_die "$1 must have mode $2 (found $actual)"
}

demo_env_value() {
    awk -F= -v wanted="$2" '
        $1 == wanted {
            if (found) exit 2
            print substr($0, index($0, "=") + 1)
            found = 1
        }
        END { if (!found) exit 1 }
    ' "$1"
}

demo_validate_local_keys() {
    file=$DEMO_ROOT/.env.local-anvil
    [ -f "$file" ] || demo_die ".env.local-anvil is required"
    demo_require_mode "$file" 600
    expected="CONTRACT_OWNER ADMIN BANK_1 BANK_2 BANK_3 BANK_4 USER_WALLET_1 USER_WALLET_2 USER_WALLET_3 USER_WALLET_4"
    count=$(awk -F= '$1 ~ /^LOCAL_DEMO_.*_PRIVATE_KEY$/ { count++ } END { print count + 0 }' "$file")
    [ "$count" = 10 ] || demo_die ".env.local-anvil must contain exactly ten private-key mappings"
    for role in $expected; do
        private_key=$(demo_env_value "$file" "LOCAL_DEMO_${role}_PRIVATE_KEY") \
            || demo_die "missing local key mapping for $role"
        address=$(demo_env_value "$file" "LOCAL_DEMO_${role}_EXPECTED_ADDRESS") \
            || demo_die "missing expected address mapping for $role"
        valid=$(printf '%s' "$private_key" | awk '
            length($0) == 66 && substr($0, 1, 2) == "0x" &&
                substr($0, 3) !~ /[^0-9a-fA-F]/ { print "yes" }
        ')
        [ "$valid" = yes ] || demo_die "invalid local key mapping for $role"
        private_key=
        case "$role" in
            CONTRACT_OWNER) approved=0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266 ;;
            ADMIN) approved=0x70997970c51812dc3a010c7d01b50e0d17dc79c8 ;;
            BANK_1) approved=0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc ;;
            BANK_2) approved=0x90f79bf6eb2c4f870365e785982e1f101e93b906 ;;
            BANK_3) approved=0x15d34aaf54267db7d7c367839aaf71a00a2c6a65 ;;
            BANK_4) approved=0x9965507d1a55bcc2695c58ba16fb37d819b0a4dc ;;
            USER_WALLET_1) approved=0x976ea74026e726554db657fa54763abd0c3a0aa9 ;;
            USER_WALLET_2) approved=0x14dc79964da2c08b23698b3d3cc7ca32193d9955 ;;
            USER_WALLET_3) approved=0x23618e81e3f5cdf7f54c3d65f7fbc0abf5b21e8f ;;
            USER_WALLET_4) approved=0xa0ee7a142d267c1f36714e4a8f75612f20a79720 ;;
        esac
        address_lower=$(printf '%s' "$address" | tr '[:upper:]' '[:lower:]')
        [ "$approved" = "$address_lower" ] \
            || demo_die "unexpected approved local address for $role"
    done
}

demo_check_prerequisites() {
    missing=
    case "$(uname -s 2>/dev/null || true)" in
        Darwin|Linux) ;;
        *) missing="$missing\n- supported macOS or Linux shell" ;;
    esac
    command -v docker >/dev/null 2>&1 || missing="$missing\n- Docker"
    if command -v docker >/dev/null 2>&1; then
        docker info >/dev/null 2>&1 || missing="$missing\n- running Docker engine"
        docker compose version >/dev/null 2>&1 || missing="$missing\n- Docker Compose v2"
    fi
    command -v curl >/dev/null 2>&1 || missing="$missing\n- curl"
    command -v jq >/dev/null 2>&1 || missing="$missing\n- jq"
    command -v openssl >/dev/null 2>&1 || missing="$missing\n- OpenSSL"
    if ! command -v cast >/dev/null 2>&1; then
        missing="$missing\n- Foundry cast 1.5.1"
    elif ! cast --version 2>/dev/null | grep -Eq '(^|[^0-9])1[.]5[.]1([^0-9]|$)'; then
        missing="$missing\n- exact Foundry cast 1.5.1"
    fi
    if ! command -v forge >/dev/null 2>&1; then
        missing="$missing\n- Foundry forge 1.5.1"
    elif ! forge --version 2>/dev/null | grep -Eq '(^|[^0-9])1[.]5[.]1([^0-9]|$)'; then
        missing="$missing\n- exact Foundry forge 1.5.1"
    fi
    [ "$(id -u)" != 0 ] || missing="$missing\n- non-root host user"
    [ -x "$DEMO_ROOT/mvnw" ] || missing="$missing\n- executable committed Maven wrapper"
    java_command=${JAVA_HOME:+$JAVA_HOME/bin/java}
    java_command=${java_command:-java}
    if ! "$java_command" -version 2>&1 | head -1 | grep -Eq 'version "25([.]|\")'; then
        missing="$missing\n- JDK 25 (set JAVA_HOME if needed)"
    fi
    [ -f "$DEMO_ROOT/.env.local-anvil" ] \
        || missing="$missing\n- ignored .env.local-anvil"
    if [ -n "$missing" ]; then
        printf 'Missing Phase 6D prerequisites:%b\n' "$missing" >&2
        return 1
    fi
    demo_validate_local_keys
}

demo_compose() {
    [ -f "$DEMO_COMPOSE_ENV" ] || demo_die "run scripts/demo/bootstrap.sh first"
    docker compose --project-name "$DEMO_PROJECT" \
        --env-file "$DEMO_COMPOSE_ENV" -f "$DEMO_ROOT/compose.yaml" "$@"
}

demo_require_runtime() {
    [ -d "$DEMO_RUNTIME_DIR" ] || demo_die "demo runtime is absent; run start.sh"
    demo_require_mode "$DEMO_RUNTIME_DIR" 700
    for file in postgres-password sender-token operator-token run-id; do
        [ -f "$DEMO_RUNTIME_DIR/$file" ] || demo_die "demo runtime is incomplete"
        demo_require_mode "$DEMO_RUNTIME_DIR/$file" 600
    done
}

demo_read_secret() {
    demo_require_mode "$1" 600
    value=$(sed -n '1p' "$1")
    [ -n "$value" ] || demo_die "required demo secret is empty"
    printf '%s' "$value"
}

demo_curl() {
    method=$1
    token_file=$2
    path=$3
    body=${4-}
    idempotency=${5-}
    case "$(basename -- "$token_file")" in
        sender-token) container_token=/run/secrets/sender-token ;;
        operator-token) container_token=/run/secrets/operator-token ;;
        *) demo_die "unsupported demo bearer-token file" ;;
    esac
    demo_require_mode "$token_file" 600
    response=$(demo_compose exec -T control-plane /bin/sh -c '
        set -eu
        method=$1
        token_file=$2
        path=$3
        idempotency=$4
        body=$5
        case "$method" in GET|POST) ;; *) exit 2 ;; esac
        case "$path" in /*) ;; *) exit 2 ;; esac
        case "$path" in *[!A-Za-z0-9_./:-]*) exit 2 ;; esac
        if [ "$method" = POST ]; then
            case "$idempotency" in ""|*[!A-Za-z0-9._:-]*) exit 2 ;; esac
        fi
        token=$(sed -n "1p" "$token_file")
        [ -n "$token" ] || exit 1
        content_length=${#body}
        if ! {
            printf "%s %s HTTP/1.0\r\n" "$method" "$path"
            printf "Host: 127.0.0.1:8080\r\n"
            printf "Authorization: Bearer %s\r\n" "$token"
            if [ "$method" = POST ]; then
                printf "Content-Type: application/json\r\n"
                printf "Idempotency-Key: %s\r\n" "$idempotency"
                printf "Content-Length: %s\r\n" "$content_length"
            fi
            printf "Connection: close\r\n\r\n"
            [ "$method" = GET ] || printf "%s" "$body"
        } | nc -w 30 127.0.0.1 8080; then
            token=
            exit 1
        fi
        token=
    ' demo-http "$method" "$container_token" "$path" "$idempotency" "$body") \
        || demo_die "HTTP $method $path failed; state was preserved; run scripts/demo/status.sh"
    status_code=$(printf '%s\n' "$response" | sed -n \
        '1{s/\r$//;s/^HTTP\/[0-9.]* \([0-9][0-9][0-9]\).*/\1/p;}')
    case "$status_code" in
        2??) ;;
        *) demo_die "HTTP $method $path returned ${status_code:-no status}; state was preserved; run scripts/demo/status.sh" ;;
    esac
    printf '%s\n' "$response" | awk '
        body { sub(/\r$/, ""); print; next }
        /^\r?$/ { body = 1 }
    '
}

demo_status_json() {
    demo_curl GET "$DEMO_RUNTIME_DIR/operator-token" /local/v1/demo/status
}

demo_assert_equal() {
    actual=$1
    expected=$2
    label=$3
    [ "$actual" = "$expected" ] \
        || demo_die "$label expected $expected but observed $actual; state was preserved"
}

demo_assert_status_value() {
    document=$1
    filter=$2
    expected=$3
    label=$4
    actual=$(jq -er "$filter" "$document") \
        || demo_die "$label is unavailable; state was preserved"
    demo_assert_equal "$actual" "$expected" "$label"
}

demo_wait_parent() {
    path=$1
    output=$2
    timeout=${3:-360}
    started=$(date +%s)
    previous=
    while :; do
        demo_curl GET "$DEMO_RUNTIME_DIR/sender-token" "$path" > "$output"
        state=$(jq -er '.settlementOrchestration.status // .status' "$output") \
            || demo_die "parent status response is malformed"
        progress=$(jq -c '.progress // .settlementOrchestration // {}' "$output")
        current=$state:$progress
        if [ "$current" != "$previous" ]; then
            printf 'Parent %s: %s %s\n' \
                "$(jq -r '.workflowId // .transferId' "$output")" \
                "$state" "$progress"
            previous=$current
        fi
        case "$state" in
            COMPLETED) return 0 ;;
            MANUAL_REVIEW|FAILED_NO_EFFECT)
                demo_die "parent reached $state; run status.sh and inspect retained state" ;;
        esac
        now=$(date +%s)
        [ $((now - started)) -lt "$timeout" ] \
            || demo_die "parent polling timed out; run status.sh and inspect retained state"
        sleep 1
    done
}

demo_assert_clean_initial_state() {
    status_file=$1
    jq -e '
        .bankBalancesCents.USER_1_BANK_ACCOUNT == "10000" and
        .bankBalancesCents.USER_2_BANK_ACCOUNT == "0" and
        ([.ledgerBalancesCents[]] | all(. == "0")) and
        ([.operationalPositionsCents[]] | all(. == "0")) and
        ([.tokenBalancesAtomic[]] | all(. == "0")) and
        ([.confirmedEffects[]] | all(. == 0)) and
        .latestAcquisition == null and .latestRedemption == null and
        .latestSettlement == null
    ' "$status_file" >/dev/null \
        || demo_die "demo requires fresh state; run $DEMO_SCRIPT_DIR/reset.sh --yes then $DEMO_SCRIPT_DIR/start.sh; state was preserved"
    demo_assert_status_value "$status_file" '.bankBalancesCents.USER_1_BANK_ACCOUNT' 10000 'USER_1 bank cents'
    demo_assert_status_value "$status_file" '.bankBalancesCents.USER_2_BANK_ACCOUNT' 0 'USER_2 bank cents'
    for filter in \
        '.ledgerBalancesCents.RESERVE_CASH_ASSET' \
        '.ledgerBalancesCents.FIAT_RECEIVED_PENDING_MINT_LIABILITY' \
        '.ledgerBalancesCents.USDZELLE_CIRCULATING_LIABILITY' \
        '.ledgerBalancesCents.REDEMPTION_PAYABLE_LIABILITY' \
        '.operationalPositionsCents.ADMIN_REDEMPTION_CUSTODY_PENDING_BURN' \
        '.tokenBalancesAtomic.userOne' '.tokenBalancesAtomic.userTwo' \
        '.tokenBalancesAtomic.admin' '.tokenBalancesAtomic.totalSupply' \
        '.confirmedEffects.withdrawals' '.confirmedEffects.mints' \
        '.confirmedEffects.userTransfers' '.confirmedEffects.custodyTransfers' \
        '.confirmedEffects.payouts' '.confirmedEffects.burns'; do
        demo_assert_status_value "$status_file" "$filter" 0 "$filter"
    done
}

demo_safe_contract_address() {
    [ -f "$DEMO_RUNTIME_DIR/deployment.env" ] || demo_die "deployment metadata is unavailable"
    contract=$(demo_env_value "$DEMO_RUNTIME_DIR/deployment.env" LOCAL_ETHEREUM_CONTRACT_ADDRESS) \
        || demo_die "deployment metadata is incomplete"
    case "$contract" in
        0x????????????????????????????????????????) printf '%s' "$contract" ;;
        *) demo_die "deployment contract address is invalid" ;;
    esac
}
