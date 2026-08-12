#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
    cat <<'EOF'
Usage: run-higher-timeframe-warmup.sh --scenario DIR [--cli PATH] [--verify-only]

Runs a financially read-only live higher-timeframe warmup probe against the
prepared localhost MT5 gateway. It retains closed M15/H1/H4 bar evidence for
one-hour, one-day, and two-day style warmup windows and verifies that no
positions, orders, or venue deals are produced.
EOF
}

fail() {
    printf 'run-higher-timeframe-warmup: %s\n' "$1" >&2
    exit 1
}

scenario=""
cli="$repo_root/build/install/qkt/bin/qkt"
verify_only=false

while [ "$#" -gt 0 ]; do
    case "$1" in
        --scenario) scenario="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --verify-only) verify_only=true; shift ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$scenario" ] || fail "--scenario is required"
scenario="$(realpath "$scenario")"
[ -d "$scenario" ] || fail "scenario directory does not exist: $scenario"
[ -x "$cli" ] || fail "QKT CLI is not executable: $cli"
for command in curl jq rg sha256sum; do
    command -v "$command" >/dev/null || fail "$command is required"
done

for file in qkt.config.yaml expected.json scenario.json SHA256SUMS; do
    [ -f "$scenario/$file" ] || fail "missing prepared artifact: $file"
done
(cd "$scenario" && sha256sum --check SHA256SUMS >/dev/null) ||
    fail "prepared artifact checksum verification failed"

jq -e '
    .schema == "qkt-live-higher-timeframe-warmup-scenario-v1" and
    .credentialsStored == false and
    .executionState == "prepared" and
    (.gatewayUrl | test("^http://127\\.0\\.0\\.1:[0-9]{1,5}$"))
' "$scenario/scenario.json" >/dev/null || fail "scenario metadata failed safety validation"
jq -e '
    .schema == "qkt-live-higher-timeframe-warmup-expected-v1" and
    .financiallyReadOnly == true and
    (.symbol | test("^EXNESS:(EURUSD|GBPUSD|XAUUSD)$"))
' "$scenario/expected.json" >/dev/null || fail "expected contract failed safety validation"
jq -e '
    [.probes[] | .timeframe + ":" + (.bars|tostring)] == [
      "15m:4","15m:96","15m:192",
      "1h:1","1h:24","1h:48",
      "4h:1","4h:6","4h:12"
    ] and
    all(.probes[]; .timeframeMs > 0 and .bars > 0)
' "$scenario/expected.json" >/dev/null || fail "expected probes do not match the reviewed M15/H1/H4 matrix"
grep -F 'api_key: ${QKT_BROKER_API_KEY}' "$scenario/qkt.config.yaml" >/dev/null ||
    fail "config does not resolve credentials at execution time"

if $verify_only; then
    printf 'verified %s\n' "$scenario"
    exit 0
fi

[ -n "${QKT_BROKER_API_KEY:-}" ] || fail "QKT_BROKER_API_KEY is required"
[ -z "$(find "$scenario/evidence" -mindepth 1 -maxdepth 1 -print -quit)" ] ||
    fail "evidence directory is not empty; prepare a fresh scenario"

gateway_url="$(jq -er '.gatewayUrl' "$scenario/scenario.json")"
expected_login="$(jq -er '.account.login' "$scenario/expected.json")"
expected_server="$(jq -er '.account.server' "$scenario/expected.json")"
expected_leverage="$(jq -er '.account.leverage' "$scenario/expected.json")"
expected_balance="$(jq -er '.account.startingBalance' "$scenario/expected.json")"
dsl_symbol="$(jq -er '.symbol' "$scenario/expected.json")"
config="$scenario/qkt.config.yaml"
evidence="$scenario/evidence"
run_started_ms="$(date +%s%3N)"

gateway_get() {
    local path="$1"
    printf 'header = "Authorization: Bearer %s"\n' "$QKT_BROKER_API_KEY" |
        curl --silent --show-error --fail --config - "$gateway_url$path"
}

gateway_get /health > "$evidence/gateway-health.json"
jq -e '.ok == true and .status == "healthy" and .mt5_status == "connected" and .kill_switch_active == false' \
    "$evidence/gateway-health.json" >/dev/null || fail "gateway is not healthy and connected"

gateway_get /account > "$evidence/gateway-account-initial.json"
jq -e \
    --argjson login "$expected_login" \
    --arg server "$expected_server" \
    --argjson leverage "$expected_leverage" \
    --arg balance "$expected_balance" '
        .login == $login and
        .server == $server and
        .trade_mode == 0 and
        .currency == "USD" and
        .leverage == $leverage and
        .balance == ($balance | tonumber) and
        .equity == ($balance | tonumber) and
        .margin == 0 and
        .trade_allowed == true and
        .trade_expert == true
    ' "$evidence/gateway-account-initial.json" >/dev/null || fail "gateway account does not match the flat demo allowlist"

"$cli" bot positions --broker exness --config "$config" --json > "$evidence/positions-initial.json"
"$cli" bot orders --broker exness --config "$config" --json > "$evidence/orders-initial.json"
jq -e 'length == 0' "$evidence/positions-initial.json" >/dev/null || fail "demo account has open positions"
jq -e 'length == 0' "$evidence/orders-initial.json" >/dev/null || fail "demo account has pending orders"

mkdir -p "$evidence/bars"
: > "$evidence/probes.jsonl"
probe_count="$(jq '.probes | length' "$scenario/expected.json")"
for index in $(seq 0 $((probe_count - 1))); do
    timeframe="$(jq -er ".probes[$index].timeframe" "$scenario/expected.json")"
    timeframe_ms="$(jq -er ".probes[$index].timeframeMs" "$scenario/expected.json")"
    warmup_label="$(jq -er ".probes[$index].warmupLabel" "$scenario/expected.json")"
    bars="$(jq -er ".probes[$index].bars" "$scenario/expected.json")"
    evidence_name="${timeframe}-${warmup_label}-${bars}.json"
    "$cli" bot bars "$dsl_symbol" --tf "$timeframe" --count "$bars" --config "$config" --json \
        > "$evidence/bars/$evidence_name"
    observed_at_ms="$(date +%s%3N)"
    jq -e \
        --argjson expectedBars "$bars" \
        --argjson timeframeMs "$timeframe_ms" \
        --argjson observedAtMs "$observed_at_ms" '
            length == $expectedBars and
            all(.[]; (.t % $timeframeMs) == 0 and (.t + $timeframeMs) <= $observedAtMs and
                .o > 0 and .h > 0 and .l > 0 and .c > 0 and .h >= .l and .v >= 0) and
            ([.[].t] == ([.[].t] | sort | unique))
        ' "$evidence/bars/$evidence_name" >/dev/null ||
        fail "$timeframe $warmup_label probe did not return the reviewed closed aligned bar set"
    jq -c \
        --arg timeframe "$timeframe" \
        --arg warmupLabel "$warmup_label" \
        --arg path "bars/$evidence_name" \
        --argjson bars "$bars" \
        --argjson timeframeMs "$timeframe_ms" \
        --argjson observedAtMs "$observed_at_ms" '
            {
              timeframe:$timeframe,
              warmupLabel:$warmupLabel,
              bars:$bars,
              timeframeMs:$timeframeMs,
              warmupPseudoTicks:($bars * 4),
              observedAtMs:$observedAtMs,
              evidencePath:$path,
              firstBarStartMs:.[0].t,
              lastBarStartMs:.[-1].t,
              allClosed:true,
              aligned:true,
              unique:true
            }
        ' "$evidence/bars/$evidence_name" >> "$evidence/probes.jsonl"
done

"$cli" bot positions --broker exness --config "$config" --json > "$evidence/positions-final.json"
"$cli" bot orders --broker exness --config "$config" --json > "$evidence/orders-final.json"
"$cli" bot history --broker exness --since "$run_started_ms" --config "$config" --json > "$evidence/history-during-run.json"
gateway_get /account > "$evidence/gateway-account-final.json"
jq -e 'length == 0' "$evidence/positions-final.json" >/dev/null || fail "higher-timeframe probe ended with an open position"
jq -e 'length == 0' "$evidence/orders-final.json" >/dev/null || fail "higher-timeframe probe ended with a pending order"
jq -e 'length == 0' "$evidence/history-during-run.json" >/dev/null || fail "higher-timeframe probe unexpectedly produced a venue deal"
jq -e --slurpfile initial "$evidence/gateway-account-initial.json" '
    .login == $initial[0].login and .server == $initial[0].server and
    .balance == $initial[0].balance and .equity == $initial[0].equity and
    .margin == 0 and .trade_allowed == true and .trade_expert == true
' "$evidence/gateway-account-final.json" >/dev/null || fail "higher-timeframe probe changed the venue account snapshot"

finished_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
qkt_commit="$(jq -er '.qktCommit' "$scenario/scenario.json")"
qkt_dirty="$(jq -r '.qktDirty' "$scenario/scenario.json")"
qkt_version="$("$cli" --version)"
gateway_version="$(jq -r '.version' "$evidence/gateway-health.json")"
jq -n \
    --arg finishedAt "$finished_at" \
    --arg qktVersion "$qkt_version" \
    --arg qktCommit "$qkt_commit" \
    --argjson qktDirty "$qkt_dirty" \
    --arg gatewayVersion "$gateway_version" \
    --arg symbol "$dsl_symbol" \
    --slurpfile probes "$evidence/probes.jsonl" '
        {
          schema:"qkt-live-higher-timeframe-warmup-result-v1",
          status:"passed",
          finishedAt:$finishedAt,
          qktVersion:$qktVersion,
          qktCommit:$qktCommit,
          qktDirty:$qktDirty,
          gatewayVersion:$gatewayVersion,
          symbol:$symbol,
          financiallyReadOnly:true,
          probes:$probes,
          coverage:{
            timeframes:["15m","1h","4h"],
            warmupLabels:["one-hour","four-hours","one-day","two-days"],
            closedBars:true,
            alignedBars:true,
            uniqueBars:true,
            accountUnchanged:true,
            venueDealsDuringRun:0
          },
          finalPositions:0,
          finalOrders:0
        }
    ' > "$evidence/result.json"

(
    cd "$scenario"
    find . -type f ! -path './RUN-SHA256SUMS' ! -path './SHA256SUMS' -print0 |
        sort -z |
        xargs -0 sha256sum > RUN-SHA256SUMS
)

if printf '%s' "$QKT_BROKER_API_KEY" | rg --text --fixed-strings --quiet -f - "$scenario"; then
    fail "broker credential was persisted in retained artifacts"
fi
if [ -e "$scenario/state/control.token" ] || [ -e "$scenario/state/daemon.pid" ]; then
    fail "daemon control artifacts were created during a read-only bot-bars probe"
fi

printf 'passed %s\n' "$evidence/result.json"
