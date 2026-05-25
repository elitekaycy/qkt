# Troubleshooting

Symptom → cause → fix. Use this page when something's wrong and you need to figure out which doc to read next.

## First, run the health check

```bash
qkt status --deep
```

Single-screen summary of daemon, control plane, every deployed strategy. Exits
`0` when everything is healthy; `1` with a list of issues when anything is
wrong. The output below is the fastest way to know which section of this page
to read.

```text
qkt: HEALTHY

qkt 0.28.9 (424d5d9) built 2026-05-25T21:08:37Z

DAEMON       running (uptime 12h5m)
CONTROL      reachable
STRATEGIES
  alpha                running, 14 trades, up 12h5m
  beta                 running, 3 trades, up 12h5m
```

When unhealthy, the issues land on stderr — pipe to `tee` or your alert
channel:

```bash
qkt status --deep || echo "qkt is unhealthy — check logs"
```

## The flowchart

```
┌────────────────────────────────────────────────────────────────────┐
│ Is the daemon running?                                             │
│   qkt list  → should print a table                                 │
│   No?  →  see "Daemon won't start"                                 │
└────────────────────────────────────────────────────────────────────┘
                              ↓ yes
┌────────────────────────────────────────────────────────────────────┐
│ Is the strategy in the table?                                      │
│   No?  →  see "qkt deploy fails"                                   │
└────────────────────────────────────────────────────────────────────┘
                              ↓ yes
┌────────────────────────────────────────────────────────────────────┐
│ Is the state column "running" / "active"?                          │
│   No?  →  see "Strategy stuck in a non-running state"              │
└────────────────────────────────────────────────────────────────────┘
                              ↓ yes
┌────────────────────────────────────────────────────────────────────┐
│ Are trades happening?                                              │
│   No?  →  see "No trades firing"                                   │
└────────────────────────────────────────────────────────────────────┘
                              ↓ yes
┌────────────────────────────────────────────────────────────────────┐
│ Trades happening, results unexpected?                              │
│   →  see "Trades not as expected"                                  │
└────────────────────────────────────────────────────────────────────┘
```

## Daemon won't start

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| `qkt: command not found` | PATH not set | `export PATH="$PWD/build/install/qkt/bin:$PATH"` |
| `Address already in use` on startup | Previous daemon didn't shut down cleanly | `qkt daemon stop` then restart, or check for orphaned `qkt` processes |
| `Could not find qkt.config.yaml` | Working directory wrong | `cd` to the directory containing the config or pass `--config /path/to/file` |
| `MT5 gateway connection refused` (live) | Gateway container not healthy | `docker compose ps`; if gateway is `unhealthy`, VNC at `:3000` and log in |
| `Failed to bind port 47000-47100` | Port range collision | Restart daemon with a different range; or kill the process holding the port |

## `qkt deploy` fails

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| `Parse error at line N:M` | DSL syntax bug | Run `qkt parse <file>` for the full diagnostic; check [DSL reference](../reference/dsl/index.md) |
| `unknown broker prefix: FOO` | The prefix isn't a built-in and isn't in your `qkt.config.yaml` | Add a `brokers:` entry or use a built-in (BACKTEST, BYBIT_SPOT, EXNESS, etc.) |
| `Unknown indicator: ADX` | The indicator isn't registered with the DSL | Check the [indicator catalog](../reference/dsl/indicators.md); `ADX` is on the backlog |
| `Daemon not running` | You forgot to start the daemon | `qkt daemon &` or `docker compose up -d` |
| `Strategy with name X already exists` | Already deployed under that name | `qkt stop X` first, or pass `--as <newname>` |

## Strategy stuck in a non-running state

| State | Means | Fix |
| --- | --- | --- |
| `inactive` (portfolio child) | Regime gate is false | Either change market regime or `qkt start <parent>/<child>` to override |
| `halted` | Risk engine triggered a halt | Restart the daemon to clear stateful halts (`qkt resume` CLI lands in Phase 25); investigate why first |
| `stopped` | Operator stopped it | `qkt deploy ...` to redeploy or `qkt start <name>` for a portfolio child |
| `crashed` | Unhandled exception | Check `qkt logs <name>` for the stack trace; common causes: broker connectivity, data feed dropout |

## No trades firing

This is the most common complaint. Six things, in order of how often they happen — see [the full debug recipe](../how-to/debug-not-firing.md) for the detail.

| In order of frequency | Quick check |
| --- | --- |
| 1. **Condition never transitioned `false → true`** | Add a `LOG` to the rule body to confirm the WHEN actually evaluates true |
| 2. **Risk engine rejected the order** | `qkt logs <name> | grep -i reject` |
| 3. **Broker rejected the order** | Same grep; look for venue rejection reasons (symbol not found, insufficient margin, stop too close) |
| 4. **Indicators not warm yet** | `qkt status <name>` and look for warmup completion |
| 5. **Wrong candle window** | Stream declared `EVERY 1m` but data is daily-only |
| 6. **Wrong broker prefix** | `BACKTEST:` in production routes to paper, not your real broker |

## Trades not as expected

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Trade fired but at the wrong price | Slippage on live; ignore on backtest (no slippage model) | Add a slippage buffer to your stops, or migrate to a venue with tighter spreads |
| Strategy double-enters | Missing `POSITION.<stream> = 0` entry guard | Add the guard to your WHEN clause |
| Strategy never re-enters after exit | The condition is now true continuously (not edge-triggered) | Most often: `CROSSES ABOVE` already happened; wait for the next cross. Edge-triggers are correct. |
| Bracket stop fires immediately | Stop too close to entry given current volatility | Widen the stop or use ATR-scaled stops |
| Position sizes look wrong on MT5 | Broker minimum lot / lot step violated | Check `qkt brokers list`; MT5 brokers have `volumeMin` and `volumeStep` |
| P&L numbers seem off | Confusion between realized and unrealized, or strategy-level vs symbol-level | Use `qkt status <name>` for the breakdown |

## Live-broker-specific issues

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| MT5 connection drops daily | Some brokers force re-auth | VNC back in and log in again; or build a watchdog |
| Bybit `retCode=10001` | Invalid API permissions | Re-issue keys with Read + Trade enabled |
| Bybit rate-limit errors | Too many orders/second | The `BybitTransport` retries with backoff; if persistent, reduce strategy frequency |
| Symbol rejected on MT5 (`symbol not found`) | Broker uses a different symbol name | Check `symbolPolicy` in your broker profile — Exness adds `m` suffix |
| Position drift between qkt and broker | Manual trade on the venue, or magic-number collision | qkt's `MT5StateRecovery` reconciles on next daemon start; or restart |

## Data / data store issues

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| `Symbol BTCUSDT not found` in backtest | Data store empty for that symbol | Populate via `./scripts/fetch-dukascopy.sh BTCUSDT <from> <to>` (or use `data/sample/` with `--data-root data/sample`) |
| Backtest finishes instantly with 0 trades | Date range outside what's in the store | Check data coverage with `ls ~/.qkt/data/symbols/<SYMBOL>/` |
| Manifest corrupted error | Partial write killed mid-fetch | `./gradlew rebuildManifest` or delete `~/.qkt/data/symbols/<SYMBOL>/manifest.json` and refetch |
| Different P&L between two runs of the same backtest | This shouldn't happen (parity contract). | If it does, file an issue — that's a regression of the parity test |

## Performance issues

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Backtest is slow | High-resolution data + long range | Use `EVERY 5m` or `EVERY 15m` for sweeps; reduce date range |
| Daemon high CPU when idle | Polling loops too aggressive | Check broker profile `pollIntervalMs` — default is 1000 |
| Memory growth over time | Position tracker accumulating closed positions | Restart daemon nightly; long-running daemons aren't yet stress-tested |
| JVM startup ~3s | This is normal | Daemon process amortizes startup over its uptime |

## Where to get more help

- [Recipes: Debug a strategy that isn't firing](../how-to/debug-not-firing.md) — deeper version of the "no trades" section above
- [Logging](logging.md) — MDC keys, log levels, structured fields
- [GitHub issues](https://github.com/elitekaycy/qkt/issues) — file new issues with logs + strategy file + repro steps
