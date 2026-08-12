# Monitoring

qkt exposes several observability surfaces. This page covers what's instrumented, where to scrape it, and what to alert on.

## What's instrumented

| Surface | Per-strategy port | Daemon control plane | File output |
| --- | --- | --- | --- |
| `/health` | yes | yes (`/daemon/health`) | — |
| `/status` (positions, trades, equity) | yes | aggregated (`/daemon/list`) | — |
| `/events` (recent events, JSON stream) | yes | — | — |
| `/logs` (tail of file logs) | yes | — | `${QKT_STATE_DIR}/logs/<name>.log` |
| `/stop` (control endpoint) | yes (POST) | — | — |

Each running strategy gets an ephemeral loopback port. The port is printed on
stdout at deploy time (`QKT_PORT=47291`) and visible in `qkt list`. In Docker,
run probes from the qkt container with `docker compose exec qkt`; the servers
are intentionally not published on the host.

## What to scrape

### Liveness — `/health`

```bash
curl -fsS http://localhost:47291/health
# {"ok": true, "uptime_ms": 12345}
```

Returns 200 OK when the strategy's tick loop is running. A failed curl = the process is dead or the port has shifted.

**Alert when:** the curl returns non-200 or times out for >60 seconds.

### Equity + P&L — `/status`

```bash
curl -fsS http://localhost:47291/status | jq
```

Returns a JSON snapshot with:

- `strategy`, `version`, `uptimeMs`
- `equity`, `balance`, `realized`, `unrealized`
- `positions[]` — open positions with symbol, qty, avgPrice
- `trades[]` — recent fills (bounded ring; default 50)
- `pendingStackLayers[]` — STACK layers waiting to trigger
- `riskState` — halt status, drawdown

**Alert when:**

- `equity` drops below a threshold (e.g. starting_balance × 0.95)
- `riskState.halted == true` (a halt fired and is sticky)
- `unrealized` is steeply negative with the position open longer than expected (a stop didn't fire)

### Event stream — `/events`

```bash
curl -N http://localhost:47291/events
```

Long-poll-style JSON-line stream of recent events: ticks, signals, orders, fills, rejections. Bounded ring; default 1000 entries.

Useful for:
- Real-time debugging dashboards
- Tying P&L changes back to specific trades
- Detecting unusual rejection patterns

**Not for:** continuous monitoring (use file logs instead — the event ring is best-effort)

### File logs

Per-strategy files at `${QKT_STATE_DIR:-~/.local/state/qkt}/logs/<strategy>.log`.

Standard SLF4J + logback. The MDC tags every line with `strategy=<name>` so a grep on the daemon-wide log file gives you per-strategy output:

```bash
grep '\[my-strategy\]' ~/.local/state/qkt/logs/*.log
```

For JSON-shipping (Logstash, Vector, Loki), override `logback.xml` with a JSON appender. The MDC `log.*` keys flow through automatically — see [Logging](logging.md).

## What to alert on

A reasonable starting set:

| Severity | Signal | Why |
| --- | --- | --- |
| **P1 — page now** | Daemon `/daemon/health` not 200 for >30s | The whole trading system is down |
| **P1** | Any strategy's `riskState.halted == true` | A risk rule fired (DD halt, daily-loss halt) — needs operator review |
| **P1** | Aggregate equity drop >5% in 1 hour | Either a fast drawdown or a market event worth a human eye |
| **P2 — investigate in hours** | Any strategy 0 trades in N hours when expected | Strategy might be silently broken (warmup, condition logic) |
| **P2** | Broker rejection rate >5% over 15 min | Venue-side problem; can it recover? |
| **P2** | MT5 gateway `/health/ready` not 200 | Broker connection lost or trading halted; positions still safe but no new trades |
| **P3 — review daily** | High slippage trades (live entry price differs from signal price by >X) | Tune stops or move to a tighter venue |

## Wiring it to Prometheus

qkt doesn't yet expose a native Prometheus scrape endpoint, but the JSON `/status` is trivially convertible. A small sidecar:

```yaml title="prometheus-sidecar.yml"
scrape_configs:
  - job_name: 'qkt'
    static_configs:
      - targets: ['localhost:47291', 'localhost:47292']
    metrics_path: '/status'
    params:
      format: ['prometheus']
```

The `format=prometheus` query parameter on `/status` is a planned feature (Phase 25 territory) — for now, use a shell script to fetch JSON and translate to Prom text-format. Example one-liner:

```bash
curl -s http://localhost:47291/status | jq -r '
  "qkt_equity{strategy=\"\(.strategy)\"} \(.equity)",
  "qkt_unrealized_pnl{strategy=\"\(.strategy)\"} \(.unrealized)",
  "qkt_trades_total{strategy=\"\(.strategy)\"} \(.trades | length)"
'
```

Pipe that into `node_exporter`'s textfile collector and Prometheus will pick it up.

## Daemon control plane

The control plane binds to loopback. Read-only endpoints remain unauthenticated for
local monitoring. Every `POST` mutation requires `Authorization: Bearer <token>`.
The daemon reads `QKT_CONTROL_TOKEN` when set; otherwise it creates
`<state-dir>/control.token` with owner-only permissions. Ordinary `qkt` mutation
commands load the same credential automatically.

The daemon also keeps bounded asynchronous evidence journals under
`<state-dir>/state/audit-journal/` and `<state-dir>/state/mt5-transport-journal/`.
The transport journal omits authentication headers but contains raw order and
venue data, so retain and transfer golden bundles as sensitive operational
artifacts. Engine audit records contain structured live ticks, warmup ticks,
completed candles, and fills for replay. Disk writes do not run on the engine
thread. Queue or write loss is recorded in day-scoped `.dropped` markers and
makes `qkt golden capture` fail.

Beyond per-strategy ports, the daemon itself exposes:

- `/daemon/health` — daemon process health
- `/daemon/list` — same as `qkt list` JSON
- `/daemon/strategies/<name>/status` — proxy to a child's `/status`
- `/daemon/strategies/<name>/stop` — proxy to a child's stop

The daemon binds these on a fixed control port (default in `~/.local/state/qkt/control.port`).

## See also

- [Logging](logging.md) — log routing, MDC keys, JSON output
- [Troubleshooting](troubleshooting.md) — what to do when an alert fires
- [Phase 12b — Observability](../phases/phase-12b-observability.md) — internals of the observability surface
