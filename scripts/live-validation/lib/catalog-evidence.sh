#!/usr/bin/env bash

# Counts exact candle/evaluation joins, requiring a positive rule count only for rule drivers.
qkt_catalog_matched_evaluation_count() {
    local strategy="$1"
    local alias="$2"
    local symbol="$3"
    local timeframe="$4"
    local evaluation_role="$5"
    shift 5
    case "$evaluation_role" in
        rule-driver) local require_rules=true ;;
        dependency) local require_rules=false ;;
        *) return 2 ;;
    esac
    jq -s --arg strategy "$strategy" --arg alias "$alias" --arg symbol "$symbol" \
        --arg timeframe "$timeframe" --argjson requireRules "$require_rules" '
        . as $events |
        [$events[] | select(
            .eventType == "com.qkt.events.StrategyCandleEvaluatedEvent" and
            .strategyId == $strategy and .alias == $alias and .symbol == $symbol and
            .timeframe == $timeframe and .rulesEvaluated >= 0 and
            ((.rulesEvaluated > 0) or ($requireRules | not)) and
            (. as $evaluation | any($events[];
                .eventType == "com.qkt.events.StreamCandleEvent" and
                .symbol == $symbol and .timeframe == $timeframe and
                .candle.startTimeMs == $evaluation.candle.startTimeMs and
                .candle.endTimeMs == $evaluation.candle.endTimeMs
            ))
        )] | length' "$@"
}

# Summarizes runtime logs and fails when stale episodes, disconnects, or errors violate policy.
qkt_catalog_runtime_log_summary() {
    local before_shutdown_log="$1"
    local complete_log="$2"
    local stale recovered unrecovered unmatched_recoveries
    local in_window_disconnects all_disconnects shutdown_disconnects unexpected_errors post_boundary_stale before_lines
    read -r stale recovered unrecovered unmatched_recoveries < <(awk '
        /market data for .* STALE:/ {
            symbol=$0
            sub(/^.*market data for /, "", symbol)
            sub(/ STALE:.*$/, "", symbol)
            open[symbol]++
            stale++
        }
        /market data for .* healthy again/ {
            symbol=$0
            sub(/^.*market data for /, "", symbol)
            sub(/ healthy again.*$/, "", symbol)
            recovered++
            if (open[symbol] > 0) open[symbol]--
            else unmatched++
        }
        END {
            for (symbol in open) pending += open[symbol]
            print stale + 0, recovered + 0, pending + 0, unmatched + 0
        }
    ' "$before_shutdown_log")
    in_window_disconnects="$(awk '/LiveTickFeed source disconnected/ {count++} END {print count + 0}' "$before_shutdown_log")"
    all_disconnects="$(awk '/LiveTickFeed source disconnected/ {count++} END {print count + 0}' "$complete_log")"
    shutdown_disconnects=$((all_disconnects - in_window_disconnects))
    before_lines="$(awk 'END {print NR + 0}' "$before_shutdown_log")"
    post_boundary_stale="$(awk -v beforeLines="$before_lines" '
        NR > beforeLines && /market data .* STALE:/ {count++}
        END {print count + 0}
    ' "$complete_log")"
    unexpected_errors="$(awk '
        /ERROR/ && !(/MarketDataGate/ && /market data .* STALE:/) {count++}
        END {print count + 0}
    ' "$complete_log")"
    jq -cn --argjson staleEvents "$stale" --argjson recoveredStaleEvents "$recovered" \
        --argjson inWindowDisconnectWarnings "$in_window_disconnects" \
        --argjson shutdownDisconnectWarnings "$shutdown_disconnects" \
        --argjson unexpectedErrors "$unexpected_errors" --argjson postBoundaryStaleEvents "$post_boundary_stale" \
        --argjson unrecoveredEpisodes "$unrecovered" \
        --argjson unmatchedRecoveries "$unmatched_recoveries" '
        {staleEvents:$staleEvents,recoveredStaleEvents:$recoveredStaleEvents,
         unrecoveredEpisodes:$unrecoveredEpisodes,unmatchedRecoveries:$unmatchedRecoveries,
         inWindowDisconnectWarnings:$inWindowDisconnectWarnings,
         shutdownDisconnectWarnings:$shutdownDisconnectWarnings,
         postBoundaryStaleEvents:$postBoundaryStaleEvents,unexpectedErrors:$unexpectedErrors,
         allStaleEpisodesRecovered:($unrecoveredEpisodes == 0 and $unmatchedRecoveries == 0)}'
    [ "$unrecovered" -eq 0 ] && [ "$unmatched_recoveries" -eq 0 ] && [ "$in_window_disconnects" -eq 0 ] &&
        [ "$shutdown_disconnects" -ge 0 ] && [ "$post_boundary_stale" -eq 0 ] && [ "$unexpected_errors" -eq 0 ]
}
