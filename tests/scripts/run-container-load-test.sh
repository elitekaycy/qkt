#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
runner="$repo_root/scripts/live-validation/run-container-load.sh"
evidence_lib="$repo_root/scripts/live-validation/lib/container-load-evidence.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

bash -n "$runner"
bash -n "$evidence_lib"
bash "$runner" --help | grep -F 'eleven-minute minimum' >/dev/null

grep -F 'tick_poll_interval_ms: 500' "$runner" >/dev/null
grep -F 'poll_interval_ms: 5000' "$runner" >/dev/null
grep -F 'must be unset; this run does not restrict or override the JVM' "$runner" >/dev/null
grep -F 'Docker image config restricts or overrides the JVM' "$runner" >/dev/null
grep -F 'jvmOverrideEnvironmentPresent:false' "$runner" >/dev/null
grep -F 'resourceRestrictions:{memoryBytes:.HostConfig.Memory' "$runner" >/dev/null
grep -F 'required_end_second=$((restart_ready_second + 311))' "$runner" >/dev/null
grep -F 'postRestartObservationSeconds' "$runner" >/dev/null
grep -F 'persistedDeploymentRestore:false' "$runner" >/dev/null
grep -F 'case b retained no live tick while case a restarted' "$runner" >/dev/null
grep -F 'case b lacks a $boundary-restart matched candle/evaluation' "$runner" >/dev/null
grep -F 'issued a mutating gateway request' "$runner" >/dev/null
grep -F 'DecisionOrderLinkedEvent' "$runner" >/dev/null
grep -F 'configuredWarmupCounts:true' "$runner" >/dev/null
grep -F 'stateAsync:true' "$runner" >/dev/null
grep -F 'publicationSafe:false,containsPrivateAccountMetadata:true' "$runner" >/dev/null

if rg --quiet -- '-Xmx|-Xms|MaxRAMPercentage|MaxRAM=|--memory(=|[[:space:]])|--cpus(=|[[:space:]])|--pids-limit|--cpuset-cpus' "$runner"; then
    echo 'container runner adds a JVM or Docker resource restriction' >&2
    exit 1
fi
if rg --quiet -- '--env[ =]QKT_BROKER_API_KEY|-e[ =]QKT_BROKER_API_KEY' "$runner"; then
    echo 'container runner places the broker key in Docker configuration' >&2
    exit 1
fi

fixture="$tmp/audit.jsonl"
for ts in 100 101 102 103; do
    printf '{"ts":%s,"eventType":"com.qkt.events.WarmupTickEvent","symbol":"EXNESS:EURUSD","sourceTimeframeMs":60000}\n' "$ts"
done > "$fixture"
cat >> "$fixture" <<'EOF'
{"ts":900,"eventType":"com.qkt.events.StreamCandleEvent","symbol":"EXNESS:EURUSD","timeframe":"1m","candle":{"startTimeMs":600000,"endTimeMs":660000}}
{"ts":901,"eventType":"com.qkt.events.StrategyCandleEvaluatedEvent","strategyId":"container_load_a","alias":"eur1","symbol":"EXNESS:EURUSD","timeframe":"1m","rulesEvaluated":1,"candle":{"startTimeMs":600000,"endTimeMs":660000}}
{"ts":1500,"eventType":"com.qkt.events.TickEvent","symbol":"EXNESS:EURUSD"}
{"ts":2100,"eventType":"com.qkt.events.StreamCandleEvent","symbol":"EXNESS:EURUSD","timeframe":"1m","candle":{"startTimeMs":660000,"endTimeMs":720000}}
{"ts":2101,"eventType":"com.qkt.events.StrategyCandleEvaluatedEvent","strategyId":"container_load_a","alias":"eur1","symbol":"EXNESS:EURUSD","timeframe":"1m","rulesEvaluated":1,"candle":{"startTimeMs":660000,"endTimeMs":720000}}
{"ts":2200,"eventType":"com.qkt.events.StrategyCandleEvaluatedEvent","strategyId":"container_load_a","alias":"eur1","symbol":"EXNESS:EURUSD","timeframe":"1m","rulesEvaluated":1,"candle":{"startTimeMs":720000,"endTimeMs":780000}}
EOF

# shellcheck source=scripts/live-validation/lib/container-load-evidence.sh
source "$evidence_lib"
[ "$(qkt_count_warmup_pseudo_ticks EXNESS:EURUSD 60000 -1 -1 "$fixture")" -eq 4 ]
[ "$(qkt_count_warmup_pseudo_ticks EXNESS:EURUSD 300000 -1 -1 "$fixture")" -eq 0 ]
[ "$(qkt_count_matched_evaluations container_load_a eur1 EXNESS:EURUSD 1m -1 1000 "$fixture")" -eq 1 ]
[ "$(qkt_count_matched_evaluations container_load_a eur1 EXNESS:EURUSD 1m 2000 -1 "$fixture")" -eq 1 ]
[ "$(qkt_count_matched_evaluations container_load_a eur5 EXNESS:EURUSD 5m -1 -1 "$fixture")" -eq 0 ]
[ "$(qkt_count_events_in_window com.qkt.events.TickEvent 1000 2000 "$fixture")" -eq 1 ]

printf 'run-container-load-test: passed\n'
