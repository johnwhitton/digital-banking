#!/bin/sh
set -eu

state=/home/foundry/state.json
common="--host 0.0.0.0 --port 8545 --chain-id 31337 --accounts 0 --auto-impersonate --silent --state-interval 1"
# Anvil 1.5.1 forbids --init with --dump-state, and anvil_dumpState returns a
# compressed state token that its --state flag does not accept. Start from the
# fixed genesis, load any saved token over stdin, and persist a fresh token when
# Compose sends a normal stop signal.
# shellcheck disable=SC2086
anvil $common --init /config/anvil-genesis.json &
anvil_pid=$!
persist() {
    temporary=$state.tmp
    if cast rpc --rpc-url http://127.0.0.1:8545 anvil_dumpState > "$temporary"; then
        chmod 600 "$temporary"
        mv "$temporary" "$state"
    else
        rm -f -- "$temporary"
    fi
    kill -TERM "$anvil_pid" 2>/dev/null || true
    wait "$anvil_pid" || true
    exit 0
}
trap persist HUP INT TERM

for attempt in 1 2 3 4 5 6 7 8 9 10; do
    if cast chain-id --rpc-url http://127.0.0.1:8545 >/dev/null 2>&1; then
        break
    fi
    if [ "$attempt" -eq 10 ]; then
        echo "Anvil did not become ready for local state initialization" >&2
        exit 1
    fi
    sleep 1
done

if [ -s "$state" ]; then
    {
        printf '['
        cat "$state"
        printf ']'
    } | cast rpc --rpc-url http://127.0.0.1:8545 --raw anvil_loadState >/dev/null
fi
touch /tmp/anvil-state-ready
wait "$anvil_pid"
