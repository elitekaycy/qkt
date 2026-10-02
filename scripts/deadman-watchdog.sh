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
#   - the answer is not health JSON, or the daemon reports a status other than ok,
#   - a strategy is not running or is halted, or
#   - a running strategy's last-event age exceeds MAX_EVENT_AGE_SECS
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

# Page once per distinct state, and once on recovery. The state is recorded only after the page
# is delivered, so a page that fails is retried on the next run.
transition() {
    local new_state="$1" msg="$2"
    local old_state=""
    [ -f "$STATE_FILE" ] && old_state="$(cat "$STATE_FILE")"
    if [ "$new_state" != "$old_state" ]; then
        page "$msg" && echo "$new_state" > "$STATE_FILE"
    fi
}

body="$(curl -fsS -m 10 "$HEALTH_URL" 2>/dev/null)"
if [ -z "$body" ]; then
    transition "down" "qkt daemon is NOT answering ${HEALTH_URL} — host down, OOM, or docker failure. Open positions are protected only by venue-side stops."
    exit 1
fi

# Prints two lines: a stable state key (no ages, so a lasting problem pages once) and the message.
# Anything but well-formed health JSON is a failure, never "ok".
verdict="$(printf '%s' "$body" | python3 -c '
import json, sys
max_age_ms = int(sys.argv[1]) * 1000
try:
    h = json.load(sys.stdin)
    strategies = h["perStrategy"]
except Exception as e:
    print("unreadable")
    print("health answer is not qkt health JSON: " + type(e).__name__)
    sys.exit(0)
keys, notes = [], []
if h.get("status") != "ok":
    keys.append("status:" + str(h.get("status")))
    notes.append("daemon status " + str(h.get("status")) + " (pending deploys: " + str(len(h.get("pendingAutoDeploys") or [])) + ")")
for s in strategies:
    name = s.get("name")
    age = s.get("lastEventAgeMs")
    if not s.get("running"):
        keys.append("stopped:" + str(name))
        notes.append(str(name) + " is not running")
    elif s.get("halted"):
        keys.append("halted:" + str(name))
        notes.append(str(name) + " is halted (" + str(s.get("haltReason")) + ")")
    elif age is not None and age > max_age_ms:
        keys.append("stale:" + str(name))
        notes.append(str(name) + " silent " + str(age // 1000) + "s, queue " + str(s.get("inboundQueueDepth")))
print(",".join(sorted(keys)) or "ok")
print("; ".join(notes))
' "$MAX_AGE" 2>&1)"
state="$(printf '%s\n' "$verdict" | sed -n 1p)"
detail="$(printf '%s\n' "$verdict" | sed -n '2,$p')"

if [ "$state" != "ok" ]; then
    transition "${state:-unreadable}" "qkt needs attention at ${HEALTH_URL}: ${detail:-$verdict}"
    exit 1
fi

transition "ok" "qkt daemon recovered: ${HEALTH_URL} answering, all strategies running and emitting events."
exit 0
