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
    --runtime-account-identity \
    --expected-balance 10000 \
    --expected-leverage 200 \
    --magic 876543 \
    --symbol GBPUSD >/dev/null

verify_output="$(bash "$runner" --scenario "$scenario" --insights-image unavailable:fixture \
    --cli "$fake_cli" --verify-only)"
[[ "$verify_output" == *"on EXNESS:GBPUSD"* ]] || fail "verify-only did not derive the prepared armed symbol"
[[ "$verify_output" == *"insights_gbp_fixture_bars_readonly"* ]] || fail "verify-only did not retain the read-only sibling"
if rg --text --fixed-strings --quiet -e 123456 -e Demo-Server "$scenario"; then
    fail "runtime account identity reached the prepared scenario"
fi
grep -F 'expected_account_login: ${QKT_BROKER_EXNESS_EXPECTED_ACCOUNT_LOGIN}' "$scenario/qkt.config.yaml" >/dev/null
grep -F 'expected_account_server: ${QKT_BROKER_EXNESS_EXPECTED_ACCOUNT_SERVER}' "$scenario/qkt.config.yaml" >/dev/null
jq -e '.account.identitySource == "runtimeEnvironment" and
    (.account | has("login") == false and has("server") == false)' "$scenario/expected.json" >/dev/null

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
    'emergency-cancel-$ticket.json' \
    'qkt_write_safe_account_snapshot' \
    'qkt_sanitize_account_transport_journals' \
    'qkt_assert_no_retained_account_identity'; do
    rg --fixed-strings --quiet "$required" "$runner" || fail "missing hardening contract: $required"
done

identity_lib="$repo_root/scripts/live-validation/lib/account-identity.sh"
rg --fixed-strings --quiet 'QKT_BROKER_EXNESS_EXPECTED_ACCOUNT_LOGIN' "$identity_lib"
fixture="$work/identity-fixture"
mkdir -p "$fixture/state" "$fixture/logs"
raw_account='{"login":123456,"server":"Demo-Server","name":"Account Owner","balance":10000,"equity":10000,"margin":0,"profit":0,"margin_free":10000,"margin_level":null,"currency":"USD","leverage":200,"margin_mode":2,"trade_mode":0,"trade_allowed":true,"trade_expert":true}'
source "$identity_lib"
(
    export QKT_BROKER_EXNESS_EXPECTED_ACCOUNT_LOGIN=123456
    export QKT_BROKER_EXNESS_EXPECTED_ACCOUNT_SERVER=Demo-Server
    qkt_require_runtime_account_identity
    [ "$QKT_EXPECTED_ACCOUNT_LOGIN" = 123456 ]
    [ "$QKT_EXPECTED_ACCOUNT_SERVER" = Demo-Server ]
)
qkt_write_safe_account_snapshot "$fixture/account.json" <<< "$raw_account"
printf '%s\n' '{"path":"/account","responseBody":"{\"login\":123456,\"server\":\"Demo-Server\",\"name\":\"Account Owner\",\"balance\":10000,\"equity\":10000,\"margin\":0,\"currency\":\"USD\",\"leverage\":200,\"trade_mode\":0}"}' > "$fixture/state/transport.jsonl"
printf '%s\n' '[INFO] mt5 account: exness: login=123456 server=Demo-Server mode=demo' > "$fixture/logs/daemon.log"
qkt_sanitize_account_transport_journals "$fixture/state"
qkt_redact_account_identity_log "$fixture/logs/daemon.log" 123456 Demo-Server
qkt_assert_no_retained_account_identity "$fixture" 123456 Demo-Server || fail "identity sanitizer left fixture data"
jq -e 'has("login") == false and has("server") == false and has("name") == false and .balance == 10000' "$fixture/account.json" >/dev/null
jq -e '(.responseBody | fromjson | has("login")) == false and (.responseBody | fromjson | has("server")) == false' "$fixture/state/transport.jsonl" >/dev/null

probe_line="$(rg -n 'Reject an image that predates' "$runner" | cut -d: -f1)"
broker_line="$(rg -n 'account_initial=.*gateway_get /account' "$runner" | cut -d: -f1 | head -1)"
[ "$probe_line" -lt "$broker_line" ] || fail "stale collector probe does not precede broker access"

printf 'run-insights-attribution-test: passed\n'
