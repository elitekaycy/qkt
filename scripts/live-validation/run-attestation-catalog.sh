#!/usr/bin/env bash
# Every `status: ready` case in the attestation catalog, all lanes at once, on one demo account.
# The shadow lane shares one daemon; every other case gets its own daemon and its own magic, so
# they overlap freely. Wall-clock is the slowest case, not the sum. One verdict, one result file.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
    cat <<'USAGE'
Usage: run-attestation-catalog.sh --out DIR --gateway-url URL --expected-login N
         --expected-server NAME --magic-base N --arm I_UNDERSTAND_DEMO_ORDER_0.01
         [--lanes shadow,orders,risk,book,engine,daemon,stress[,stocks][,derivatives][,bybit][,alpaca][,cross]] [--max-parallel N]
         [--crypto-symbols]   (the shadow lane on 24/7 symbols, for a weekend run)
         [--cli PATH] [--deriv-gateway-url URL --deriv-expected-login LOGIN]
         [--bybit-gateway-url URL --bybit-expected-login LOGIN] [--alpaca-gateway-url URL --alpaca-expected-login LOGIN]
         [--cross-venue LABEL=URL@LOGIN ...] [--binding FILE]

Needs QKT_BROKER_API_KEY and QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY. Demo, loopback only.
The derivatives lane (futures, perpetuals, options) runs on a VGP gateway account instead: it needs the
--deriv-* flags and QKT_DERIV_GATEWAY_KEY, and its cases run one after another (one netting account).
The bybit lane is the same on a gateway running the Bybit adapter: the --bybit-* flags and
QKT_BYBIT_GATEWAY_KEY (QKT_BYBIT_GUARDIAN_KEY for its kill-switch drill); it runs beside the derivatives lane.
The cross lane runs one daemon on this MT5 demo and the gateway venues its cases name: one --cross-venue flag per
gateway venue (its broker prefix, URL and login), its trader token in QKT_CROSS_<LABEL>_KEY (its guardian token in
QKT_CROSS_<LABEL>_GUARDIAN_KEY for a kill-switch drill). Its cases run one after another, each under its own magic.
Writes DIR/result.json: per-case verdicts, the capabilities proven, the wall-clock, and
`status: passed` only when every ready case passed. Exits non-zero otherwise.
At most --max-parallel daemons (default 8) run at once: one gateway serves them all, and past that
it answers late enough that quotes stall and modifies time out, which fails cases for the harness's
reasons, not the engine's.

Every attempt of a case is recorded in DIR/attempts/<name>.jsonl (lib/attest_cases.py) with its failure class. A failed
assertion earns one rerun; a gateway or venue failure up to three, after 30, 60 and 120 s; a missing tool, a full disk or
a closed market none. A retry reruns the whole case, and every attempt stays in the result. --binding FILE (the build the
evidence belongs to) lets DIR exist from an earlier run on the same binding: passed cases are kept, only the rest run.
Session-bound lanes start first: the catalog's slowest lane must not start its window late behind the 24/7 cases.
USAGE
}
fail() { printf 'run-attestation-catalog: %s\n' "$1" >&2; exit 1; }

out=""; gateway_url=""; expected_login=""; expected_server=""; magic_base=""; arm=""; binding=""
lanes="shadow,orders,risk,book,engine,daemon,stress"; cli="$repo_root/build/install/qkt/bin/qkt"
max_parallel=8; deriv_gateway_url=""; deriv_expected_login=""; bybit_gateway_url=""; bybit_expected_login=""
alpaca_gateway_url=""; alpaca_expected_login=""; cross_venues=()
while [ "$#" -gt 0 ]; do
    case "$1" in
        --out) out="${2:-}"; shift 2 ;;
        --gateway-url) gateway_url="${2:-}"; shift 2 ;;
        --expected-login) expected_login="${2:-}"; shift 2 ;;
        --expected-server) expected_server="${2:-}"; shift 2 ;;
        --magic-base) magic_base="${2:-}"; shift 2 ;;
        --arm) arm="${2:-}"; shift 2 ;;
        --lanes) lanes="${2:-}"; shift 2 ;;
        --crypto-symbols) crypto_symbols=true; shift ;;
        --max-parallel) max_parallel="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --binding) binding="${2:-}"; shift 2 ;;
        --deriv-gateway-url) deriv_gateway_url="${2:-}"; shift 2 ;;
        --deriv-expected-login) deriv_expected_login="${2:-}"; shift 2 ;;
        --bybit-gateway-url) bybit_gateway_url="${2:-}"; shift 2 ;;
        --bybit-expected-login) bybit_expected_login="${2:-}"; shift 2 ;;
        --alpaca-gateway-url) alpaca_gateway_url="${2:-}"; shift 2 ;;
        --alpaca-expected-login) alpaca_expected_login="${2:-}"; shift 2 ;;
        --cross-venue) cross_venues+=(--venue "${2:-}"); shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done
for v in out gateway_url expected_login expected_server magic_base; do [ -n "${!v}" ] || { usage >&2; exit 2; }; done
[ "$arm" = "I_UNDERSTAND_DEMO_ORDER_0.01" ] || fail "--arm I_UNDERSTAND_DEMO_ORDER_0.01 is required"
[[ "$max_parallel" =~ ^[1-9][0-9]*$ ]] || fail "--max-parallel must be a positive integer"
if [ -e "$out" ]; then
    [ -n "$binding" ] && [ -f "$out/binding.json" ] || fail "output already exists: $out"
    cmp -s <(jq -S . "$binding") <(jq -S . "$out/binding.json") || fail "$out belongs to another build, profile or account"
fi
[ -x "$cli" ] || fail "qkt CLI is not executable: $cli"
python3 "$repo_root/attestation/lib/validate.py" > /dev/null || fail "the case catalog does not validate"

mkdir -p "$out/logs"
lib="$repo_root/scripts/live-validation/lib"
cases_tool() { python3 "$lib/attest_cases.py" "$@"; }
run_no=1; [ ! -f "$out/runs" ] || run_no=$(($(cat "$out/runs") + 1)); echo "$run_no" > "$out/runs"
[ -z "$binding" ] || [ -f "$out/binding.json" ] || cp "$binding" "$out/binding.json"
rm -f "$out/result.json"
declare -A kept=()
if [ -n "$binding" ]; then
    while read -r name; do [ -z "$name" ] || kept["$name"]=1; done < <(cases_tool reusable --out "$out" --binding "$binding")
fi
expected="$out/expected-cases"; : > "$expected"
started="$(date +%s)"; started_at="$(date -u +%FT%TZ)"
common=(--gateway-url "$gateway_url" --expected-login "$expected_login" --expected-server "$expected_server" --cli "$cli")
# A failed case's next attempt gets a fresh magic, and a later run starts past an earlier run's.
pids=(); names=(); magic=$((magic_base + (run_no - 1) % 8 * 100))
declare -A case_runner case_path case_started case_dirname

launch() {  # name command...
    local name="$1"; shift
    while [ "$(jobs -rp | wc -l)" -ge "$max_parallel" ]; do sleep 2; done
    ( "$@" ) > "$out/logs/$name.log" 2>&1 &
    pids+=("$!"); names+=("$name")
}
# One attempt of a magic-lane case, in its own directory.
launch_case() {  # name magic
    local name="$1" tail runner=(bash "$repo_root/scripts/live-validation/run-order-lane-case.sh")
    [ "${case_runner[$name]}" = steps ] && runner=(python3 "$repo_root/scripts/live-validation/run-daemon-lane-case.py")
    tail="$(cases_tool suffix --out "$out" --name "$name")"
    case_started["$name"]="$(date -u +%FT%TZ)"; case_dirname["$name"]="$name$tail"
    launch "$name" "${runner[@]}" --case "${case_path[$name]}" --out "$out/$name$tail" "${common[@]}" --magic "$2" --arm "$arm"
}
# One gateway case and its retries, one after another on the lane's account.
gateway_case() {  # name command...   (the command takes --out last)
    local name="$1" tail code when status cause action delay; shift
    while :; do
        tail="$(cases_tool suffix --out "$out" --name "$name")"; when="$(date -u +%FT%TZ)"; code=0
        "$@" --out "$out/$name$tail" > "$out/logs/$name$tail.log" 2>&1 || code=$?
        tail -n 1 "$out/logs/$name$tail.log"
        read -r status cause action delay < <(cases_tool record --out "$out" --name "$name" --dir "$out/$name$tail" \
            --log "$out/logs/$name$tail.log" --exit "$code" --started "$when" --require-result)
        [ "$action" = retry ] || { [ "$status" = passed ]; return; }
        printf 'retrying %s after %ss: %s\n' "$name" "$delay" "$cause"
        sleep "$delay"
    done
}
# The market itself is the only limit: a session-bound case that can no longer run fails, by name, as `session`.
can_start() { python3 "$lib/attest_session.py" can-start --lanes "$repo_root/attestation/lanes.yaml" --case-yaml "$1"; }
session_closed() {  # name
    cases_tool record --out "$out" --name "$1" --dir "$out/$1" --log /dev/null --exit 1 --started "$(date -u +%FT%TZ)" --session-closed > /dev/null
}

if [[ ",$lanes," == *,shadow,* ]]; then
    echo shadow >> "$expected"
    if [ -z "${kept[shadow]:-}" ]; then
        tail="$(cases_tool suffix --out "$out" --name shadow)"; case_dirname[shadow]="shadow$tail"; case_started[shadow]="$started_at"
        launch shadow bash "$repo_root/scripts/live-validation/run-shadow-lane.sh" --out "$out/shadow$tail" "${common[@]}" --magic "$magic" \
            ${crypto_symbols:+--crypto-symbols}
        magic=$((magic + 1))
    fi
fi
# A venue-gateway lane (derivatives: Deribit; bybit: the Bybit adapter) runs its ready cases one after another on
# its own netting account; the lanes run beside each other.
run_gateway_lane() {  # lane url login key guardian
    local lane="$1" url="$2" login="$3" id code=0
    export QKT_DERIV_GATEWAY_KEY="$4" QKT_DERIV_GUARDIAN_KEY="$5"
    for case_yaml in "$repo_root/attestation/cases/$lane"/*/case.yaml; do
        [ "$(python3 -c 'import sys,yaml; print(yaml.safe_load(open(sys.argv[1]))["status"])' "$case_yaml")" = ready ] ||
            continue
        id="$(basename "$(dirname "$case_yaml")")"
        [ -z "${kept[$lane-$id]:-}" ] || continue
        if [ "$lane" = alpaca ] && ! can_start "$case_yaml"; then session_closed "$lane-$id"; code=1; continue; fi
        gateway_case "$lane-$id" python3 "$repo_root/scripts/live-validation/run-derivatives-lane-case.py" \
            --case "$repo_root/attestation/cases/$lane/$id" \
            --gateway-url "$url" --expected-login "$login" --arm "$arm" --cli "$cli" || code=1
    done
    return "$code"
}
gateway_lane() {  # lane url login key guardian
    local lane="$1" case_yaml name pending=0
    for case_yaml in "$repo_root/attestation/cases/$lane"/*/case.yaml; do
        [ "$(python3 -c 'import sys,yaml; print(yaml.safe_load(open(sys.argv[1]))["status"])' "$case_yaml")" = ready ] || continue
        name="$lane-$(basename "$(dirname "$case_yaml")")"; echo "$name" >> "$expected"
        [ -n "${kept[$name]:-}" ] || pending=1
    done
    [ "$pending" = 0 ] || launch "$lane" run_gateway_lane "$@"
}
# The alpaca lane's longest path decides the catalog's wall-clock, and its shares trade only while the market is
# open, so it starts first. One case at a time on its own account: it takes one of --max-parallel's slots.
if [[ ",$lanes," == *,alpaca,* ]]; then
    [ -n "$alpaca_gateway_url" ] && [ -n "$alpaca_expected_login" ] ||
        fail "--lanes alpaca needs --alpaca-gateway-url and --alpaca-expected-login"
    [ -n "${QKT_ALPACA_GATEWAY_KEY:-}" ] || fail "--lanes alpaca needs QKT_ALPACA_GATEWAY_KEY"
    gateway_lane alpaca "$alpaca_gateway_url" "$alpaca_expected_login" "$QKT_ALPACA_GATEWAY_KEY" \
        "${QKT_ALPACA_GUARDIAN_KEY:-}"
fi
# The stocks lane trades US shares, so it runs only inside the lane's session window (attestation/lanes.yaml) while
# the venue still quotes them. Neither alone is enough: Exness quotes AAPL from 06:00 New York but refuses opening
# orders until 09:40, and only the venue's own last tick can say a holiday or an early close.
stocks_session_open() {
    local tick now
    python3 "$lib/in_session_window.py" "$repo_root/attestation/lanes.yaml" stocks || return 1
    tick="$(curl --silent --fail --max-time 10 "$gateway_url/symbol_info_tick/AAPLm" | python3 -c 'import json,sys; print(int(json.load(sys.stdin).get("time", 0)))' 2>/dev/null)" || return 1
    now="$(date +%s)"
    [ $((now - tick)) -lt 120 ]
}
# Whether any stocks case still has to run: a resumed run that kept them all does not need the market open.
stocks_pending() {
    local case_yaml
    for case_yaml in "$repo_root/attestation/cases/stocks"/*/case.yaml; do
        grep -q '^status: ready' "$case_yaml" || continue
        [ -n "${kept[stocks-$(basename "$(dirname "$case_yaml")")]:-}" ] || return 0
    done
    return 1
}
if [[ ",$lanes," == *,stocks,* ]] && stocks_pending && ! stocks_session_open; then
    fail "the stocks lane needs the venue's US share session open ($(python3 -c 'import sys,yaml; print(yaml.safe_load(open(sys.argv[1]))["lanes"]["stocks"]["session"])' "$repo_root/attestation/lanes.yaml") New York) and a fresh AAPL quote"
fi
for lane in stocks orders risk book engine daemon stress; do
    [[ ",$lanes," == *,"$lane",* ]] || continue
    for case_yaml in "$repo_root/attestation/cases/$lane"/*/case.yaml; do
        [ -f "$case_yaml" ] || continue
        case_dir="$(dirname "$case_yaml")"; id="$(basename "$case_dir")"
        ready="$(python3 -c 'import sys,yaml; d=yaml.safe_load(open(sys.argv[1])); print(d["status"], "steps" if d.get("steps") else "strategy")' "$case_yaml")"
        [[ "$ready" == ready* ]] || continue
        name="$lane-$id"; echo "$name" >> "$expected"
        [ -z "${kept[$name]:-}" ] || continue
        if [ -n "${crypto_symbols:-}" ] && [ "$lane" = stress ]; then
            # The stress lane observes live bars on FX and metals too; on a weekend they print none, so a run
            # with --crypto-symbols moves a copy of the case to 24/7 symbols, as the shadow lane does.
            moved="$out/crypto-cases/$lane-$id"; rm -rf "$moved"; mkdir -p "$(dirname "$moved")"; cp -r "$case_dir" "$moved"
            find "$moved" -name '*.qkt' -exec python3 "$lib/crypto-symbols.py" {} + ||
                fail "could not move $lane/$id to 24/7 symbols"
            case_dir="$moved"
        fi
        case_path["$name"]="$case_dir"
        [[ "$ready" == *steps ]] && case_runner["$name"]=steps || case_runner["$name"]=orders
        launch_case "$name" "$magic"
        magic=$((magic + 1))
    done
done
if [[ ",$lanes," == *,derivatives,* ]]; then
    [ -n "$deriv_gateway_url" ] && [ -n "$deriv_expected_login" ] ||
        fail "--lanes derivatives needs --deriv-gateway-url and --deriv-expected-login"
    gateway_lane derivatives "$deriv_gateway_url" "$deriv_expected_login" "${QKT_DERIV_GATEWAY_KEY:-}" \
        "${QKT_DERIV_GUARDIAN_KEY:-}"
fi
if [[ ",$lanes," == *,bybit,* ]]; then
    [ -n "$bybit_gateway_url" ] && [ -n "$bybit_expected_login" ] ||
        fail "--lanes bybit needs --bybit-gateway-url and --bybit-expected-login"
    [ -n "${QKT_BYBIT_GATEWAY_KEY:-}" ] || fail "--lanes bybit needs QKT_BYBIT_GATEWAY_KEY"
    gateway_lane bybit "$bybit_gateway_url" "$bybit_expected_login" "$QKT_BYBIT_GATEWAY_KEY" \
        "${QKT_BYBIT_GUARDIAN_KEY:-}"
fi
# The cross lane: one daemon per case on the MT5 demo (its own magic) and the gateway venues the case names, one case
# after another, since a case's gateway legs and drills (a kill switch, an outage) are its venue's for the whole case.
# Each case reserves four magics, one per possible attempt.
cross_attempt() {  # id base-magic --out DIR
    local n; n="$(ls -d "$4" "${4%-retry*}"-retry* 2>/dev/null | wc -l)"
    python3 "$repo_root/scripts/live-validation/run-cross-lane-case.py" --case "$repo_root/attestation/cases/cross/$1" \
        --out "$4" "${common[@]}" --magic "$(($2 + n % 4))" --arm "$arm" "${cross_venues[@]}"
}
run_cross_lane() {  # id:magic ...
    local entry id code=0
    for entry in "$@"; do
        id="${entry%%:*}"
        gateway_case "cross-$id" cross_attempt "$id" "${entry##*:}" || code=1
    done
    return "$code"
}
if [[ ",$lanes," == *,cross,* ]]; then
    [ "${#cross_venues[@]}" -gt 0 ] || fail "--lanes cross needs a --cross-venue LABEL=URL@LOGIN per gateway venue"
    cross_cases=()
    for case_yaml in "$repo_root/attestation/cases/cross"/*/case.yaml; do
        [ "$(python3 -c 'import sys,yaml; print(yaml.safe_load(open(sys.argv[1]))["status"])' "$case_yaml")" = ready ] ||
            continue
        id="$(basename "$(dirname "$case_yaml")")"; echo "cross-$id" >> "$expected"
        [ -n "${kept[cross-$id]:-}" ] || { cross_cases+=("$id:$magic"); magic=$((magic + 4)); }
    done
    [ "${#cross_cases[@]}" -eq 0 ] || launch cross run_cross_lane "${cross_cases[@]}"
fi
[ -s "$expected" ] || fail "no ready case in lanes: $lanes"

# Waits for what was launched and records each magic-lane attempt; `again` holds the ones that earn another.
record_round() {
    local i name code status cause action delay flags
    again=(); longest=0
    for i in "${!pids[@]}"; do
        name="${names[$i]}"; code=0; wait "${pids[$i]}" || code=$?
        case "$name" in derivatives|bybit|alpaca|cross) continue ;; esac  # recorded case by case as they ran
        flags=(); [ "$name" != shadow ] || flags=(--no-retry)
        cp "$out/logs/$name.log" "$out/logs/${case_dirname[$name]}.attempt.log" 2>/dev/null || true
        read -r status cause action delay < <(cases_tool record --out "$out" --name "$name" "${flags[@]}" \
            --dir "$out/${case_dirname[$name]}" --log "$out/logs/$name.log" --exit "$code" --started "${case_started[$name]}")
        [ "$action" = retry ] || continue
        again+=("$name"); [ "$delay" -le "$longest" ] || longest="$delay"
    done
    pids=(); names=()
}
# Every case enters on the same bar close, so the one gateway takes a burst the engine never sees in production; a
# case can fail on that contention alone. Failed cases rerun together in much quieter rounds, each logged. A case passes
# only if its last attempt passes, its earlier failures stay in the result, and the shadow lane is never retried:
# it is the parity evidence of one daemon's session.
record_round
while [ "${#again[@]}" -gt 0 ]; do
    printf 'retrying after %ss: %s\n' "$longest" "${again[*]}"
    sleep "$longest"
    for name in "${again[@]}"; do
        if [ "${name%%-*}" = stocks ] && ! stocks_session_open; then session_closed "$name"; continue; fi
        launch_case "$name" "$magic"; magic=$((magic + 1))
    done
    record_round
done

code=0
cases_tool finalize --out "$out" --expected "$expected" --started "$started_at" \
    --cli "$("$cli" --version | head -n 1)" --max-parallel "$max_parallel" --seconds "$(( $(date +%s) - started ))" || code=$?
exit "$code"
