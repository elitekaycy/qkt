#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
runner="$repo_root/scripts/live-validation/run-insights-attribution.sh"
prepare="$repo_root/scripts/live-validation/prepare-scenario.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fail() {
    printf 'run-insights-attribution-test: %s\n' "$1" >&2
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

scenario="$work/gbp-scenario"
bash "$prepare" \
    --output "$scenario" \
    --id insights_gbp_fixture \
    --gateway-url http://127.0.0.1:18080 \
    --expected-login 123456 \
    --expected-server Demo-Server \
    --expected-balance 10000 \
    --expected-leverage 200 \
    --magic 876543 \
    --symbol GBPUSD >/dev/null

verify_output="$(bash "$runner" --scenario "$scenario" --insights-image unavailable:fixture \
    --cli "$fake_cli" --verify-only)"
[[ "$verify_output" == *"on EXNESS:GBPUSD"* ]] || fail "verify-only did not derive the prepared armed symbol"
[[ "$verify_output" == *"insights_gbp_fixture_bars_readonly"* ]] || fail "verify-only did not retain the read-only sibling"

bash -n "$runner"
if rg --quiet 'kill .*--flatten|bot close EXNESS:|(-Xmx|--memory|--cpus|--cpu-quota)' "$runner"; then
    fail "runner contains operator flatten, hardcoded cleanup symbol, or a runtime resource cap"
fi

for required in \
    'scenario was not freshly prepared from the current QKT commit' \
    'scenario must be freshly prepared from a clean checkout' \
    'Insights image rejected the causal execution contract' \
    'Insights does not fold lifecycle events after a producer sequence restart' \
    'Insights does not preserve known position attribution across sibling state polls' \
    'Insights treats producer-local sequences as global delivery continuity' \
    'decision.rule_evaluated:2 decision.order_linked:2 order.submit:2 order.accepted:2' \
    'order.filled:2 trade:2 fill.accounted:2' \
    'collector did not join both rule decisions through links to submitted orders' \
    'engine audit lacks exactly two rule decisions' \
    'collector rule decisions differ from engine audit' \
    'collector lacks the canonical bracket AST and reviewed distances' \
    'bracket plan created an orphan order row' \
    'collector close order lacks full strategy-owned ticket and leg ownership' \
    'entry fill accounting does not establish the 0.01 strategy position' \
    'exit fill accounting does not reduce the complete strategy position' \
    'armed runtime lacks live tick evidence' \
    'armed runtime lacks matched M1/M5 bars and evaluations' \
    'collector observed a sequence gap or regression' \
    'Insights health reported dropped envelopes' \
    'Insights journal did not fully drain' \
    'second DSL decision did not close the owned ticket' \
    'live-state-open-attributed.json' \
    'emergency-close-$ticket.json' \
    'emergency-cancel-$ticket.json'; do
    rg --fixed-strings --quiet "$required" "$runner" || fail "missing hardening contract: $required"
done

probe_line="$(rg -n 'Reject an image that predates' "$runner" | cut -d: -f1)"
broker_line="$(rg -n '^gateway_get /account' "$runner" | cut -d: -f1 | head -1)"
[ "$probe_line" -lt "$broker_line" ] || fail "stale collector probe does not precede broker access"

printf 'run-insights-attribution-test: passed\n'
