#!/usr/bin/env bash

# Counts synthetic warmup ticks for one exact symbol/timeframe inside an optional audit window.
qkt_count_warmup_pseudo_ticks() {
    local symbol="$1"
    local timeframe_ms="$2"
    local after_ms="$3"
    local before_ms="$4"
    shift 4
    jq -s --arg symbol "$symbol" --argjson timeframeMs "$timeframe_ms" \
        --argjson afterMs "$after_ms" --argjson beforeMs "$before_ms" '[.[] | select(
            .eventType == "com.qkt.events.WarmupTickEvent" and
            .symbol == $symbol and .sourceTimeframeMs == $timeframeMs and
            ($afterMs < 0 or .ts >= $afterMs) and ($beforeMs < 0 or .ts <= $beforeMs)
        )] | length' "$@"
}

# Counts evaluations joined to the exact stream candle by symbol, timeframe, and candle window.
qkt_count_matched_evaluations() {
    local strategy="$1"
    local alias="$2"
    local symbol="$3"
    local timeframe="$4"
    local after_ms="$5"
    local before_ms="$6"
    shift 6
    jq -s --arg strategy "$strategy" --arg alias "$alias" --arg symbol "$symbol" \
        --arg timeframe "$timeframe" --argjson afterMs "$after_ms" --argjson beforeMs "$before_ms" '
        def in_window:
            ($afterMs < 0 or .ts >= $afterMs) and ($beforeMs < 0 or .ts < $beforeMs);
        . as $events |
        [$events[] | select(
            in_window and
            .eventType == "com.qkt.events.StrategyCandleEvaluatedEvent" and
            .strategyId == $strategy and .alias == $alias and .symbol == $symbol and
            .timeframe == $timeframe and .rulesEvaluated == 1 and
            (. as $evaluation | any($events[];
                in_window and .eventType == "com.qkt.events.StreamCandleEvent" and
                .symbol == $symbol and .timeframe == $timeframe and
                .candle.startTimeMs == $evaluation.candle.startTimeMs and
                .candle.endTimeMs == $evaluation.candle.endTimeMs
            ))
        )] | length' "$@"
}

qkt_count_events_in_window() {
    local event_type="$1"
    local after_ms="$2"
    local before_ms="$3"
    shift 3
    jq -s --arg eventType "$event_type" --argjson afterMs "$after_ms" --argjson beforeMs "$before_ms" \
        '[.[] | select(
            .eventType == $eventType and .ts >= $afterMs and ($beforeMs < 0 or .ts <= $beforeMs)
        )] | length' "$@"
}
