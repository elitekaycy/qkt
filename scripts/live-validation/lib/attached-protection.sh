# Waits for a just-opened position to carry both its stop and its target.
#
# On an attach venue a relative target (BY, PCT or RR) is not sent with the entry; it is set by
# modifying the position once the fill price is known. The first read after the fill can therefore
# show the stop and no target yet. Callers keep their own contract check and run it after this, so a
# target that never arrives still fails that check.
#
# Usage: wait_for_attached_protection MAGIC FILE [TIMEOUT_SECONDS]
# Re-reads /get_positions?magic=MAGIC into FILE (via the caller's gateway_get) until the single
# position there has sl > 0 and tp > 0, for at most TIMEOUT_SECONDS (default 15). Returns 0 once both
# are set, 1 on timeout.
wait_for_attached_protection() {
    local magic="$1" file="$2" timeout="${3:-15}" attempt
    for attempt in $(seq 0 "$timeout"); do
        if jq -e '(.data | length) == 1 and .data[0].sl > 0 and .data[0].tp > 0' "$file" >/dev/null 2>&1; then
            return 0
        fi
        [ "$attempt" -lt "$timeout" ] || break
        sleep 1
        gateway_get "/get_positions?magic=$magic" > "$file"
    done
    return 1
}
