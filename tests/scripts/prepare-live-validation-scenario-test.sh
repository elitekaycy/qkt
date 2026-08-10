#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

script="$repo_root/scripts/live-validation/prepare-scenario.sh"
out="$tmp/scenario"

bash "$script" \
    --output "$out" \
    --id validation_a01 \
    --gateway-url http://127.0.0.1:5001/ \
    --expected-login 436804390 \
    --expected-server Exness-MT5Trial9 \
    --expected-balance 100000.22 \
    --expected-leverage 500 \
    --magic 917001 >/dev/null

test -f "$out/qkt.config.yaml"
test -f "$out/strategies/readonly/validation_a01_bars_readonly.qkt"
test -f "$out/strategies/armed/validation_a01_market_bracket.qkt"
test -f "$out/expected.json"
test -f "$out/cleanup.json"
test -f "$out/scenario.json"
test -f "$out/SHA256SUMS"

grep -F 'gateway_url: http://127.0.0.1:5001' "$out/qkt.config.yaml" >/dev/null
grep -F 'api_key: ${QKT_BROKER_API_KEY}' "$out/qkt.config.yaml" >/dev/null
if grep -F 'expected_leverage:' "$out/qkt.config.yaml" >/dev/null; then
    echo 'generated config treats dynamic venue leverage as immutable identity' >&2
    exit 1
fi
grep -F 'max_order_qty: "0.01"' "$out/qkt.config.yaml" >/dev/null
grep -F 'max_trades_per_day: 1' "$out/qkt.config.yaml" >/dev/null
grep -F 'book_risk:' "$out/qkt.config.yaml" >/dev/null
grep -F 'EVERY 1m' "$out/strategies/readonly/validation_a01_bars_readonly.qkt" >/dev/null
grep -F 'EVERY 5m' "$out/strategies/readonly/validation_a01_bars_readonly.qkt" >/dev/null
grep -F 'TRADES.today = 0' "$out/strategies/armed/validation_a01_market_bracket.qkt" >/dev/null
grep -F 'STOP LOSS BY 0.0030, TAKE PROFIT BY 0.0060' "$out/strategies/armed/validation_a01_market_bracket.qkt" >/dev/null

jq -e '.account.tradeMode == "demo" and .safety.maximumLots == "0.01"' "$out/expected.json" >/dev/null
jq -e '.credentialsStored == false and .executionState == "prepared" and (.qktDirty | type) == "boolean"' "$out/scenario.json" >/dev/null
(cd "$out" && sha256sum --check SHA256SUMS >/dev/null)

if bash "$script" \
    --output "$tmp/remote" \
    --id validation_a02 \
    --gateway-url https://example.test \
    --expected-login 1 \
    --expected-server Demo \
    --expected-balance 10000 \
    --expected-leverage 500 \
    --magic 917002 >"$tmp/remote.out" 2>&1; then
    echo 'expected remote gateway rejection' >&2
    exit 1
fi
grep -F 'must be an explicit http://127.0.0.1:PORT endpoint' "$tmp/remote.out" >/dev/null

if bash "$script" \
    --output "$out" \
    --id validation_a03 \
    --gateway-url http://127.0.0.1:5001 \
    --expected-login 1 \
    --expected-server Demo \
    --expected-balance 10000 \
    --expected-leverage 500 \
    --magic 917003 >"$tmp/nonempty.out" 2>&1; then
    echo 'expected non-empty output rejection' >&2
    exit 1
fi
grep -F 'already exists and is not empty' "$tmp/nonempty.out" >/dev/null

if bash "$script" \
    --output "$tmp/zero" \
    --id validation_a04 \
    --gateway-url http://127.0.0.1:5001 \
    --expected-login 1 \
    --expected-server Demo \
    --expected-balance 0.00 \
    --expected-leverage 500 \
    --magic 917004 >"$tmp/zero.out" 2>&1; then
    echo 'expected zero starting-balance rejection' >&2
    exit 1
fi
grep -F 'must be greater than zero' "$tmp/zero.out" >/dev/null

cli="$repo_root/build/install/qkt/bin/qkt"
test -x "$cli"
"$cli" parse "$out/strategies/readonly/validation_a01_bars_readonly.qkt" >/dev/null
"$cli" parse "$out/strategies/armed/validation_a01_market_bracket.qkt" >/dev/null
bash "$repo_root/scripts/live-validation/run-readonly.sh" \
    --scenario "$out" \
    --cli "$cli" \
    --verify-only >/dev/null
bash "$repo_root/scripts/live-validation/run-market-bracket.sh" \
    --scenario "$out" \
    --cli "$cli" \
    --verify-only >/dev/null
if rg --quiet '\$cli" status .*--json' "$repo_root/scripts/live-validation/run-market-bracket.sh"; then
    echo 'order runner passes unsupported --json to qkt status' >&2
    exit 1
fi
grep -F 'com.qkt.events.BrokerEvent.OrderAccepted' "$repo_root/scripts/live-validation/run-market-bracket.sh" >/dev/null
grep -F 'com.qkt.events.BrokerEvent.OrderFilled' "$repo_root/scripts/live-validation/run-market-bracket.sh" >/dev/null
if rg --quiet 'Order(Accepted|Filled)Event' "$repo_root/scripts/live-validation/run-market-bracket.sh"; then
    echo 'order runner uses obsolete broker audit event names' >&2
    exit 1
fi
grep -F "|| printf '0\\n'" "$repo_root/scripts/live-validation/run-market-bracket.sh" >/dev/null
grep -F 'golden capture' "$repo_root/scripts/live-validation/run-market-bracket.sh" >/dev/null
grep -F '.captureGitSha as $capture' "$repo_root/scripts/live-validation/run-market-bracket.sh" >/dev/null
grep -F 'maxDroppedTicks' "$repo_root/scripts/live-validation/run-readonly.sh" >/dev/null
grep -F 'dropped live tick(s)' "$repo_root/scripts/live-validation/run-readonly.sh" >/dev/null
grep -F 'TICK_PROCESSING.count > 0' "$repo_root/scripts/live-validation/run-readonly.sh" >/dev/null

comparison_script="$repo_root/scripts/live-validation/compare-golden-replay.sh"
bash -n "$comparison_script"
bash "$comparison_script" --help | grep -F 'full-tick, bar, and tick-resolved replay evidence' >/dev/null
if bash "$comparison_script" \
    --scenario "$out" \
    --out "$tmp/replay" \
    --cli "$cli" \
    --verify-only >"$tmp/no-golden.out" 2>&1; then
    echo 'expected replay comparison to reject a scenario without live evidence' >&2
    exit 1
fi
grep -F 'required file not found:' "$tmp/no-golden.out" >/dev/null

if bash "$repo_root/scripts/live-validation/run-market-bracket.sh" \
    --scenario "$out" \
    --cli "$cli" \
    --timeout-seconds 60 >"$tmp/unarmed.out" 2>&1; then
    echo 'expected unarmed order-runner rejection' >&2
    exit 1
fi
grep -F 'missing exact --arm confirmation' "$tmp/unarmed.out" >/dev/null

if bash "$repo_root/scripts/live-validation/run-readonly.sh" \
    --scenario "$out" \
    --cli "$cli" \
    --duration-seconds 30 >"$tmp/short.out" 2>&1; then
    echo 'expected short observation rejection' >&2
    exit 1
fi
grep -F 'must be at least 310' "$tmp/short.out" >/dev/null

printf '\n# tampered\n' >> "$out/qkt.config.yaml"
if bash "$repo_root/scripts/live-validation/run-readonly.sh" \
    --scenario "$out" \
    --cli "$cli" \
    --verify-only >"$tmp/tampered.out" 2>&1; then
    echo 'expected tampered scenario rejection' >&2
    exit 1
fi
grep -F 'prepared artifact checksum verification failed' "$tmp/tampered.out" >/dev/null
