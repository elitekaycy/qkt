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
