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
armed="$out/strategies/armed/validation_a01_market_bracket.qkt"
grep -F 'asset1 = EXNESS:EURUSD EVERY 1m WARMUP 10 BARS' "$armed" >/dev/null
grep -F 'asset5 = EXNESS:EURUSD EVERY 5m WARMUP 10 BARS' "$armed" >/dev/null
grep -F 'm1_fast = ema(asset1.close, 3)' "$armed" >/dev/null
grep -F 'm1_slow = ema(asset1.close, 5)' "$armed" >/dev/null
grep -F 'm5_fast = ema(asset5.close, 3)' "$armed" >/dev/null
grep -F 'm5_slow = ema(asset5.close, 5)' "$armed" >/dev/null
grep -F 'score = (m1_fast - m1_slow) + (m5_fast - m5_slow)' "$armed" >/dev/null
grep -F 'AND score >= 0' "$armed" >/dev/null
grep -F 'AND score < 0' "$armed" >/dev/null
grep -F 'THEN BUY asset1 SIZING 0.01' "$armed" >/dev/null
grep -F 'THEN SELL asset1 SIZING 0.01' "$armed" >/dev/null
grep -F 'AND TRADES.today >= 1' "$armed" >/dev/null
grep -F 'AND POSITION.asset1.holding_duration >= 1' "$armed" >/dev/null
grep -F 'THEN CLOSE asset1' "$armed" >/dev/null
[ "$(grep -Fc 'TRADES.today = 0' "$armed")" -eq 2 ]
[ "$(grep -Fc 'STOP LOSS BY 0.0030, TAKE PROFIT BY 0.0060' "$armed")" -eq 2 ]
[ "$(grep -Fc 'SIZING 0.01' "$armed")" -eq 2 ]
if grep -F 'close > 0' "$armed" >/dev/null; then
    echo 'armed strategy uses a tautological positive-price trigger' >&2
    exit 1
fi

jq -e '
    .schema == "qkt-live-validation-expected-v2" and
    .account.tradeMode == "demo" and .safety.maximumLots == "0.01" and
    (.readOnlyStreams | length) == 2 and
    .armedScenario.symbol == "EXNESS:EURUSD" and
    (.armedScenario.streams | map(.timeframe) == ["1m", "5m"]) and
    all(.armedScenario.streams[]; .symbol == "EXNESS:EURUSD") and
    all(.armedScenario.streams[]; .warmupBars == 10) and
    .armedScenario.quantityLots == "0.01" and
    .armedScenario.maximumEntries == 1 and .armedScenario.maximumExits == 1 and
    .armedScenario.closeWhen == "position!=0 and tradesToday>=1 and holdingDurationSeconds>=1" and
    .armedScenario.buyWhen == "score>=0" and .armedScenario.sellWhen == "score<0" and
    .armedScenario.exitTimeframe == "1m" and .armedScenario.minimumHoldingSeconds == 1 and
    .armedScenario.maximumEntryAnchorDriftPoints == 20
' "$out/expected.json" >/dev/null
jq -e '.credentialsStored == false and .executionState == "prepared" and (.qktDirty | type) == "boolean"' "$out/scenario.json" >/dev/null
if grep -F './cleanup.json' "$out/SHA256SUMS" >/dev/null; then
    echo 'prepared checksum manifest includes the mutable cleanup ledger' >&2
    exit 1
fi
(cd "$out" && sha256sum --check SHA256SUMS >/dev/null)

gbp_out="$tmp/gbp-scenario"
bash "$script" \
    --output "$gbp_out" \
    --id validation_gbp \
    --gateway-url http://127.0.0.1:5001 \
    --expected-login 436804390 \
    --expected-server Exness-MT5Trial9 \
    --expected-balance 100000.22 \
    --expected-leverage 500 \
    --magic 917005 \
    --symbol GBPUSD >/dev/null
gbp_armed="$gbp_out/strategies/armed/validation_gbp_market_bracket.qkt"
grep -F 'asset1 = EXNESS:GBPUSD EVERY 1m WARMUP 10 BARS' "$gbp_armed" >/dev/null
grep -F 'asset5 = EXNESS:GBPUSD EVERY 5m WARMUP 10 BARS' "$gbp_armed" >/dev/null
jq -e '
    .armedScenario.symbol == "EXNESS:GBPUSD" and
    (.armedScenario.streams | all(.symbol == "EXNESS:GBPUSD")) and
    (.readOnlyStreams | all(.symbol == "EXNESS:EURUSD"))
' "$gbp_out/expected.json" >/dev/null
(cd "$gbp_out" && sha256sum --check SHA256SUMS >/dev/null)

if bash "$script" \
    --output "$tmp/unsupported-symbol" \
    --id validation_jpy \
    --gateway-url http://127.0.0.1:5001 \
    --expected-login 1 \
    --expected-server Demo \
    --expected-balance 10000 \
    --expected-leverage 500 \
    --magic 917006 \
    --symbol USDJPY >"$tmp/unsupported-symbol.out" 2>&1; then
    echo 'expected unsupported armed symbol rejection' >&2
    exit 1
fi
grep -F -- '--symbol must be one of: EURUSD, GBPUSD' "$tmp/unsupported-symbol.out" >/dev/null

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
"$cli" parse "$gbp_armed" >/dev/null
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
grep -F -- '--read-only' "$repo_root/scripts/live-validation/run-readonly.sh" >/dev/null
grep -F 'com.qkt.events.StreamCandleEvent' "$repo_root/scripts/live-validation/run-readonly.sh" >/dev/null
grep -F '.counts.mutations == 0' "$repo_root/scripts/live-validation/run-readonly.sh" >/dev/null

container_script="$repo_root/scripts/live-validation/run-container-load.sh"
container_evidence_lib="$repo_root/scripts/live-validation/lib/container-load-evidence.sh"
bash -n "$container_script"
bash -n "$container_evidence_lib"
bash "$container_script" --help | grep -F 'two isolated, read-only QKT containers' >/dev/null
grep -F -- '--network host' "$container_script" >/dev/null
grep -F 'QKT_LATENCY_TRACKING=1' "$container_script" >/dev/null
grep -F 'printf '\''%s\n'\'' "$QKT_BROKER_API_KEY" |' "$container_script" >/dev/null
if rg --quiet 'THEN LOG .* value=.* close=' "$container_script"; then
    echo 'container runner emits unsupported multiple LOG value fields' >&2
    exit 1
fi
grep -F 'repository must be clean' "$container_script" >/dev/null
grep -F 'Docker image is not built from' "$container_script" >/dev/null
grep -F 'broker credential was persisted in retained artifacts' "$container_script" >/dev/null
grep -F 'health-during-peer-restart.jsonl' "$container_script" >/dev/null
grep -F 'com.qkt.events.StreamCandleEvent' "$container_script" >/dev/null
grep -F 'sourceTimeframeMs' "$container_evidence_lib" >/dev/null
grep -F 'transport journal reported dropped records' "$container_script" >/dev/null
grep -F 'transport crossed magic ownership' "$container_script" >/dev/null
grep -F 'daemon control token was persisted' "$container_script" >/dev/null
grep -F 'maxAggregateMemoryKiB' "$container_script" >/dev/null
grep -F 'com.qkt.events.StrategyCandleEvaluatedEvent' "$container_script" >/dev/null
grep -F 'generation 2 re-fired a restored true rule edge' "$container_script" >/dev/null
grep -F 'retained no matched $alias strategy evaluation' "$container_script" >/dev/null
grep -F 'load_started_second=$SECONDS' "$container_script" >/dev/null
grep -F 'elapsed_seconds=$((SECONDS - load_started_second))' "$container_script" >/dev/null
grep -F '[ "$elapsed_seconds" -ge "$next_sample_second" ]' "$container_script" >/dev/null
if rg --quiet 'for second in \$\(seq 1 "\$duration_seconds"\)' "$container_script"; then
    echo 'container runner measures duration by loop iterations instead of wall-clock time' >&2
    exit 1
fi
if rg --quiet -- '-Xmx|-Xms|MaxRAMPercentage|MaxRAM=|--memory(=|[[:space:]])|--cpus(=|[[:space:]])|--pids-limit|--cpuset-cpus' "$container_script"; then
    echo 'container runner adds a JVM or container resource restriction' >&2
    exit 1
fi

comparison_script="$repo_root/scripts/live-validation/compare-golden-replay.sh"
bash -n "$comparison_script"
bash "$comparison_script" --help | grep -F 'full-tick and plain-bar replay evidence' >/dev/null
grep -F 'liveInitialProtectionMatchesCanonicalIntent: true' "$comparison_script" >/dev/null
if rg --quiet 'run_replay tick-resolved|--tick-fills' "$comparison_script"; then
    echo 'legacy comparator invokes unsupported tick-resolved bars' >&2
    exit 1
fi
if bash "$comparison_script" \
    --scenario "$out" \
    --out "$tmp/replay" \
    --cli "$cli" \
    --verify-only >"$tmp/no-golden.out" 2>&1; then
    echo 'expected replay comparison to reject a scenario without live evidence' >&2
    exit 1
fi
grep -F 'required file not found:' "$tmp/no-golden.out" >/dev/null

readonly_comparison_script="$repo_root/scripts/live-validation/compare-readonly-replay.sh"
bash -n "$readonly_comparison_script"
bash "$readonly_comparison_script" --help | grep -F 'plain-bar paper modes' >/dev/null
grep -F 'capture and replay builds differ' "$readonly_comparison_script" >/dev/null
grep -F '.inputSummary.liveTicks == $ticks' "$readonly_comparison_script" >/dev/null
grep -F 'M1/M5 traces differ from live' "$readonly_comparison_script" >/dev/null

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
