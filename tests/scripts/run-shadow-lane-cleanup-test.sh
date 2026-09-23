#!/usr/bin/env bash
# A shadow lane must never outlive its window or its parent: its daemon is stopped by PID even
# when `daemon stop` reaches nothing (a reused state dir whose control.port names another daemon),
# and a TERM from the parent suite stops lane and daemon promptly instead of after the window.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
lane="$repo_root/scripts/live-validation/run-shadow-lane.sh"
# shellcheck source=scripts/live-validation/lib/process-stop.sh
source "$repo_root/scripts/live-validation/lib/process-stop.sh"
tmp="$(mktemp -d)"
trap 'pkill -f "$tmp/" 2>/dev/null || true; rm -rf "$tmp"' EXIT

# A daemon that announces readiness then runs until killed; `daemon stop` does nothing.
mkdir -p "$tmp/cli/bin" "$tmp/cases/demo"
cat > "$tmp/cli/bin/qkt" <<'CLI'
#!/usr/bin/env bash
case "$1 ${2:-}" in
    "daemon start") echo "$$" > "$FAKE_DAEMON_PIDFILE"; echo "daemon ready"; exec sleep 100000 ;;
    "daemon stop") exit 0 ;;
    "--version ") echo "qkt fake"; exit 0 ;;
    *) exit 1 ;;
esac
CLI
chmod +x "$tmp/cli/bin/qkt"
printf 'status: ready\n' > "$tmp/cases/demo/case.yaml"
printf 'STRATEGY demo VERSION 1\n' > "$tmp/cases/demo/strategy.qkt"

start_lane() {  # name duration
    FAKE_DAEMON_PIDFILE="$tmp/$1.daemon.pid" QKT_BROKER_API_KEY=x QKT_SHADOW_STOP_GRACE_SECONDS=2 \
        bash "$lane" --out "$tmp/$1" --gateway-url http://127.0.0.1:9 --expected-login 1 \
        --expected-server fake --magic 1 --cases "$tmp/cases" --cli "$tmp/cli/bin/qkt" \
        --duration-seconds "$2" > "$tmp/$1.log" 2>&1 &
    lane_pid=$!
    for _ in $(seq 1 30); do [ -s "$tmp/$1.daemon.pid" ] && break; sleep 1; done
    daemon_pid="$(cat "$tmp/$1.daemon.pid")"
    kill -0 "$daemon_pid"
}

start_lane term 540
kill -TERM "$lane_pid"
await_exit "$lane_pid" 15 || { echo "FAIL lane still running 15s after TERM"; exit 1; }
await_exit "$daemon_pid" 1 || { echo "FAIL daemon $daemon_pid survived its lane"; exit 1; }
echo "ok a TERMed lane stops its daemon by PID even when daemon stop reaches nothing"

start_lane window 60
started=$SECONDS
await_exit "$lane_pid" 90 || { echo "FAIL lane outlived its 60s window by 30s"; exit 1; }
await_exit "$daemon_pid" 1 || { echo "FAIL daemon $daemon_pid outlived the lane window"; exit 1; }
elapsed=$((SECONDS - started))
[ "$elapsed" -le 80 ] || { echo "FAIL lane took ${elapsed}s for a 60s window"; exit 1; }
echo "ok a lane ends its daemon when the window closes, even when daemon stop reaches nothing"

sh -c 'trap "" TERM; exec sleep 100000' & stubborn=$!
sleep 1
stop_process "$stubborn" 1 "stubborn" 2>/dev/null
await_exit "$stubborn" 1 || { echo "FAIL stop_process left a TERM-ignoring process running"; exit 1; }
echo "ok stop_process kills a process that ignores TERM"
