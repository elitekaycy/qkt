#!/usr/bin/env bash
# External deadman watchdog for the qkt daemon (#397, FIA 7.1).
#
# Run this from a DIFFERENT machine than the trading host (a second box, a cheap
# VPS, or any always-on machine) via cron or a systemd timer, e.g. every minute:
#
#   * * * * * /opt/qkt/deadman-watchdog.sh
#
# It pages via Telegram when:
#   - the daemon's /health stops answering (host down, OOM, docker failure), or
#   - any strategy's last-event age exceeds MAX_EVENT_AGE_SECS while running
#     (wedged session: alive process, dead engine).
#
# Required environment (put them in the crontab line or an EnvironmentFile):
#   QKT_HEALTH_URL      e.g. http://trading-host:8200/health (through an SSH tunnel
#                       or private network — the control plane is loopback-only by
#                       design; do NOT expose it publicly)
#   TELEGRAM_BOT_TOKEN  bot token for alerts
#   TELEGRAM_CHAT_ID    chat to page
# Optional:
#   MAX_EVENT_AGE_SECS  default 900 (15 minutes)
#   ALERT_STATE_FILE    default /tmp/qkt-deadman.state (dedupe: one page per outage)
set -u

HEALTH_URL="${QKT_HEALTH_URL:?QKT_HEALTH_URL not set}"
MAX_AGE="${MAX_EVENT_AGE_SECS:-900}"
STATE_FILE="${ALERT_STATE_FILE:-/tmp/qkt-deadman.state}"

page() {
    local msg="$1"
    curl -fsS -m 10 "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
        -d chat_id="${TELEGRAM_CHAT_ID}" \
        --data-urlencode text="DEADMAN: ${msg}" >/dev/null 2>&1
}

# Page once per distinct failure, and once on recovery.
transition() {
    local new_state="$1" msg="$2"
    local old_state=""
    [ -f "$STATE_FILE" ] && old_state="$(cat "$STATE_FILE")"
    if [ "$new_state" != "$old_state" ]; then
        echo "$new_state" > "$STATE_FILE"
        page "$msg"
    fi
}

body="$(curl -fsS -m 10 "$HEALTH_URL" 2>/dev/null)"
if [ -z "$body" ]; then
    transition "down" "qkt daemon is NOT answering ${HEALTH_URL} — host down, OOM, or docker failure. Open positions are protected only by venue-side stops."
    exit 1
fi

stale="$(printf '%s' "$body" | python3 -c '
import json, sys
max_age_ms = int(sys.argv[1]) * 1000
h = json.load(sys.stdin)
out = []
for s in h.get("perStrategy", []):
    age = s.get("lastEventAgeMs")
    if s.get("running") and age is not None and age > max_age_ms:
        out.append(f"{s[\"name\"]} (last event {age//1000}s ago, queue {s.get(\"inboundQueueDepth\")})")
print("; ".join(out))
' "$MAX_AGE" 2>/dev/null)"

if [ -n "$stale" ]; then
    transition "stale:$stale" "qkt strategy wedged — running but silent past ${MAX_AGE}s: ${stale}"
    exit 1
fi

transition "ok" "qkt daemon recovered: ${HEALTH_URL} answering, all strategies emitting events."
exit 0
