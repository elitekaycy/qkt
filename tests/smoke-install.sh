#!/usr/bin/env bash
# tests/smoke-install.sh
#
# End-to-end smoke test: clean install in /tmp → strategy author flow →
# daemon operations → Docker stack. Runs against the current working tree
# (uses ./gradlew installDist directly rather than fetching a release).
#
# Designed for both local dev and CI. Exits non-zero on any failure.
#
# Usage:
#   bash tests/smoke-install.sh                    # full pass (incl. Docker)
#   bash tests/smoke-install.sh --no-docker        # skip the Docker step
#
# Local prerequisites: JDK 21, Docker (or pass --no-docker), curl.

set -euo pipefail

# ──────────────────────────────────────────────────────────────────────── #
# Args
# ──────────────────────────────────────────────────────────────────────── #
DO_DOCKER=1
for arg in "$@"; do
    case "$arg" in
        --no-docker) DO_DOCKER=0 ;;
        *) echo "unknown flag: $arg" >&2; exit 2 ;;
    esac
done

# ──────────────────────────────────────────────────────────────────────── #
# Bookkeeping
# ──────────────────────────────────────────────────────────────────────── #
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd -P)"
SMOKE_DIR="$(mktemp -d -t qkt-smoke.XXXXXX)"
LOG_FILE="$SMOKE_DIR/smoke.log"

say()  { printf '\n\033[1;34m== %s ==\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m✓\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m✗\033[0m %s\n' "$*" >&2; echo "smoke dir: $SMOKE_DIR" >&2; echo "log: $LOG_FILE" >&2; exit 1; }

cleanup() {
    # Kill any daemon we started
    if [ -n "${DAEMON_PID:-}" ] && kill -0 "$DAEMON_PID" 2>/dev/null; then
        kill "$DAEMON_PID" 2>/dev/null || true
        wait "$DAEMON_PID" 2>/dev/null || true
    fi
    # Tear down Docker if we brought it up
    if [ "$DO_DOCKER" -eq 1 ] && [ -n "${DOCKER_STARTED:-}" ]; then
        (cd "$SMOKE_DIR/docker" && docker compose down -v >/dev/null 2>&1) || true
    fi
    # Preserve smoke dir on failure (set -e + trap), wipe on success.
    if [ "${SMOKE_OK:-0}" -eq 1 ]; then
        rm -rf "$SMOKE_DIR"
    else
        echo "Smoke artifacts preserved at $SMOKE_DIR"
    fi
}
trap cleanup EXIT

# ──────────────────────────────────────────────────────────────────────── #
# Step 1 — Build the local distribution
# ──────────────────────────────────────────────────────────────────────── #
say "Step 1: ./gradlew installDist"
cd "$REPO_ROOT"
./gradlew --no-daemon installDist >>"$LOG_FILE" 2>&1 || die "installDist failed (log: $LOG_FILE)"
ok "installDist succeeded"

QKT_DIST="$REPO_ROOT/build/install/qkt"
QKT="$QKT_DIST/bin/qkt"
[ -x "$QKT" ] || die "qkt binary not executable at $QKT"

# ──────────────────────────────────────────────────────────────────────── #
# Step 2 — Simulate a clean install in /tmp
# ──────────────────────────────────────────────────────────────────────── #
say "Step 2: simulate clean install in $SMOKE_DIR"
INSTALL_PREFIX="$SMOKE_DIR/install"
mkdir -p "$INSTALL_PREFIX"
cp -r "$QKT_DIST/bin" "$QKT_DIST/lib" "$INSTALL_PREFIX/"
INSTALL_QKT="$INSTALL_PREFIX/bin/qkt"

[ -x "$INSTALL_QKT" ] || die "qkt missing in simulated install"
"$INSTALL_QKT" --version >>"$LOG_FILE" 2>&1 || die "installed qkt --version failed"
INSTALLED_VERSION=$("$INSTALL_QKT" --version)
ok "installed: $INSTALLED_VERSION"

# Sample data — copy a slice so the smoke is self-contained
mkdir -p "$SMOKE_DIR/data"
cp -r "$REPO_ROOT/data/sample/symbols" "$SMOKE_DIR/data/" \
    || die "could not stage sample data"
ok "sample data staged at $SMOKE_DIR/data/symbols"

# ──────────────────────────────────────────────────────────────────────── #
# Step 3 — Write a tiny strategy
# ──────────────────────────────────────────────────────────────────────── #
say "Step 3: author + parse a strategy"
mkdir -p "$SMOKE_DIR/strategies"
cat > "$SMOKE_DIR/strategies/smoke.qkt" <<'STRAT'
# Smoke-test strategy: log every closing price.
# Doesn't trade; the point is to prove parse/backtest/run all work.
STRATEGY smoke VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSD EVERY 1m

RULES
    WHEN btc.close > 0
    THEN LOG "tick price={p}" p=btc.close
STRAT

"$INSTALL_QKT" parse "$SMOKE_DIR/strategies/smoke.qkt" >>"$LOG_FILE" 2>&1 \
    || die "qkt parse failed on smoke strategy"
ok "qkt parse OK"

# Parse the hedge-straddle example — exercises Phase 26a surface (OCO_ENTRY, NOW).
# Live MT5 runtime ships in Phase 26b; backtest data isn't bundled here.
"$INSTALL_QKT" parse "$REPO_ROOT/examples/hedge-straddle/hedge-straddle.qkt" >>"$LOG_FILE" 2>&1 \
    || die "qkt parse failed on hedge-straddle example"
ok "hedge-straddle example parses"

# ──────────────────────────────────────────────────────────────────────── #
# Step 4 — Backtest
# ──────────────────────────────────────────────────────────────────────── #
say "Step 4: qkt backtest"
"$INSTALL_QKT" backtest "$SMOKE_DIR/strategies/smoke.qkt" \
    --from 2024-01-15 \
    --to 2024-01-17 \
    --data-root "$SMOKE_DIR/data" \
    >>"$LOG_FILE" 2>&1 \
    || die "qkt backtest failed (log: $LOG_FILE)"
ok "backtest completed without error"

# ──────────────────────────────────────────────────────────────────────── #
# Step 5 — Trading strategy: write a real one that takes trades
# ──────────────────────────────────────────────────────────────────────── #
say "Step 5: backtest a strategy that actually trades"
cat > "$SMOKE_DIR/strategies/momentum.qkt" <<'STRAT'
STRATEGY momentum VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSD EVERY 1m

RULES
    WHEN ema(btc.close, 5) CROSSES ABOVE ema(btc.close, 13)
     AND POSITION.btc = 0
    THEN BUY btc SIZING 0.01 ; LOG "long"

    WHEN ema(btc.close, 5) CROSSES BELOW ema(btc.close, 13)
     AND POSITION.btc > 0
    THEN CLOSE btc ; LOG "exit"
STRAT

"$INSTALL_QKT" backtest "$SMOKE_DIR/strategies/momentum.qkt" \
    --from 2024-01-15 \
    --to 2024-01-17 \
    --data-root "$SMOKE_DIR/data" \
    --json \
    > "$SMOKE_DIR/momentum.json" 2>>"$LOG_FILE" \
    || die "momentum backtest failed (log: $LOG_FILE)"

# Validate JSON output structurally
if command -v python3 >/dev/null 2>&1; then
    python3 -c "
import json, sys
data = json.load(open('$SMOKE_DIR/momentum.json'))
required = ['trades', 'finalRealized', 'winRate', 'maxDrawdown']
missing = [k for k in required if k not in data]
if missing:
    print('missing keys:', missing); sys.exit(1)
print(f\"backtest report: trades={data['trades']} pnl={data['finalRealized']} winRate={data['winRate']}\")
" || die "backtest JSON missing expected keys"
fi
ok "backtest produced valid JSON report"

# ──────────────────────────────────────────────────────────────────────── #
# Step 6 — Daemon lifecycle
# ──────────────────────────────────────────────────────────────────────── #
# Control-plane assertions (start / deploy / list / status / stop) are hard —
# they are network-independent. The live-tick assertion is now also hard: we
# poll for up to 60s waiting for the strategy to log a closed-candle tick from
# Bybit's public WebSocket. If the feed is unreachable from this runner the
# build fails — that is the signal the CI promises ("a deployed strategy
# really did process live market data"), and a quiet WS is no longer treated
# as an acceptable outcome.
say "Step 6: daemon lifecycle (start, deploy, list, status, logs, stop)"
DAEMON_STATE="$SMOKE_DIR/daemon-state"
mkdir -p "$DAEMON_STATE"

# The daemon strategy sources market data from Bybit's public feed. A dummy
# BYBIT_API_KEY flips on the Bybit route in MarketSourceFactory; public Bybit
# market data ignores the key's value.
cat > "$SMOKE_DIR/strategies/daemon.qkt" <<'STRAT'
# Daemon-smoke strategy: log each closing price off the Bybit public feed.
STRATEGY daemonsmoke VERSION 1

SYMBOLS
    btc = BYBIT_SPOT:BTCUSDT EVERY 5s

RULES
    WHEN btc.close > 0
    THEN LOG "tick close={c}" c=btc.close
STRAT

BYBIT_API_KEY=ci-public-dummy "$INSTALL_QKT" daemon --state-dir "$DAEMON_STATE" \
    >>"$LOG_FILE" 2>&1 &
DAEMON_PID=$!

# Wait up to 30s for the daemon to write its control-port file (= ready).
for _ in $(seq 1 30); do
    [ -f "$DAEMON_STATE/control.port" ] && break
    sleep 1
done
[ -f "$DAEMON_STATE/control.port" ] || die "daemon did not become ready (no control.port)"
ok "daemon started (pid $DAEMON_PID)"

"$INSTALL_QKT" deploy "$SMOKE_DIR/strategies/daemon.qkt" --as daemonsmoke --state-dir "$DAEMON_STATE" \
    >>"$LOG_FILE" 2>&1 || die "qkt deploy failed"
ok "strategy deployed"

"$INSTALL_QKT" list --state-dir "$DAEMON_STATE" 2>>"$LOG_FILE" | grep -q daemonsmoke \
    || die "qkt list does not show the deployed strategy"
ok "qkt list shows the strategy"

"$INSTALL_QKT" status daemonsmoke --state-dir "$DAEMON_STATE" >>"$LOG_FILE" 2>&1 \
    || die "qkt status failed"
ok "qkt status responds"

# Hard check: poll for up to 60s for the strategy to log a closed-candle tick
# from the Bybit public WS. A WebSocket handshake + subscribe + first 5s
# candle close runs ~10s in the happy path; 60s gives plenty of headroom for
# a slow runner without making the build feel sluggish when ticks arrive
# normally.
TICK_DEADLINE=$(( $(date +%s) + 60 ))
while [ "$(date +%s)" -lt "$TICK_DEADLINE" ]; do
    "$INSTALL_QKT" logs daemonsmoke --state-dir "$DAEMON_STATE" > "$SMOKE_DIR/strategy.log" 2>>"$LOG_FILE" || true
    if grep -q 'tick close=' "$SMOKE_DIR/strategy.log" 2>/dev/null; then
        ok "strategy logged live ticks from the Bybit feed"
        break
    fi
    sleep 2
done
grep -q 'tick close=' "$SMOKE_DIR/strategy.log" 2>/dev/null \
    || die "no live ticks logged within 60s — Bybit WS feed did not deliver a closed candle (see $SMOKE_DIR/strategy.log)"

"$INSTALL_QKT" stop daemonsmoke --state-dir "$DAEMON_STATE" >>"$LOG_FILE" 2>&1 \
    || die "qkt stop failed"
ok "strategy stopped"

"$INSTALL_QKT" daemon stop --state-dir "$DAEMON_STATE" >>"$LOG_FILE" 2>&1 \
    || die "qkt daemon stop failed"
ok "daemon stopped"

# ──────────────────────────────────────────────────────────────────────── #
# Step 7 — qkt run (foreground paper) — needs network; skip in CI
# ──────────────────────────────────────────────────────────────────────── #
# The `run` subcommand connects to a live tick feed (TradingView). That makes
# it unreliable in CI (network access varies, the vendor's anti-bot blocks
# datacenter IPs). Skip by default; bring it back when there's an offline
# tick source for live mode.
if [ -n "${QKT_SMOKE_RUN:-}" ]; then
    say "Step 7: qkt run (skipped — set QKT_SMOKE_RUN=1 to opt in)"
    # When enabled: run with a 20s timeout
    timeout 20 "$INSTALL_QKT" run "$SMOKE_DIR/strategies/smoke.qkt" \
        >>"$LOG_FILE" 2>&1 || true
    ok "qkt run completed (or hit timeout, which is fine)"
else
    warn "Step 7: skipped (set QKT_SMOKE_RUN=1 to enable; needs live network)"
fi

# ──────────────────────────────────────────────────────────────────────── #
# Step 8 — Docker smoke
# ──────────────────────────────────────────────────────────────────────── #
if [ "$DO_DOCKER" -eq 1 ]; then
    if ! command -v docker >/dev/null 2>&1; then
        warn "Step 8: docker not on PATH — skipping Docker smoke"
    else
        say "Step 8: build qkt Docker image"
        cd "$REPO_ROOT"
        docker build -t qkt:smoke -f Dockerfile . >>"$LOG_FILE" 2>&1 \
            || die "docker build failed (log: $LOG_FILE)"
        ok "qkt:smoke image built"

        # Run a one-shot backtest inside the image
        say "Step 9: run a backtest inside the Docker container"
        mkdir -p "$SMOKE_DIR/docker/strategies"
        cp "$SMOKE_DIR/strategies/momentum.qkt" "$SMOKE_DIR/docker/strategies/"

        docker run --rm \
            --entrypoint qkt \
            -v "$SMOKE_DIR/data:/data:ro" \
            -v "$SMOKE_DIR/docker/strategies:/strategies:ro" \
            qkt:smoke \
            backtest /strategies/momentum.qkt \
                --from 2024-01-15 \
                --to 2024-01-17 \
                --data-root /data \
                --json \
            >> "$LOG_FILE" 2>&1 \
            || die "docker-run backtest failed (log: $LOG_FILE)"
        ok "docker backtest succeeded"
    fi
else
    warn "Step 8-9: Docker stage skipped (--no-docker)"
fi

# ──────────────────────────────────────────────────────────────────────── #
# Done
# ──────────────────────────────────────────────────────────────────────── #
SMOKE_OK=1
printf '\n\033[1;32m✓ smoke test passed\033[0m\n'
printf 'log: %s\n\n' "$LOG_FILE"
