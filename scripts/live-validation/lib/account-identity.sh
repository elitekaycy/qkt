#!/usr/bin/env bash

qkt_require_runtime_account_identity() {
    QKT_EXPECTED_ACCOUNT_LOGIN="${QKT_BROKER_EXNESS_EXPECTED_ACCOUNT_LOGIN:-}"
    QKT_EXPECTED_ACCOUNT_SERVER="${QKT_BROKER_EXNESS_EXPECTED_ACCOUNT_SERVER:-}"
    [[ "$QKT_EXPECTED_ACCOUNT_LOGIN" =~ ^[1-9][0-9]*$ ]] || {
        printf 'QKT_BROKER_EXNESS_EXPECTED_ACCOUNT_LOGIN must be a positive integer\n' >&2
        return 1
    }
    [[ "$QKT_EXPECTED_ACCOUNT_SERVER" =~ ^[A-Za-z0-9._-]+$ ]] || {
        printf 'QKT_BROKER_EXNESS_EXPECTED_ACCOUNT_SERVER contains unsupported characters\n' >&2
        return 1
    }
}

qkt_write_safe_account_snapshot() {
    local output="$1"
    jq '{balance,equity,margin,profit,margin_free,margin_level,currency,leverage,
         margin_mode,trade_mode,trade_allowed,trade_expert}' > "$output"
}

qkt_write_safe_gateway_health_snapshot() {
    local output="$1"
    jq '{ok,status,mt5_status,kill_switch_active,last_error,uptime_seconds,version}' > "$output"
}

qkt_write_safe_live_state_snapshot() {
    local output="$1"
    jq '{
        schema:"qkt-live-state-attribution-evidence-v1",
        positions:[.positions[]? | {list:[.list[]? | {ticket,strategyId,state}]}]
    }' > "$output"
}

qkt_sanitize_account_transport_journals() {
    local root="$1"
    local journal temporary
    while IFS= read -r -d '' journal; do
        temporary="$journal.identity-safe.tmp"
        jq -c '
            if ((.path // "") | split("?")[0]) == "/account" and (.responseBody? | type) == "string" then
                .responseBody = ((.responseBody | fromjson |
                    {balance,equity,margin,profit,margin_free,margin_level,currency,leverage,
                     margin_mode,trade_mode,trade_allowed,trade_expert}) | tojson)
            else . end
        ' "$journal" > "$temporary"
        mv "$temporary" "$journal"
    done < <(find "$root" -type f -name '*.jsonl' -print0)
}

qkt_redact_account_identity_log() {
    local log_file="$1"
    local login="$2"
    local server="$3"
    [ -f "$log_file" ] || return 0
    sed -i \
        -e "s/login=$login server=$server/login=[redacted] server=[redacted]/g" \
        -e "s/account login mismatch: expected $login, got [0-9][0-9]*/account login mismatch: [redacted]/g" \
        -e "s/account server mismatch: expected '$server', got '[^']*'/account server mismatch: [redacted]/g" \
        "$log_file"
}

qkt_assert_no_retained_account_identity() {
    local root="$1"
    local login="$2"
    local server="$3"
    local leaked=()
    mapfile -d '' -t leaked < <(
        rg --files-with-matches --null --text --fixed-strings \
            -e "$login" -e "$server" "$root" 2>/dev/null || true
    )
    [ "${#leaked[@]}" -eq 0 ] && return 0
    rm -f -- "${leaked[@]}"
    printf 'removed %d artifact(s) that retained account identity\n' "${#leaked[@]}" >&2
    return 1
}
