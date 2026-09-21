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
         [--lanes shadow,orders,risk,book,engine,daemon,stress] [--cli PATH]

Needs QKT_BROKER_API_KEY and QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY. Demo, loopback only.
Writes DIR/result.json: per-case verdicts, the capabilities proven, the wall-clock, and
`status: passed` only when every ready case passed. Exits non-zero otherwise.
USAGE
}
fail() { printf 'run-attestation-catalog: %s\n' "$1" >&2; exit 1; }

out=""; gateway_url=""; expected_login=""; expected_server=""; magic_base=""; arm=""
lanes="shadow,orders,risk,book,engine,daemon,stress"; cli="$repo_root/build/install/qkt/bin/qkt"
while [ "$#" -gt 0 ]; do
    case "$1" in
        --out) out="${2:-}"; shift 2 ;;
        --gateway-url) gateway_url="${2:-}"; shift 2 ;;
        --expected-login) expected_login="${2:-}"; shift 2 ;;
        --expected-server) expected_server="${2:-}"; shift 2 ;;
        --magic-base) magic_base="${2:-}"; shift 2 ;;
        --arm) arm="${2:-}"; shift 2 ;;
        --lanes) lanes="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done
for v in out gateway_url expected_login expected_server magic_base; do [ -n "${!v}" ] || { usage >&2; exit 2; }; done
[ "$arm" = "I_UNDERSTAND_DEMO_ORDER_0.01" ] || fail "--arm I_UNDERSTAND_DEMO_ORDER_0.01 is required"
[ ! -e "$out" ] || fail "output already exists: $out"
[ -x "$cli" ] || fail "qkt CLI is not executable: $cli"
python3 "$repo_root/attestation/lib/validate.py" > /dev/null || fail "the case catalog does not validate"

mkdir -p "$out/logs"
started="$(date +%s)"; started_at="$(date -u +%FT%TZ)"
common=(--gateway-url "$gateway_url" --expected-login "$expected_login" --expected-server "$expected_server" --cli "$cli")
pids=(); names=(); magic="$magic_base"

launch() {  # name command...
    local name="$1"; shift
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
        magic=$((magic + 1))
    done
done
[ "${#pids[@]}" -gt 0 ] || fail "no ready case in lanes: $lanes"

verdicts=()
for i in "${!pids[@]}"; do
    code=0; wait "${pids[$i]}" || code=$?
    verdicts+=("$(jq -n --arg name "${names[$i]}" --argjson exit "$code" --arg line "$(tail -n 1 "$out/logs/${names[$i]}.log" | cut -c1-300)" \
        '{name:$name, status:(if $exit == 0 then "passed" else "failed" end), summary:$line}')")
done

capabilities='[]'
[ -f "$out/shadow/result.json" ] && capabilities="$(jq -c '.capabilities' "$out/shadow/result.json")"
printf '%s\n' "${verdicts[@]}" | jq -s --arg startedAt "$started_at" --arg finishedAt "$(date -u +%FT%TZ)" \
    --argjson seconds "$(( $(date +%s) - started ))" --arg cli "$("$cli" --version | head -n 1)" --argjson capabilities "$capabilities" '
    {schema:"qkt-attestation-catalog-run-v1", startedAtUtc:$startedAt, finishedAtUtc:$finishedAt, wallClockSeconds:$seconds,
     cli:$cli, runs:., capabilitiesProvenLive:($capabilities|length), capabilities:$capabilities,
     status:(if all(.[]; .status == "passed") then "passed" else "failed" end)}' > "$out/result.json"
jq -r '"\(.status) runs=\(.runs|length) passed=\([.runs[]|select(.status=="passed")]|length) capabilities=\(.capabilitiesProvenLive) wallClock=\(.wallClockSeconds)s"' "$out/result.json"
jq -r '.runs[] | select(.status != "passed") | "  FAILED \(.name): \(.summary)"' "$out/result.json"
[ "$(jq -r .status "$out/result.json")" = passed ]
