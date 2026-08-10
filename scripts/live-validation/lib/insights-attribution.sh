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
