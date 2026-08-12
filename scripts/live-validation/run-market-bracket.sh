#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly_runner="$repo_root/scripts/live-validation/run-readonly.sh"
# shellcheck source=scripts/live-validation/lib/catalog-startup-window.sh
source "$repo_root/scripts/live-validation/lib/catalog-startup-window.sh"

usage() {
    cat <<'EOF'
Usage: run-market-bracket.sh --scenario DIR [--cli PATH] [--timeout-seconds N]
       run-market-bracket.sh --scenario DIR [--cli PATH] --verify-only

Live execution additionally requires both:
  --arm I_UNDERSTAND_DEMO_ORDER_0.01
  QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY

Armed live runs are serialized per demo account through:
  /var/tmp/qkt-validation/LIVE-LOCK-<server>-<login>

Optional lock-holder metadata:
  QKT_LIVE_LOCK_OWNER=codex

Runs exactly one generated 0.01-lot demo bracket through the real QKT daemon
and localhost MT5 gateway, then uses QKT's broker-verified kill/flatten path.
The scenario must be freshly prepared and the whole account must initially be flat.
EOF
}

fail() {
    printf 'run-market-bracket: %s\n' "$1" >&2
    exit 1
}

scenario=""
cli="$repo_root/build/install/qkt/bin/qkt"
timeout_seconds=180
history_attempt_timeout_seconds=20
arm=""
verify_only=false

while [ "$#" -gt 0 ]; do
    case "$1" in
        --scenario) scenario="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --timeout-seconds) timeout_seconds="${2:-}"; shift 2 ;;
        --arm) arm="${2:-}"; shift 2 ;;
        --verify-only) verify_only=true; shift ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$scenario" ] || fail "--scenario is required"
scenario="$(realpath "$scenario")"
[ -x "$cli" ] || fail "QKT CLI is not executable: $cli"
command -v unzip >/dev/null || fail "unzip is required"
command -v timeout >/dev/null || fail "timeout is required"
bash "$readonly_runner" --scenario "$scenario" --cli "$cli" --verify-only >/dev/null

mapfile -t armed_strategies < <(find "$scenario/strategies/armed" -maxdepth 1 -type f -name '*_market_bracket.qkt' | sort)
[ "${#armed_strategies[@]}" -eq 1 ] || fail "expected exactly one armed market-bracket strategy"
armed_strategy="${armed_strategies[0]}"
strategy_name="$(basename "$armed_strategy" .qkt)"
config="$scenario/qkt.config.yaml"
stop_distance="$(jq -r '.armedScenario.stopDistance' "$scenario/expected.json")"
take_profit_distance="$(jq -r '.armedScenario.takeProfitDistance' "$scenario/expected.json")"
expected_contract_size="$(jq -r '.armedScenario.expectedContractSize // "100000"' "$scenario/expected.json")"
lifecycle="$(jq -r '.armedScenario.lifecycle // "single"' "$scenario/expected.json")"
expected_entries="$(jq -r '.armedScenario.maximumEntries // 1' "$scenario/expected.json")"
expected_exits="$(jq -r '.armedScenario.maximumExits // 1' "$scenario/expected.json")"
expected_blocked_entries="$(jq -r '.armedScenario.maximumBlockedEntries // 0' "$scenario/expected.json")"
expected_blocked_reason="$(jq -r '.armedScenario.expectedBlockedReason // ""' "$scenario/expected.json")"
cooldown_after_loss_ms="$(jq -r '.armedScenario.cooldownAfterLossMs // 0' "$scenario/expected.json")"
seeded_risk_state_kind="$(jq -r '.armedScenario.seededRiskState.kind // ""' "$scenario/expected.json")"
seeded_risk_state_path="$(jq -r '.armedScenario.seededRiskState.path // ""' "$scenario/expected.json")"
seeded_risk_state_entry_ms="$(jq -r '.armedScenario.seededRiskState.entryFillMs // 0' "$scenario/expected.json")"
grep -F 'SIZING 0.01' "$armed_strategy" >/dev/null || fail "armed strategy is not fixed at 0.01 lots"
case "$lifecycle" in
    single)
        grep -F 'TRADES.today = 0' "$armed_strategy" >/dev/null || fail "armed strategy does not prevent re-entry"
        [ "$expected_entries" -eq 1 ] && [ "$expected_exits" -eq 1 ] && [ "$expected_blocked_entries" -eq 0 ] ||
            fail "single lifecycle must expect exactly one entry and one exit"
        ;;
    reentry)
        grep -F 'TRADES.today < 2' "$armed_strategy" >/dev/null || fail "re-entry strategy does not allow a second entry"
        [ "$expected_entries" -eq 2 ] && [ "$expected_exits" -eq 2 ] && [ "$expected_blocked_entries" -eq 0 ] ||
            fail "re-entry lifecycle must expect exactly two entries and two exits"
        ;;
    reentry_blocked_max_trades)
        grep -F 'TRADES.today < 2' "$armed_strategy" >/dev/null ||
            fail "blocked re-entry strategy does not attempt the reviewed second entry"
        grep -F 'max_trades_per_day: 1' "$config" >/dev/null ||
            fail "blocked re-entry scenario does not cap live entries at one"
        [ "$expected_entries" -eq 1 ] && [ "$expected_exits" -eq 1 ] && [ "$expected_blocked_entries" -eq 1 ] ||
            fail "blocked re-entry lifecycle must expect one filled entry, one close, and one blocked entry"
        [ "$expected_blocked_reason" = "MaxTradesPerDay" ] ||
            fail "blocked re-entry lifecycle must retain the MaxTradesPerDay rejection reason"
        ;;
    reentry_max_trades_next_day_recovered)
        grep -F 'TRADES.today < 2' "$armed_strategy" >/dev/null ||
            fail "max-trades next-day scenario does not attempt the reviewed same-day second entry"
        grep -F 'max_trades_per_day: 1' "$config" >/dev/null ||
            fail "max-trades next-day scenario does not cap current-day live entries at one"
        [ "$expected_entries" -eq 1 ] && [ "$expected_exits" -eq 1 ] && [ "$expected_blocked_entries" -eq 1 ] ||
            fail "max-trades next-day lifecycle must expect one filled entry, one close, and one blocked same-day entry"
        [ "$expected_blocked_reason" = "MaxTradesPerDay" ] ||
            fail "max-trades next-day lifecycle must retain the MaxTradesPerDay rejection reason"
        [ "$seeded_risk_state_kind" = "previous-day-max-trades" ] ||
            fail "max-trades next-day lifecycle must declare the previous-day risk-state seed"
        ;;
    reentry_blocked_operator_halt)
        grep -F 'TRADES.today < 2' "$armed_strategy" >/dev/null ||
            fail "operator-halt re-entry strategy does not attempt the reviewed second entry"
        grep -F 'max_trades_per_day: 2' "$config" >/dev/null ||
            fail "operator-halt re-entry scenario should leave max trades open for the operator gate"
        [ "$expected_entries" -eq 1 ] && [ "$expected_exits" -eq 1 ] && [ "$expected_blocked_entries" -eq 1 ] ||
            fail "operator-halt re-entry lifecycle must expect one filled entry, one close, and one blocked entry"
        [ "$expected_blocked_reason" = "halted: operator" ] ||
            fail "operator-halt re-entry lifecycle must retain the halted: operator rejection reason"
        ;;
    reentry_operator_halt_recovered)
        grep -F 'TRADES.today < 2' "$armed_strategy" >/dev/null ||
            fail "operator-halt recovery strategy does not attempt the reviewed second entry"
        grep -F 'max_trades_per_day: 2' "$config" >/dev/null ||
            fail "operator-halt recovery scenario should leave max trades open for the resumed entry"
        [ "$expected_entries" -eq 2 ] && [ "$expected_exits" -eq 2 ] && [ "$expected_blocked_entries" -eq 1 ] ||
            fail "operator-halt recovery lifecycle must expect two filled entries, two closes, and one blocked entry"
        [ "$expected_blocked_reason" = "halted: operator" ] ||
            fail "operator-halt recovery lifecycle must retain the halted: operator rejection reason"
        ;;
    reentry_cooldown_recovered)
        grep -F 'TRADES.today < 3' "$armed_strategy" >/dev/null ||
            fail "cooldown recovery strategy does not attempt the reviewed recovered entry"
        grep -F 'max_trades_per_day: 3' "$config" >/dev/null ||
            fail "cooldown recovery scenario should leave trade count open for the recovered entry"
        grep -F 'cooldown_after_loss: "90000"' "$config" >/dev/null ||
            fail "cooldown recovery scenario does not configure the reviewed cooldown"
        [ "$expected_entries" -eq 2 ] && [ "$expected_exits" -eq 2 ] && [ "$expected_blocked_entries" -eq 1 ] ||
            fail "cooldown recovery lifecycle must expect two filled entries, two closes, and one blocked entry"
        [ "$expected_blocked_reason" = "CooldownAfterLoss" ] ||
            fail "cooldown recovery lifecycle must retain the CooldownAfterLoss rejection reason"
        [ "$cooldown_after_loss_ms" -eq 90000 ] ||
            fail "cooldown recovery lifecycle must retain the reviewed 90000ms cooldown"
        ;;
    reentry_blocked_loss_streak)
        grep -F 'TRADES.today < 2' "$armed_strategy" >/dev/null ||
            fail "loss-streak re-entry strategy does not attempt the reviewed second entry"
        grep -F 'max_trades_per_day: 2' "$config" >/dev/null ||
            fail "loss-streak scenario should leave max trades open for the loss-streak gate"
        grep -F 'loss_streak_halt: "1"' "$config" >/dev/null ||
            fail "loss-streak scenario does not configure the reviewed halt threshold"
        [ "$expected_entries" -eq 1 ] && [ "$expected_exits" -eq 1 ] && [ "$expected_blocked_entries" -eq 1 ] ||
            fail "loss-streak lifecycle must expect one filled entry, one close, and one blocked entry"
        [ "$expected_blocked_reason" = "LossStreakHalt" ] ||
            fail "loss-streak lifecycle must retain the LossStreakHalt rejection reason"
        ;;
    *) fail "unsupported armed lifecycle: $lifecycle" ;;
esac
grep -F "STOP LOSS BY $stop_distance, TAKE PROFIT BY $take_profit_distance" "$armed_strategy" >/dev/null ||
    fail "armed strategy does not contain the reviewed symbol bracket"

if $verify_only; then
    printf 'verified %s\n' "$armed_strategy"
    exit 0
fi

[[ "$timeout_seconds" =~ ^[0-9]+$ ]] || fail "--timeout-seconds must be an integer"
[ "$timeout_seconds" -ge 60 ] && [ "$timeout_seconds" -le 600 ] ||
    fail "--timeout-seconds must be in 60..600"
[ "$arm" = "I_UNDERSTAND_DEMO_ORDER_0.01" ] || fail "missing exact --arm confirmation"
[ "${QKT_LIVE_DEMO_ORDER_APPROVAL:-}" = "LOCALHOST_DEMO_ONLY" ] ||
    fail "QKT_LIVE_DEMO_ORDER_APPROVAL must equal LOCALHOST_DEMO_ONLY"
[ -n "${QKT_BROKER_API_KEY:-}" ] || fail "QKT_BROKER_API_KEY is required"
[ -z "$(find "$scenario/evidence" -mindepth 1 -maxdepth 1 -print -quit)" ] ||
    fail "evidence directory is not empty; prepare a fresh scenario"

gateway_url="$(jq -r '.gatewayUrl' "$scenario/scenario.json")"
magic="$(jq -r '.magic' "$scenario/scenario.json")"
expected_login="$(jq -r '.account.login' "$scenario/expected.json")"
expected_server="$(jq -r '.account.server' "$scenario/expected.json")"
expected_leverage="$(jq -r '.account.leverage' "$scenario/expected.json")"
expected_balance="$(jq -r '.account.startingBalance' "$scenario/expected.json")"
dsl_symbol="$(jq -r '.armedScenario.symbol' "$scenario/expected.json")"
venue_symbol="$(jq -r '.armedScenario.venueSymbol // ((.armedScenario.symbol | split(":")[1]) + "m")' "$scenario/expected.json")"
qkt_commit="$(jq -r '.qktCommit' "$scenario/scenario.json")"
qkt_dirty="$(jq -r '.qktDirty' "$scenario/scenario.json")"
evidence="$scenario/evidence"
run_started_ms="$(date +%s%3N)"
lock_server_key="${expected_server//[^A-Za-z0-9._-]/_}"
live_lock_path="/var/tmp/qkt-validation/LIVE-LOCK-$lock_server_key-$expected_login"
live_lock_owner="${QKT_LIVE_LOCK_OWNER:-${TMUX_PANE:-${USER:-unknown}}}"
live_lock_started_at=""
live_lock_fd=""
live_lock_acquired=false

gateway_get() {
    local path="$1"
    printf 'header = "Authorization: Bearer %s"\n' "$QKT_BROKER_API_KEY" |
        curl --silent --show-error --fail --config - "$gateway_url$path"
}

verify_cli_git_sha() {
    local version_line cli_git_sha expected_short
    version_line="$("$cli" --version 2>/dev/null | head -n 1)" ||
        fail "could not read QKT CLI version metadata from $cli"
    cli_git_sha="$(printf '%s\n' "$version_line" | sed -n 's/.*(\([0-9a-f]\{8\}\)).*/\1/p')"
    [ -n "$cli_git_sha" ] || fail "QKT CLI version line did not expose a git sha: $version_line"
    expected_short="${qkt_commit:0:8}"
    [ "$cli_git_sha" = "$expected_short" ] ||
        fail "QKT CLI git sha $cli_git_sha does not match scenario qktCommit $qkt_commit; rebuild the CLI before arming"
}

acquire_live_lock() {
    command -v flock >/dev/null || fail "flock is required for armed live runs"
    mkdir -p "$(dirname "$live_lock_path")"
    exec {live_lock_fd}> "$live_lock_path"
    if ! flock -n "$live_lock_fd"; then
        local holder
        holder="$(tr '\n' ';' < "$live_lock_path" 2>/dev/null | sed 's/;*$//')"
        exec {live_lock_fd}>&-
        live_lock_fd=""
        if [ -n "$holder" ]; then
            fail "live lock is already held at $live_lock_path by $holder"
        fi
        fail "live lock is already held at $live_lock_path"
    fi
    live_lock_started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'owner=%s\ncase=%s\nscenario=%s\nstarted_at_utc=%s\npid=%s\nworktree=%s\n' \
        "$live_lock_owner" \
        "$strategy_name" \
        "$scenario" \
        "$live_lock_started_at" \
        "$$" \
        "$repo_root" > "$live_lock_path"
    printf 'gateway_url=%s\naccount_login=%s\naccount_server=%s\n' \
        "$gateway_url" \
        "$expected_login" \
        "$expected_server" >> "$live_lock_path"
    cp "$live_lock_path" "$evidence/live-lock.txt"
    live_lock_acquired=true
}

wait_for_startup_window() {
    local evidence_path="$evidence/startup-window.jsonl"
    local max_total_wait_seconds=260
    local total_wait_seconds=0
    : > "$evidence_path"
    local valid_observations=0
    for attempt in $(seq 1 10); do
        local tick_file="$evidence/startup-tick-$attempt.json"
        gateway_get "/symbol_info_tick/$venue_symbol" > "$tick_file"
        local observed_at_ms
        observed_at_ms="$(date +%s%3N)"
        local tick_sample tick_invalid broker_tick_ms
        tick_sample="$(jq -c \
            --arg venueSymbol "$venue_symbol" \
            --argjson attempt "$attempt" \
            --argjson observedAtMs "$observed_at_ms" '
            (.time_msc | tonumber? // ((.time | tonumber? // 0) * 1000)) as $tickMs |
            (.bid | tonumber? // 0) as $bid |
            (.ask | tonumber? // 0) as $ask |
            ($observedAtMs - $tickMs) as $tickAgeMs |
            {
              venueSymbol:$venueSymbol,
              attempt:$attempt,
              brokerTickMs:$tickMs,
              observedAtMs:$observedAtMs,
              bid:$bid,
              ask:$ask,
              tickAgeMs:$tickAgeMs,
              tickInvalid: (
                if $tickMs <= 0 then "missing-or-zero-timestamp"
                elif $bid <= 0 or $ask <= 0 then "missing-or-zero-price"
                else null
                end
              )
            }
        ' "$tick_file")"
        tick_invalid="$(jq -er '.tickInvalid // empty' <<<"$tick_sample" || true)"
        if [ -n "$tick_invalid" ]; then
            jq -c '. + {status:"invalid"}' <<<"$tick_sample" >> "$evidence_path"
            sleep 2
            continue
        fi
        broker_tick_ms="$(jq -er '.brokerTickMs' <<<"$tick_sample")"
        local tick_age_ms=$((observed_at_ms - broker_tick_ms))
        [ "$tick_age_ms" -ge -5000 ] && [ "$tick_age_ms" -le 60000 ] ||
            fail "gateway startup tick is not current enough to select a safe deploy window"
        valid_observations=$((valid_observations + 1))
        local phase_clock_ms="$broker_tick_ms"
        [ "$tick_age_ms" -lt 0 ] || phase_clock_ms=$((broker_tick_ms + tick_age_ms))
        local broker_phase_ms=$((broker_tick_ms % QKT_CATALOG_ROLLOVER_PERIOD_MS))
        local phase_ms=$((phase_clock_ms % QKT_CATALOG_ROLLOVER_PERIOD_MS))
        local delay_ms
        delay_ms="$(qkt_catalog_startup_delay_ms "$phase_ms")" ||
            fail "could not calculate broker startup window delay"
        jq -n \
            --arg venueSymbol "$venue_symbol" \
            --argjson attempt "$attempt" \
            --argjson brokerTickMs "$broker_tick_ms" \
            --argjson observedAtMs "$observed_at_ms" \
            --argjson tickAgeMs "$tick_age_ms" \
            --argjson brokerPhaseMs "$broker_phase_ms" \
            --argjson phaseMs "$phase_ms" \
            --argjson delayMs "$delay_ms" \
            --argjson totalWaitSeconds "$total_wait_seconds" \
            --argjson validObservations "$valid_observations" '
                {
                  venueSymbol:$venueSymbol,
                  attempt:$attempt,
                  status:"valid",
                  validObservations:$validObservations,
                  brokerTickMs:$brokerTickMs,
                  observedAtMs:$observedAtMs,
                  tickAgeMs:$tickAgeMs,
                  brokerPhaseMs:$brokerPhaseMs,
                  phaseMs:$phaseMs,
                  delayMs:$delayMs,
                  totalWaitSeconds:$totalWaitSeconds
                }
            ' >> "$evidence_path"
        if [ "$delay_ms" -eq 0 ]; then
            local entered_at_broker_ms="$broker_tick_ms"
            local entered_at_clock_ms="$phase_clock_ms"
            local entered_at_phase_ms="$phase_ms"
            jq -n \
                --arg venueSymbol "$venue_symbol" \
                --argjson enteredAtBrokerMs "$entered_at_broker_ms" \
                --argjson enteredAtClockMs "$entered_at_clock_ms" \
                --argjson enteredAtPhaseMs "$entered_at_phase_ms" \
                --argjson totalWaitSeconds "$total_wait_seconds" '
                {
                  venueSymbol:$venueSymbol,
                  status:"entered",
                  safeStartMs:90000,
                  safeEndMs:150000,
                  enteredAtBrokerMs:$enteredAtBrokerMs,
                  enteredAtClockMs:$enteredAtClockMs,
                  enteredAtPhaseMs:$enteredAtPhaseMs,
                  totalWaitSeconds:$totalWaitSeconds,
                  maxWaitSeconds:260,
                  maxObservations:3
                }
            ' > "$evidence/startup-window.json"
            return
        fi
        [ "$valid_observations" -lt 3 ] ||
            fail "broker tick clock did not enter the bounded bracket startup window after three valid observations"
        local sleep_seconds=$(((delay_ms + 999) / 1000))
        [ "$((total_wait_seconds + sleep_seconds))" -le "$max_total_wait_seconds" ] ||
            fail "broker tick clock did not enter the bracket startup window within 260 seconds"
        total_wait_seconds=$((total_wait_seconds + sleep_seconds))
        sleep "$sleep_seconds"
    done
    fail "broker tick clock did not enter the bounded bracket startup window after ten startup probes"
}

wait_for_history_ready() {
    local attempt tf stdout_file stderr_file
    for attempt in $(seq 1 12); do
        local all_ready=true
        for tf in 1m 5m; do
            stdout_file="$evidence/history-ready-$tf-attempt-$attempt.json"
            stderr_file="$evidence/history-ready-$tf-attempt-$attempt.log"
            if ! "$cli" bot bars "$dsl_symbol" --tf "$tf" --count 3 --config "$config" --json \
                > "$stdout_file" 2> "$stderr_file"; then
                all_ready=false
                continue
            fi
            jq -e 'length >= 2' "$stdout_file" >/dev/null || all_ready=false
        done
        if $all_ready; then
            jq -n \
                --arg symbol "$dsl_symbol" \
                --argjson attempts "$attempt" \
                '{symbol:$symbol,status:"ready",attempts:$attempts,timeframes:["1m","5m"]}' \
                > "$evidence/history-ready.json"
            return
        fi
        sleep 5
    done
    fail "broker history did not become ready for the 1m/5m warmup probes; see evidence/history-ready-*.log"
}

wait_for_fresh_tick_after_daemon() {
    local attempt tick_file broker_tick_ms observed_at_ms tick_age_ms tick_sample tick_invalid
    for attempt in $(seq 1 30); do
        tick_file="$evidence/post-daemon-tick-$attempt.json"
        gateway_get "/symbol_info_tick/$venue_symbol" > "$tick_file"
        observed_at_ms="$(date +%s%3N)"
        tick_sample="$(jq -c \
            --arg venueSymbol "$venue_symbol" \
            --argjson attempt "$attempt" \
            --argjson observedAtMs "$observed_at_ms" '
            (.time_msc | tonumber? // ((.time | tonumber? // 0) * 1000)) as $tickMs |
            (.bid | tonumber? // 0) as $bid |
            (.ask | tonumber? // 0) as $ask |
            ($observedAtMs - $tickMs) as $tickAgeMs |
            {
              venueSymbol:$venueSymbol,
              attempt:$attempt,
              brokerTickMs:$tickMs,
              observedAtMs:$observedAtMs,
              bid:$bid,
              ask:$ask,
              tickAgeMs:$tickAgeMs,
              freshnessThresholdMs:5000,
              valid: ($tickMs > 0 and $bid > 0 and $ask > 0 and $tickAgeMs >= -5000 and $tickAgeMs <= 5000),
              invalidReason: (
                if $tickMs <= 0 then "missing-or-zero-timestamp"
                elif $bid <= 0 or $ask <= 0 then "missing-or-zero-price"
                elif $tickAgeMs < -5000 then "future-tick-clock-skew"
                elif $tickAgeMs > 5000 then "stale-tick"
                else null
                end
              )
            }
        ' "$tick_file")"
        tick_invalid="$(jq -er '.invalidReason // empty' <<<"$tick_sample" || true)"
        printf '%s\n' "$tick_sample" > "$evidence/post-daemon-tick-freshness.json"
        if [ -n "$tick_invalid" ]; then
            sleep 1
            continue
        fi
        broker_tick_ms="$(jq -er '.brokerTickMs' <<<"$tick_sample")"
        tick_age_ms=$((observed_at_ms - broker_tick_ms))
        if [ "$tick_age_ms" -ge -5000 ] && [ "$tick_age_ms" -le 5000 ]; then
            return
        fi
        sleep 1
    done
    fail "gateway tick did not become fresh enough after daemon startup; see evidence/post-daemon-tick-*.json"
}

"$cli" preflight "$armed_strategy" --config "$config" > "$evidence/preflight.log" 2>&1

daemon_pid=""
owned_ticket=""
owned_tickets_file="$evidence/owned-tickets.jsonl"
cleanup_running=false
cleanup_owned() {
    $cleanup_running && return
    cleanup_running=true
    if [ -n "$daemon_pid" ] && kill -0 "$daemon_pid" 2>/dev/null; then
        "$cli" kill "$strategy_name" --flatten --state-dir "$scenario/state" --json \
            > "$evidence/emergency-kill.json" 2>/dev/null || true
    fi
    if $live_lock_acquired; then
        local positions=""
        positions="$(gateway_get "/get_positions?magic=$magic" 2>/dev/null || true)"
        if [ -n "$positions" ]; then
            while IFS= read -r ticket; do
                [ -n "$ticket" ] || continue
                "$cli" bot close "$dsl_symbol" --ticket "$ticket" --config "$config" --json >/dev/null 2>&1 || true
            done < <(jq -r '.data[]?.ticket' <<<"$positions")
        fi
        local orders=""
        orders="$(gateway_get "/orders?magic=$magic" 2>/dev/null || true)"
        if [ -n "$orders" ]; then
            while IFS= read -r ticket; do
                [ -n "$ticket" ] || continue
                "$cli" bot cancel "$dsl_symbol" --order "$ticket" --config "$config" --json >/dev/null 2>&1 || true
            done < <(jq -r '.orders[]?.ticket' <<<"$orders")
        fi
    fi
    if [ -n "$daemon_pid" ] && kill -0 "$daemon_pid" 2>/dev/null; then
        "$cli" daemon stop --state-dir "$scenario/state" >/dev/null 2>&1 || kill -TERM "$daemon_pid" 2>/dev/null || true
        wait "$daemon_pid" 2>/dev/null || true
    fi
    for transient in "$scenario/state/control.token" "$scenario/state/daemon.pid"; do
        if [ -e "$transient" ]; then
            unlink "$transient"
        fi
    done
    if [ -n "$live_lock_fd" ]; then
        exec {live_lock_fd}>&-
        live_lock_fd=""
    fi
    live_lock_acquired=false
}
trap cleanup_owned EXIT

validate_open_position() {
    local positions_file="$1"
    jq -e \
        --argjson magic "$magic" \
        --arg venueSymbol "$venue_symbol" \
        --arg strategyPrefix "dsl-${strategy_name}" '
            .ok == true and
            (.data | length) == 1 and
            .data[0].symbol == $venueSymbol and
            .data[0].magic == $magic and
            (.data[0].type == 0 or .data[0].type == 1) and
            .data[0].volume == 0.01 and
            .data[0].price_open > 0 and
            .data[0].sl > 0 and
            .data[0].tp > 0 and
            (
                (.data[0].type == 0 and .data[0].sl < .data[0].price_open and .data[0].tp > .data[0].price_open) or
                (.data[0].type == 1 and .data[0].sl > .data[0].price_open and .data[0].tp < .data[0].price_open)
            ) and
            (
                .data[0].comment as $comment |
                ($comment | startswith($strategyPrefix)) or
                ($strategyPrefix | startswith($comment))
            )
        ' "$positions_file" >/dev/null || fail "venue position does not match the reviewed bracket contract"
}

wait_for_open_cycle() {
    local cycle="$1"
    local latest="$evidence/positions-magic-cycle-$cycle-open.json"
    local seen=false
    for _ in $(seq 1 "$timeout_seconds"); do
        kill -0 "$daemon_pid" 2>/dev/null || fail "daemon exited while waiting for cycle $cycle bracket fill"
        gateway_get "/get_positions?magic=$magic" > "$latest"
        count="$(jq '.data | length' "$latest")"
        [ "$count" -le 1 ] || fail "scenario created more than one open position in cycle $cycle"
        if [ "$count" -eq 1 ]; then
            seen=true
            break
        fi
        if rg --quiet 'risk rejected .*market data .*unhealthy' "$scenario/logs/daemon.log"; then
            fail "market data gate rejected cycle $cycle before a position opened; rerun after fresh data"
        fi
        if rg --quiet 'Order rejected:' "$scenario/logs/daemon.log"; then
            fail "broker rejected the bracket before cycle $cycle opened"
        fi
        sleep 1
    done
    $seen || fail "no magic-scoped bracket position appeared for cycle $cycle within $timeout_seconds seconds"
    validate_open_position "$latest"
    local ticket
    ticket="$(jq -r '.data[0].ticket' "$latest")"
    jq -cn --argjson cycle "$cycle" --argjson ticket "$ticket" '{cycle:$cycle,ticket:$ticket}' >> "$owned_tickets_file"
    owned_ticket="${owned_ticket:-$ticket}"
    jq \
        --slurpfile tickets "$owned_tickets_file" \
        '.ownedPositionTickets = ($tickets | map(.ticket)) | .status = "position_open"' \
        "$scenario/cleanup.json" > "$scenario/cleanup.json.tmp"
    mv "$scenario/cleanup.json.tmp" "$scenario/cleanup.json"
}

wait_for_flat_cycle() {
    local cycle="$1"
    local latest="$evidence/positions-magic-cycle-$cycle-flat.json"
    local seen=false
    for _ in $(seq 1 "$timeout_seconds"); do
        kill -0 "$daemon_pid" 2>/dev/null || fail "daemon exited while waiting for cycle $cycle strategy close"
        gateway_get "/get_positions?magic=$magic" > "$latest"
        count="$(jq '.data | length' "$latest")"
        [ "$count" -le 1 ] || fail "scenario created more than one open position while closing cycle $cycle"
        if [ "$count" -eq 0 ]; then
            seen=true
            break
        fi
        if rg --quiet 'Order rejected:' "$scenario/logs/daemon.log"; then
            fail "broker rejected a lifecycle order while closing cycle $cycle"
        fi
        sleep 1
    done
    $seen || fail "cycle $cycle did not return to flat within $timeout_seconds seconds"
}

wait_for_blocked_reentry() {
    local latest="$evidence/positions-magic-blocked-reentry.json"
    local orders_latest="$evidence/orders-magic-blocked-reentry.json"
    local seen=false
    for _ in $(seq 1 "$timeout_seconds"); do
        kill -0 "$daemon_pid" 2>/dev/null || fail "daemon exited while waiting for blocked re-entry"
        gateway_get "/get_positions?magic=$magic" > "$latest"
        jq -e '.ok == true and (.data | length) == 0' "$latest" >/dev/null ||
            fail "blocked re-entry opened or retained a position"
        gateway_get "/orders?magic=$magic" > "$orders_latest"
        jq -e '.ok == true and (.orders | length) == 0' "$orders_latest" >/dev/null ||
            fail "blocked re-entry left a pending order"
        if rg --quiet "risk rejected .*${expected_blocked_reason}" "$scenario/logs/daemon.log"; then
            seen=true
            break
        fi
        if rg --quiet 'Order rejected:' "$scenario/logs/daemon.log"; then
            fail "broker rejected an order while waiting for blocked re-entry; expected pre-transport risk rejection"
        fi
        sleep 1
    done
    $seen || fail "no $expected_blocked_reason risk rejection appeared for blocked re-entry"
    "$cli" status "$strategy_name" --state-dir "$scenario/state" > "$evidence/strategy-status-blocked-reentry.json"
}

wait_for_cooldown_recovery_window() {
    [ "$cooldown_after_loss_ms" -gt 0 ] || fail "cooldown recovery lifecycle has no cooldown duration"
    local sleep_seconds=$(((cooldown_after_loss_ms + 999) / 1000 + 5))
    jq -n \
        --argjson cooldownAfterLossMs "$cooldown_after_loss_ms" \
        --argjson sleepSeconds "$sleep_seconds" \
        '{cooldownAfterLossMs:$cooldownAfterLossMs,sleepSeconds:$sleepSeconds,status:"waiting"}' \
        > "$evidence/cooldown-recovery-wait.json"
    sleep "$sleep_seconds"
    jq -n \
        --argjson cooldownAfterLossMs "$cooldown_after_loss_ms" \
        --argjson sleepSeconds "$sleep_seconds" \
        '{cooldownAfterLossMs:$cooldownAfterLossMs,sleepSeconds:$sleepSeconds,status:"elapsed"}' \
        > "$evidence/cooldown-recovery-wait.json"
}

verify_seeded_risk_state() {
    [ -z "$seeded_risk_state_kind" ] && return
    [ "$seeded_risk_state_kind" = "previous-day-max-trades" ] ||
        fail "unsupported seeded risk-state kind: $seeded_risk_state_kind"
    [ -n "$seeded_risk_state_path" ] || fail "seeded risk-state path is missing"
    case "$seeded_risk_state_path" in
        /*|*..*) fail "seeded risk-state path must be scenario-relative: $seeded_risk_state_path" ;;
    esac
    local seed_file="$scenario/$seeded_risk_state_path"
    [ -f "$seed_file" ] || fail "seeded risk-state file is missing: $seed_file"
    local current_epoch_day prior_epoch_day current_day_start_ms prior_day_start_ms
    current_epoch_day="$(($(date -u +%s) / 86400))"
    prior_epoch_day="$((current_epoch_day - 1))"
    current_day_start_ms="$((current_epoch_day * 86400000))"
    prior_day_start_ms="$((prior_epoch_day * 86400000))"
    jq -e \
        --arg strategy "$strategy_name" \
        --argjson priorEpochDay "$prior_epoch_day" \
        --argjson priorDayStartMs "$prior_day_start_ms" \
        --argjson currentDayStartMs "$current_day_start_ms" \
        --argjson expectedEntryMs "$seeded_risk_state_entry_ms" '
            .version == 1 and
            .strategyId == $strategy and
            .epochDay == $priorEpochDay and
            .halted == false and
            .haltReason == null and
            .pacerEntryFillsByStrategy[$strategy] == [$expectedEntryMs] and
            ($expectedEntryMs >= $priorDayStartMs and $expectedEntryMs < $currentDayStartMs)
        ' "$seed_file" >/dev/null || fail "seeded risk-state does not prove a previous-day max-trades entry"
    cp "$seed_file" "$evidence/seeded-risk-state.json"
    jq -n \
        --arg kind "$seeded_risk_state_kind" \
        --arg path "$seeded_risk_state_path" \
        --arg strategy "$strategy_name" \
        --argjson epochDay "$prior_epoch_day" \
        --argjson entryFillMs "$seeded_risk_state_entry_ms" \
        '{kind:$kind,path:$path,strategy:$strategy,epochDay:$epochDay,entryFillMs:$entryFillMs,status:"verified"}' \
        > "$evidence/seeded-risk-state-verification.json"
}

capture_history_snapshot() {
    local attempt="$1"
    timeout --foreground "${history_attempt_timeout_seconds}s" \
        "$cli" bot history --broker exness --since "$run_started_ms" --config "$config" --json \
        > "$evidence/history-during-run.json" 2> "$evidence/history-during-run-attempt-$attempt.log"
}

acquire_live_lock
verify_cli_git_sha

gateway_get /health > "$evidence/gateway-health.json"
jq -e '.ok == true and .status == "healthy" and .mt5_status == "connected" and .kill_switch_active == false' \
    "$evidence/gateway-health.json" >/dev/null || fail "gateway is not healthy and connected"
gateway_get /account > "$evidence/gateway-account-initial.json"
jq -e \
    --argjson login "$expected_login" \
    --arg server "$expected_server" \
    --argjson leverage "$expected_leverage" \
    --arg balance "$expected_balance" '
        .login == $login and
        .server == $server and
        .trade_mode == 0 and
        .currency == "USD" and
        .leverage == $leverage and
        .balance == ($balance | tonumber) and
        .trade_allowed == true and
        .trade_expert == true
    ' "$evidence/gateway-account-initial.json" >/dev/null || fail "gateway account does not match the demo allowlist"
gateway_get "/symbol_info/$venue_symbol" > "$evidence/symbol-info.json"
jq -e --arg venueSymbol "$venue_symbol" --arg expectedContractSize "$expected_contract_size" '
    .name == $venueSymbol and
    .trade_mode == 4 and
    .volume_min == 0.01 and
    .volume_step == 0.01 and
    .trade_contract_size == ($expectedContractSize | tonumber)
' "$evidence/symbol-info.json" >/dev/null || fail "$venue_symbol venue metadata does not match the bounded scenario"

"$cli" bot positions --broker exness --config "$config" --json > "$evidence/positions-initial.json"
"$cli" bot orders --broker exness --config "$config" --json > "$evidence/orders-initial.json"
jq -e 'length == 0' "$evidence/positions-initial.json" >/dev/null || fail "demo account has open positions"
jq -e 'length == 0' "$evidence/orders-initial.json" >/dev/null || fail "demo account has pending orders"
gateway_get "/get_positions?magic=$magic" > "$evidence/positions-magic-initial.json"
gateway_get "/orders?magic=$magic" > "$evidence/orders-magic-initial.json"
jq -e '.ok == true and (.data | length) == 0' "$evidence/positions-magic-initial.json" >/dev/null ||
    fail "scenario magic already owns a position"
jq -e '.ok == true and (.orders | length) == 0' "$evidence/orders-magic-initial.json" >/dev/null ||
    fail "scenario magic already owns a pending order"

wait_for_startup_window
wait_for_history_ready
verify_seeded_risk_state

QKT_STATE_DIR="$scenario/state" "$cli" daemon start \
    --config "$config" \
    --state-dir "$scenario/state" \
    > "$scenario/logs/daemon.log" 2>&1 &
daemon_pid=$!

ready=false
for _ in $(seq 1 60); do
    kill -0 "$daemon_pid" 2>/dev/null || fail "daemon exited before becoming ready"
    if "$cli" daemon status --state-dir "$scenario/state" --json > "$evidence/daemon-status-initial.json" 2>/dev/null; then
        ready=true
        break
    fi
    sleep 1
done
$ready || fail "daemon did not become ready within 60 seconds"

wait_for_fresh_tick_after_daemon

"$cli" deploy "$armed_strategy" --as "$strategy_name" --state-dir "$scenario/state" --json > "$evidence/deploy.json"
jq -e --arg name "$strategy_name" '.name == $name and .state == "running"' "$evidence/deploy.json" >/dev/null ||
    fail "armed strategy did not enter running state"

: > "$owned_tickets_file"
if [ "$lifecycle" = "reentry" ]; then
    for cycle in $(seq 1 "$expected_entries"); do
        wait_for_open_cycle "$cycle"
        "$cli" status "$strategy_name" --state-dir "$scenario/state" > "$evidence/strategy-status-cycle-$cycle-open.json"
        wait_for_flat_cycle "$cycle"
        "$cli" status "$strategy_name" --state-dir "$scenario/state" > "$evidence/strategy-status-cycle-$cycle-flat.json"
    done
    gateway_get "/get_positions?magic=$magic" > "$evidence/positions-magic-final.json"
    jq -e '.ok == true and (.data | length) == 0' "$evidence/positions-magic-final.json" >/dev/null ||
        fail "re-entry lifecycle did not end flat"
elif [ "$lifecycle" = "reentry_blocked_max_trades" ] || [ "$lifecycle" = "reentry_max_trades_next_day_recovered" ] || [ "$lifecycle" = "reentry_blocked_operator_halt" ] || [ "$lifecycle" = "reentry_operator_halt_recovered" ] || [ "$lifecycle" = "reentry_cooldown_recovered" ] || [ "$lifecycle" = "reentry_blocked_loss_streak" ]; then
    wait_for_open_cycle 1
    "$cli" status "$strategy_name" --state-dir "$scenario/state" > "$evidence/strategy-status-cycle-1-open.json"
    wait_for_flat_cycle 1
    "$cli" status "$strategy_name" --state-dir "$scenario/state" > "$evidence/strategy-status-cycle-1-flat.json"
    if [ "$lifecycle" = "reentry_blocked_operator_halt" ] || [ "$lifecycle" = "reentry_operator_halt_recovered" ]; then
        "$cli" halt "$strategy_name" --state-dir "$scenario/state" --json > "$evidence/operator-halt-before-blocked-reentry.json"
        jq -e --arg strategy "$strategy_name" '
            .state == "halted" and (.affected | index($strategy)) != null
        ' "$evidence/operator-halt-before-blocked-reentry.json" >/dev/null ||
            fail "operator halt did not affect the re-entry strategy"
    fi
    wait_for_blocked_reentry
    gateway_get "/get_positions?magic=$magic" > "$evidence/positions-magic-final.json"
    jq -e '.ok == true and (.data | length) == 0' "$evidence/positions-magic-final.json" >/dev/null ||
        fail "blocked re-entry lifecycle did not end flat"
    if [ "$lifecycle" = "reentry_operator_halt_recovered" ]; then
        "$cli" resume "$strategy_name" --state-dir "$scenario/state" --json > "$evidence/operator-resume-before-recovered-reentry.json"
        jq -e '.state == "resumed"' "$evidence/operator-resume-before-recovered-reentry.json" >/dev/null ||
            fail "operator resume did not lift the re-entry halt"
    elif [ "$lifecycle" = "reentry_cooldown_recovered" ]; then
        wait_for_cooldown_recovery_window
    fi
    if [ "$lifecycle" = "reentry_operator_halt_recovered" ] || [ "$lifecycle" = "reentry_cooldown_recovered" ]; then
        wait_for_open_cycle 2
        "$cli" status "$strategy_name" --state-dir "$scenario/state" > "$evidence/strategy-status-cycle-2-open.json"
        wait_for_flat_cycle 2
        "$cli" status "$strategy_name" --state-dir "$scenario/state" > "$evidence/strategy-status-cycle-2-flat.json"
        gateway_get "/get_positions?magic=$magic" > "$evidence/positions-magic-final.json"
        jq -e '.ok == true and (.data | length) == 0' "$evidence/positions-magic-final.json" >/dev/null ||
            fail "recovered re-entry lifecycle did not end flat after the recovered entry"
    fi
else
    wait_for_open_cycle 1
    cp "$evidence/positions-magic-cycle-1-open.json" "$evidence/positions-magic-open.json"
    "$cli" status "$strategy_name" --state-dir "$scenario/state" > "$evidence/strategy-status-open.json"
    "$cli" kill "$strategy_name" --flatten --state-dir "$scenario/state" --json > "$evidence/kill-flatten.json"
    jq -e '.state == "killed" and .flatten == true and .flattenVerified == true and (.remainingTickets | length) == 0' \
        "$evidence/kill-flatten.json" >/dev/null || fail "QKT could not verify the bracket position was flattened"

    flat_seen=false
    for _ in $(seq 1 30); do
        gateway_get "/get_positions?magic=$magic" > "$evidence/positions-magic-final.json"
        if jq -e '.ok == true and (.data | length) == 0' "$evidence/positions-magic-final.json" >/dev/null; then
            flat_seen=true
            break
        fi
        sleep 1
    done
    $flat_seen || fail "scenario magic remained non-flat after verified flatten"
fi

"$cli" stop "$strategy_name" --state-dir "$scenario/state" --json > "$evidence/stop-strategy.json"
"$cli" daemon stop --state-dir "$scenario/state" > "$evidence/daemon-stop.log"
wait "$daemon_pid"
daemon_pid=""

"$cli" bot positions --broker exness --config "$config" --json > "$evidence/positions-final.json"
"$cli" bot orders --broker exness --config "$config" --json > "$evidence/orders-final.json"
jq -e 'length == 0' "$evidence/positions-final.json" >/dev/null || fail "demo account is not flat after the scenario"
jq -e 'length == 0' "$evidence/orders-final.json" >/dev/null || fail "demo account has a pending order after the scenario"

deals_seen=false
for attempt in $(seq 1 30); do
    if ! capture_history_snapshot "$attempt"; then
        sleep 1
        continue
    fi
    entry_count="$(jq --arg venueSymbol "$venue_symbol" '[.[] | select(.symbol == $venueSymbol and .entry == "IN" and .lots == 0.01)] | length' "$evidence/history-during-run.json")"
    exit_count="$(jq --arg venueSymbol "$venue_symbol" '[.[] | select(.symbol == $venueSymbol and .entry == "OUT" and .lots == 0.01)] | length' "$evidence/history-during-run.json")"
    if [ "$entry_count" -ge "$expected_entries" ] && [ "$exit_count" -ge "$expected_exits" ]; then
        deals_seen=true
        break
    fi
    sleep 1
done
$deals_seen || fail "venue history did not expose the expected entry and exit deals"
mapfile -t owned_tickets < <(jq -r '.ticket' "$owned_tickets_file")
[ "${#owned_tickets[@]}" -eq "$expected_entries" ] ||
    fail "runner retained ${#owned_tickets[@]} owned tickets, expected $expected_entries"
for ticket in "${owned_tickets[@]}"; do
    jq -e --argjson ticket "$ticket" '
        ([.[] | select(.positionTicket == $ticket and .entry == "IN")] | length) >= 1 and
        ([.[] | select(.positionTicket == $ticket and .entry == "OUT")] | length) >= 1
    ' "$evidence/history-during-run.json" >/dev/null || fail "entry and exit deals do not share owned position ticket $ticket"
done

gateway_get /account > "$evidence/gateway-account-final.json"
initial_balance="$(jq -r '.balance' "$evidence/gateway-account-initial.json")"
final_balance="$(jq -r '.balance' "$evidence/gateway-account-final.json")"
initial_leverage="$(jq -r '.leverage' "$evidence/gateway-account-initial.json")"
final_leverage="$(jq -r '.leverage' "$evidence/gateway-account-final.json")"
leverage_changed=false
[ "$initial_leverage" = "$final_leverage" ] || leverage_changed=true
balance_delta="$(awk -v initial="$initial_balance" -v final="$final_balance" 'BEGIN {printf "%.2f", final - initial}')"
deal_net="$(
    jq -r --slurpfile tickets "$owned_tickets_file" '
        ($tickets | map(.ticket)) as $owned |
        [.[] | select(.positionTicket as $ticket | $owned | index($ticket)) |
            ((.profit // 0) + (.commission // 0) + (.swap // 0) + (.fee // 0))] | add // 0
    ' "$evidence/history-during-run.json" |
        awk '{printf "%.2f", $1}'
)"
[ "$balance_delta" = "$deal_net" ] || fail "venue balance delta $balance_delta does not reconcile to deal net $deal_net"
jq -e '.trade_mode == 0 and .trade_allowed == true and .trade_expert == true and .leverage > 0 and .margin == 0 and .equity == .balance' \
    "$evidence/gateway-account-final.json" >/dev/null || fail "final demo account snapshot is not flat and tradeable"

mapfile -t audit_journals < <(find "$scenario/state/state/audit-journal" -type f -name '*.jsonl' | sort)
mapfile -t transport_journals < <(find "$scenario/state/state/mt5-transport-journal" -type f -name '*.jsonl' | sort)
[ "${#audit_journals[@]}" -gt 0 ] || fail "daemon produced no engine audit journal"
[ "${#transport_journals[@]}" -gt 0 ] || fail "daemon produced no MT5 transport journal"
for journal in "${audit_journals[@]}" "${transport_journals[@]}"; do
    jq -c . "$journal" >/dev/null || fail "journal is not valid JSONL: $journal"
done
accepted_events="$(jq -r 'select(.eventType == "com.qkt.events.BrokerEvent.OrderAccepted") | 1' "${audit_journals[@]}" | awk 'END {print NR + 0}')"
filled_events="$(jq -r 'select(.eventType == "com.qkt.events.BrokerEvent.OrderFilled") | 1' "${audit_journals[@]}" | awk 'END {print NR + 0}')"
expected_lifecycle_events="$((expected_entries + expected_exits))"
[ "$accepted_events" -ge "$expected_lifecycle_events" ] || fail "audit journal is missing accepted lifecycle events"
[ "$filled_events" -ge "$expected_lifecycle_events" ] || fail "audit journal is missing filled lifecycle events"
risk_rejections="$(jq -r --arg reason "$expected_blocked_reason" '
    select(.eventType == "com.qkt.events.RiskRejectedEvent" and (.reason // "" | contains($reason))) | 1
' "${audit_journals[@]}" | awk 'END {print NR + 0}')"
order_posts="$(jq -r 'select(.method == "POST" and .path == "/order" and (.responseCode >= 200 and .responseCode < 300)) | 1' "${transport_journals[@]}" | awk 'END {print NR + 0}')"
close_posts="$(jq -r 'select(.method == "POST" and .path == "/close_position" and (.responseCode >= 200 and .responseCode < 300)) | 1' "${transport_journals[@]}" | awk 'END {print NR + 0}')"
[ "$order_posts" -ge "$expected_entries" ] || fail "transport journal is missing accepted MT5 order calls"
[ "$close_posts" -ge "$expected_exits" ] || fail "transport journal is missing accepted MT5 close calls"
if [ "$expected_blocked_entries" -gt 0 ]; then
    [ "$risk_rejections" -ge "$expected_blocked_entries" ] ||
        fail "audit journal is missing the expected blocked re-entry risk rejection"
    [ "$order_posts" -eq "$expected_entries" ] ||
        fail "blocked re-entry reached MT5 transport; order posts=$order_posts expected=$expected_entries"
fi

golden_zip="$evidence/golden.zip"
golden_manifest="$evidence/golden-manifest.json"
"$cli" golden capture \
    --session "$strategy_name" \
    --state-dir "$scenario/state" \
    --out "$golden_zip" > "$evidence/golden-capture.log"
unzip -p "$golden_zip" manifest.json > "$golden_manifest"
jq -e \
    --arg strategy "$strategy_name" \
    --arg qktCommit "$qkt_commit" '
        .schemaVersion == 2 and
        .kind == "MT5_GOLDEN_CAPTURE" and
        .session == $strategy and
        (.captureGitSha as $capture | ($qktCommit | startswith($capture))) and
        .counts.ticks > 0 and
        .counts.warmupTicks > 0 and
        .counts.candles > 0 and
        .counts.fills >= '"$expected_lifecycle_events"' and
        .counts.gatewayExchanges > 0 and
        .counts.linkedPlacements >= '"$expected_entries"'
    ' "$golden_manifest" >/dev/null || fail "golden capture does not match the completed live session"
while IFS=$'\t' read -r path expected_sha; do
    actual_sha="$(unzip -p "$golden_zip" "$path" | sha256sum | awk '{print $1}')"
    [ "$actual_sha" = "$expected_sha" ] || fail "golden capture entry hash mismatch: $path"
done < <(jq -r '.entries[] | [.path,.sha256] | @tsv' "$golden_manifest")
golden_ticks="$(jq -r '.counts.ticks' "$golden_manifest")"
golden_warmup_ticks="$(jq -r '.counts.warmupTicks' "$golden_manifest")"
golden_candles="$(jq -r '.counts.candles' "$golden_manifest")"
golden_fills="$(jq -r '.counts.fills' "$golden_manifest")"
golden_exchanges="$(jq -r '.counts.gatewayExchanges' "$golden_manifest")"
golden_placements="$(jq -r '.counts.linkedPlacements' "$golden_manifest")"
golden_sha="$(sha256sum "$golden_zip" | awk '{print $1}')"

stale_log="$scenario/logs/daemon.log"
halt_line="$(rg -n 'halt \((operator kill|operator)\):' "$scenario/logs/daemon.log" | tail -n 1 | cut -d: -f1 || true)"
if [ -n "$halt_line" ] && [ "$halt_line" -gt 1 ]; then
    stale_log="$evidence/daemon-pre-halt.log"
    sed -n "1,$((halt_line - 1))p" "$scenario/logs/daemon.log" > "$stale_log"
fi
stale_events="$(rg -c 'market data .* STALE:' "$stale_log" || printf '0\n')"
recovery_events="$(rg -c 'market data .* healthy again' "$stale_log" || printf '0\n')"
[ "$recovery_events" -ge "$stale_events" ] || fail "market-data stale episode did not recover before shutdown"

jq \
    --slurpfile tickets "$owned_tickets_file" \
    '.ownedPositionTickets = ($tickets | map(.ticket)) | .ownedOrderTickets = [] | .status = "verified_flat"' \
    "$scenario/cleanup.json" > "$scenario/cleanup.json.tmp"
mv "$scenario/cleanup.json.tmp" "$scenario/cleanup.json"

qkt_version="$("$cli" --version)"
gateway_version="$(jq -r '.version' "$evidence/gateway-health.json")"
finished_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
jq -n \
    --arg finishedAt "$finished_at" \
    --arg qktVersion "$qkt_version" \
    --arg gatewayVersion "$gateway_version" \
    --arg qktCommit "$qkt_commit" \
    --argjson qktDirty "$qkt_dirty" \
    --arg strategy "$strategy_name" \
    --argjson magic "$magic" \
    --argjson ticket "$owned_ticket" \
    --slurpfile tickets "$owned_tickets_file" \
    --arg lifecycle "$lifecycle" \
    --argjson expectedEntries "$expected_entries" \
    --argjson expectedExits "$expected_exits" \
    --argjson expectedBlockedEntries "$expected_blocked_entries" \
    --arg blockedReason "$expected_blocked_reason" \
    --arg balanceDelta "$balance_delta" \
    --arg dealNet "$deal_net" \
    --arg initialLeverage "$initial_leverage" \
    --arg finalLeverage "$final_leverage" \
    --argjson leverageChanged "$leverage_changed" \
    --arg acceptedEvents "$accepted_events" \
    --arg filledEvents "$filled_events" \
    --arg riskRejections "$risk_rejections" \
    --arg orderPosts "$order_posts" \
    --arg closePosts "$close_posts" \
    --arg goldenTicks "$golden_ticks" \
    --arg goldenWarmupTicks "$golden_warmup_ticks" \
    --arg goldenCandles "$golden_candles" \
    --arg goldenFills "$golden_fills" \
    --arg goldenExchanges "$golden_exchanges" \
    --arg goldenPlacements "$golden_placements" \
    --arg goldenSha "$golden_sha" \
    --arg staleEvents "$stale_events" \
    --arg recoveryEvents "$recovery_events" \
    --arg stopDistance "$stop_distance" \
    --arg takeProfitDistance "$take_profit_distance" \
    --arg seededRiskStateKind "$seeded_risk_state_kind" \
    --arg seededRiskStatePath "$seeded_risk_state_path" \
    --arg seededRiskStateEntryMs "$seeded_risk_state_entry_ms" '
        {
          schema:"qkt-live-validation-market-bracket-v1",
          status:"passed",
          finishedAt:$finishedAt,
          qktVersion:$qktVersion,
          qktCommit:$qktCommit,
          qktDirty:$qktDirty,
          gatewayVersion:$gatewayVersion,
          strategy:$strategy,
          lifecycle:$lifecycle,
          magic:$magic,
          positionTicket:$ticket,
          positionTickets:($tickets | map(.ticket)),
          lots:"0.01",
          expectedLifecycle:{entries:$expectedEntries,exits:$expectedExits},
          blockedReentry:{
            expected:$expectedBlockedEntries,
            reason:(if $blockedReason == "" then null else $blockedReason end),
            rejections:($riskRejections|tonumber),
            preTransport:($expectedBlockedEntries == 0 or ($orderPosts|tonumber) == $expectedEntries)
          },
          bracket:{stopDistance:$stopDistance,takeProfitDistance:$takeProfitDistance},
          flattenVerified:(if $lifecycle == "single" then true else false end),
          strategyOwnedLifecycle:($lifecycle == "reentry" or $lifecycle == "reentry_blocked_max_trades" or $lifecycle == "reentry_max_trades_next_day_recovered" or $lifecycle == "reentry_blocked_operator_halt" or $lifecycle == "reentry_operator_halt_recovered" or $lifecycle == "reentry_cooldown_recovered" or $lifecycle == "reentry_blocked_loss_streak"),
          seededRiskState:(if $seededRiskStateKind == "" then null else {kind:$seededRiskStateKind,path:$seededRiskStatePath,entryFillMs:($seededRiskStateEntryMs|tonumber)} end),
          finalPositions:0,
          finalOrders:0,
          balanceDelta:$balanceDelta,
          dealNet:$dealNet,
          leverage:{
            initial:($initialLeverage|tonumber),
            final:($finalLeverage|tonumber),
            changed:$leverageChanged
          },
          audit:{
            acceptedEvents:($acceptedEvents|tonumber),
            filledEvents:($filledEvents|tonumber),
            riskRejections:($riskRejections|tonumber)
          },
          transport:{orderPosts:($orderPosts|tonumber),closePosts:($closePosts|tonumber)},
          golden:{
            ticks:($goldenTicks|tonumber),
            warmupTicks:($goldenWarmupTicks|tonumber),
            candles:($goldenCandles|tonumber),
            fills:($goldenFills|tonumber),
            gatewayExchanges:($goldenExchanges|tonumber),
            linkedPlacements:($goldenPlacements|tonumber),
            sha256:$goldenSha
          },
          staleEvents:($staleEvents|tonumber),
          recoveredStaleEvents:($recoveryEvents|tonumber)
        }
    ' > "$evidence/result.json"

for transient in "$scenario/state/control.token" "$scenario/state/daemon.pid"; do
    if [ -e "$transient" ]; then
        unlink "$transient"
    fi
done
if printf '%s' "$QKT_BROKER_API_KEY" | rg --text --fixed-strings --quiet -f - "$scenario"; then
    fail "broker credential was persisted in the scenario artifacts"
fi

manifest="$evidence/artifact-manifest.json"
printf '{"schema":"qkt-live-validation-artifacts-v1","artifacts":[' > "$manifest"
first=true
while IFS= read -r -d '' artifact; do
    relative="${artifact#"$scenario/"}"
    [ "$relative" = "evidence/artifact-manifest.json" ] && continue
    [ "$relative" = "RUN-SHA256SUMS" ] && continue
    if $first; then first=false; else printf ',' >> "$manifest"; fi
    jq -cn \
        --arg path "$relative" \
        --arg sha256 "$(sha256sum "$artifact" | awk '{print $1}')" \
        --argjson size "$(stat -c %s "$artifact")" \
        '{path:$path,size:$size,sha256:$sha256}' >> "$manifest"
done < <(find "$scenario" -type f -print0 | sort -z)
printf ']}\n' >> "$manifest"
(
    cd "$scenario"
    find . -type f ! -path './RUN-SHA256SUMS' -print0 |
        sort -z |
        xargs -0 sha256sum > RUN-SHA256SUMS
    sha256sum --check RUN-SHA256SUMS >/dev/null
)

trap - EXIT
printf 'passed %s\n' "$evidence/result.json"
