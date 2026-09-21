#!/usr/bin/env bash
# One armed attestation case from the catalog, on a demo account it shares with other lanes.
# Generic: it knows nothing about the strategy. It proves the invariants every order-bearing case
# must hold, whatever order shapes the strategy uses:
#   - the case starts and ends owning nothing under its magic (no position, no pending order);
#   - what the engine booked for the strategy equals the venue's own deals under that magic;
#   - the engine logged no fault, unknown outcome or unattributed fill;
#   - replaying the captured input through the MT5 simulation yields the same fills, in the same
#     order, on the same sides, at the same sizes.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
    cat <<'USAGE'
Usage: run-order-lane-case.sh --case DIR --out DIR --gateway-url URL --expected-login N
         --expected-server NAME --magic N --arm I_UNDERSTAND_DEMO_ORDER_0.01 [--cli PATH]

DIR is attestation/cases/<lane>/<id> with case.yaml and strategy.qkt. The case runs for its
`budget_seconds` (lane default otherwise), is then stopped with --flatten, and is judged.
Needs QKT_BROKER_API_KEY and QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY. Demo accounts only:
the account's trade mode is checked before anything is deployed.
USAGE
}
fail() { printf 'run-order-lane-case: %s\n' "$1" >&2; exit 1; }

case_dir=""; out=""; gateway_url=""; expected_login=""; expected_server=""; magic=""; arm=""
cli="$repo_root/build/install/qkt/bin/qkt"
while [ "$#" -gt 0 ]; do
    case "$1" in
        --case) case_dir="${2:-}"; shift 2 ;;
        --out) out="${2:-}"; shift 2 ;;
        --gateway-url) gateway_url="${2:-}"; shift 2 ;;
        --expected-login) expected_login="${2:-}"; shift 2 ;;
        --expected-server) expected_server="${2:-}"; shift 2 ;;
        --magic) magic="${2:-}"; shift 2 ;;
        --arm) arm="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done
for v in case_dir out gateway_url expected_login expected_server magic; do [ -n "${!v}" ] || { usage >&2; exit 2; }; done
[ "$arm" = "I_UNDERSTAND_DEMO_ORDER_0.01" ] || fail "--arm I_UNDERSTAND_DEMO_ORDER_0.01 is required"
[ "${QKT_LIVE_DEMO_ORDER_APPROVAL:-}" = "LOCALHOST_DEMO_ONLY" ] || fail "QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY is required"
: "${QKT_BROKER_API_KEY:?QKT_BROKER_API_KEY must be set}"
[[ "$gateway_url" =~ ^http://(127\.0\.0\.1|localhost):[0-9]+$ ]] || fail "only a loopback gateway is allowed"
[ -f "$case_dir/case.yaml" ] && [ -f "$case_dir/strategy.qkt" ] || fail "case needs case.yaml and strategy.qkt: $case_dir"
[ -x "$cli" ] || fail "qkt CLI is not executable: $cli"
[ ! -e "$out" ] || fail "output already exists: $out"
for tool in jq python3 unzip flock curl; do command -v "$tool" >/dev/null || fail "$tool is required"; done

id="$(basename "$case_dir")"; lane="$(basename "$(dirname "$case_dir")")"
budget="$(python3 - "$case_dir/case.yaml" "$repo_root/attestation/lanes.yaml" "$lane" <<'PY'
import sys, yaml
case = yaml.safe_load(open(sys.argv[1])); lanes = yaml.safe_load(open(sys.argv[2]))["lanes"]
print(int(case.get("budget_seconds", lanes[sys.argv[3]]["budget_seconds"])))
PY
)"

gateway_get() {
    printf 'header = "Authorization: Bearer %s"\n' "$QKT_BROKER_API_KEY" |
        curl --silent --show-error --fail --max-time 20 --config - "$gateway_url$1"
}
owned() { gateway_get "/get_positions?magic=$magic" | jq -er '(.data // []) | length'; }
# Filtered here as well: the gateway's magic filter on /orders is not relied upon.
pending() { gateway_get "/orders?magic=$magic" | jq -er --argjson magic "$magic" '[(.orders // [])[] | select((.magic // $magic) == $magic)] | length'; }

strategy="$(sed -nE 's/^STRATEGY[[:space:]]+([A-Za-z0-9_]+).*/\1/p' "$case_dir/strategy.qkt" | head -n 1)"
[ -n "$strategy" ] || fail "strategy.qkt declares no STRATEGY name"
mkdir -p "$out/strategies" "$out/state" "$out/evidence"
# The daemon names a deployment after its file, so the file carries the strategy's own name.
cp "$case_dir/strategy.qkt" "$out/strategies/$strategy.qkt"
gateway_get /account > "$out/evidence/account-initial.json"
jq -e --argjson login "$expected_login" --arg server "$expected_server" \
    '.login == $login and .server == $server and .trade_mode == 0 and .trade_allowed == true' \
    "$out/evidence/account-initial.json" >/dev/null || fail "gateway is not logged into the expected DEMO account"

# Shared with every other lane; still excluded by any exclusive run on this account.
lock="/var/tmp/qkt-validation/LIVE-LOCK-${expected_server//[^A-Za-z0-9._-]/_}-$expected_login"
mkdir -p "$(dirname "$lock")"; exec {lock_fd}>> "$lock"
flock -s -n "$lock_fd" || fail "an exclusive live run holds $lock"
[ "$(owned)" = 0 ] && [ "$(pending)" = 0 ] || fail "magic $magic already owns a position or a pending order"

cat > "$out/qkt.config.yaml" <<YAML
source: local
data_root: "$out/data"
log_level: info
runtime:
  mode: dev
account:
  currency: USD
brokers:
  exness:
    type: mt5
    extends: exness
    calendars:
      "BTC*": crypto
    gateway_url: $gateway_url
    magic: $magic
    server_time_zone: Etc/UTC
    expected_account_login: $expected_login
    expected_account_server: $expected_server
    expected_trade_mode: demo
    tick_poll_interval_ms: 100
    poll_interval_ms: 1000
risk:
  max_daily_loss: "50"
  max_order_qty: "0.05"
  max_order_notional: "10000"
  price_collar_pct: "5"
  measured_usage_hours: "0"
  max_round_trips_10m: 0
  live_equity_basis: modeled
state:
  enabled: true
  async: true
insights:
  enabled: false
YAML

# A case may tighten or extend the config it runs under (`config:` in case.yaml, deep-merged):
# risk cases are the config as much as they are the strategy.
python3 - "$case_dir/case.yaml" "$out/qkt.config.yaml" <<'PY'
import sys, yaml
case = yaml.safe_load(open(sys.argv[1])); extra = case.get("config") or {}
def merge(base, over):
    for key, value in over.items():
        if isinstance(value, dict) and isinstance(base.get(key), dict):
            merge(base[key], value)
        else:
            base[key] = value
    return base
if extra:
    config = yaml.safe_load(open(sys.argv[2]))
    yaml.safe_dump(merge(config, extra), open(sys.argv[2], "w"), sort_keys=False)
PY

# A replay has no venue to ask for contract specs, so copy them from the live account now. Symbols
# outside the built-in table (BTCUSD, indices) are otherwise rejected by the MT5 simulation.
symbols="$(grep -oE 'EXNESS:[A-Z0-9]+' "$case_dir/strategy.qkt" | sort -u | paste -sd, -)"
"$cli" instruments pull --config "$out/qkt.config.yaml" --symbols "$symbols" --out "$out/instruments.yaml" \
    > "$out/evidence/instruments-pull.log" 2>&1 || fail "could not pull instrument specs: $(tail -n 1 "$out/evidence/instruments-pull.log")"

started_ms="$(date +%s%3N)"; started_at="$(date -u +%FT%TZ)"
"$cli" daemon start --config "$out/qkt.config.yaml" --state-dir "$out/state" --load-dir "$out/strategies" \
    > "$out/daemon.log" 2>&1 &
daemon_pid=$!
cleanup() {
    "$cli" kill "$strategy" --flatten --state-dir "$out/state" --json > "$out/evidence/kill.json" 2>&1 || true
    "$cli" daemon stop --state-dir "$out/state" >/dev/null 2>&1 || true
    wait "$daemon_pid" 2>/dev/null || true
}
trap cleanup EXIT
for _ in $(seq 1 120); do
    grep -q 'daemon ready' "$out/daemon.log" && break
    kill -0 "$daemon_pid" 2>/dev/null || fail "daemon exited during startup: $(tail -n 1 "$out/daemon.log")"
    sleep 1
done
grep -q 'daemon ready' "$out/daemon.log" || fail "daemon was not ready within 120 seconds"
sleep "$budget"

# A case exits by its own rules inside its budget. Booked P&L is read while the strategy is still
# deployed (stopping removes it); anything still open is then flattened and counted as a problem.
"$cli" status "$strategy" --state-dir "$out/state" > "$out/evidence/status-final.json" 2>/dev/null || true
open_at_budget="$(owned)"
"$cli" stop "$strategy" --flatten --state-dir "$out/state" --json > "$out/evidence/stop.json" 2>&1 || true
for _ in $(seq 1 30); do [ "$(owned)" = 0 ] && [ "$(pending)" = 0 ] && break; sleep 1; done
trap - EXIT
"$cli" daemon stop --state-dir "$out/state" >/dev/null 2>&1 || true
wait "$daemon_pid" 2>/dev/null || true

problems=()
[ "$open_at_budget" = 0 ] || problems+=("the strategy still held a position when its budget ended; its own exit did not run")
if [ "$(owned)" != 0 ]; then
    # Last resort, by ticket: a case must never leave a position behind.
    leftovers="$(gateway_get "/get_positions?magic=$magic" | jq -r '(.data // [])[].ticket')"
    for ticket in $leftovers; do
        printf 'header = "Authorization: Bearer %s"\n' "$QKT_BROKER_API_KEY" |
            curl --silent --show-error --max-time 30 --config - -X POST -H 'content-type: application/json' \
                -d "{\"position\":{\"ticket\":$ticket}}" "$gateway_url/close_position" > /dev/null || true
    done
    problems+=("magic still owned a position after the case; force-closed: $(echo $leftovers)")
fi
[ "$(pending)" = 0 ] || problems+=("magic still owns a pending order")
grep -Eq 'engine loop fault|unattributed fill dropped' "$out/daemon.log" &&
    problems+=("engine fault or unattributed fill in the daemon log")
# A lost acknowledgement is not a failure - the engine is built to ask the venue and resolve it.
# An outcome it never resolved is.
unknown="$({ grep -c 'outcome UNKNOWN' "$out/daemon.log" || true; } | head -n 1)"
resolved="$({ grep -cE 'resolved as [A-Z_]+' "$out/daemon.log" || true; } | head -n 1)"
[ "$unknown" -le "$resolved" ] || problems+=("$unknown unknown order outcome(s), only $resolved resolved")

"$cli" bot history --broker exness --since "$started_ms" --config "$out/qkt.config.yaml" --json \
    > "$out/evidence/history.json" 2>/dev/null || echo '[]' > "$out/evidence/history.json"
# Venue history is account-wide and a closing deal carries no comment, so ownership goes by
# position ticket: every ticket whose opening deal carries this strategy's order comment (the
# venue truncates comments, hence the two-way prefix match), then every deal on those tickets.
deal_net="$(jq -r --arg prefix "dsl-$strategy" '
    ([.[] | select(.entry == "IN") | select((.comment // "") as $c | ($c | length) > 4 and
        (($c | startswith($prefix)) or ($prefix | startswith($c)))) | .positionTicket] | unique) as $owned |
    [.[] | select(.positionTicket as $t | $owned | index($t)) |
        ((.profit // 0) + (.commission // 0) + (.swap // 0) + (.fee // 0))] | add // 0
' "$out/evidence/history.json" | awk '{printf "%.2f", $1}')"
engine_realized="$(jq -r '.realized // 0' "$out/evidence/status-final.json" 2>/dev/null | awk '{printf "%.2f", $1}')"
# The venue truncates every closing deal's profit to whole cents while the engine keeps exact
# values, so the two may differ by up to one cent per closing deal - and by no more than that.
closing_deals="$(jq -r --arg prefix "dsl-$strategy" '
    ([.[] | select(.entry == "IN") | select((.comment // "") as $c | ($c | length) > 4 and
        (($c | startswith($prefix)) or ($prefix | startswith($c)))) | .positionTicket] | unique) as $owned |
    [.[] | select(.entry != "IN") | select(.positionTicket as $t | $owned | index($t))] | length
' "$out/evidence/history.json")"
awk -v e="${engine_realized:-0}" -v d="$deal_net" -v n="$closing_deals" 'BEGIN { diff = e - d; if (diff < 0) diff = -diff; exit !(diff <= n * 0.01 + 0.000001) }' ||
    problems+=("engine realized $engine_realized differs from venue deal net $deal_net by more than one cent per closing deal ($closing_deals)")

fills() { { grep -E 'order filled ' "$1" || true; } | sed -nE 's/.*side=([A-Z]+) qty=([0-9.]+).*/\1 \2/p'; }
fills "$out/daemon.log" > "$out/evidence/live-fills.txt"
live_fills="$(wc -l < "$out/evidence/live-fills.txt")"
[ "$live_fills" -gt 0 ] || problems+=("the case placed no order that filled")

replay_fills=0
if "$cli" golden capture --session "$strategy" --state-dir "$out/state" --out "$out/evidence/golden.zip" \
        > "$out/evidence/golden-capture.log" 2>&1 &&
    QKT_BROKER_API_KEY=offline "$cli" golden materialize --bundle "$out/evidence/golden.zip" --out "$out/replay-data" \
        > "$out/evidence/materialize.log" 2>&1; then
    from_utc="$(jq -er '.replayWindow.fromUtc' "$out/replay-data/golden-replay-manifest.json")"
    to_utc="$(jq -er '.replayWindow.toUtc' "$out/replay-data/golden-replay-manifest.json")"
    QKT_BROKER_API_KEY=offline QKT_STATE_DIR="$out/replay-state" "$cli" backtest "$out/strategies/$strategy.qkt" \
        --from "$from_utc" --to "$to_utc" --data-root "$out/replay-data" --no-fetch --allow-incomplete \
        --config "$out/qkt.config.yaml" --instruments "$out/instruments.yaml" --broker mt5-sim --json > "$out/replay.log" 2>&1 || true
    fills "$out/replay.log" > "$out/evidence/replay-fills.txt"
    replay_fills="$(wc -l < "$out/evidence/replay-fills.txt")"
    # The final flatten is the runner's, not the strategy's, so the replay may hold the last
    # position open: the live sequence must START with the replay's, fill for fill.
    head -n "$replay_fills" "$out/evidence/live-fills.txt" | cmp -s - "$out/evidence/replay-fills.txt" ||
        problems+=("live fills do not begin with the replay's fill sequence")
    [ "$replay_fills" -gt 0 ] || problems+=("the replay filled nothing")
else
    problems+=("capture or materialize failed: $(tail -n 1 "$out/evidence/materialize.log" 2>/dev/null | cut -c1-120)")
fi

# Risk rejections must be the same live and in replay: a cap that binds in one and not the other is
# exactly the divergence the risk lane exists to catch. `expect_rejections` in case.yaml pins the count.
rejections() { { grep -cE 'risk rejected ' "$1" || true; } | head -n 1; }
live_rejections="$(rejections "$out/daemon.log")"; replay_rejections=0
# A backtest reports rejections in its JSON result, not as log lines.
[ -f "$out/replay.log" ] && replay_rejections="$({ grep -m1 '^{"schema' "$out/replay.log" || echo '{}'; } | jq -r '.tradeSummary.rejections // 0')"
expected_rejections="$(python3 -c 'import sys,yaml; print(yaml.safe_load(open(sys.argv[1])).get("expect_rejections", ""))' "$case_dir/case.yaml")"
if [ -n "$expected_rejections" ]; then
    [ "$live_rejections" = "$expected_rejections" ] || problems+=("live risk rejections $live_rejections, expected $expected_rejections")
    [ "$replay_rejections" = "$expected_rejections" ] || problems+=("replay risk rejections $replay_rejections, expected $expected_rejections")
fi

status="passed"; [ "${#problems[@]}" -eq 0 ] || status="failed"
printf '%s\n' "${problems[@]:-}" | jq -R . | jq -s --arg id "$id" --arg lane "$lane" --arg status "$status" \
    --arg startedAt "$started_at" --arg finishedAt "$(date -u +%FT%TZ)" --argjson magic "$magic" --argjson budget "$budget" \
    --arg engineRealized "$engine_realized" --arg dealNet "$deal_net" --argjson liveFills "$live_fills" --argjson replayFills "$replay_fills" \
    --argjson unknownOutcomes "$unknown" --argjson liveRejections "$live_rejections" --argjson replayRejections "$replay_rejections" \
    --arg cli "$("$cli" --version | head -n 1)" \
    '{schema:"qkt-attestation-order-case-v1", id:$id, lane:$lane, status:$status, startedAtUtc:$startedAt, finishedAtUtc:$finishedAt,
      magic:$magic, budgetSeconds:$budget, cli:$cli, engineRealized:$engineRealized, dealNet:$dealNet,
      liveFills:$liveFills, replayFills:$replayFills, unknownOutcomesResolved:$unknownOutcomes, liveRejections:$liveRejections, replayRejections:$replayRejections, problems:(map(select(. != "")))}' > "$out/result.json"
jq -r '"\(.status) \(.id) liveFills=\(.liveFills) replayFills=\(.replayFills) rejections=\(.liveRejections)/\(.replayRejections) realized=\(.engineRealized) dealNet=\(.dealNet) \(.problems|join("; "))"' "$out/result.json"
[ "$status" = passed ]
