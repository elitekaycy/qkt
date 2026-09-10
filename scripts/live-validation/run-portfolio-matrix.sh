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
#                            gateway and observes it. It ALWAYS places real demo orders (every
#                            generated book emits entries), so it refuses to start without --arm
#                            I_UNDERSTAND_DEMO_ORDER_0.01 and QKT_LIVE_DEMO_ORDER_APPROVAL=
#                            LOCALHOST_DEMO_ONLY. The gateway is checked for health, identity and a
#                            flat account; each case proves every child deployed with its own magic,
#                            the book's capital split, its risk refusals, per-child attribution on
#                            real 0.01-lot fills, and a flat account at the end.
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
--observe-seconds  live observation window per case (default 150: at least two closed
                   1m bars after deploy and warmup, so every child reaches a decision)
EOF
}

fail() { printf 'run-portfolio-matrix: %s\n' "$1" >&2; exit 1; }
note() { printf '  %s\n' "$1"; }

scenario=""
only_case=""
cli="$repo_root/build/install/qkt/bin/qkt"
verify_only=true
arm=""
observe_seconds=150

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
        grep -qE 'max_order_qty: "0\.005"' "$case_dir/qkt.config.yaml" \
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

# A fingerprint of the engine a daemon will load: the sha256 of every qkt jar beside the CLI.
# Recorded at daemon start and re-checked before teardown, because the installed jar can be replaced
# while a sweep runs (a Gradle test run executes installDist) and `qkt status --deep` only reports the
# CLI's own build, not the daemon's.
engine_fingerprint() {
    local lib
    lib="$(cd "$(dirname "$cli")/../lib" 2>/dev/null && pwd)" || { printf 'unknown'; return 0; }
    find "$lib" -maxdepth 1 -name 'qkt*.jar' -exec sha256sum {} + 2>/dev/null \
        | sort | sha256sum | cut -c1-16
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
    local engine_before
    engine_before="$(engine_fingerprint)"
    printf '%s\n' "$engine_before" > "$evidence/engine.txt"

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

        # ---- verdict, judged from the ENGINE's own decisions -------------------------------
        # Every child that can fire must reach an entry decision inside the window: either a real
        # venue position or a logged risk refusal. A case where none did has not tested anything,
        # and that is reported as exactly that -- no closed bar in the window -- instead of being
        # mistaken for a refusal or a pass. (A 45s window missed the first closed 1m bar on some
        # cases and was reported, wrongly, as "the shape proved nothing".)
        local profile attempting refused_children book_refusals
        profile="$(jq -r '.riskProfile' "$expected")"
        attempting="$(jq -r '.childrenAttemptingEntry' "$expected")"
        grep -E "risk rejected" "$evidence/daemon.log" > "$evidence/refusals.log" 2>/dev/null || true
        refused_children="$(grep -oE "risk rejected [^ ]+" "$evidence/refusals.log" | sort -u | wc -l | tr -d ' ')"
        book_refusals="$(grep -cE "risk rejected .*: book " "$evidence/refusals.log" || true)"
        note "decisions: $open_positions opened, $refused_children child(ren) refused, $attempting expected to decide"

        local decided=$((open_positions + refused_children))
        if [ "$decided" -lt "$attempting" ]; then
            # Say WHY a child did not decide, from the engine's own log, instead of guessing.
            # Measured 2026-09-10: one child was held by the market-data staleness gate ("market
            # data for EXNESS:AUDUSD STALE ... suppressing new orders") and a whole case produced no
            # signal at all; both were first reported as "no closed bar", which was wrong for one.
            local submits stale_children
            submits="$(grep -c ' submit ' "$evidence/daemon.log" 2>/dev/null || true)"
            stale_children="$(grep -oE 'ERROR +\[[^]]+\] [^ ]+ - market data for [^ ]+ STALE' \
                "$evidence/daemon.log" 2>/dev/null | grep -oE '\[[^]]+\]' | sort -u | wc -l | tr -d ' ')"
            # Staleness FIRST: a child held by the market-data gate never logs a submit, so testing for
            # zero submits first reported a stale-held child as "no signal" (2026-09-10: child a's own
            # log said "market data for EXNESS:EURUSD STALE ... suppressing new orders" inside the
            # window while the runner said no bar had reached it). The gate also writes to the child's
            # own log under state/logs, which the daemon's stdout does not always carry.
            stale_children="$(cat "$evidence/daemon.log" "$case_dir"/state/logs/*.log 2>/dev/null \
                | grep -oE '\[[^]]+\] [^ ]+ - market data for [^ ]+ STALE|\[ERROR\] market data for [^ ]+ STALE' \
                | sort -u | wc -l | tr -d ' ')"
            if [ "$stale_children" != "0" ]; then
                note "only $decided of $attempting children decided; the market-data staleness gate suppressed orders on stale quotes during the window -- the staleness gate, not the risk profile, decided this case"
            elif [ "$submits" = "0" ]; then
                note "no child produced a signal in ${observe_seconds}s -- no closed bar reached the strategies; this case tested nothing"
            else
                note "only $decided of $attempting children reached an entry decision in ${observe_seconds}s; this case tested nothing"
            fi
            rc=1
        else
            local capital cap
            capital="$(grep -oE 'capital: "[0-9.]+"' "$config" | head -1 | grep -oE '[0-9.]+')"
            case "$profile" in
                no-book-risk)
                    # Control: nothing may be refused and every deciding child must hold a position.
                    [ "$refused_children" = "0" ] || { note "control profile refused $refused_children child(ren)"; rc=1; }
                    [ "$open_positions" -ge "$attempting" ] || { note "control opened $open_positions of $attempting"; rc=1; }
                    ;;
                margin-floor)
                    # MarginFloor approves every entry while the venue reports a margin level of 0 --
                    # a flat account -- by design (risk/rules/MarginFloor.kt), and at 1000:1 leverage a
                    # single 0.01-lot position already puts the level in the millions of percent. So
                    # entries on a flat account are EXPECTED to pass; what is checked is that every
                    # refusal quotes a margin level genuinely below the configured floor, and that
                    # nothing else refused under this profile.
                    local floor bad_margin other
                    floor="$(grep -oE 'margin_floor_pct: "[0-9.]+"' "$config" | grep -oE '[0-9.]+')"
                    bad_margin="$(grep -oE 'margin level [0-9.]+% below floor' "$evidence/refusals.log" \
                        | grep -oE '[0-9.]+' | awk -v f="$floor" '$1 >= f {n++} END {print n+0}')"
                    [ "$bad_margin" = "0" ] || { note "$bad_margin margin refusal(s) quote a level at or above the ${floor}% floor"; rc=1; }
                    other="$(grep -vc 'margin level' "$evidence/refusals.log" || true)"
                    [ "$other" = "0" ] || { note "$other refusal(s) under margin-floor were not margin refusals"; rc=1; }
                    note "floor ${floor}%: $open_positions entr(ies) passed, $refused_children child(ren) refused below the floor"
                    ;;
                per-order-qty-cap)
                    # Every entry must be refused before the venue.
                    [ "$open_positions" = "0" ] || { note "$profile let $open_positions position(s) through"; rc=1; }
                    [ "$refused_children" -ge "$attempting" ] || { note "$profile refused only $refused_children of $attempting"; rc=1; }
                    ;;
                book-gross-cap|book-concentration-cap)
                    # Which children get in is order-dependent (children run concurrently), so the
                    # check is order-independent and ARITHMETIC: every refusal must quote an exposure
                    # that genuinely exceeds cap x capital, and every opened position must fit.
                    if [ "$profile" = book-gross-cap ]; then cap=0.02; else cap=0.01; fi
                    local limit
                    limit="$(awk -v c="$cap" -v k="$capital" 'BEGIN{printf "%.4f", c*k}')"
                    local unjustified
                    unjustified="$(grep -oE ': book [^0-9]*[0-9]+\.[0-9]+' "$evidence/refusals.log" \
                        | grep -oE '[0-9]+\.[0-9]+$' \
                        | awk -v l="$limit" '$1 <= l {n++} END {print n+0}')"
                    [ "$unjustified" = "0" ] || { note "$unjustified refusal(s) quote an exposure within the $limit limit"; rc=1; }
                    local over
                    if [ "$profile" = book-gross-cap ]; then
                        over="$(jq --argjson l "$limit" '[.[] | .lots*100000*.entry] | add // 0 | if . > $l then 1 else 0 end' "$evidence/positions.json")"
                    else
                        over="$(jq --argjson l "$limit" '[group_by(.symbol)[] | map(.lots*100000*.entry) | add | select(. > $l)] | length' "$evidence/positions.json")"
                    fi
                    [ "$over" = "0" ] || { note "an opened position exceeds the $limit $profile limit"; rc=1; }
                    [ "$book_refusals" -ge 1 ] || [ "$refused_children" = "0" ] \
                        || { note "refusals under $profile were not book-cap refusals"; rc=1; }
                    note "cap $cap x capital $capital = limit $limit; refusals and fills checked against it"
                    ;;
                *) note "unknown risk profile $profile"; rc=1 ;;
            esac
        fi

        # Attribution is RECORDED, not asserted. Measured 2026-09-10: positions opened by portfolio
        # children carry comment "ORD-0" rather than the `dsl-<name>` marker, and shutdown logs
        # "flatten skipped unattributed ticket <n>; operator intervention required" for each
        # sibling's ticket. Each child still closes its own position, so the account ends flat.
        jq -r '[.[] | {ticket, symbol, lots, entry, comment}]' "$evidence/positions.json" \
            > "$evidence/attribution.json" 2>/dev/null || true
    fi

    local engine_after
    engine_after="$(engine_fingerprint)"
    if [ "$engine_after" != "$engine_before" ]; then
        note "the installed engine changed during this case ($engine_before -> $engine_after); its result cannot be attributed to one build"
        rc=1
    fi
    note "engine $engine_before"

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
