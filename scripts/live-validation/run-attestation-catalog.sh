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
         [--lanes shadow,orders,risk,book,engine,daemon,stress] [--max-parallel N] [--cli PATH]

Needs QKT_BROKER_API_KEY and QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY. Demo, loopback only.
Writes DIR/result.json: per-case verdicts, the capabilities proven, the wall-clock, and
`status: passed` only when every ready case passed. Exits non-zero otherwise.
At most --max-parallel daemons (default 8) run at once: one gateway serves them all, and past that
it answers late enough that quotes stall and modifies time out, which fails cases for the harness's
reasons, not the engine's.
USAGE
}
fail() { printf 'run-attestation-catalog: %s\n' "$1" >&2; exit 1; }

out=""; gateway_url=""; expected_login=""; expected_server=""; magic_base=""; arm=""
lanes="shadow,orders,risk,book,engine,daemon,stress"; cli="$repo_root/build/install/qkt/bin/qkt"
max_parallel=8
while [ "$#" -gt 0 ]; do
    case "$1" in
        --out) out="${2:-}"; shift 2 ;;
        --gateway-url) gateway_url="${2:-}"; shift 2 ;;
        --expected-login) expected_login="${2:-}"; shift 2 ;;
        --expected-server) expected_server="${2:-}"; shift 2 ;;
        --magic-base) magic_base="${2:-}"; shift 2 ;;
        --arm) arm="${2:-}"; shift 2 ;;
        --lanes) lanes="${2:-}"; shift 2 ;;
        --max-parallel) max_parallel="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done
for v in out gateway_url expected_login expected_server magic_base; do [ -n "${!v}" ] || { usage >&2; exit 2; }; done
[ "$arm" = "I_UNDERSTAND_DEMO_ORDER_0.01" ] || fail "--arm I_UNDERSTAND_DEMO_ORDER_0.01 is required"
[[ "$max_parallel" =~ ^[1-9][0-9]*$ ]] || fail "--max-parallel must be a positive integer"
[ ! -e "$out" ] || fail "output already exists: $out"
[ -x "$cli" ] || fail "qkt CLI is not executable: $cli"
python3 "$repo_root/attestation/lib/validate.py" > /dev/null || fail "the case catalog does not validate"

mkdir -p "$out/logs"
started="$(date +%s)"; started_at="$(date -u +%FT%TZ)"
common=(--gateway-url "$gateway_url" --expected-login "$expected_login" --expected-server "$expected_server" --cli "$cli")
pids=(); names=(); magic="$magic_base"
declare -A retry_runner retry_case

launch() {  # name command...
    local name="$1"; shift
    while [ "$(jobs -rp | wc -l)" -ge "$max_parallel" ]; do sleep 2; done
    ( "$@" ) > "$out/logs/$name.log" 2>&1 &
    pids+=("$!"); names+=("$name")
}

if [[ ",$lanes," == *,shadow,* ]]; then
    launch shadow bash "$repo_root/scripts/live-validation/run-shadow-lane.sh" --out "$out/shadow" "${common[@]}" --magic "$magic"
    magic=$((magic + 1))
fi
for lane in orders risk book engine daemon stress; do
    [[ ",$lanes," == *,"$lane",* ]] || continue
    for case_yaml in "$repo_root/attestation/cases/$lane"/*/case.yaml; do
        [ -f "$case_yaml" ] || continue
        case_dir="$(dirname "$case_yaml")"; id="$(basename "$case_dir")"
        ready="$(python3 -c 'import sys,yaml; d=yaml.safe_load(open(sys.argv[1])); print(d["status"], "steps" if d.get("steps") else "strategy")' "$case_yaml")"
        [[ "$ready" == ready* ]] || continue
        if [[ "$ready" == *steps ]]; then
            launch "$lane-$id" python3 "$repo_root/scripts/live-validation/run-daemon-lane-case.py" --case "$case_dir" \
                --out "$out/$lane-$id" "${common[@]}" --magic "$magic" --arm "$arm"
        else
            launch "$lane-$id" bash "$repo_root/scripts/live-validation/run-order-lane-case.sh" --case "$case_dir" \
                --out "$out/$lane-$id" "${common[@]}" --magic "$magic" --arm "$arm"
        fi
        retry_case["$lane-$id"]="$case_dir"
        [[ "$ready" == *steps ]] && retry_runner["$lane-$id"]=steps || retry_runner["$lane-$id"]=orders
        magic=$((magic + 1))
    done
done
[ "${#pids[@]}" -gt 0 ] || fail "no ready case in lanes: $lanes"

codes=()
for i in "${!pids[@]}"; do
    code=0; wait "${pids[$i]}" || code=$?
    codes+=("$code")
done

# Every case enters on the same bar close, so the one gateway takes a burst the engine never sees in
# production; a case can fail on that contention alone (a stalled quote makes the market-data gate refuse
# an entry - correctly). Failed cases therefore get ONE more attempt, together, in a second and much
# quieter wave. A case passes only if that attempt passes, the first failure stays in the result as
# `firstAttempt`, and a case that fails twice fails the run. The shadow lane is never retried: it is
# the parity evidence.
declare -A retry_pid first_line
for i in "${!pids[@]}"; do
    name="${names[$i]}"
    [ "${codes[$i]}" -ne 0 ] && [ -n "${retry_case[$name]:-}" ] || continue
    first_line["$name"]="$(tail -n 1 "$out/logs/$name.log" | cut -c1-300)"
    runner=(bash "$repo_root/scripts/live-validation/run-order-lane-case.sh")
    [ "${retry_runner[$name]}" = steps ] && runner=(python3 "$repo_root/scripts/live-validation/run-daemon-lane-case.py")
    ( "${runner[@]}" --case "${retry_case[$name]}" --out "$out/$name-retry" "${common[@]}" --magic "$magic" --arm "$arm" ) \
        > "$out/logs/$name-retry.log" 2>&1 &
    retry_pid["$name"]="$!"; magic=$((magic + 1))
done

verdicts=()
for i in "${!pids[@]}"; do
    name="${names[$i]}"; code="${codes[$i]}"; last="$out/logs/$name.log"
    if [ -n "${retry_pid[$name]:-}" ]; then
        code=0; wait "${retry_pid[$name]}" || code=$?
        last="$out/logs/$name-retry.log"
    fi
    verdicts+=("$(jq -n --arg name "$name" --argjson exit "$code" --arg first "${first_line[$name]:-}" \
        --arg line "$(tail -n 1 "$last" | cut -c1-300)" \
        '{name:$name, status:(if $exit == 0 then "passed" else "failed" end), summary:$line}
         + (if $first == "" then {} else {retried:true, firstAttempt:$first} end)')")
done

capabilities='[]'
[ -f "$out/shadow/result.json" ] && capabilities="$(jq -c '.capabilities' "$out/shadow/result.json")"
printf '%s\n' "${verdicts[@]}" | jq -s --arg startedAt "$started_at" --arg finishedAt "$(date -u +%FT%TZ)" \
    --argjson seconds "$(( $(date +%s) - started ))" --arg cli "$("$cli" --version | head -n 1)" --argjson maxParallel "$max_parallel" --argjson capabilities "$capabilities" '
    {schema:"qkt-attestation-catalog-run-v1", startedAtUtc:$startedAt, finishedAtUtc:$finishedAt, wallClockSeconds:$seconds,
     cli:$cli, maxParallel:$maxParallel, runs:., capabilitiesProvenLive:($capabilities|length), capabilities:$capabilities,
     retried:[.[]|select(.retried)|.name],
     status:(if all(.[]; .status == "passed") then "passed" else "failed" end)}' > "$out/result.json"
jq -r '"\(.status) runs=\(.runs|length) passed=\([.runs[]|select(.status=="passed")]|length) capabilities=\(.capabilitiesProvenLive) retried=\(.retried|length) wallClock=\(.wallClockSeconds)s"' "$out/result.json"
jq -r '.runs[] | select(.retried) | "  RETRIED \(.name): first attempt: \(.firstAttempt)"' "$out/result.json"
jq -r '.runs[] | select(.status != "passed") | "  FAILED \(.name): \(.summary)"' "$out/result.json"
[ "$(jq -r .status "$out/result.json")" = passed ]
