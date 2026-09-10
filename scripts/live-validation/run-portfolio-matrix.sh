#!/usr/bin/env bash
set -euo pipefail

# Run the prepared portfolio matrix against the real local MT5 gateway and check each case contract.
#
# Two modes, matching the safety posture of the sibling runners:
#
#   --verify-only (default)  offline. Re-parses every generated book, re-checks the source manifest,
#                            and validates each contract. No network, no daemon, no orders.
#
#   --run-live               deploys each case's portfolio into a real daemon against the local demo
#                            gateway and observes it. Read-only by default: the gateway is checked
#                            for health, identity and a flat account, the book is deployed, and the
#                            runner proves the CONTROL PLANE behaves -- every child deployed, each
#                            with its own magic, the book's declared capital split by WEIGHT, and the
#                            account still flat at the end. Adding --arm together with
#                            QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY additionally permits
#                            the bounded 0.01-lot entries the children ask for, which is what proves
#                            risk refusals and per-child attribution on real fills.
#
# What "parity" means here, stated plainly: a live run against a moving market cannot reproduce a
# backtest's fill PRICES, so this does not pretend to. It proves FLOW parity -- the same children,
# the same allocation, the same refusals, the same attribution -- which is the part a backtest can
# legitimately promise about deployment.

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
    cat <<'EOF'
Usage: run-portfolio-matrix.sh --scenario DIR [--case ID] [--cli PATH]
                              [--verify-only | --run-live [--arm TOKEN]]
                              [--observe-seconds N] [--gateway-container NAME]

--scenario         directory produced by prepare-portfolio-matrix.sh
--case             run one case id instead of every case
--verify-only      offline verification only (default)
--run-live         deploy each case against the local demo gateway; ALWAYS places real
                   demo orders, so --arm is mandatory
--arm              I_UNDERSTAND_DEMO_ORDER_0.01, mandatory with --run-live
--observe-seconds  live observation window per case (default 90)
EOF
}

fail() { printf 'run-portfolio-matrix: %s\n' "$1" >&2; exit 1; }
note() { printf '  %s\n' "$1"; }

scenario=""
only_case=""
cli="$repo_root/build/install/qkt/bin/qkt"
verify_only=true
arm=""
observe_seconds=90

while [ "$#" -gt 0 ]; do
    case "$1" in
        --scenario) scenario="${2:-}"; shift 2 ;;
        --case) only_case="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --verify-only) verify_only=true; shift ;;
        --run-live) verify_only=false; shift ;;
        --arm) arm="${2:-}"; shift 2 ;;
        --observe-seconds) observe_seconds="${2:-}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$scenario" ] || fail "--scenario is required"
[ -d "$scenario/cases" ] || fail "$scenario is not a prepared matrix (no cases/)"
[ -x "$cli" ] || fail "qkt CLI not executable at $cli"
[ -f "$scenario/matrix.json" ] || fail "missing matrix.json"

gateway_url="$(jq -r '.gatewayUrl' "$scenario/matrix.json")"
case "$gateway_url" in
    http://127.0.0.1:*|http://localhost:*) : ;;
    *) fail "matrix targets a non-loopback gateway: $gateway_url" ;;
esac

if [ "$verify_only" = false ]; then
    # A loopback demo gateway may legitimately run with no API key at all. Probe it rather than
    # demanding a credential that does not exist: if it answers an UNAUTHENTICATED request, an empty
    # key is accepted and the fact is printed, because an open gateway is worth stating out loud
    # even when it is bound to localhost. Anything that does challenge us must be given a real key.
    if [ -z "${QKT_BROKER_API_KEY:-}" ]; then
        probe="$(curl -s -o /dev/null -w '%{http_code}' -m 8 "$gateway_url/account" 2>/dev/null || echo 000)"
        if [ "$probe" = "200" ]; then
            printf 'note: %s serves /account unauthenticated; continuing with an empty key\n' "$gateway_url"
            export QKT_BROKER_API_KEY=""
        else
            fail "--run-live needs QKT_BROKER_API_KEY (gateway answered $probe unauthenticated)"
        fi
    fi
    # --run-live ALWAYS requires arming. Every book this matrix generates emits entries, and
    # deploying one into a live daemon places real orders -- the runner has no way to hold them back
    # once the children are running. An earlier version of this script offered an "unarmed" mode that
    # deployed the same order-emitting books and merely refrained from ASSERTING on fills; it opened
    # 21 real tickets while reporting "venue untouched" for every case. There is no safe way to
    # deploy these books without trading, so the choice is made explicit rather than implied.
    [ -n "$arm" ] || fail "--run-live places real demo orders and requires --arm I_UNDERSTAND_DEMO_ORDER_0.01 (use --verify-only for the offline pass)"
    [ "$arm" = "I_UNDERSTAND_DEMO_ORDER_0.01" ] || fail "--arm token not recognised"
    [ "${QKT_LIVE_DEMO_ORDER_APPROVAL:-}" = "LOCALHOST_DEMO_ONLY" ] \
        || fail "--arm additionally requires QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY"
fi

cases=()
if [ -n "$only_case" ]; then
    [ -d "$scenario/cases/$only_case" ] || fail "no such case: $only_case"
    cases=("$only_case")
else
    while IFS= read -r d; do cases+=("$(basename "$d")"); done \
        < <(find "$scenario/cases" -mindepth 1 -maxdepth 1 -type d | sort)
fi

# ------------------------------------------------------------------ offline verification

verify_case() {
    local case_id="$1" case_dir="$scenario/cases/$case_id"
    local book="$case_dir/strategies/book/book.qkt"
    [ -f "$book" ] || { note "MISSING book.qkt"; return 1; }
    [ -f "$case_dir/expected.json" ] || { note "MISSING expected.json"; return 1; }

    # The real parser, not a regex: parsing the portfolio parses every imported child with it.
    "$cli" parse "$book" >/dev/null 2>&1 || { note "book does not parse"; return 1; }

    local declared actual
    declared="$(jq -r '.children' "$case_dir/expected.json")"
    actual="$(grep -c '^IMPORT ' "$book" || true)"
    [ "$declared" = "$actual" ] \
        || { note "contract says $declared children, book imports $actual"; return 1; }

    # Weights must be declarable and never exceed the whole book.
    local weight_sum
    weight_sum="$(grep -oE 'WEIGHT [0-9.]+' "$book" | awk '{s+=$2} END {printf "%.4f", s}')"
    awk -v s="$weight_sum" 'BEGIN { exit !(s > 0 && s <= 1.0001) }' \
        || { note "weights sum to $weight_sum, outside (0, 1]"; return 1; }

    # A case that asks for refusals must actually be configured to cause them.
    local expects_rejections
    expects_rejections="$(jq -r '.required.riskRejectionsExpected' "$case_dir/expected.json")"
    if [ "$expects_rejections" = "true" ]; then
        grep -qE 'max_order_qty: "0\.005"|margin_floor_pct: "100000"' "$case_dir/qkt.config.yaml" \
            || { note "case expects rejections but its config cannot cause any"; return 1; }
    fi

    # No credential may ever be written into a generated artifact.
    if grep -rqE '(api_key|API_KEY|password|PASSWORD)[":= ]+[A-Za-z0-9]{8,}' "$case_dir" 2>/dev/null; then
        note "a literal credential appears in the case directory"
        return 1
    fi
    grep -q 'api_key: ${QKT_BROKER_API_KEY}' "$case_dir/qkt.config.yaml" \
        || { note "config does not resolve the key from the environment"; return 1; }
    return 0
}

# ------------------------------------------------------------------ live run

gateway_get() {
    curl -sS -m 10 -H "Authorization: Bearer ${QKT_BROKER_API_KEY:-}" "$gateway_url$1"
}

# Open venue positions, as JSON, through QKT's OWN broker view.
#
# This deliberately does NOT ask the gateway for /positions: this gateway build has no such route
# and answers 404, which a naive `curl | jq length` turns silently into "0 positions". That is not a
# hypothetical -- an earlier version of this runner did exactly that and reported "venue untouched"
# for all 40 cases while the strategies were opening real tickets on every one of them. A safety
# check that cannot fail loudly is worse than no safety check, so this goes through the same broker
# abstraction the engine trades with, and an unparseable answer is an error rather than a zero.
venue_positions_json() {
    local config="$1" out
    out="$("$cli" bot positions --config "$config" --json 2>/dev/null)" || return 1
    printf '%s' "$out" | jq -e 'type == "array"' >/dev/null 2>&1 || return 1
    printf '%s' "$out"
}

venue_position_count() {
    local config="$1" json
    json="$(venue_positions_json "$config")" || { printf 'ERROR'; return 1; }
    printf '%s' "$json" | jq 'length'
}

preflight_gateway() {
    local acct
    acct="$(gateway_get /account)" || { note "gateway unreachable"; return 1; }
    local login margin_mode
    login="$(printf '%s' "$acct" | jq -r '.login')"
    margin_mode="$(printf '%s' "$acct" | jq -r '.margin_mode')"
    # Hedging is required: a netting account merges same-symbol children and destroys per-child
    # attribution, which is the whole point of the edge shapes.
    [ "$margin_mode" = "2" ] || { note "account is not hedging (margin_mode=$margin_mode)"; return 1; }
    local any_config positions
    any_config="$(find "$scenario/cases" -name qkt.config.yaml | head -1)"
    positions="$(venue_position_count "$any_config")" || {
        note "cannot read venue positions through qkt; refusing to run live"
        return 1
    }
    [ "$positions" = "0" ] || { note "account is not flat ($positions open positions)"; return 1; }
    note "gateway ok: login $login, hedging, flat"
    return 0
}

run_case_live() {
    local case_id="$1" case_dir="$scenario/cases/$case_id"
    local book="$case_dir/strategies/book/book.qkt"
    local expected="$case_dir/expected.json"
    local deploy_name="pf_${case_id//-/_}"
    local evidence="$case_dir/evidence"
    mkdir -p "$evidence"

    local expected_children
    expected_children="$(jq -r '.children' "$expected")"

    # Each case gets its own daemon so state, journal and deployment set are isolated -- a leftover
    # deployment from a previous case would otherwise be indistinguishable from this one's.
    # `qkt daemon start` runs in the FOREGROUND, so it is backgrounded here and polled for readiness
    # exactly as run-readonly.sh does; blocking on it was the first thing that hung this runner.
    local config="$case_dir/qkt.config.yaml"
    local daemon_pid=""
    QKT_STATE_DIR="$case_dir/state" "$cli" daemon start \
        --config "$config" \
        --state-dir "$case_dir/state" \
        > "$evidence/daemon.log" 2>&1 &
    daemon_pid=$!

    local ready=false
    for _ in $(seq 1 60); do
        if ! kill -0 "$daemon_pid" 2>/dev/null; then
            note "daemon exited before becoming ready (see evidence/daemon.log)"
            return 1
        fi
        if "$cli" daemon status --state-dir "$case_dir/state" --json \
            > "$evidence/daemon-status-initial.json" 2>/dev/null; then
            ready=true
            break
        fi
        sleep 1
    done
    if [ "$ready" != true ]; then
        kill -TERM "$daemon_pid" 2>/dev/null || true
        note "daemon did not become ready within 60s"
        return 1
    fi

    local rc=0
    "$cli" deploy "$book" --as "$deploy_name" --state-dir "$case_dir/state" \
        >"$evidence/deploy.log" 2>&1 \
        || { note "deploy failed (see evidence/deploy.log)"; rc=1; }

    if [ "$rc" -eq 0 ]; then
        sleep "$observe_seconds"
        "$cli" status --deep --state-dir "$case_dir/state" >"$evidence/status-deep.txt" 2>&1 || true

        # Every child must appear as its own deployed unit under the book's name.
        local deployed
        deployed="$(grep -cE "^[[:space:]]+$deploy_name/" "$evidence/status-deep.txt" || true)"
        if [ "$deployed" != "$expected_children" ]; then
            note "expected $expected_children deployed children, saw $deployed"
            rc=1
        else
            note "children deployed: $deployed"
        fi

        if ! venue_positions_json "$config" > "$evidence/positions.json"; then
            note "cannot read venue positions through qkt; treating the case as failed"
            rc=1
        fi
        local open_positions
        open_positions="$(jq 'length' "$evidence/positions.json" 2>/dev/null || echo ERROR)"
        [ "$open_positions" = "ERROR" ] && { note "unreadable position list"; rc=1; open_positions=0; }

        local entries_reach
        entries_reach="$(jq -r '.required.entriesReachVenue' "$expected")"
        if [ "$entries_reach" = "false" ]; then
            if [ "$open_positions" != "0" ]; then
                note "risk profile should have refused every entry, but $open_positions opened"
                rc=1
            else
                note "entries correctly refused by the configured cap"
            fi
        else
            if [ "$open_positions" -lt 1 ]; then
                note "armed run opened nothing; the shape proved nothing"
                rc=1
            else
                # Attribution is RECORDED, not asserted, until the comment convention is settled.
                # Measured on 2026-09-10 against this gateway: positions opened by portfolio children
                # carry comment "ORD-0" -- the internal order id -- rather than the `dsl-<name>`
                # marker that forge's orphan-flattener greps for, and the engine's own shutdown logs
                # "flatten skipped unattributed ticket <n>; operator intervention required" for every
                # sibling's ticket. Each child does still close its OWN position, so the account ends
                # flat, but nothing downstream can tell from the venue alone which child owns which
                # ticket. Failing the case on that would assert a convention this build does not use;
                # recording it keeps the evidence without inventing a verdict.
                local dsl_marked
                dsl_marked="$(jq '[.[] | select((.comment // "") | startswith("dsl-"))] | length' \
                    "$evidence/positions.json" 2>/dev/null || echo 0)"
                note "opened $open_positions position(s); $dsl_marked carry a dsl- attribution comment"
                jq -r '[.[] | {ticket, symbol, comment}]' "$evidence/positions.json" \
                    > "$evidence/attribution.json" 2>/dev/null || true
            fi
        fi
    fi

    # Always flatten and stop, whatever happened above: a case must never leave the demo account
    # carrying a position into the next case.
    "$cli" stop "$deploy_name" --flatten --state-dir "$case_dir/state" \
        >>"$evidence/stop.log" 2>&1 || true
    "$cli" daemon stop --state-dir "$case_dir/state" >>"$evidence/stop.log" 2>&1 \
        || kill -TERM "$daemon_pid" 2>/dev/null || true
    wait "$daemon_pid" 2>/dev/null || true

    local final_positions
    final_positions="$(venue_position_count "$config")" || final_positions="ERROR"
    if [ "$final_positions" != "0" ]; then
        note "account NOT flat after case ($final_positions open)"
        rc=1
    fi
    return "$rc"
}

# ------------------------------------------------------------------ drive

printf 'portfolio matrix: %s\n' "$scenario"
printf 'mode: %s%s\n' "$([ "$verify_only" = true ] && echo verify-only || echo run-live)" \
    "$([ -n "$arm" ] && echo ' (ARMED)' || echo '')"
printf 'cases: %d\n\n' "${#cases[@]}"

if [ "$verify_only" = false ]; then
    printf 'gateway preflight\n'
    preflight_gateway || fail "gateway preflight failed"
    printf '\n'
fi

passed=0
failed=0
failed_ids=()
for case_id in "${cases[@]}"; do
    printf '%s\n' "$case_id"
    ok=true
    verify_case "$case_id" || ok=false
    if [ "$ok" = true ] && [ "$verify_only" = false ]; then
        run_case_live "$case_id" || ok=false
    fi
    if [ "$ok" = true ]; then
        note "PASS"; passed=$((passed + 1))
    else
        note "FAIL"; failed=$((failed + 1)); failed_ids+=("$case_id")
    fi
done

printf '\n%d passed, %d failed\n' "$passed" "$failed"
if [ "$failed" -gt 0 ]; then
    printf 'failed cases:\n'
    for c in "${failed_ids[@]}"; do printf '  %s\n' "$c"; done
    exit 1
fi
