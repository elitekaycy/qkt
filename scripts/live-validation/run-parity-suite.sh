#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
prepare="$repo_root/scripts/live-validation/prepare-generated-parity-wave.sh"
readonly_runner="$repo_root/scripts/live-validation/run-readonly.sh"
armed_runner="$repo_root/scripts/live-validation/run-market-bracket.sh"
replay_runner="$repo_root/scripts/live-validation/compare-golden-replay.sh"
catalog="$repo_root/src/test/resources/validation/oracle-evidence.json"

usage() {
    cat <<'EOF'
Usage: run-parity-suite.sh --output DIR --id ID --gateway-url http://127.0.0.1:PORT \
  --expected-login N --expected-server NAME --expected-balance DECIMAL \
  --expected-leverage N --magic-base N [--cli PATH] [--verify-only] [--parallel]

Prepare and validate the generated four-case live/replay parity suite. By default
this performs static verification only. Add --run-live together with
QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY and --arm
I_UNDERSTAND_DEMO_ORDER_0.01 to run real bounded demo orders.
EOF
}

fail() { printf 'run-parity-suite: %s\n' "$1" >&2; exit 1; }

output=""
suite_id=""
gateway_url=""
expected_login=""
expected_server=""
expected_balance=""
expected_leverage=""
magic_base=""
ema_fast=3
ema_slow=5
cli="$repo_root/build/install/qkt/bin/qkt"
verify_only=true
# Run the cases side by side instead of one after another; see run_parallel below.
parallel=false
# How long the shadow lane observes. The attestation verifier requires a live window of at least
# ten minutes; nine minutes of observation plus the armed and replay phases clears it honestly.
shadow_seconds=540
arm=""
run_id="parity-$(date -u +%Y%m%dT%H%M%SZ)-$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')"

while [ "$#" -gt 0 ]; do
    case "$1" in
        --output) output="${2:-}"; shift 2 ;;
        --id) suite_id="${2:-}"; shift 2 ;;
        --gateway-url) gateway_url="${2:-}"; shift 2 ;;
        --expected-login) expected_login="${2:-}"; shift 2 ;;
        --expected-server) expected_server="${2:-}"; shift 2 ;;
        --expected-balance) expected_balance="${2:-}"; shift 2 ;;
        --expected-leverage) expected_leverage="${2:-}"; shift 2 ;;
        --magic-base) magic_base="${2:-}"; shift 2 ;;
        --ema-fast) ema_fast="${2:-}"; shift 2 ;;
        --ema-slow) ema_slow="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --verify-only) verify_only=true; shift ;;
        --run-live) verify_only=false; shift ;;
        --parallel) parallel=true; shift ;;
        --shadow-seconds) shadow_seconds="${2:-}"; shift 2 ;;
    --arm) arm="${2:-}"; shift 2 ;;
        --run-id) run_id="${2:-}"; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$output" ] || fail "--output is required"
[ -n "$suite_id" ] || fail "--id is required"
[ -n "$gateway_url" ] || fail "--gateway-url is required"
[ -n "$expected_login" ] || fail "--expected-login is required"
[ -n "$expected_server" ] || fail "--expected-server is required"
[ -n "$expected_balance" ] || fail "--expected-balance is required"
[ -n "$expected_leverage" ] || fail "--expected-leverage is required"
[ -n "$magic_base" ] || fail "--magic-base is required"
[ -x "$cli" ] || fail "QKT CLI is not executable: $cli"
[ -f "$catalog" ] || fail "capability catalog is missing: $catalog"

refresh_case_balance() {
    local scenario="$1" account_json balance
    [ -n "${QKT_BROKER_API_KEY:-}" ] || fail "QKT_BROKER_API_KEY is required for live account refresh"
    account_json="$(printf 'header = \"Authorization: Bearer %s\"\n' "$QKT_BROKER_API_KEY" |
        curl --silent --show-error --fail --config - "$gateway_url/account")" ||
        fail "could not refresh the live account snapshot before case $(basename "$scenario")"
    jq -e --argjson login "$expected_login" --arg server "$expected_server" --argjson leverage "$expected_leverage" '
        .login == $login and .server == $server and .trade_mode == 0 and
        .currency == "USD" and .leverage == $leverage and .trade_allowed == true and
        .trade_expert == true
    ' <<<"$account_json" >/dev/null || fail "live account identity changed before case $(basename "$scenario")"
    balance="$(jq -er '.balance | tostring' <<<"$account_json")"
    jq --arg balance "$balance" '.account.startingBalance = $balance' \
        "$scenario/expected.json" > "$scenario/.expected.json.tmp"
    mv "$scenario/.expected.json.tmp" "$scenario/expected.json"
    sed -E -i \
        -e "s/^starting_balance: \"[^\"]*\"/starting_balance: \"$balance\"/" \
        -e "s/^  capital: \"[^\"]*\"/  capital: \"$balance\"/" \
        "$scenario/qkt.config.yaml"
    (
        cd "$scenario"
        find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
    )
}

if ! $verify_only; then
    [ "$arm" = "I_UNDERSTAND_DEMO_ORDER_0.01" ] ||
        fail "--run-live requires --arm I_UNDERSTAND_DEMO_ORDER_0.01"
    [ "${QKT_LIVE_DEMO_ORDER_APPROVAL:-}" = "LOCALHOST_DEMO_ONLY" ] ||
        fail "--run-live requires QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY"
fi

mkdir -m 700 -p "$(dirname "$output")"
if [ -e "$output" ]; then fail "output already exists: $output"; fi
bash "$prepare" --output "$output" --id "$suite_id" --gateway-url "$gateway_url" \
    --expected-login "$expected_login" --expected-server "$expected_server" \
    --expected-balance "$expected_balance" --expected-leverage "$expected_leverage" \
    --magic-base "$magic_base" --ema-fast "$ema_fast" --ema-slow "$ema_slow" \
    --cli "$cli" >/dev/null

mapfile -t cases < <(find "$output/cases" -mindepth 1 -maxdepth 1 -type d | sort)
[ "${#cases[@]}" -eq 4 ] || fail "generated suite did not contain four cases"

seal_armed_scenario() {
    local scenario="$1" armed_scenario="$1/armed-live"
    mkdir -m 700 "$armed_scenario" "$armed_scenario/evidence" "$armed_scenario/logs" "$armed_scenario/state"
    cp "$scenario/expected.json" "$scenario/qkt.config.yaml" "$scenario/scenario.json" "$scenario/cleanup.json" "$armed_scenario/"
    jq '.qktDirty = false' "$armed_scenario/scenario.json" > "$armed_scenario/.scenario.json.tmp"
    mv "$armed_scenario/.scenario.json.tmp" "$armed_scenario/scenario.json"
    cp -a "$scenario/strategies" "$armed_scenario/strategies"
    (
        cd "$armed_scenario"
        find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
    )
}

# Every case at once, one phase at a time: the read-only captures place no orders, the armed
# runs each own a distinct magic and reconcile against it (--shared-account), and the replays
# are offline. Wall-clock is the slowest case of each phase instead of the sum of all of them.
run_phase() {  # name command-template-function
    local name="$1" fn="$2" pids=() failed=0 scenario
    mkdir -p "$output/phases"
    for scenario in "${cases[@]}"; do
        ( "$fn" "$scenario" ) > "$output/phases/$(basename "$scenario")-$name.log" 2>&1 &
        pids+=("$!")
    done
    for i in "${!pids[@]}"; do
        wait "${pids[$i]}" || { failed=1; printf 'run-parity-suite: %s failed for %s: %s\n' "$name" \
            "$(basename "${cases[$i]}")" "$(tail -n 1 "$output/phases/$(basename "${cases[$i]}")-$name.log")" >&2; }
    done
    [ "$failed" -eq 0 ] || fail "parallel phase '$name' failed"
}
phase_readonly() { bash "$readonly_runner" --scenario "$1" --cli "$cli" >/dev/null; }
phase_armed() { bash "$armed_runner" --scenario "$1/armed-live" --cli "$cli" --shared-account --arm "$arm" >/dev/null; }
phase_replay() { bash "$replay_runner" --scenario "$1/armed-live" --out "$1/armed-live/replay" --cli "$cli" >/dev/null; }

if $parallel && ! $verify_only; then
    for scenario in "${cases[@]}"; do
        refresh_case_balance "$scenario"
        bash "$readonly_runner" --scenario "$scenario" --cli "$cli" --verify-only >/dev/null
    done
    # The shadow lane places no orders, so it shares the read-only window: every ready shadow case
    # in one daemon, value parity against replay. Its verdict gates the suite like any case.
    shadow_pid=""
    if [ -d "$repo_root/attestation/cases/shadow" ]; then
        bash "$repo_root/scripts/live-validation/run-shadow-lane.sh" --out "$output/shadow-lane" \
            --gateway-url "$gateway_url" --expected-login "$expected_login" --expected-server "$expected_server" \
            --magic "$((magic_base + 90))" --cli "$cli" --duration-seconds "$shadow_seconds" > "$output/shadow-lane.log" 2>&1 &
        shadow_pid="$!"
    fi
    run_phase readonly phase_readonly
    if [ -n "$shadow_pid" ]; then
        wait "$shadow_pid" || fail "shadow lane failed: $(tail -n 1 "$output/shadow-lane.log")"
    fi
    for scenario in "${cases[@]}"; do seal_armed_scenario "$scenario"; done
    run_phase armed phase_armed
    run_phase replay phase_replay
    cases=()
fi
for scenario in "${cases[@]}"; do
    if ! $verify_only; then
        refresh_case_balance "$scenario"
    fi
    bash "$readonly_runner" --scenario "$scenario" --cli "$cli" --verify-only >/dev/null
    if ! $verify_only; then
        bash "$readonly_runner" --scenario "$scenario" --cli "$cli" >/dev/null
        armed_scenario="$scenario/armed-live"
        seal_armed_scenario "$scenario"
        bash "$armed_runner" --scenario "$armed_scenario" --cli "$cli" \
            --arm "$arm" >/dev/null
        bash "$replay_runner" --scenario "$armed_scenario" \
            --out "$armed_scenario/replay" --cli "$cli" >/dev/null
    else
        bash "$armed_runner" --scenario "$scenario" --cli "$cli" --verify-only >/dev/null
    fi
done

mode="verify-only"
if ! $verify_only; then mode="live"; fi
jq --arg mode "$mode" --arg completedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg runId "$run_id" \
    --arg inputFingerprint "$(sha256sum "$output/SHA256SUMS" | awk '{print $1}')" \
    --slurpfile oracle "$catalog" \
    --slurpfile shadow <(cat "$output/shadow-lane/result.json" 2>/dev/null || echo null) \
    '. + {shadowLane:($shadow[0] | if . == null then null else {status, cases:(.cases|length), capabilities} end)} + {execution:{mode:$mode,completedAt:$completedAt,cases:4,runId:$runId,inputFingerprint:$inputFingerprint},capabilityCatalog:{indicators:($oracle[0].categories.indicators | map(.capabilities) | add),numericFunctions:($oracle[0].categories.numericFunctions | map(.capabilities) | add)}}' \
    "$output/suite.json" > "$output/.suite.json.tmp"
mv "$output/.suite.json.tmp" "$output/suite.json"
find "$output" -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > "$output/SHA256SUMS"
printf '%s\n' "$output"
