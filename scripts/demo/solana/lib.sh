#!/bin/sh

set -eu

SOLANA_DEMO_SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
DEMO_SCRIPT_DIR_OVERRIDE=$SOLANA_DEMO_SCRIPT_DIR/..
. "$SOLANA_DEMO_SCRIPT_DIR/../lib.sh"
unset DEMO_SCRIPT_DIR_OVERRIDE

DEMO_SCRIPT_DIR=$SOLANA_DEMO_SCRIPT_DIR
DEMO_ROOT=$(CDPATH= cd -- "$DEMO_SCRIPT_DIR/../../.." && pwd -P)
DEMO_PROJECT=digital-banking-phase7f-solana
DEMO_RUNTIME_DIR=$DEMO_ROOT/.demo-runtime/solana
DEMO_OUTPUT_DIR=$DEMO_RUNTIME_DIR/output
DEMO_COMPOSE_ENV=$DEMO_RUNTIME_DIR/compose.env
DEMO_API_URL=${LOCAL_SOLANA_DEMO_API_URL:-http://127.0.0.1:18080}
DEMO_RPC_URL=http://127.0.0.1:8899
SOLANA_DEMO_POSTGRES_PORT=${SOLANA_DEMO_POSTGRES_PORT:-15432}
SOLANA_DEMO_VALIDATOR_ROOT=$DEMO_RUNTIME_DIR/validator
SOLANA_DEMO_COMPOSE_FILE=$DEMO_ROOT/compose.solana-demo.yaml
SOLANA_DEMO_JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk}
SOLANA_DEMO_JAR=$DEMO_ROOT/control-plane/target/digital-banking-control-plane-0.1.0-SNAPSHOT.jar
SOLANA_DEMO_CONFIG=$DEMO_RUNTIME_DIR/config/application.properties
SOLANA_DEMO_PID_FILE=$DEMO_RUNTIME_DIR/pids/control-plane.pid
SOLANA_DEMO_COMMAND_FILE=$DEMO_RUNTIME_DIR/pids/control-plane.command
SOLANA_DEMO_WORKER_FILE=$DEMO_RUNTIME_DIR/pids/control-plane.worker
SOLANA_DEMO_LOG=$DEMO_RUNTIME_DIR/logs/control-plane.log
SOLANA_DEMO_POSTGRES_IMAGE='postgres:17.10-alpine3.23@sha256:8189a1f6e40904781fc9e2612687877791d21679866db58b1de996b31fc312e4'

SOLANA_SCRIPT_DIR_OVERRIDE=$DEMO_ROOT/scripts/solana
LOCAL_SOLANA_RUNTIME_ROOT=$SOLANA_DEMO_VALIDATOR_ROOT
LOCAL_SOLANA_RESET_LEDGER=false
. "$DEMO_ROOT/scripts/solana/lib.sh"
unset SOLANA_SCRIPT_DIR_OVERRIDE

case "$DEMO_RUNTIME_DIR" in
    "$DEMO_ROOT"/.demo-runtime/solana) ;;
    *) printf '%s\n' 'Refusing unexpected Phase 7F runtime root.' >&2; exit 1 ;;
esac

demo_die() {
    printf 'Phase 7F Solana demo: %s\n' "$1" >&2
    exit 1
}

case "$DEMO_API_URL" in
    http://127.0.0.1:*)
        demo_api_port=${DEMO_API_URL#http://127.0.0.1:}
        case "$demo_api_port" in
            ''|*[!0-9]*) demo_die 'demo API URL must be loopback HTTP with a numeric port' ;;
        esac
        unset demo_api_port
        ;;
    *) demo_die 'demo API URL must be loopback HTTP with a numeric port' ;;
esac

demo_require_safe_path() {
    path=$1
    case "$path" in
        "$DEMO_RUNTIME_DIR"|"$DEMO_RUNTIME_DIR"/*) ;;
        *) demo_die "path is outside the Solana demo runtime: $path" ;;
    esac
    [ ! -L "$path" ] || demo_die "refusing symlink path: $path"
    parent=$(dirname -- "$path")
    while [ "$parent" != "$DEMO_ROOT" ]; do
        [ ! -L "$parent" ] || demo_die "refusing symlinked parent: $path"
        parent=$(dirname -- "$parent")
    done
}

demo_prepare_directory() {
    directory=$1
    demo_require_safe_path "$directory"
    [ ! -e "$directory" ] || [ -d "$directory" ] \
        || demo_die "expected directory: $directory"
    mkdir -p "$directory"
    chmod 700 "$directory"
    demo_require_mode "$directory" 700
}

demo_validator() {
    LOCAL_SOLANA_RUNTIME_ROOT=$SOLANA_DEMO_VALIDATOR_ROOT \
    LOCAL_SOLANA_RESET_LEDGER=false "$@"
}

demo_start_validator() {
    if solana_validator_running; then
        return 0
    fi
    solana_start_validator
    solana_wait_ready
    solana_record_identity || demo_die 'could not retain private validator identity'
    solana_write_state healthy
}

demo_compose() {
    [ -f "$DEMO_COMPOSE_ENV" ] || demo_die "run $DEMO_SCRIPT_DIR/bootstrap.sh first"
    SOLANA_DEMO_RUNTIME_DIR=$DEMO_RUNTIME_DIR \
    SOLANA_DEMO_POSTGRES_PORT=$SOLANA_DEMO_POSTGRES_PORT \
        docker compose --project-name "$DEMO_PROJECT" \
        --env-file "$DEMO_COMPOSE_ENV" -f "$SOLANA_DEMO_COMPOSE_FILE" "$@"
}

demo_port_busy() {
    lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
}

demo_check_prerequisites() {
    missing=
    case "$(uname -s 2>/dev/null || true)" in Darwin|Linux) ;; *) missing="$missing\n- macOS or Linux" ;; esac
    for command_name in docker curl jq openssl lsof; do
        command -v "$command_name" >/dev/null 2>&1 \
            || missing="$missing\n- $command_name"
    done
    if command -v docker >/dev/null 2>&1; then
        docker info >/dev/null 2>&1 || missing="$missing\n- running Docker engine"
        docker compose version >/dev/null 2>&1 || missing="$missing\n- Docker Compose v2"
        docker image inspect "$SOLANA_DEMO_POSTGRES_IMAGE" >/dev/null 2>&1 \
            || missing="$missing\n- approved cached PostgreSQL digest"
    fi
    [ -x "$DEMO_ROOT/mvnw" ] || missing="$missing\n- executable Maven wrapper"
    [ -x "$SOLANA_DEMO_JAVA_HOME/bin/java" ] \
        || missing="$missing\n- configured Java 25 home"
    if [ -x "$SOLANA_DEMO_JAVA_HOME/bin/java" ] \
            && ! "$SOLANA_DEMO_JAVA_HOME/bin/java" -version 2>&1 \
                | head -1 | grep -F 'version "25.0.2"' >/dev/null; then
        missing="$missing\n- exact configured Java 25.0.2"
    fi
    for specification in \
        "$DEMO_ROOT/.solana-tools/agave/bin/solana|solana-cli 4.1.2" \
        "$DEMO_ROOT/.solana-tools/agave/bin/solana-test-validator|solana-test-validator 4.1.2" \
        "$DEMO_ROOT/.solana-tools/spl-token/bin/spl-token|spl-token-cli 5.6.1"; do
        tool=${specification%%|*}
        version=${specification#*|}
        if [ ! -x "$tool" ] || ! "$tool" --version 2>/dev/null \
                | grep -F "$version" >/dev/null; then
            missing="$missing\n- $version"
        fi
    done
    if [ ! -d "$DEMO_RUNTIME_DIR" ]; then
        for port in "$SOLANA_DEMO_POSTGRES_PORT" 18080 8899 9900; do
            demo_port_busy "$port" && missing="$missing\n- free loopback port $port"
        done
    fi
    if docker ps --filter name=digital-banking-phase6d --format '{{.Names}}' \
            2>/dev/null | grep -q .; then
        missing="$missing\n- stopped Phase 6D Ethereum demo project"
    fi
    [ "$(id -u)" != 0 ] || missing="$missing\n- non-root host user"
    if [ -n "$missing" ]; then
        printf 'Missing Phase 7F prerequisites:%b\n' "$missing" >&2
        return 1
    fi
}

demo_require_runtime() {
    [ -d "$DEMO_RUNTIME_DIR" ] || demo_die "runtime is absent; run $DEMO_SCRIPT_DIR/start.sh"
    demo_require_safe_path "$DEMO_RUNTIME_DIR"
    demo_require_mode "$DEMO_RUNTIME_DIR" 700
    for file in postgres-password sender-token operator-token run-id; do
        [ -f "$DEMO_RUNTIME_DIR/$file" ] || demo_die "runtime is incomplete"
        [ ! -L "$DEMO_RUNTIME_DIR/$file" ] || demo_die "runtime secret is symlinked"
        demo_require_mode "$DEMO_RUNTIME_DIR/$file" 600
    done
}

demo_curl() {
    method=$1
    token_file=$2
    path=$3
    body=${4-}
    idempotency=${5-}
    case "$method" in GET|POST) ;; *) demo_die "unsupported HTTP method" ;; esac
    case "$path" in /*) ;; *) demo_die "HTTP path must be absolute" ;; esac
    case "$path" in *[!A-Za-z0-9_./:-]*) demo_die "HTTP path is unsafe" ;; esac
    token=$(demo_read_secret "$token_file")
    response=$(
        {
            printf 'header = "Authorization: Bearer %s"\n' "$token"
            [ "$method" = GET ] || printf 'header = "Content-Type: application/json"\nheader = "Idempotency-Key: %s"\n' "$idempotency"
        } | curl --config - --silent --show-error --connect-timeout 5 \
            --max-time 35 --request "$method" \
            ${body:+--data "$body"} --write-out '\n%{http_code}' \
            "$DEMO_API_URL$path"
    ) || { token=; demo_die "HTTP $method $path failed; state was preserved"; }
    token=
    status_code=$(printf '%s\n' "$response" | tail -1)
    case "$status_code" in 2??) ;; *) demo_die "HTTP $method $path returned ${status_code:-no status}; state was preserved" ;; esac
    printf '%s\n' "$response" | sed '$d'
}

demo_status_json() {
    demo_curl GET "$DEMO_RUNTIME_DIR/operator-token" /local/v1/demo/status
}

demo_control_pid_alive() {
    [ -f "$SOLANA_DEMO_PID_FILE" ] || return 1
    pid=$(sed -n '1p' "$SOLANA_DEMO_PID_FILE")
    case "$pid" in ''|*[!0-9]*) return 1 ;; esac
    kill -0 "$pid" >/dev/null 2>&1
}

demo_control_pid_matches() {
    demo_control_pid_alive || return 1
    [ -f "$SOLANA_DEMO_COMMAND_FILE" ] || return 1
    expected=$(sed -n '1p' "$SOLANA_DEMO_COMMAND_FILE")
    actual=$(ps -ww -p "$(sed -n '1p' "$SOLANA_DEMO_PID_FILE")" -o command= 2>/dev/null || true)
    [ -n "$expected" ] && [ "$actual" = "$expected" ]
}

demo_stop_control_plane() {
    demo_control_pid_alive || return 0
    demo_control_pid_matches \
        || demo_die "refusing to signal a PID that is not the scoped control plane"
    pid=$(sed -n '1p' "$SOLANA_DEMO_PID_FILE")
    kill "$pid"
    attempts=0
    while kill -0 "$pid" >/dev/null 2>&1; do
        attempts=$((attempts + 1))
        [ "$attempts" -lt 45 ] || demo_die "control plane did not stop"
        sleep 1
    done
    rm -f -- "$SOLANA_DEMO_PID_FILE" "$SOLANA_DEMO_COMMAND_FILE" \
        "$SOLANA_DEMO_WORKER_FILE"
}

demo_start_control_plane() {
    worker=${1:-true}
    case "$worker" in true|false) ;; *) demo_die "worker mode must be true or false" ;; esac
    if demo_control_pid_matches; then
        current=$(sed -n '1p' "$SOLANA_DEMO_WORKER_FILE")
        [ "$current" = "$worker" ] || demo_die "control plane is running with a different worker mode"
        return 0
    fi
    demo_control_pid_alive && demo_die "refusing to replace an unowned control-plane PID"
    [ -f "$SOLANA_DEMO_JAR" ] || demo_die "packaged control-plane JAR is unavailable"
    [ -f "$SOLANA_DEMO_CONFIG" ] || demo_die "runtime configuration is unavailable"
    command="$SOLANA_DEMO_JAVA_HOME/bin/java -jar $SOLANA_DEMO_JAR --spring.config.location=file:$SOLANA_DEMO_CONFIG"
    : > "$SOLANA_DEMO_LOG"
    chmod 600 "$SOLANA_DEMO_LOG"
    LOCAL_DEMO_WORKER_ENABLED=$worker nohup "$SOLANA_DEMO_JAVA_HOME/bin/java" \
        -jar "$SOLANA_DEMO_JAR" \
        "--spring.config.location=file:$SOLANA_DEMO_CONFIG" \
        >"$SOLANA_DEMO_LOG" 2>&1 &
    printf '%s\n' "$!" > "$SOLANA_DEMO_PID_FILE"
    printf '%s\n' "$command" > "$SOLANA_DEMO_COMMAND_FILE"
    printf '%s\n' "$worker" > "$SOLANA_DEMO_WORKER_FILE"
    chmod 600 "$SOLANA_DEMO_PID_FILE" "$SOLANA_DEMO_COMMAND_FILE" \
        "$SOLANA_DEMO_WORKER_FILE"
}

demo_metadata_value() {
    jq -er "$1" "$DEMO_RUNTIME_DIR/mint.json"
}

demo_write_application_config() {
    cluster=$(jq -er '.clusterIdentity' "$DEMO_RUNTIME_DIR/cluster.json")
    mint=$(demo_metadata_value '.mintAddress')
    user1=$(demo_metadata_value '.userOneOwner')
    user2=$(demo_metadata_value '.userTwoOwner')
    admin=$(demo_metadata_value '.adminOwner')
    fee=$(demo_metadata_value '.feePayer')
    authority=$(demo_metadata_value '.mintAuthority')
    cat > "$SOLANA_DEMO_CONFIG" <<EOF
spring.profiles.active=local-demo,local-solana,local-demo-environment
spring.config.import=configtree:$DEMO_RUNTIME_DIR/configtree/
spring.application.name=digital-banking-control-plane
spring.datasource.url=jdbc:postgresql://127.0.0.1:$SOLANA_DEMO_POSTGRES_PORT/digital_banking_demo
spring.datasource.username=digital_banking_demo
spring.flyway.clean-disabled=true
spring.jackson.deserialization.fail-on-unknown-properties=true
server.address=127.0.0.1
server.port=18080
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=never
management.endpoint.health.probes.enabled=true
digital-banking.transfer.default-network=SOLANA
digital-banking.delivery-worker.enabled=\${LOCAL_DEMO_WORKER_ENABLED:true}
digital-banking.delivery-worker.worker-id=local-solana-worker
digital-banking.delivery-worker.poll-interval=PT1S
digital-banking.delivery-worker.max-attempts=100
digital-banking.delivery-worker.initial-backoff=PT1S
digital-banking.delivery-worker.max-backoff=PT1S
digital-banking.usdzelle-workflow.enabled=true
digital-banking.usdzelle-workflow.workflow-version=phase-6b-v1
digital-banking.usdzelle-workflow.payout-policy-version=payout-before-burn-v1
digital-banking.usdzelle-workflow.conversion-policy-version=usd-usdzelle-cent-v1
digital-banking.usdzelle-workflow.fee-policy-version=no-fee-local-v1
digital-banking.usdzelle-workflow.finality-policy-version=local-solana-finality-v1
digital-banking.usdzelle-workflow.reconciliation-policy-version=reserve-chain-reconciliation-v1
digital-banking.usdzelle-workflow.participants[0].tenant-id=local-demo
digital-banking.usdzelle-workflow.participants[0].participant-id=USER_1
digital-banking.usdzelle-workflow.participants[0].bank-id=BANK_1
digital-banking.usdzelle-workflow.participants[0].bank-account-id=USER_1_BANK_ACCOUNT
digital-banking.usdzelle-workflow.participants[0].bank-account-reference=synthetic-bank:USER_1_BANK_ACCOUNT
digital-banking.usdzelle-workflow.participants[0].user-wallet-reference=synthetic-wallet:USER_WALLET_1
digital-banking.usdzelle-workflow.participants[1].tenant-id=local-demo
digital-banking.usdzelle-workflow.participants[1].participant-id=USER_2
digital-banking.usdzelle-workflow.participants[1].bank-id=BANK_2
digital-banking.usdzelle-workflow.participants[1].bank-account-id=USER_2_BANK_ACCOUNT
digital-banking.usdzelle-workflow.participants[1].bank-account-reference=synthetic-bank:USER_2_BANK_ACCOUNT
digital-banking.usdzelle-workflow.participants[1].user-wallet-reference=synthetic-wallet:USER_WALLET_2
digital-banking.local-finance.enabled=true
digital-banking.local-finance.fixture-version=phase-6a-v1
digital-banking.local-finance.maximum-pre-effect-attempts=2
digital-banking.local-finance.inquiry-timeout=5s
digital-banking.local-finance.bank-policy-version=synthetic-bank-v1
digital-banking.local-finance.accounting-policy-version=reserve-accounting-v1
digital-banking.local-finance.mint-evidence-policy-version=local-solana-mint-v1
digital-banking.local-finance.custody-evidence-policy-version=local-solana-mint-v1
digital-banking.local-finance.burn-evidence-policy-version=local-solana-mint-v1
digital-banking.local-finance.chain-asset-id=USD_STABLE
digital-banking.local-demo-identity.sender-token-file=$DEMO_RUNTIME_DIR/sender-token
digital-banking.local-demo-identity.operator-token-file=$DEMO_RUNTIME_DIR/operator-token
digital-banking.local-finance.settlement-network=LOCAL_SOLANA
digital-banking.local-finance.contract-reference=$mint
digital-banking.local-finance.maximum-observation-age=24h
digital-banking.local-finance.banks[0].bank-id=BANK_1
digital-banking.local-finance.banks[0].enabled=true
digital-banking.local-finance.banks[1].bank-id=BANK_2
digital-banking.local-finance.banks[1].enabled=true
digital-banking.local-finance.banks[2].bank-id=BANK_3
digital-banking.local-finance.banks[2].enabled=true
digital-banking.local-finance.banks[3].bank-id=BANK_4
digital-banking.local-finance.banks[3].enabled=true
digital-banking.local-finance.accounts[0].bank-id=BANK_1
digital-banking.local-finance.accounts[0].account-id=USER_1_BANK_ACCOUNT
digital-banking.local-finance.accounts[0].tenant-id=local-demo
digital-banking.local-finance.accounts[0].participant-id=USER_1
digital-banking.local-finance.accounts[0].currency=USD
digital-banking.local-finance.accounts[0].initial-balance=100
digital-banking.local-finance.accounts[0].enabled=true
digital-banking.local-finance.accounts[1].bank-id=BANK_2
digital-banking.local-finance.accounts[1].account-id=USER_2_BANK_ACCOUNT
digital-banking.local-finance.accounts[1].tenant-id=local-demo
digital-banking.local-finance.accounts[1].participant-id=USER_2
digital-banking.local-finance.accounts[1].currency=USD
digital-banking.local-finance.accounts[1].initial-balance=0
digital-banking.local-finance.accounts[1].enabled=true
digital-banking.local-solana.rpc-uri=$DEMO_RPC_URL
digital-banking.local-solana.cluster-identity=$cluster
digital-banking.local-solana.mint-address=$mint
digital-banking.local-solana.destination-owner=$user1
digital-banking.local-solana.transfer-destination-owner=$user2
digital-banking.local-solana.runtime-root=$SOLANA_DEMO_VALIDATOR_ROOT
digital-banking.local-solana.fee-payer-key-file=$SOLANA_DEMO_VALIDATOR_ROOT/keys/fee-payer.json
digital-banking.local-solana.fee-payer-public-key=$fee
digital-banking.local-solana.mint-authority-key-file=$SOLANA_DEMO_VALIDATOR_ROOT/keys/admin-mint-authority.json
digital-banking.local-solana.mint-authority-public-key=$authority
digital-banking.local-solana.transfer-authority-key-file=$SOLANA_DEMO_VALIDATOR_ROOT/keys/user-1.json
digital-banking.local-solana.transfer-destination-authority-key-file=$SOLANA_DEMO_VALIDATOR_ROOT/keys/user-2.json
digital-banking.local-solana.redemption-owner=$admin
digital-banking.local-solana.burn-authority-key-file=$SOLANA_DEMO_VALIDATOR_ROOT/keys/admin-redemption-owner.json
digital-banking.local-solana.fee-payer-key-alias=local-solana:fee-payer
digital-banking.local-solana.fee-payer-key-version=local-solana-fee-payer-v1
digital-banking.local-solana.mint-authority-key-alias=local-solana:admin-mint-authority
digital-banking.local-solana.mint-authority-key-version=local-solana-mint-authority-v1
digital-banking.local-solana.transfer-authority-key-alias=local-solana:user-1-transfer-authority
digital-banking.local-solana.transfer-authority-key-version=local-solana-user-1-v1
digital-banking.local-solana.transfer-destination-authority-key-alias=local-solana:user-2-transfer-authority
digital-banking.local-solana.transfer-destination-authority-key-version=local-solana-user-2-v1
digital-banking.local-solana.burn-authority-key-alias=local-solana:admin-redemption-burn-authority
digital-banking.local-solana.burn-authority-key-version=local-solana-admin-redemption-v1
digital-banking.local-solana.wallet-registry-version=local-solana-wallet-registry-v1
digital-banking.local-solana.asset-id=USD_STABLE
digital-banking.local-solana.unit-id=USD
digital-banking.local-solana.unit-version=1
digital-banking.local-solana.decimals=2
digital-banking.local-solana.max-atomic-units=1000000000000
digital-banking.local-solana.policy-version=local-solana-mint-v1
digital-banking.local-solana.preparation-commitment=CONFIRMED
digital-banking.local-solana.observation-commitment=FINALIZED
digital-banking.local-solana.minimum-fee-payer-lamports=1000000
digital-banking.local-solana.maximum-fee-lamports=100000
digital-banking.local-solana.request-timeout=5s
EOF
    chmod 600 "$SOLANA_DEMO_CONFIG"
}

demo_bootstrap_fixture() {
    solana=$DEMO_ROOT/.solana-tools/agave/bin/solana
    spl=$DEMO_ROOT/.solana-tools/spl-token/bin/spl-token
    fee_key=$SOLANA_DEMO_VALIDATOR_ROOT/keys/fee-payer.json
    if [ -f "$DEMO_RUNTIME_DIR/cluster.json" ] \
            && [ -f "$DEMO_RUNTIME_DIR/mint.json" ]; then
        expected=$(jq -er '.clusterIdentity' "$DEMO_RUNTIME_DIR/cluster.json")
        actual=$($solana --url "$DEMO_RPC_URL" genesis-hash)
        [ "$actual" = "$expected" ] || demo_die "retained validator cluster identity changed; run reset.sh --yes"
        mint=$(demo_metadata_value '.mintAddress')
        supply=$($spl --url "$DEMO_RPC_URL" supply "$mint")
        case "$supply" in ''|*[!0-9]*) demo_die "retained mint is unavailable" ;; esac
        return 0
    fi
    fixture=$DEMO_RUNTIME_DIR/fixture.json
    demo_validator "$DEMO_ROOT/scripts/solana/phase7b-fixture.sh" > "$fixture"
    chmod 600 "$fixture"
    cluster=$(jq -er '.clusterIdentity' "$fixture")
    mint=$(jq -er '.mintAddress' "$fixture")
    user1=$(jq -er '.destinationOwner' "$fixture")
    user2=$(jq -er '.transferDestinationOwner' "$fixture")
    admin=$(jq -er '.redemptionOwner' "$fixture")
    for owner in "$user1" "$user2" "$admin"; do
        $spl --url "$DEMO_RPC_URL" --program-id TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA \
            --fee-payer "$fee_key" --output json create-account "$mint" \
            --owner "$owner" >/dev/null
    done
    jq -n --arg cluster "$cluster" \
        '{network:"LOCAL_SOLANA",clusterIdentity:$cluster,rpc:"http://127.0.0.1:8899",agave:"4.1.2",splTokenCli:"5.6.1",tokenProgram:"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",ataProgram:"ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"}' \
        > "$DEMO_RUNTIME_DIR/cluster.json"
    jq '{mintAddress,userOneOwner:.destinationOwner,userTwoOwner:.transferDestinationOwner,adminOwner:.redemptionOwner,feePayer:.feePayerPublicKey,mintAuthority:.mintAuthorityPublicKey,decimals:2,initialSupplyAtomic:"0"}' \
        "$fixture" > "$DEMO_RUNTIME_DIR/mint.json"
    chmod 600 "$DEMO_RUNTIME_DIR/cluster.json" "$DEMO_RUNTIME_DIR/mint.json"
}
