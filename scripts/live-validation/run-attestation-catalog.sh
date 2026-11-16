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
         [--deriv-drill-gateway-url URL ...] [--deriv-parallel N]
         [--bybit-gateway-url URL --bybit-expected-login LOGIN] [--alpaca-gateway-url URL --alpaca-expected-login LOGIN]
         [--cross-venue LABEL=URL@LOGIN ...] [--binding FILE] [--session-wait-max-h H]
         [--venue-wait-max SECONDS] [--deribit-public-url URL]

Needs QKT_BROKER_API_KEY and QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY. Demo, loopback only.
The derivatives lane (futures, perpetuals, options) runs on a VGP gateway account instead: it needs the
--deriv-* flags and QKT_DERIV_GATEWAY_KEY. One netting account, so its cases run one after another, unless
--deriv-drill-gateway-url names a second gateway process on the same account for each kill_switch case (the switch is
per process; attestation/README.md, lib/deriv_parallel.py): then the lane's `group:`s of cases, which trade disjoint
instruments, run side by side, at most --deriv-parallel (default 4) at once, each case holding a lock per instrument
class it trades, and the cross lane's Deribit leg queues behind the same locks.
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
Session-bound lanes (stocks, alpaca: a `session` window in attestation/lanes.yaml) are planned, never refused at the
start (lib/attest_session.py lane-plan). Inside a window they fit in, they start first, so they finish before the close.
Outside it, every other lane runs now and the session lane waits in this process for the next window it fits in (each
wait logged and recorded in DIR/session-waits.jsonl), up to --session-wait-max-h hours (default 72) from the start;
past that its cases fail as `session`, by name. Whatever the plan says, no session-bound case starts unless it can
finish before the close (attest_session.py can-start); one that cannot fails as `session` and a resumed run reruns it.

A gateway lane case (derivatives, bybit, alpaca, cross) first waits for its venue (lib/venue_health.py: the gateway's
venue link and, on Deribit, each of its instruments `open` with a fresh ticker on --deribit-public-url, default the
testnet's API), up to --venue-wait-max seconds (default 21600), and samples the venue while it runs. An attempt that
failed while those samples show the venue unavailable is recorded as `venue-outage` with its evidence and reruns once the
venue is back without spending the case's budget; a venue still down past the maximum fails the case as
`venue-unavailable` (no retry), which a resumed release reruns. Each wait goes to the case log and, when
QKT_ATTEST_NOTICE_FD names an open descriptor, to the release log.
USAGE
}
fail() { printf 'run-attestation-catalog: %s\n' "$1" >&2; exit 1; }

out=""; gateway_url=""; expected_login=""; expected_server=""; magic_base=""; arm=""; binding=""
lanes="shadow,orders,risk,book,engine,daemon,stress"; cli="$repo_root/build/install/qkt/bin/qkt"
max_parallel=8; deriv_gateway_url=""; deriv_expected_login=""; bybit_gateway_url=""; bybit_expected_login=""
deriv_drill_urls=(); deriv_parallel=4
alpaca_gateway_url=""; alpaca_expected_login=""; cross_venues=(); session_wait_max_h=72
venue_wait_max=21600; deribit_public_url="https://test.deribit.com/api/v2"
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
        --deriv-drill-gateway-url) deriv_drill_urls+=("${2:-}"); shift 2 ;;
        --deriv-parallel) deriv_parallel="${2:-}"; shift 2 ;;
        --bybit-gateway-url) bybit_gateway_url="${2:-}"; shift 2 ;;
        --bybit-expected-login) bybit_expected_login="${2:-}"; shift 2 ;;
        --alpaca-gateway-url) alpaca_gateway_url="${2:-}"; shift 2 ;;
        --alpaca-expected-login) alpaca_expected_login="${2:-}"; shift 2 ;;
        --cross-venue) cross_venues+=(--venue "${2:-}"); shift 2 ;;
        --session-wait-max-h) session_wait_max_h="${2:-}"; shift 2 ;;
        --venue-wait-max) venue_wait_max="${2:-}"; shift 2 ;;
        --deribit-public-url) deribit_public_url="${2:-}"; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done
for v in out gateway_url expected_login expected_server magic_base; do [ -n "${!v}" ] || { usage >&2; exit 2; }; done
[ "$arm" = "I_UNDERSTAND_DEMO_ORDER_0.01" ] || fail "--arm I_UNDERSTAND_DEMO_ORDER_0.01 is required"
[[ "$max_parallel" =~ ^[1-9][0-9]*$ ]] || fail "--max-parallel must be a positive integer"
[[ "$deriv_parallel" =~ ^[1-9][0-9]*$ ]] || fail "--deriv-parallel must be a positive integer"
[[ "$session_wait_max_h" =~ ^[0-9]+([.][0-9]+)?$ ]] || fail "--session-wait-max-h must be a number of hours"
[[ "$venue_wait_max" =~ ^[0-9]+$ ]] || fail "--venue-wait-max must be a whole number of seconds"
export QKT_ATTEST_VENUE_WAIT_MAX_S="$venue_wait_max" QKT_ATTEST_DERIBIT_URL="$deribit_public_url"
if [ -e "$out" ]; then
    [ -n "$binding" ] && [ -f "$out/binding.json" ] || fail "output already exists: $out"
    cmp -s <(jq -S . "$binding") <(jq -S . "$out/binding.json") || fail "$out belongs to another build, profile or account"
fi
[ -x "$cli" ] || fail "qkt CLI is not executable: $cli"
(cd "$repo_root" && python3 attestation/lib/validate.py) > /dev/null || fail "the case catalog does not validate"

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
for name in "${!kept[@]}"; do echo "$name"; done > "$out/kept-cases"
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
# A venue wait or outage rerun, in the catalog log and, when the release opened one, its own log.
venue_notice() {
    printf '%s venue: %s\n' "$(date -u +%T)" "$1"
    if [ -n "${QKT_ATTEST_NOTICE_FD:-}" ] && { true >&"$QKT_ATTEST_NOTICE_FD"; } 2>/dev/null; then
        printf '%s venue: %s\n' "$(date -u +%T)" "$1" >&"$QKT_ATTEST_NOTICE_FD"
    fi
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
        if [ "$cause" = venue-outage ]; then
            venue_notice "$name failed while its venue was unavailable (evidence in attempts/$name.jsonl); rerunning after ${delay}s without spending an attempt"
        else
            printf 'retrying %s after %ss: %s\n' "$name" "$delay" "$cause"
        fi
        sleep "$delay"
    done
}
# The market itself is the only limit: a session-bound case that can no longer run fails, by name, as `session`; the
# plan only decides when each session lane runs, and this per-case check stands whatever the plan said.
lanes_yaml="$repo_root/attestation/lanes.yaml"
session_tool() { python3 "$lib/attest_session.py" "$@"; }
can_start() { session_tool can-start --lanes "$lanes_yaml" --case-yaml "$1"; }
session_closed() {  # name [reason]
    cases_tool record --out "$out" --name "$1" --dir "$out/$1" --log /dev/null --exit 1 --started "$(date -u +%FT%TZ)" \
        --session-closed ${2:+--reason "$2"} > /dev/null
    printf 'session: %s did not run: %s\n' "$1" "${2:-the market closed before this case could run}"
}
# When each session lane runs: `none` or `now` (first, inside its window), `wait START TEXT`, or `fail TEXT`.
session_deadline="$(session_tool deadline --hours "$session_wait_max_h")"
lane_plan() { session_tool lane-plan --lanes "$lanes_yaml" --repo-root "$repo_root" --lane "$1" --deadline "$session_deadline" --keep-file "$out/kept-cases"; }
declare -A session_verdict=() session_text=() session_start=()
for lane in alpaca stocks; do
    [[ ",$lanes," == *,"$lane",* ]] || continue
    read -r verdict rest < <(lane_plan "$lane")
    session_verdict["$lane"]="$verdict"
    case "$verdict" in
        wait) session_start["$lane"]="${rest%% *}"; session_text["$lane"]="${rest#* }" ;;
        now) session_text["$lane"]="${rest#* }" ;;
        fail) session_text["$lane"]="$rest" ;;
    esac
    [ "$verdict" = none ] || printf 'session plan: %s %s: %s\n' "$lane" "$verdict" "${session_text[$lane]}"
done
runs_now() { [ "${session_verdict[$1]:-none}" = none ] || [ "${session_verdict[$1]}" = now ]; }

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
        if [ "$lane" = alpaca ] && ! can_start "$case_yaml"; then
            session_closed "$lane-$id" "not enough of the $lane window was left to start it and finish before the close"; code=1; continue
        fi
        gateway_case "$lane-$id" with_claims "$repo_root/attestation/cases/$lane/$id" \
            python3 "$repo_root/scripts/live-validation/run-derivatives-lane-case.py" \
            --case "$repo_root/attestation/cases/$lane/$id" \
            --gateway-url "$url" --expected-login "$login" --arm "$arm" --cli "$cli" || code=1
    done
    return "$code"
}
# The derivatives lane's groups of cases side by side, on one account (lib/deriv_parallel.py): a group's cases one after
# another, a kill_switch case on a gateway of its own, every case under a lock per instrument class it trades.
run_derivatives_group() {  # group plan-file login
    local group="$1" plan="$2" login="$3" gid id gurl code=0
    while IFS=$'\t' read -r -u 3 gid id gurl; do
        [ "$gid" = "$group" ] || continue
        gateway_case "derivatives-$id" with_claims "$repo_root/attestation/cases/derivatives/$id" \
            python3 "$repo_root/scripts/live-validation/run-derivatives-lane-case.py" \
            --case "$repo_root/attestation/cases/derivatives/$id" \
            --gateway-url "$gurl" --expected-login "$login" --arm "$arm" --cli "$cli" || code=1
    done 3< "$plan"
    return "$code"
}
run_derivatives_groups() {  # url login key guardian
    local url="$1" login="$2" plan="$out/derivatives-plan.tsv" group code=0 gpids=() p
    export QKT_DERIV_GATEWAY_KEY="$3" QKT_DERIV_GUARDIAN_KEY="$4" QKT_ATTEST_DERIV_PARALLEL=1
    python3 "$lib/deriv_parallel.py" plan --cases "$repo_root/attestation/cases/derivatives" --base-url "$url" \
        --drill-urls "$(IFS=,; echo "${deriv_drill_urls[*]}")" --kept "$out/kept-cases" > "$plan" || return 1
    python3 "$lib/deriv_parallel.py" check-gateways --urls "$(IFS=,; echo "${deriv_drill_urls[*]}")" --login "$login" \
        --base-url "$url" || return 1
    printf 'derivatives: %s groups, at most %s at once: %s\n' "$(cut -f1 "$plan" | sort -u | wc -l)" "$deriv_parallel" \
        "$(cut -f1 "$plan" | awk '!seen[$0]++' | tr '\n' ' ')"
    for group in $(cut -f1 "$plan" | awk '!seen[$0]++'); do
        while [ "$(jobs -rp | wc -l)" -ge "$deriv_parallel" ]; do sleep 1; done
        run_derivatives_group "$group" "$plan" "$login" &
        gpids+=("$!")
    done
    for p in "${gpids[@]}"; do wait "$p" || code=1; done
    return "$code"
}
# [command...] under a lock per instrument class the case at [case-dir] trades (lib/claim_lock.py).
with_claims() {  # case-dir command...
    local dir="$1" claims; shift
    mapfile -t claims < <(python3 "$lib/deriv_parallel.py" claims --case "$dir")
    python3 "$lib/claim_lock.py" hold --dir "$out/claims" "${claims[@]}" -- "$@"
}
gateway_lane() {  # lane url login key guardian
    local lane="$1" case_yaml name pending=0
    for case_yaml in "$repo_root/attestation/cases/$lane"/*/case.yaml; do
        [ "$(python3 -c 'import sys,yaml; print(yaml.safe_load(open(sys.argv[1]))["status"])' "$case_yaml")" = ready ] || continue
        name="$lane-$(basename "$(dirname "$case_yaml")")"; echo "$name" >> "$expected"
        [ -n "${kept[$name]:-}" ] || pending=1
    done
    [ "$pending" = 0 ] || ! runs_now "$lane" || launch "$lane" run_gateway_lane "$@"
}
# The derivatives lane in groups when the run has the gateways for it, else one case at a time as always.
derivatives_lane() {  # url login key guardian
    if [ "${#deriv_drill_urls[@]}" -eq 0 ]; then gateway_lane derivatives "$@"; return; fi
    local case_yaml name pending=0
    for case_yaml in "$repo_root/attestation/cases/derivatives"/*/case.yaml; do
        [ "$(python3 -c 'import sys,yaml; print(yaml.safe_load(open(sys.argv[1]))["status"])' "$case_yaml")" = ready ] || continue
        name="derivatives-$(basename "$(dirname "$case_yaml")")"; echo "$name" >> "$expected"
        [ -n "${kept[$name]:-}" ] || pending=1
    done
    [ "$pending" = 0 ] || launch derivatives run_derivatives_groups "$@"
}
# The alpaca lane's longest path decides the catalog's wall-clock, and its shares trade only while the market is
# open, so inside its window it starts first; outside it, it waits for its window after the other lanes (below).
# One case at a time on its own account: it takes one of --max-parallel's slots.
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
    session_tool in-window --lanes "$lanes_yaml" --lane stocks || return 1
    tick="$(curl --silent --fail --max-time 10 "$gateway_url/symbol_info_tick/AAPLm" | python3 -c 'import json,sys; print(int(json.load(sys.stdin).get("time", 0)))' 2>/dev/null)" || return 1
    now="$(date +%s)"
    [ $((now - tick)) -lt 120 ]
}
# Inside the window the venue's quote can still be stale (a holiday, an early close): a few minutes' grace, then the
# lane's cases fail as `session`, named, and a resumed run reruns them in a later window.
stocks_quote_fresh() {
    local tries="${QKT_ATTEST_STOCK_QUOTE_TRIES:-10}"
    while [ "$tries" -gt 0 ]; do
        stocks_session_open && return 0
        tries=$((tries - 1)); [ "$tries" = 0 ] || sleep 60
    done
    return 1
}
# The stocks cases to run, launched together when their window lets them (now, or after the wait below).
stocks_cases=()
launch_stocks() {
    local name reason=""
    [ "${#stocks_cases[@]}" -gt 0 ] || return 0
    stocks_quote_fresh || reason="the stocks window is open but the venue's AAPL quote is stale (a holiday or an early close)"
    for name in "${stocks_cases[@]}"; do
        if [ -n "$reason" ]; then session_closed "$name" "$reason"
        elif ! can_start "${case_path[$name]}/case.yaml"; then
            session_closed "$name" "not enough of the stocks window was left to start it and finish before the close"
        else launch_case "$name" "$magic"; magic=$((magic + 1)); fi
    done
    stocks_cases=()
}
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
        if [ "$lane" = stocks ]; then stocks_cases+=("$name"); continue; fi
        launch_case "$name" "$magic"
        magic=$((magic + 1))
    done
    # The stocks lane goes before every other magic lane when its window is open now; otherwise it waits (below).
    [ "$lane" != stocks ] || ! runs_now stocks || launch_stocks
done
if [[ ",$lanes," == *,derivatives,* ]]; then
    [ -n "$deriv_gateway_url" ] && [ -n "$deriv_expected_login" ] ||
        fail "--lanes derivatives needs --deriv-gateway-url and --deriv-expected-login"
    derivatives_lane "$deriv_gateway_url" "$deriv_expected_login" "${QKT_DERIV_GATEWAY_KEY:-}" "${QKT_DERIV_GUARDIAN_KEY:-}"
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
    # Its Deribit leg trades an instrument a derivatives group may hold: it queues behind that group's lock.
    with_claims "$repo_root/attestation/cases/cross/$1" \
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
        case "$name" in  # recorded case by case as they ran
            derivatives|bybit|alpaca|cross)
                [ "$code" = 0 ] || grep -h '^deriv-parallel:' "$out/logs/$name.log" 2>/dev/null || true  # why a plan did not start
                continue ;;
        esac
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
drain() {
    record_round
    while [ "${#again[@]}" -gt 0 ]; do
        printf 'retrying after %ss: %s\n' "$longest" "${again[*]}"
        sleep "$longest"
        for name in "${again[@]}"; do
            if [ "${name%%-*}" = stocks ] && { ! stocks_session_open || ! can_start "${case_path[$name]}/case.yaml"; }; then
                session_closed "$name" "its retry could not finish inside the stocks window"; continue
            fi
            launch_case "$name" "$magic"; magic=$((magic + 1))
        done
        record_round
    done
}
drain

# Session lanes the run reached outside a window they fit in: each waits, in turn by its planned start, for that window,
# then runs. The plan is asked again after the other lanes and after every wait, so a late finish or a moved clock
# re-plans from the moment it is; a lane that can no longer be reached in time fails its cases, by name, as `session`.
lane_cases_pending() {  # lane: prints the lane's ready cases not kept
    local case_yaml name
    for case_yaml in "$repo_root/attestation/cases/$1"/*/case.yaml; do
        [ -f "$case_yaml" ] && grep -q '^status: ready' "$case_yaml" || continue
        name="$1-$(basename "$(dirname "$case_yaml")")"; [ -n "${kept[$name]:-}" ] || echo "$name"
    done
}
later=()
for lane in "${!session_verdict[@]}"; do
    runs_now "$lane" || later+=("${session_start[$lane]:-0000} $lane")
done
if [ "${#later[@]}" -gt 0 ]; then
    while read -r -u 3 _ lane; do
        verdict="${session_verdict[$lane]}"; text="${session_text[$lane]}"
        for _ in 1 2 3 4 5; do
            [ "$verdict" = wait ] || break
            read -r verdict rest < <(lane_plan "$lane")
            case "$verdict" in
                wait) session_tool wait-until --until "${rest%% *}" --lane "$lane" --reason "${rest#* }" --record "$out/session-waits.jsonl" ;;
                fail) text="$rest" ;;
            esac
        done
        if [ "$verdict" = fail ] || [ "$verdict" = wait ]; then
            while read -r name; do session_closed "$name" "$text"; done < <(lane_cases_pending "$lane")
            continue
        fi
        session_verdict["$lane"]=now
        case "$lane" in
            alpaca) launch alpaca run_gateway_lane alpaca "$alpaca_gateway_url" "$alpaca_expected_login" "$QKT_ALPACA_GATEWAY_KEY" "${QKT_ALPACA_GUARDIAN_KEY:-}" ;;
            stocks) launch_stocks ;;
        esac
    done 3< <(printf '%s\n' "${later[@]}" | sort)
    drain
fi

code=0
cases_tool finalize --out "$out" --expected "$expected" --started "$started_at" \
    --cli "$("$cli" --version | head -n 1)" --max-parallel "$max_parallel" --seconds "$(( $(date +%s) - started ))" || code=$?
exit "$code"
