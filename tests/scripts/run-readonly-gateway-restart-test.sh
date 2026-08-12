#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
runner="$repo_root/scripts/live-validation/run-readonly-gateway-restart.sh"
prepare="$repo_root/scripts/live-validation/prepare-scenario.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fail() {
    printf 'run-readonly-gateway-restart-test: %s\n' "$1" >&2
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
bash "$runner" --help | grep -F 'restarts the named Docker gateway container' >/dev/null

scenario="$work/scenario"
bash "$prepare" \
    --output "$scenario" \
    --id reconnect_fixture \
    --gateway-url http://127.0.0.1:18080 \
    --expected-login 123456 \
    --expected-server Demo-Server \
    --expected-balance 10000 \
    --expected-leverage 200 \
    --magic 765433 >/dev/null

verify_output="$(bash "$runner" --scenario "$scenario" --gateway-container gateway-fixture --cli "$fake_cli" --verify-only)"
[[ "$verify_output" == *"reconnect_fixture_bars_readonly"* ]] || fail "verify-only did not retain the prepared strategy name"
[[ "$verify_output" == *"gateway-fixture"* ]] || fail "verify-only did not retain the requested gateway container"

for required in \
    '--gateway-container is required' \
    'gateway container is not inspectable' \
    'containerRestarted:true' \
    'wait_for_matched_evaluations pre-restart "$session_started_ms"' \
    'wait_for_matched_evaluations post-restart "$restart_ready_ms"' \
    'wait_for_startup_window' \
    'qkt-live-readonly-gateway-restart-startup-window-v1' \
    'LiveTickFeed source disconnected; waiting up to' \
    'LiveTickFeed source reconnected; resuming' \
    'gateway restart retained no feed disconnect warning' \
    'gateway restart retained no feed reconnect info' \
    'read-only gateway restart emitted an order, fill, accounting, linkage, or rejection event' \
    'read-only gateway restart issued a mutating gateway request' \
    'post-restart 5m warmup count was $post_5m_warmups; expected no reconnect warmup' \
    'schema:"qkt-live-validation-readonly-gateway-restart-v1"' \
    'docker restart "$gateway_container"' \
    'docker inspect "$gateway_container" | sanitize_container_inspect > "$evidence/gateway-container-restarted.json"' \
    'wait_for_gateway_health "$evidence/gateway-health-post-restart.json"' \
    'QKT_LATENCY_TRACKING=1 QKT_STATE_DIR="$scenario/state" "$cli" daemon start' \
    'API_KEY=<redacted>' \
    'MT5_PASSWORD=<redacted>' \
    'MT5_LOGIN=<redacted>' \
    'MT5_SERVER=<redacted>'; do
    rg --fixed-strings --quiet -- "$required" "$runner" || fail "missing gateway-restart contract: $required"
done

if rg --quiet -- '-Xmx|-Xms|MaxRAMPercentage|--memory(=|[[:space:]])|--cpus(=|[[:space:]])|--pids-limit|--cpuset-cpus' "$runner"; then
    fail "runner contains a JVM or Docker resource restriction"
fi

printf 'run-readonly-gateway-restart-test: passed\n'
