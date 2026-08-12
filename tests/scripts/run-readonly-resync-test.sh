#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
runner="$repo_root/scripts/live-validation/run-readonly-resync.sh"
prepare="$repo_root/scripts/live-validation/prepare-scenario.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fail() {
    printf 'run-readonly-resync-test: %s\n' "$1" >&2
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
bash "$runner" --help | grep -F 'already-deployed' >/dev/null

scenario="$work/scenario"
bash "$prepare" \
    --output "$scenario" \
    --id resync_fixture \
    --gateway-url http://127.0.0.1:18080 \
    --expected-login 123456 \
    --expected-server Demo-Server \
    --expected-balance 10000 \
    --expected-leverage 200 \
    --magic 765432 >/dev/null

verify_output="$(bash "$runner" --scenario "$scenario" --cli "$fake_cli" --verify-only)"
[[ "$verify_output" == *"resync_fixture_bars_readonly"* ]] || fail "verify-only did not retain the prepared strategy name"
[[ "$verify_output" == *"_resync.qkt"* ]] || fail "verify-only did not generate the replacement strategy"

for required in \
    'daemon did not start empty; already-deployed validation requires zero auto-loaded strategies' \
    'control-plane deploy did not enter running state' \
    'control-plane resync did not return running state' \
    'control-plane resume did not clear the post-resync halt state' \
    'daemon journal did not retain action=$action' \
    'pre-resync 1m warmup count was $pre_1m_warmups; expected 80 pseudo-ticks' \
    'post-resync 5m warmup count was $post_5m_warmups; expected 80 pseudo-ticks' \
    'read-only resync emitted an order, fill, accounting, linkage, or rejection event' \
    'read-only resync issued a mutating gateway request' \
    'phase lacked exact post-deploy matched M1/M5 bars and evaluations within $phase_timeout_seconds seconds' \
    'daemonStartedEmpty:true' \
    'schema:"qkt-live-validation-readonly-resync-v1"' \
    'resumeAfterResync:true' \
    'wait_for_matched_evaluations deploy "$deploy_started_ms"' \
    'wait_for_matched_evaluations resync "$resume_ready_ms"' \
    'qkt_count_matched_evaluations "$strategy_name" eur5 EXNESS:EURUSD 5m "$resync_ready_ms" -1' \
    'qkt_count_warmup_pseudo_ticks EXNESS:EURUSD 300000 "$resync_started_ms" -1' \
    'QKT_LATENCY_TRACKING=1 QKT_STATE_DIR="$scenario/state" "$cli" daemon start' \
    '"$cli" deploy "$readonly_strategy" --as "$strategy_name" --state-dir "$scenario/state" --json' \
    '"$cli" resync "$replacement_strategy" --as "$strategy_name" --state-dir "$scenario/state" --json' \
    '"$cli" resume "$strategy_name" --state-dir "$scenario/state" --json'; do
    rg --fixed-strings --quiet "$required" "$runner" || fail "missing deploy/resync contract: $required"
done

if rg --quiet -- '--load-dir|-Xmx|-Xms|MaxRAMPercentage|--memory(=|[[:space:]])|--cpus(=|[[:space:]])|--pids-limit|--cpuset-cpus' "$runner"; then
    fail "runner contains a load-dir or a JVM/Docker resource restriction"
fi

printf 'run-readonly-resync-test: passed\n'
