#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
runner="$repo_root/scripts/live-validation/run-order-gateway-restart.sh"
prepare="$repo_root/scripts/live-validation/prepare-scenario.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fail() {
    printf 'run-order-gateway-restart-test: %s\n' "$1" >&2
    exit 1
}

fake_cli="$work/qkt"
cat > "$fake_cli" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
    parse) exit 0 ;;
    --version) printf 'qkt test (00000000)\n' ;;
    *) printf 'unexpected fake qkt call: %s\n' "$*" >&2; exit 1 ;;
esac
EOF
chmod +x "$fake_cli"

bash -n "$runner"
bash "$runner" --help | grep -F 'strategy-owned demo position' >/dev/null

scenario="$work/scenario"
bash "$prepare" \
    --output "$scenario" \
    --id order_restart_fixture \
    --gateway-url http://127.0.0.1:18080 \
    --expected-login 123456 \
    --expected-server Demo-Server \
    --expected-balance 10000 \
    --expected-leverage 200 \
    --magic 765455 >/dev/null

verify_output="$(bash "$runner" --scenario "$scenario" --gateway-container gateway-fixture --cli "$fake_cli" --verify-only)"
[[ "$verify_output" == *"order_restart_fixture_bars_readonly"* ]] || fail "verify-only did not retain the prepared read-only strategy name"
[[ "$verify_output" == *"order_restart_fixture_market_bracket"* ]] || fail "verify-only did not retain the prepared armed strategy name"
[[ "$verify_output" == *"gateway-fixture"* ]] || fail "verify-only did not retain the requested gateway container"

for required in \
    '--gateway-container is required' \
    'missing exact --arm confirmation' \
    'QKT_LIVE_DEMO_ORDER_APPROVAL must equal LOCALHOST_DEMO_ONLY' \
    'qkt-live-order-gateway-restart-startup-window-v1' \
    'wait_for_matched_evaluations pre-restart-readonly "$readonly_name" EXNESS:EURUSD eur1 eur5 "$session_started_ms"' \
    'wait_for_matched_evaluations post-restart-readonly "$readonly_name" EXNESS:EURUSD eur1 eur5 "$restart_ready_ms"' \
    'wait_for_matched_evaluations post-restart-armed "$armed_name" "$armed_symbol" asset1 asset5 "$restart_ready_ms" any' \
    'daemon_started_ms="$(date +%s%3N)"' \
    'control_port="$(<"$scenario/state/control.port")"' \
    'curl --silent --show-error --fail "http://127.0.0.1:$control_port/latency" > "$evidence/latency.json"' \
    'qkt_count_warmup_pseudo_ticks EXNESS:EURUSD 60000 "$daemon_started_ms" "$restart_started_ms"' \
    'qkt_count_warmup_pseudo_ticks EXNESS:EURUSD 300000 "$daemon_started_ms" "$restart_started_ms"' \
    'count_matched_evaluations_any' \
    'docker restart "$gateway_container"' \
    'gateway restart did not retain the owned open ticket long enough to observe or close after reconnect' \
    'strategy-owned close did not complete after reconnect within $timeout_seconds seconds' \
    'order gateway restart retained no feed reconnect info' \
    'read-only sibling emitted an order, fill, accounting, linkage, or rejection event' \
    'armed strategy retained more than one failed close mutation after reconnect' \
    'armed strategy did not retain exactly one successful strategy close' \
    'closeAfterRestart:true' \
    'successfulClosePosts:' \
    'failedClosePosts:' \
    'retryCloseSucceeded:' \
    'positionPersistedAcrossRestart:$positionPersistedAcrossRestart' \
    'schema:"qkt-live-validation-order-gateway-restart-v1"' \
    'API_KEY=<redacted>' \
    'MT5_PASSWORD=<redacted>' \
    'MT5_LOGIN=<redacted>' \
    'MT5_SERVER=<redacted>' \
    'qkt_write_safe_gateway_health_snapshot "$evidence/gateway-health-initial.json"' \
    'qkt_write_safe_gateway_health_snapshot "$output_path"' \
    'qkt_redact_account_identity_log "$evidence/preflight-readonly.log" "$expected_login" "$expected_server"' \
    'qkt_redact_account_identity_log "$evidence/preflight-armed.log" "$expected_login" "$expected_server"' \
    "rg --text --pcre2 --quiet 'MT5_PASSWORD=(?!<redacted>)' \"\$scenario\"" \
    'gateway password metadata was persisted in the scenario artifacts' \
    'QKT_LATENCY_TRACKING=1 "$cli" daemon start'; do
    rg --fixed-strings --quiet -- "$required" "$runner" || fail "missing order-restart contract: $required"
done

if rg --quiet -- '-Xmx|-Xms|MaxRAMPercentage|--memory(=|[[:space:]])|--cpus(=|[[:space:]])|--pids-limit|--cpuset-cpus' "$runner"; then
    fail "runner contains a JVM or Docker resource restriction"
fi

printf 'run-order-gateway-restart-test: passed\n'
