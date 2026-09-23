#!/usr/bin/env bash
# Stop one child process for certain. Sourced by run scripts that background a daemon or a lane.

# stop_process PID GRACE_SECONDS LABEL
# Sends TERM (unless the caller already asked the process to stop some other way and it is gone),
# waits up to GRACE_SECONDS, then KILLs, and reaps it. A no-op for an empty or finished PID.
stop_process() {
    local pid="$1" grace="$2" label="$3"
    [ -n "$pid" ] || return 0
    if kill -0 "$pid" 2>/dev/null; then
        kill -TERM "$pid" 2>/dev/null || true
        local waited=0
        while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt "$grace" ]; do
            sleep 1
            waited=$((waited + 1))
        done
        if kill -0 "$pid" 2>/dev/null; then
            printf '%s: %s (pid %s) ignored TERM for %ss; killing it\n' "${0##*/}" "$label" "$pid" "$grace" >&2
            kill -KILL "$pid" 2>/dev/null || true
        fi
    fi
    wait "$pid" 2>/dev/null || true
}

# await_exit PID SECONDS: true once PID has exited, false if it is still running after SECONDS.
await_exit() {
    local pid="$1" seconds="$2" waited=0
    while kill -0 "$pid" 2>/dev/null; do
        [ "$waited" -lt "$seconds" ] || return 1
        sleep 1
        waited=$((waited + 1))
    done
    return 0
}
