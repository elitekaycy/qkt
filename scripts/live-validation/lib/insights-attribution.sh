#!/usr/bin/env bash

qkt_count_cross_owner_causal_events() {
    local db="$1"
    local instance="$2"
    local owner="$3"
    sqlite3 "$db" "
        select count(*)
        from events
        where instance_id='$instance'
          and (
              type in ('decision.order_linked','order.submit','order.accepted',
                       'order.filled','trade','fill.accounted')
              or (
                  type='decision.rule_evaluated'
                  and coalesce(json_extract(payload,'$.signalCount'),0) > 0
              )
          )
          and coalesce(strategy_id,'') != '$owner';
    "
}

qkt_export_armed_rule_decisions() {
    local db="$1"
    local instance="$2"
    local owner="$3"
    local output="$4"
    sqlite3 -json "$db" "
        select payload
        from events
        where instance_id='$instance'
          and type='decision.rule_evaluated'
          and strategy_id='$owner'
        order by ts,seq;
    " > "$output"
}

qkt_count_joined_rule_order_links() {
    local db="$1"
    local instance="$2"
    local owner="$3"
    sqlite3 "$db" "
        select count(*)
        from events link
        where link.instance_id='$instance'
          and link.type='decision.order_linked'
          and link.strategy_id='$owner'
          and exists (
              select 1
              from events rule
              where rule.instance_id=link.instance_id
                and rule.type='decision.rule_evaluated'
                and rule.strategy_id=link.strategy_id
                and json_extract(rule.payload,'$.decisionId')=
                    json_extract(link.payload,'$.decisionId')
          )
          and exists (
              select 1
              from events submit
              where submit.instance_id=link.instance_id
                and submit.type='order.submit'
                and submit.strategy_id=link.strategy_id
                and (
                    json_extract(submit.payload,'$.orderId')=
                        json_extract(link.payload,'$.orderId')
                    or json_extract(submit.payload,'$.planOrderId')=
                        json_extract(link.payload,'$.orderId')
                )
          );
    "
}

qkt_has_post_deployment_matched_stream_evaluations() {
    local journal_root="$1"
    local strategy="$2"
    local symbol="$3"
    local deployed_after_ms="$4"
    local journals=()
    mapfile -t journals < <(find "$journal_root" -type f -name '*.jsonl' | sort)
    [ "${#journals[@]}" -gt 0 ] || return 1
    jq -s -e --arg strategy "$strategy" --arg symbol "$symbol" \
        --argjson deployedAfterMs "$deployed_after_ms" '
        . as $events | all([["asset1","1m"],["asset5","5m"]][];
          .[0] as $alias | .[1] as $timeframe |
          any($events[]; .eventType == "com.qkt.events.StrategyCandleEvaluatedEvent" and
            .strategyId == $strategy and .symbol == $symbol and .alias == $alias and
            .timeframe == $timeframe and .ts >= $deployedAfterMs and
            (. as $evaluation | any($events[]; .eventType == "com.qkt.events.StreamCandleEvent" and
              .symbol == $symbol and .timeframe == $timeframe and .ts >= $deployedAfterMs and
              .candle.startTimeMs == $evaluation.candle.startTimeMs and
              .candle.endTimeMs == $evaluation.candle.endTimeMs))))
    ' "${journals[@]}" >/dev/null
}

qkt_wait_for_post_deployment_matched_stream_evaluations() {
    local journal_root="$1"
    local strategy="$2"
    local symbol="$3"
    local deployed_after_ms="$4"
    local timeout_seconds="$5"
    local daemon_pid="${6:-}"
    local poll_seconds="${7:-1}"
    local deadline=$((SECONDS + timeout_seconds))
    while true; do
        if qkt_has_post_deployment_matched_stream_evaluations \
            "$journal_root" "$strategy" "$symbol" "$deployed_after_ms"; then
            return 0
        fi
        [ "$SECONDS" -lt "$deadline" ] || return 1
        if [ -n "$daemon_pid" ] && ! kill -0 "$daemon_pid" 2>/dev/null; then
            return 2
        fi
        sleep "$poll_seconds"
    done
}

qkt_validate_bounded_rule_decisions() {
    local decisions="$1"
    local symbol="$2"
    # Each entry compiles to one bracket Submit. CLOSE compiles to cancel-pending
    # plus the exact-ticket Submit; the trailing LOG contributes no signal.
    jq -e --arg symbol "$symbol" '
        map(.payload | fromjson) as $decisions |
        ($decisions | length) == 2 and
        ([$decisions[] |
            select((.ruleId == "asset1#0" or .ruleId == "asset1#1") and .signalCount == 1)
         ] | length) == 1 and
        ([$decisions[] | select(.ruleId == "asset1#2" and .signalCount == 2)] | length) == 1 and
        all($decisions[];
          . as $p |
          ($p.decisionId | type == "string" and length > 0) and
          ($p.ruleId | type == "string" and length > 0) and
          ($p.strategyFingerprint | test("^[0-9a-f]{64}$")) and
          ($p.ruleFingerprint | test("^[0-9a-f]{64}$")) and
          ($p.conditionFingerprint | test("^[0-9a-f]{64}$")) and
          $p.conditionResult == true and $p.alias == "asset1" and
          $p.broker == "EXNESS" and $p.timeframe == "1m" and
          $p.candle.startTimeMs < $p.candle.endTimeMs and
          ([$p.candle.open,$p.candle.high,$p.candle.low,$p.candle.close,$p.candle.volume] |
              all(. != null)) and
          $p.candle.symbol == $symbol)
    ' "$decisions" >/dev/null
}

qkt_write_engine_rule_decisions() {
    local owner="$1"
    local output="$2"
    shift 2
    jq -r --arg strategy "$owner" '
        select(.eventType == "com.qkt.events.RuleDecisionEvent" and .strategyId == $strategy) |
        [.decisionId,.ruleId,.strategyFingerprint,.ruleFingerprint,.conditionFingerprint,
         (.conditionResult|tostring),.alias,.broker,.timeframe,.symbol,(.signalCount|tostring),
         (.candle.startTimeMs|tostring),(.candle.endTimeMs|tostring),
         (((.candle.open|tonumber)+0)|tostring),(((.candle.high|tonumber)+0)|tostring),
         (((.candle.low|tonumber)+0)|tostring),(((.candle.close|tonumber)+0)|tostring),
         (((.candle.volume|tonumber)+0)|tostring)] | @tsv
    ' "$@" | sort > "$output"
}

qkt_write_collector_rule_decisions() {
    local decisions="$1"
    local output="$2"
    jq -r '.[] | (.payload | fromjson) |
        [.decisionId,.ruleId,.strategyFingerprint,.ruleFingerprint,.conditionFingerprint,
         (.conditionResult|tostring),.alias,.broker,.timeframe,.candle.symbol,(.signalCount|tostring),
         (.candle.startTimeMs|tostring),(.candle.endTimeMs|tostring),
         (((.candle.open|tonumber)+0)|tostring),(((.candle.high|tonumber)+0)|tostring),
         (((.candle.low|tonumber)+0)|tostring),(((.candle.close|tonumber)+0)|tostring),
         (((.candle.volume|tonumber)+0)|tostring)] | @tsv
    ' "$decisions" | sort > "$output"
}
