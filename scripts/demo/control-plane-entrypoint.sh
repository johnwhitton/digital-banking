#!/bin/sh
set -eu

load_local_keys() {
    file=/run/config/local-anvil.properties
    [ -f "$file" ] || { printf '%s\n' "Local demo wallet configuration is unavailable." >&2; exit 1; }
    count=0
    while IFS='=' read -r name value; do
        case "$name" in
            LOCAL_DEMO_*_PRIVATE_KEY)
                [ "${#value}" -eq 66 ] || exit 1
                case "$value" in 0x*) ;; *) exit 1 ;; esac
                key_hex=${value#0x}
                case "$key_hex" in *[!0-9a-fA-F]*) exit 1 ;; esac
                key_hex=
                count=$((count + 1))
                ;;
            LOCAL_DEMO_*_EXPECTED_ADDRESS|LOCAL_DEMO_ADMIN_REDEMPTION_KEY_ALIAS|LOCAL_DEMO_CHAIN_ID)
                ;;
            ''|'#'*) ;;
            *) printf '%s\n' "Unexpected local demo wallet configuration field." >&2; exit 1 ;;
        esac
    done < "$file"
    [ "$count" -eq 10 ] || { printf '%s\n' "Local demo wallet configuration is incomplete." >&2; exit 1; }
}

load_deployment() {
    file=/run/config/deployment.properties
    [ -f "$file" ] || { printf '%s\n' "Verified deployment metadata is unavailable." >&2; exit 1; }
    contract=$(awk -F= '$1 == "LOCAL_ETHEREUM_CONTRACT_ADDRESS" { print $2 }' "$file")
    chain=$(awk -F= '$1 == "LOCAL_DEMO_CHAIN_ID" { print $2 }' "$file")
    case "$contract" in 0x????????????????????????????????????????) ;; *) exit 1 ;; esac
    [ "$chain" = 31337 ] || exit 1
}

load_local_keys
load_deployment
[ -s /run/configtree/spring.datasource.password ] \
    || { printf '%s\n' "Database secret is unavailable." >&2; exit 1; }
exec java -XX:+ExitOnOutOfMemoryError -jar /app/application.jar
