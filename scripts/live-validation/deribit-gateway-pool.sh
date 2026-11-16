#!/usr/bin/env bash
# Extra gateway processes on the Deribit testnet account, one per kill_switch case of the derivatives lane.
#
#   deribit-gateway-pool.sh up     [--count N] [--first-port P]    start (or restart) the pool, wait until each is healthy
#   deribit-gateway-pool.sh urls   [--count N] [--first-port P]    print the run-attestation-catalog.sh flags that name them
#   deribit-gateway-pool.sh status [--count N] [--first-port P]    one line per gateway: running, account, kill switch
#   deribit-gateway-pool.sh down   [--count N] [--first-port P]    stop and remove the pool and its state volumes
#
# The kill switch is per gateway process, and a daemon refuses to start while one is on, so a case that engages it
# needs a gateway to itself while the lane's other cases run (lib/deriv_parallel.py picks the case per gateway, in
# case-id order). Every gateway in the pool is the lane's own gateway again: same image, same Deribit testnet key,
# same trader and guardian tokens, its own port and state volume. N is the number of ready kill_switch cases in
# attestation/cases/derivatives (default: counted from there; 2 today).
#
# Nothing here prints a credential. The key reaches the containers as the environment of `with-deribit-key.sh`
# (QKT_SOAK_DIR/bin), the tokens as the environment of this script, as scripts for the lane's own gateway do
# (/var/tmp/qkt-work/lanes-gw-up-032.sh). Run it where the lane's gateway runs, before the release's catalog, and
# `down` it after.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
soak="${QKT_SOAK_DIR:-$HOME/qkt-testnet-soak}"
image="${QKT_GATEWAY_IMAGE:-ghcr.io/elitekaycy/qkt-venue-gateway:0.3.2}"
prefix="${QKT_DRILL_GATEWAY_PREFIX:-qkt-lanes-gw-deribit-drill}"
command="${1:-}"; [ "$#" -eq 0 ] || shift
count=""; first_port=18454
while [ "$#" -gt 0 ]; do
    case "$1" in
        --count) count="${2:-}"; shift 2 ;;
        --first-port) first_port="${2:-}"; shift 2 ;;
        *) echo "deribit-gateway-pool: unknown argument: $1" >&2; exit 2 ;;
    esac
done
if [ -z "$count" ]; then
    count="$(python3 - "$repo_root" <<'PY'
import os, sys
sys.path.insert(0, os.path.join(sys.argv[1], "scripts/live-validation/lib"))
import deriv_parallel
print(sum(1 for _, doc in deriv_parallel.ready_cases(os.path.join(sys.argv[1], "attestation/cases/derivatives"))
          if deriv_parallel.kill_switch(doc)))
PY
)"
fi
[[ "$count" =~ ^[0-9]+$ ]] || { echo "deribit-gateway-pool: --count must be a number" >&2; exit 2; }
[[ "$first_port" =~ ^[0-9]+$ ]] || { echo "deribit-gateway-pool: --first-port must be a number" >&2; exit 2; }

each() {  # command...: runs it with NAME and PORT set, once per gateway
    local n
    for n in $(seq 1 "$count"); do NAME="$prefix$n" PORT=$((first_port + n - 1)) "$@"; done
}
health() {  # port: /v1/health, the trader token read from its file and handed to curl on stdin, never in an argument
    printf 'header = "Authorization: Bearer %s"\n' "$(cat "$soak/state/trader.token")" |
        curl --silent --fail --max-time 10 -K - "http://127.0.0.1:$1/v1/health"
}
start_one() {
    docker rm -f "$NAME" > /dev/null 2>&1 || true
    docker volume rm -f "$NAME-state" > /dev/null 2>&1 || true
    docker run -d --name "$NAME" --restart unless-stopped --network host -e "GATEWAY_LISTEN=127.0.0.1:$PORT" \
        -v "$NAME-state:/data" -e GATEWAY_ADAPTER=deribit -e GATEWAY_TRADER_TOKEN -e GATEWAY_GUARDIAN_TOKEN \
        -e GATEWAY_LOGIN -e GATEWAY_SECRET -e GATEWAY_SETTING_ENVIRONMENT=testnet "$image" > /dev/null
    echo "started $NAME :$PORT"
}
wait_one() {
    local tries=60
    until health "$PORT" > /dev/null 2>&1; do
        tries=$((tries - 1)); [ "$tries" -gt 0 ] || { echo "deribit-gateway-pool: $NAME never became healthy" >&2; return 1; }
        sleep 2
    done
    echo "healthy $NAME :$PORT"
}
status_one() {
    local state account
    state="$(docker inspect -f '{{.State.Status}}' "$NAME" 2> /dev/null || echo absent)"
    account="$(health "$PORT" 2> /dev/null | python3 -c 'import json,sys; h=json.load(sys.stdin); print("login=%s link=%s kill=%s" % (h.get("account_login"), h.get("venue_connected"), h.get("kill_switch")))' 2> /dev/null || echo unreachable)"
    echo "$NAME :$PORT $state $account"
}
stop_one() {
    docker rm -f "$NAME" > /dev/null 2>&1 || true
    docker volume rm -f "$NAME-state" > /dev/null 2>&1 || true
    echo "removed $NAME"
}

case "$command" in
    up)
        export GATEWAY_TRADER_TOKEN GATEWAY_GUARDIAN_TOKEN
        GATEWAY_TRADER_TOKEN="$(cat "$soak/state/trader.token")"; GATEWAY_GUARDIAN_TOKEN="$(cat "$soak/state/guardian.token")"
        export -f start_one; export image prefix
        "$soak/bin/with-deribit-key.sh" bash -c 'export GATEWAY_LOGIN="$DERIBIT_CLIENT_ID" GATEWAY_SECRET="$DERIBIT_CLIENT_SECRET"
            for n in $(seq 1 '"$count"'); do NAME="'"$prefix"'$n" PORT=$(('"$first_port"' + n - 1)) start_one; done'
        each wait_one ;;
    urls)
        flags=(); for n in $(seq 1 "$count"); do flags+=("--deriv-drill-gateway-url" "http://127.0.0.1:$((first_port + n - 1))"); done
        echo "${flags[*]}" ;;
    status) each status_one ;;
    down) each stop_one ;;
    *) sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2; exit 2 ;;
esac
