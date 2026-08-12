#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
scenario_prepare="$repo_root/scripts/live-validation/prepare-scenario.sh"

usage() {
    cat <<'EOF'
Usage: prepare-generated-parity-wave.sh --output DIR --id ID --gateway-url URL \
  --expected-login N --expected-server NAME --expected-balance DECIMAL \
  --expected-leverage N --magic-base N [--cli PATH]

Prepares a first generated order-bearing live/replay parity wave using four
bounded 0.01-lot scenarios built for the existing read-only, live, and replay
validation runners. No gateway request is made and no credential is accepted or
retained.
EOF
}

fail() {
    printf 'prepare-generated-parity-wave: %s\n' "$1" >&2
    exit 1
}

output=""
suite_id=""
gateway_url=""
expected_login=""
expected_server=""
expected_balance=""
expected_leverage=""
magic_base=""
cli="$repo_root/build/install/qkt/bin/qkt"

while [ "$#" -gt 0 ]; do
    case "$1" in
        --output) output="${2:-}"; shift 2 ;;
        --id) suite_id="${2:-}"; shift 2 ;;
        --gateway-url) gateway_url="${2:-}"; shift 2 ;;
        --expected-login) expected_login="${2:-}"; shift 2 ;;
        --expected-server) expected_server="${2:-}"; shift 2 ;;
        --expected-balance) expected_balance="${2:-}"; shift 2 ;;
        --expected-leverage) expected_leverage="${2:-}"; shift 2 ;;
        --magic-base) magic_base="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$output" ] || fail "--output is required"
[[ "$suite_id" =~ ^[a-z][a-z0-9_]{2,47}$ ]] || fail "--id must be a lowercase DSL identifier"
[[ "$gateway_url" =~ ^http://127\.0\.0\.1:[0-9]{1,5}/?$ ]] ||
    fail "--gateway-url must be an explicit http://127.0.0.1:PORT endpoint"
gateway_url="${gateway_url%/}"
[[ "$expected_login" =~ ^[1-9][0-9]*$ ]] || fail "--expected-login must be a positive integer"
[[ "$expected_server" =~ ^[A-Za-z0-9._-]+$ ]] || fail "--expected-server contains unsupported characters"
[[ "$expected_balance" =~ ^[0-9]+([.][0-9]+)?$ ]] || fail "--expected-balance must be a decimal"
[[ ! "$expected_balance" =~ ^0+([.]0+)?$ ]] || fail "--expected-balance must be greater than zero"
[[ "$expected_leverage" =~ ^[1-9][0-9]*$ ]] || fail "--expected-leverage must be a positive integer"
[[ "$magic_base" =~ ^[1-9][0-9]*$ ]] || fail "--magic-base must be a positive integer"
[ "$magic_base" -le 2147483644 ] || fail "--magic-base plus three must fit a signed 32-bit integer"
[ -x "$cli" ] || fail "QKT CLI is not executable: $cli"
[ ! -e "$output" ] || fail "output already exists: $output"

output="$(realpath -m "$output")"
git_sha="$(git -C "$repo_root" rev-parse HEAD)"

mkdir -m 700 -p "$output/cases"

case_ids=("ema-eurusd" "rsi-gbpusd" "atr-eurusd" "case-gbpusd")
symbols=("EURUSD" "GBPUSD" "EURUSD" "GBPUSD")
variants=("ema_cross" "rsi_reversion" "atr_channel" "case_math")

for index in 0 1 2 3; do
    case_id="${case_ids[$index]}"
    symbol="${symbols[$index]}"
    variant="${variants[$index]}"
    magic="$((magic_base + index))"
    scenario_id="${suite_id}_$(printf '%s' "$case_id" | tr '-' '_')"
    scenario_output="$output/cases/$case_id"

    bash "$scenario_prepare" \
        --output "$scenario_output" \
        --id "$scenario_id" \
        --gateway-url "$gateway_url" \
        --expected-login "$expected_login" \
        --expected-server "$expected_server" \
        --expected-balance "$expected_balance" \
        --expected-leverage "$expected_leverage" \
        --magic "$magic" \
        --symbol "$symbol" \
        --variant "$variant" \
        >/dev/null
done

jq -n \
    --arg suiteId "$suite_id" \
    --arg createdAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg qktCommit "$git_sha" \
    --arg gatewayUrl "$gateway_url" \
    --argjson login "$expected_login" \
    --arg server "$expected_server" \
    --arg balance "$expected_balance" \
    --argjson leverage "$expected_leverage" '
    {
      schema:"qkt-live-generated-parity-wave-v1",
      suiteId:$suiteId,
      createdAt:$createdAt,
      qktCommit:$qktCommit,
      gatewayUrl:$gatewayUrl,
      credentialsStored:false,
      account:{
        login:$login,
        server:$server,
        tradeMode:"demo",
        currency:"USD",
        balance:$balance,
        leverage:$leverage
      },
      contract:{
        cases:4,
        orderBearing:true,
        boundedLots:"0.01",
        requiredFinalPositions:0,
        requiredFinalOrders:0,
        requiredReplayComparison:true,
        liveRunner:"scripts/live-validation/run-market-bracket.sh",
        readOnlyRunner:"scripts/live-validation/run-readonly.sh",
        replayRunner:"scripts/live-validation/compare-golden-replay.sh"
      },
      cases:[
        {
          id:"ema-eurusd",
          path:"cases/ema-eurusd",
          scenarioId:($suiteId + "_ema_eurusd"),
          symbol:"EXNESS:EURUSD",
          variant:"ema_cross",
          magic:('"$magic_base"' + 0)
        },
        {
          id:"rsi-gbpusd",
          path:"cases/rsi-gbpusd",
          scenarioId:($suiteId + "_rsi_gbpusd"),
          symbol:"EXNESS:GBPUSD",
          variant:"rsi_reversion",
          magic:('"$magic_base"' + 1)
        },
        {
          id:"atr-eurusd",
          path:"cases/atr-eurusd",
          scenarioId:($suiteId + "_atr_eurusd"),
          symbol:"EXNESS:EURUSD",
          variant:"atr_channel",
          magic:('"$magic_base"' + 2)
        },
        {
          id:"case-gbpusd",
          path:"cases/case-gbpusd",
          scenarioId:($suiteId + "_case_gbpusd"),
          symbol:"EXNESS:GBPUSD",
          variant:"case_math",
          magic:('"$magic_base"' + 3)
        }
      ]
    }
' > "$output/suite.json"

(
    cd "$output"
    find . -type f ! -path './SHA256SUMS' ! -path './cases/*/cleanup.json' -print0 |
        sort -z |
        xargs -0 sha256sum > SHA256SUMS
)

printf '%s\n' "$output"
