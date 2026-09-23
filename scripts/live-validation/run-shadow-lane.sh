#!/usr/bin/env bash
# The shadow lane of the attestation: every ready read-only case in ONE daemon on one shared tick
# stream, then each case replayed offline from its own capture and its logged values compared
# with the live ones, text for text. Places no orders.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=scripts/live-validation/lib/process-stop.sh
source "$repo_root/scripts/live-validation/lib/process-stop.sh"

usage() {
    cat <<'USAGE'
Usage: run-shadow-lane.sh --out DIR --gateway-url URL --expected-login N --expected-server NAME
         --magic N [--duration-seconds N] [--cases DIR] [--cli PATH]

Loads every `status: ready` case under --cases (default attestation/cases/shadow) into one
daemon, observes for --duration-seconds (default 420, at most 570), captures each strategy's
input, replays it through full-tick paper, and runs compare-trace-vectors.py per case.
Writes DIR/result.json; exits non-zero when any case fails or logged nothing.
QKT_BROKER_API_KEY comes from the environment. No order is ever submitted.
USAGE
}
fail() { printf 'run-shadow-lane: %s\n' "$1" >&2; exit 1; }

out=""; gateway_url=""; expected_login=""; expected_server=""; magic=""; duration=420
cases_dir="$repo_root/attestation/cases/shadow"; cli="$repo_root/build/install/qkt/bin/qkt"
while [ "$#" -gt 0 ]; do
    case "$1" in
        --out) out="${2:-}"; shift 2 ;;
        --gateway-url) gateway_url="${2:-}"; shift 2 ;;
        --expected-login) expected_login="${2:-}"; shift 2 ;;
        --expected-server) expected_server="${2:-}"; shift 2 ;;
        --magic) magic="${2:-}"; shift 2 ;;
        --duration-seconds) duration="${2:-}"; shift 2 ;;
        --cases) cases_dir="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done
for v in out gateway_url expected_login expected_server magic; do [ -n "${!v}" ] || { usage >&2; exit 2; }; done
[[ "$duration" =~ ^[0-9]+$ ]] && [ "$duration" -ge 60 ] && [ "$duration" -le 570 ] || fail "--duration-seconds must be 60..570"
: "${QKT_BROKER_API_KEY:?QKT_BROKER_API_KEY must be set}"
[ -x "$cli" ] || fail "qkt CLI is not executable: $cli"
[ ! -e "$out" ] || fail "output already exists: $out"
for tool in jq python3 unzip; do command -v "$tool" >/dev/null || fail "$tool is required"; done

mkdir -p "$out/strategies" "$out/state" "$out/evidence" "$out/cases"
ids=()
for case_yaml in "$cases_dir"/*/case.yaml; do
    [ -f "$case_yaml" ] || continue
    dir="$(dirname "$case_yaml")"
    status="$(python3 -c 'import sys,yaml; print(yaml.safe_load(open(sys.argv[1]))["status"])' "$case_yaml")"
    [ "$status" = ready ] && [ -f "$dir/strategy.qkt" ] || continue
    id="$(basename "$dir")"; ids+=("$id")
    cp "$dir/strategy.qkt" "$out/strategies/$id.qkt"
done
[ "${#ids[@]}" -gt 0 ] || fail "no ready shadow case under $cases_dir"

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
state:
  enabled: true
  async: true
insights:
  enabled: false
YAML

started_at="$(date -u +%FT%TZ)"
"$cli" daemon start --config "$out/qkt.config.yaml" --state-dir "$out/state" --load-dir "$out/strategies" \
    > "$out/daemon.log" 2>&1 &
daemon_pid=$!
sleep_pid=""
# `daemon stop` addresses whichever daemon last wrote this state dir's control.port, so it is only
# the polite first step: this lane's own daemon is then stopped by PID. Without that, a lane whose
# output dir was reused could stop another lane's daemon and leave its own running indefinitely.
stop_grace="${QKT_SHADOW_STOP_GRACE_SECONDS:-30}"
stop_daemon() {
    [ -n "$sleep_pid" ] && { kill "$sleep_pid" 2>/dev/null || true; wait "$sleep_pid" 2>/dev/null || true; sleep_pid=""; }
    [ -n "$daemon_pid" ] || return 0
    "$cli" daemon stop --state-dir "$out/state" >/dev/null 2>&1 || true
    await_exit "$daemon_pid" "$stop_grace" || stop_process "$daemon_pid" 10 "shadow daemon"
    wait "$daemon_pid" 2>/dev/null || true
    daemon_pid=""
}
trap stop_daemon EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
for _ in $(seq 1 120); do
    grep -q 'daemon ready' "$out/daemon.log" && break
    kill -0 "$daemon_pid" 2>/dev/null || fail "daemon exited during startup: $(tail -n 1 "$out/daemon.log")"
    sleep 1
done
grep -q 'daemon ready' "$out/daemon.log" || fail "daemon was not ready within 120 seconds"
# Backgrounded so a TERM from the parent suite is handled now, not after the whole window.
sleep "$duration" & sleep_pid=$!
wait "$sleep_pid" || true
sleep_pid=""

trap - EXIT
stop_daemon
# Capture only once the daemon has stopped: a journal still being appended to gives a manifest
# whose counts disagree with the zipped entries.
strategy_of() { sed -nE 's/^STRATEGY[[:space:]]+([A-Za-z0-9_]+).*/\1/p' "$out/strategies/$1.qkt" | head -n 1; }
for id in "${ids[@]}"; do
    "$cli" golden capture --session "$(strategy_of "$id")" --state-dir "$out/state" \
        --out "$out/cases/$id.golden.zip" --read-only > "$out/cases/$id.capture.log" 2>&1 || true
done
grep -Eq 'submit (Market|Bracket|Limit|Stop)' "$out/daemon.log" && fail "a shadow strategy submitted an order"

export QKT_BROKER_API_KEY=offline-replay-not-used
results=()
for id in "${ids[@]}"; do
    work="$out/cases/$id"; mkdir -p "$work"
    verdict="failed"; detail=""
    if [ ! -s "$out/cases/$id.golden.zip" ]; then
        detail="no capture: $(tail -n 1 "$out/cases/$id.capture.log" | cut -c1-160)"
    elif ! "$cli" golden materialize --bundle "$out/cases/$id.golden.zip" --out "$work/data" > "$work/materialize.log" 2>&1; then
        detail="materialize failed: $(tail -n 1 "$work/materialize.log" | cut -c1-160)"
    else
        from_utc="$(jq -er '.replayWindow.fromUtc' "$work/data/golden-replay-manifest.json")"
        to_utc="$(jq -er '.replayWindow.toUtc' "$work/data/golden-replay-manifest.json")"
        QKT_STATE_DIR="$work/state" "$cli" backtest "$out/strategies/$id.qkt" --from "$from_utc" --to "$to_utc" \
            --data-root "$work/data" --no-fetch --allow-incomplete --config "$out/qkt.config.yaml" \
            --broker paper --json > "$work/replay.log" 2>&1 || true
        # Fewer than two ticks a minute per symbol is a market that is shut or asleep, not a feed at work.
        ticks="$(jq -r '.counts.ticks // 0' "$work/data/golden-replay-manifest.json")"
        symbols="$(jq -r '.symbols | length' "$work/data/golden-replay-manifest.json")"
        code=0
        python3 "$repo_root/scripts/live-validation/compare-trace-vectors.py" --live "$out/daemon.log" \
            --replay ticks-paper="$work/replay.log" --marker "case=$id " \
            --catalog "$repo_root/src/test/resources/validation/oracle-evidence.json" \
            --live-ticks "$ticks" --quiet-below "$(( duration / 60 * symbols * 2 ))" \
            --out "$work/vectors.json" > "$work/compare.log" 2>&1 || code=$?
        case "$code" in 0) verdict="passed" ;; 3) verdict="market-quiet" ;; esac
        detail="$(tail -n 1 "$work/compare.log" | cut -c1-200)"
    fi
    results+=("$(jq -n --arg id "$id" --arg status "$verdict" --arg detail "$detail" \
        --slurpfile v <(cat "$work/vectors.json" 2>/dev/null || echo '{}') \
        '{id:$id,status:$status,detail:$detail,liveVectors:($v[0].liveVectors // 0),capabilities:($v[0].capabilitiesExercised // [])}')")
done

printf '%s\n' "${results[@]}" | jq -s --arg startedAt "$started_at" --arg finishedAt "$(date -u +%FT%TZ)" \
    --arg cli "$("$cli" --version | head -n 1)" --argjson duration "$duration" '
    {schema:"qkt-attestation-shadow-lane-v1", startedAtUtc:$startedAt, finishedAtUtc:$finishedAt, cli:$cli,
     observedSeconds:$duration, financiallyReadOnly:true, cases:.,
     capabilities:([.[].capabilities[]] | unique),
     status:(if all(.[]; .status == "passed") then "passed"
             elif any(.[]; .status == "failed") then "failed" else "market-quiet" end)}' > "$out/result.json"
jq -r '"\(.status) cases=\(.cases|length) passed=\([.cases[]|select(.status=="passed")]|length) capabilities=\(.capabilities|length)"' "$out/result.json"
[ "$(jq -r .status "$out/result.json")" = passed ]
