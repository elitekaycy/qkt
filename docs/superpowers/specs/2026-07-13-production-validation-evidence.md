# Production Validation Evidence

## Scope

This change makes four production-readiness claims reproducible without sending a
live order:

1. A daemon-deployed DSL strategy can complete signal, MT5 entry, attributed
   fill, ticketed close, and realized-PnL accounting against a deterministic MT5
   gateway simulation.
2. Stale market data rejects new exposure, permits a ticketed exit, and restores
   entry permission after a fresh tick.
3. A weighted portfolio initializes each child with `CAPITAL * WEIGHT` in both
   backtest and live-paper accounting.
4. Broker-fetched CSV bars can provide strict backtest coverage when every day
   required by the replay timeframe is present.

This does not claim that a QKT order has completed a real production MT5 round
trip. That requires venue evidence from an operator-authorized live order.

## Portfolio Replay

Portfolio backtests use the declared `CAPITAL` as the global book basis and pass
each child's allocation into `StrategyPnL`. A conflicting
`--starting-balance` is rejected. Portfolios with `WHEN..RUN` gates remain
unsupported because replay does not yet model the live portfolio scheduler.

Each replay symbol is fed at its finest declared timeframe. Candle aggregation
continues to build coarser child streams from that feed, matching standalone
backtest behavior.

## Historical Data

`qkt fetch` resolves broker profile names case-insensitively. A successful empty
broker response is persisted only when the configured trading calendar reports
the entire day closed. Empty responses during an open session remain uncovered
and are retried later.

The backtest provisioner accepts fetched CSV bars in strict mode only when every
calendar-day file in the requested range exists at the exact replay timeframe.
Partial bar coverage does not bypass tick completeness validation.

## Test Evidence

- `MT5DaemonE2ETest`: full simulated entry and ticketed close lifecycle.
- `MarketDataGateTest`: stale entry suppression, exit permission, recovery.
- `PortfolioBacktestLiveParityTest`: per-child trade and allocated-equity parity.
- `BacktestCommandPortfolioTest`: weighted and mixed-timeframe portfolio replay.
- `FetchCommandTest`: profile casing and closed/open empty-day behavior.

The MT5 lifecycle test adds bounded delays to its finite feed because a real live
feed remains open while asynchronous broker callbacks complete. Immediate feed
exhaustion would intentionally shut down the session and cancel in-flight I/O.

## Historical Validation Run

On 2026-07-13, MT5 bars were fetched through the configured bot1 gateway for
2026-04-05 through 2026-07-12. Every replay then ran with `--network none`,
`--no-fetch`, read-only strategy/data mounts, and the `mt5-sim` broker. No live
order route was reachable from a validation container.

- Snapshot: 1,464 files, 2,948,944 bytes.
- Snapshot manifest SHA-256:
  `756233b0b7cc4d1a37f49aa1dc6f8a9270b86fc73845a521c655874edb61cbb6`.
- Validation image config digest:
  `sha256:6a3f64ccd9aa31b23e92cdfff95bc7917e4d88d3cf32c6ca12f79205530dfe07`.
- All 12 commands exited successfully with empty backtest stderr and no evidence
  warnings. The fetch log is retained separately from backtest stderr.

| Strategy | Fills | Total PnL | Max drawdown |
| --- | ---: | ---: | ---: |
| `aud_breadth_fade` | 0 | 0 | 0 |
| `audnzd_decouple_fade` | 0 | 0 | 0 |
| `cable_sweep_fade` | 28 | 1.06000000 | 0.00300584 |
| `eurusd_asian_fade` | 40 | 16.97000000 | 0 |
| `eurusd_metals_veto_fade` | 12 | 4.04000000 | 0 |
| `eurusd_ny_fv_fade` | 54 | 20.53000000 | 0 |
| `eurusd_probe_fade` | 12 | 5.88000000 | 0.00174298 |
| `gold_ib_rotation` | 22 | 97.64900000 | 0 |
| `gold_release_fade` | 14 | 106.85600000 | 0 |
| `gold_silver_lead` | 10 | 15.96400000 | 0.02624238 |
| `ny_open_gap_fade` | 64 | 21.18000000 | 0 |
| `near_pass_research_book` | 190 | 227.43300000 | 0.00099941 |

The combined portfolio attributed fills to eight of ten children. The two AUD
children emitted no fills over this window and therefore remain historically
unvalidated; a successful zero-trade process exit is not strategy validation.

These are bar-synthesized `mt5-basic` results. They model bid/ask spread,
configured slippage, volume rules, commission, and swap, but not gateway latency,
venue rejection, partial fills, or a real production MT5 round trip. The results
are pipeline and historical-behavior evidence, not a profitability or production-
safety guarantee.
