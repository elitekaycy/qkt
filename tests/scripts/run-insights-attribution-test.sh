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
    'qkt_assert_no_retained_account_identity' \
    'qkt_count_cross_owner_causal_events' \
    'qkt_export_armed_rule_decisions' \
    'qkt_validate_bounded_rule_decisions' \
    'qkt_write_engine_rule_decisions' \
    'qkt_write_collector_rule_decisions'; do
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

attribution_lib="$repo_root/scripts/live-validation/lib/insights-attribution.sh"
source "$attribution_lib"
audit_db="$work/attribution.db"
sqlite3 "$audit_db" <<'SQL'
create table events(instance_id text, type text, strategy_id text, payload text);
insert into events values
  ('live','decision.rule_evaluated','armed','{"signalCount":1}'),
  ('live','decision.rule_evaluated','readonly','{"signalCount":0}'),
  ('live','decision.rule_evaluated','readonly','{"signalCount":0}'),
  ('live','decision.order_linked','armed','{}'),
  ('live','order.submit','armed','{}'),
  ('live','order.accepted','armed','{}'),
  ('live','order.filled','armed','{}'),
  ('live','trade','armed','{}'),
  ('live','fill.accounted','armed','{}');
SQL
[ "$(qkt_count_cross_owner_causal_events "$audit_db" live armed)" -eq 0 ] ||
    fail "read-only zero-signal rule evaluations were treated as causal execution"
sqlite3 "$audit_db" "insert into events values ('live','order.submit','readonly','{}');"
[ "$(qkt_count_cross_owner_causal_events "$audit_db" live armed)" -eq 1 ] ||
    fail "cross-owner order submission was not rejected"
sqlite3 "$audit_db" "delete from events where type='order.submit' and strategy_id='readonly'; insert into events values ('live','fill.accounted',null,'{}');"
[ "$(qkt_count_cross_owner_causal_events "$audit_db" live armed)" -eq 1 ] ||
    fail "null-owner fill accounting was not rejected"
sqlite3 "$audit_db" "delete from events where type='fill.accounted' and strategy_id is null; insert into events values ('live','decision.rule_evaluated','readonly','{\"signalCount\":1}');"
[ "$(qkt_count_cross_owner_causal_events "$audit_db" live armed)" -eq 1 ] ||
    fail "cross-owner signal-producing rule decision was not rejected"

decision_db="$work/decisions.db"
sqlite3 "$decision_db" <<'SQL'
create table events(instance_id text, type text, strategy_id text, payload text, ts integer, seq integer);
insert into events values
  ('live','decision.rule_evaluated','readonly','{"signalCount":0}',1,1),
  ('live','decision.rule_evaluated','armed','{"decisionId":"entry","ruleId":"asset1#0","strategyFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","ruleFingerprint":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","conditionFingerprint":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc","conditionResult":true,"alias":"asset1","broker":"EXNESS","timeframe":"1m","signalCount":1,"candle":{"symbol":"EXNESS:EURUSD","startTimeMs":1,"endTimeMs":2,"open":1,"high":1,"low":1,"close":1,"volume":1}}',2,2),
  ('live','decision.rule_evaluated','armed','{"decisionId":"exit","ruleId":"asset1#2","strategyFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","ruleFingerprint":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd","conditionFingerprint":"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","conditionResult":true,"alias":"asset1","broker":"EXNESS","timeframe":"1m","signalCount":2,"candle":{"symbol":"EXNESS:EURUSD","startTimeMs":2,"endTimeMs":3,"open":1,"high":1,"low":1,"close":1,"volume":1}}',3,3);
SQL
decisions="$work/armed-decisions.json"
qkt_export_armed_rule_decisions "$decision_db" live armed "$decisions"
[ "$(jq 'length' "$decisions")" -eq 2 ] || fail "rule decision export retained the read-only sibling"
qkt_validate_bounded_rule_decisions "$decisions" EXNESS:EURUSD ||
    fail "reviewed entry/exit signal counts were rejected"
jq 'map(if (.payload | fromjson | .ruleId) == "asset1#2" then
    .payload = ((.payload | fromjson | .signalCount = 1) | tojson) else . end)' \
    "$decisions" > "$work/wrong-exit-count.json"
if qkt_validate_bounded_rule_decisions "$work/wrong-exit-count.json" EXNESS:EURUSD; then
    fail "exit decision accepted one signal instead of cancel-pending plus exact-ticket close"
fi

engine_json="$work/engine-decisions.jsonl"
collector_json="$work/collector-decisions.json"
engine_tsv="$work/engine-decisions.tsv"
collector_tsv="$work/collector-decisions.tsv"
cat > "$engine_json" <<'JSON'
{"eventType":"com.qkt.events.RuleDecisionEvent","strategyId":"armed","decisionId":"entry","ruleId":"asset1#0","strategyFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","ruleFingerprint":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","conditionFingerprint":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc","conditionResult":true,"alias":"asset1","broker":"EXNESS","timeframe":"1m","symbol":"EXNESS:EURUSD","signalCount":1,"candle":{"startTimeMs":1,"endTimeMs":2,"open":1.15439000,"high":1.15440000,"low":1.15438000,"close":1.15439000,"volume":0E-8}}
JSON
cat > "$collector_json" <<'JSON'
[{"payload":"{\"decisionId\":\"entry\",\"ruleId\":\"asset1#0\",\"strategyFingerprint\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"ruleFingerprint\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\"conditionFingerprint\":\"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\",\"conditionResult\":true,\"alias\":\"asset1\",\"broker\":\"EXNESS\",\"timeframe\":\"1m\",\"signalCount\":1,\"candle\":{\"symbol\":\"EXNESS:EURUSD\",\"startTimeMs\":1,\"endTimeMs\":2,\"open\":1.15439,\"high\":1.1544,\"low\":1.15438,\"close\":1.15439,\"volume\":0}}"}]
JSON
qkt_write_engine_rule_decisions armed "$engine_tsv" "$engine_json"
qkt_write_collector_rule_decisions "$collector_json" "$collector_tsv"
cmp -s "$engine_tsv" "$collector_tsv" || fail "decimal scale changed canonical candle equality"
jq 'map(.payload = ((.payload | fromjson | .candle.close = 1.15438) | tojson))' \
    "$collector_json" > "$work/collector-numeric-mismatch.json"
qkt_write_collector_rule_decisions "$work/collector-numeric-mismatch.json" "$collector_tsv"
if cmp -s "$engine_tsv" "$collector_tsv"; then
    fail "true candle numeric mismatch passed canonical comparison"
fi

probe_line="$(rg -n 'Reject an image that predates' "$runner" | cut -d: -f1)"
broker_line="$(rg -n 'account_initial=.*gateway_get /account' "$runner" | cut -d: -f1 | head -1)"
[ "$probe_line" -lt "$broker_line" ] || fail "stale collector probe does not precede broker access"

printf 'run-insights-attribution-test: passed\n'
