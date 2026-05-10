# Deploy paper

Paper trading runs strategies through the daemon against the in-process `PaperBroker` — no network calls, no real money, deterministic fills. It's the right environment for:

- End-to-end smoke testing before going live
- Long soak tests on a strategy you've backtested
- Multi-strategy resource and behavior validation

## Prerequisites

- `qkt` installed and on `PATH` (see [Quickstart](quickstart.md) step 1)

## Run a single strategy

```bash
# Start the daemon. The control plane binds an ephemeral port on 127.0.0.1.
qkt daemon &

qkt deploy momentum.qkt --as momentum
qkt list
qkt status momentum
qkt logs momentum --follow
```

Stop one strategy without stopping the daemon:

```bash
qkt stop momentum
```

Stop the whole daemon:

```bash
qkt daemon stop
```

## Run many strategies at once

Drop `.qkt` files into a directory, start the daemon with `--load-dir`, every file auto-deploys:

```bash
qkt daemon --load-dir ./strategies &
```

This is the same convention the Docker compose stack uses (it mounts `./strategies` at `/strategies` inside the container).

## Inspect logs

Per-strategy log files live at `${QKT_STATE_DIR}/logs/<name>.log`. The default state dir is `~/.local/state/qkt/`.

The CLI streams them via the daemon's HTTP control plane:

```bash
qkt logs momentum --lines 200
qkt logs momentum --follow
qkt logs momentum --since 2026-05-10T10:00:00Z
```

Logs are tagged with `[strategy]` so when multiple strategies log to stdout you can tell them apart. See [logging](../operations/logging.md) for the MDC keys and pattern conventions.

## What you can't do on paper

The paper broker doesn't simulate:

- Spread or slippage
- Partial fills
- Network latency
- Broker-side reconnection
- Position drift across restarts

For any of those, deploy to MT5 against a demo account ([Deploy MT5](deploy-mt5.md)).

## Next

- [Deploy MT5](deploy-mt5.md) — the real broker path
- [Concepts: determinism](../concepts/determinism.md) — why backtest and live-paper produce identical trades on identical ticks
