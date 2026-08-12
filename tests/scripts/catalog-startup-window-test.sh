#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
startup_window="$repo_root/scripts/live-validation/lib/catalog-startup-window.sh"
catalog_evidence="$repo_root/scripts/live-validation/lib/catalog-evidence.sh"
runner="$repo_root/scripts/live-validation/run-readonly-catalog-containers.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

bash -n "$startup_window"
bash -n "$catalog_evidence"
bash -n "$runner"

# shellcheck source=scripts/live-validation/lib/catalog-startup-window.sh
source "$startup_window"
[ "$(qkt_catalog_startup_delay_ms 0)" -eq 90000 ]
[ "$(qkt_catalog_startup_delay_ms 47437)" -eq 42563 ]
[ "$(qkt_catalog_startup_delay_ms 89999)" -eq 1 ]
[ "$(qkt_catalog_startup_delay_ms 90000)" -eq 0 ]
[ "$(qkt_catalog_startup_delay_ms 150000)" -eq 0 ]
[ "$(qkt_catalog_startup_delay_ms 150001)" -eq 239999 ]
[ "$(qkt_catalog_startup_delay_ms 299999)" -eq 90001 ]

if qkt_catalog_startup_delay_ms 300000 >/dev/null; then
    echo 'expected an out-of-range startup phase to fail closed' >&2
    exit 1
fi
if qkt_catalog_startup_delay_ms invalid >/dev/null; then
    echo 'expected a malformed startup phase to fail closed' >&2
    exit 1
fi

grep -F 'gateway_get /symbol_info_tick/EURUSDm' "$runner" >/dev/null
grep -F 'tick_age_ms' "$runner" >/dev/null
grep -F 'max_total_wait_seconds=260' "$runner" >/dev/null
grep -F 'maxObservations:3' "$runner" >/dev/null
grep -F 'startup-deploy-failure.log' "$runner" >/dev/null
grep -F 'startupWindow:$startupWindow[0]' "$runner" >/dev/null

# shellcheck source=scripts/live-validation/lib/catalog-evidence.sh
source "$catalog_evidence"
audit="$tmp/audit.jsonl"
cat > "$audit" <<'EOF'
{"ts":1,"eventType":"com.qkt.events.StreamCandleEvent","symbol":"EXNESS:EURUSD","timeframe":"1m","candle":{"startTimeMs":0,"endTimeMs":60000}}
{"ts":2,"eventType":"com.qkt.events.StrategyCandleEvaluatedEvent","strategyId":"catalog","alias":"eur1","symbol":"EXNESS:EURUSD","timeframe":"1m","rulesEvaluated":1,"candle":{"startTimeMs":0,"endTimeMs":60000}}
{"ts":3,"eventType":"com.qkt.events.StreamCandleEvent","symbol":"EXNESS:EURUSD","timeframe":"5m","candle":{"startTimeMs":0,"endTimeMs":300000}}
{"ts":4,"eventType":"com.qkt.events.StrategyCandleEvaluatedEvent","strategyId":"catalog","alias":"eur5","symbol":"EXNESS:EURUSD","timeframe":"5m","rulesEvaluated":0,"candle":{"startTimeMs":0,"endTimeMs":300000}}
{"ts":5,"eventType":"com.qkt.events.StrategyCandleEvaluatedEvent","strategyId":"catalog","alias":"bad","symbol":"EXNESS:EURUSD","timeframe":"5m","rulesEvaluated":0,"candle":{"startTimeMs":300000,"endTimeMs":600000}}
EOF
[ "$(qkt_catalog_matched_evaluation_count catalog eur1 EXNESS:EURUSD 1m rule-driver "$audit")" -eq 1 ]
[ "$(qkt_catalog_matched_evaluation_count catalog eur5 EXNESS:EURUSD 5m dependency "$audit")" -eq 1 ]
[ "$(qkt_catalog_matched_evaluation_count catalog eur5 EXNESS:EURUSD 5m rule-driver "$audit")" -eq 0 ]
[ "$(qkt_catalog_matched_evaluation_count catalog bad EXNESS:EURUSD 5m dependency "$audit")" -eq 0 ]
if qkt_catalog_matched_evaluation_count catalog eur1 EXNESS:EURUSD 1m invalid "$audit" >/dev/null; then
    echo 'expected an invalid evaluation role to fail closed' >&2
    exit 1
fi

before_log="$tmp/runtime-before.log"
complete_log="$tmp/runtime-complete.log"
cat > "$before_log" <<'EOF'
00:00:01 ERROR [catalog] com.qkt.marketdata.MarketDataGate - market data for EXNESS:EURUSD STALE: age 10001ms exceeds threshold 10000ms
00:00:02 INFO [catalog] com.qkt.marketdata.MarketDataGate - market data for EXNESS:EURUSD healthy again
EOF
cp "$before_log" "$complete_log"
printf '%s\n' '00:00:03 WARN LiveTickFeed source disconnected; waiting up to 120000ms for reconnect' >> "$complete_log"
qkt_catalog_runtime_log_summary "$before_log" "$complete_log" > "$tmp/runtime-summary.json"
jq -e '.staleEvents == 1 and .recoveredStaleEvents == 1 and .inWindowDisconnectWarnings == 0 and
    .shutdownDisconnectWarnings == 1 and .postBoundaryStaleEvents == 0 and
    .unexpectedErrors == 0 and .allStaleEpisodesRecovered == true' \
    "$tmp/runtime-summary.json" >/dev/null

head -n1 "$before_log" > "$tmp/unrecovered.log"
if qkt_catalog_runtime_log_summary "$tmp/unrecovered.log" "$tmp/unrecovered.log" >/dev/null; then
    echo 'expected an unrecovered stale episode to fail closed' >&2
    exit 1
fi
cat > "$tmp/cross-symbol.log" <<'EOF'
00:00:01 ERROR [catalog] com.qkt.marketdata.MarketDataGate - market data for EXNESS:EURUSD STALE: age 10001ms exceeds threshold 10000ms
00:00:02 INFO [catalog] com.qkt.marketdata.MarketDataGate - market data for EXNESS:GBPUSD healthy again
EOF
if qkt_catalog_runtime_log_summary "$tmp/cross-symbol.log" "$tmp/cross-symbol.log" >/dev/null; then
    echo 'expected a recovery for the wrong symbol to fail closed' >&2
    exit 1
fi
: > "$tmp/empty.log"
if qkt_catalog_runtime_log_summary "$tmp/empty.log" "$tmp/unrecovered.log" >/dev/null; then
    echo 'expected a stale event after the shutdown boundary capture to fail closed' >&2
    exit 1
fi
printf '%s\n' '00:00:00 WARN LiveTickFeed source disconnected unexpectedly' > "$tmp/disconnected.log"
if qkt_catalog_runtime_log_summary "$tmp/disconnected.log" "$tmp/disconnected.log" >/dev/null; then
    echo 'expected an in-window disconnect to fail closed' >&2
    exit 1
fi
printf '%s\n' '00:00:00 ERROR unrelated runtime failure' > "$tmp/error.log"
if qkt_catalog_runtime_log_summary "$tmp/error.log" "$tmp/error.log" >/dev/null; then
    echo 'expected an unrelated runtime error to fail closed' >&2
    exit 1
fi

printf 'catalog-startup-window-test: passed\n'
