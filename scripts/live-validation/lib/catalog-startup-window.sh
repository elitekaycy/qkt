#!/usr/bin/env bash

QKT_CATALOG_ROLLOVER_PERIOD_MS=300000
QKT_CATALOG_SAFE_START_MS=90000
QKT_CATALOG_SAFE_END_MS=150000

# Returns the bounded delay until the next safe catalog launch phase.
qkt_catalog_startup_delay_ms() {
    local phase_ms="$1"
    [[ "$phase_ms" =~ ^[0-9]+$ ]] || return 2
    [ "$phase_ms" -lt "$QKT_CATALOG_ROLLOVER_PERIOD_MS" ] || return 2
    if [ "$phase_ms" -lt "$QKT_CATALOG_SAFE_START_MS" ]; then
        printf '%s\n' "$((QKT_CATALOG_SAFE_START_MS - phase_ms))"
    elif [ "$phase_ms" -le "$QKT_CATALOG_SAFE_END_MS" ]; then
        printf '0\n'
    else
        printf '%s\n' "$((QKT_CATALOG_ROLLOVER_PERIOD_MS - phase_ms + QKT_CATALOG_SAFE_START_MS))"
    fi
}
