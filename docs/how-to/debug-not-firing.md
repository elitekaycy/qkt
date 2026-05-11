# Debug a strategy that isn't firing

Your strategy compiles, the daemon's running, ticks are flowing — but no trades. Six things that go wrong, in order of how often they happen.

## 1. The condition was never `false` first

Rules are **edge-triggered**, not level-triggered. `WHEN ema(fast) > ema(slow)` fires on the **transition** from false to true, not while the condition stays true.

If your condition is true on the very first candle, it never fires.

**Diagnose:** add a `LOG` to the rule to confirm:

```qkt
WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
THEN BUY btc SIZING 0.1
     LOG "edge transition fired" fast=ema(btc.close, 9) slow=ema(btc.close, 21)
```

If you see the LOG but no BUY, jump to step 2. If you see neither, the condition isn't transitioning.

**Fix:** if you genuinely want level-triggered, gate on something else. Most strategies want edge-triggered though — re-read why your rule isn't transitioning.

## 2. Risk engine rejected the order

Look in your logs for `RiskRejectedEvent`:

```bash
qkt logs my-strategy --since 1h | grep -i reject
```

If you see something like `reason=daily-loss-halt` or `reason=max-position`, that's the risk engine doing its job. Either:

- The rule is correct and you should respect it
- The rule's too tight for the strategy — tune `qkt.config.yaml` rules
- The halt is stuck — restart the daemon to clear stateful halts (a `qkt resume` CLI is on the roadmap; see [Planned features](../planned.md))

See [Phase 9 — Risk engine](../phases/phase-9-risk-engine.md) for the full halt protocol.

## 3. Broker rejected the order

The risk engine passed, but the broker said no. Look for `OrderRejected`:

```bash
qkt logs my-strategy --since 1h | grep -i 'rejected'
```

Common broker rejection reasons:

| Reason | Cause | Fix |
| --- | --- | --- |
| `Symbol not found` | Symbol naming differs per broker (Exness adds `m` suffix) | Use the right `qkt.config.yaml` profile or check `symbolPolicy.aliases` |
| `Insufficient margin` | Position size too large for account | Reduce `SIZING` or fund the account |
| `Stop too close` | Stop distance < `tradeStopsLevel` | Widen the stop |
| `Market closed` | Trying to trade outside session | Use a `WHEN session.is_open` guard |
| `Invalid volume` | Size below `volumeMin` or not a multiple of `volumeStep` | Round to the broker's lot size |

## 4. Indicators not warm yet

Indicators need N bars to produce values. Before then, `ema(btc.close, 50)` returns `null`, and any comparison with null is `false` — the rule never fires.

**Diagnose:** check the warmup status:

```bash
qkt status my-strategy
```

Look for `warmup.complete: true`. If it's still false after many ticks, your `WarmupSpec` isn't being honored.

**Fix:** the DSL compiler infers warmup from your indicator periods automatically — a strategy using `EMA(close, 200)` won't fire signals until 200 bars have arrived. If the strategy is starting fresh against a short tick stream, give it more historical data, or back-test on a date range that gives enough lead-in.

(An explicit `WARMUP N BARS` declaration is on the roadmap for Phase 24 — see [Planned features](../planned.md). For now, the implicit warmup from indicator periods is usually enough.)

## 5. The wrong candle window

Easy mistake: you declare `EVERY 1m` but you're feeding the strategy historical data with only daily candles in `~/.qkt/data/`. The aggregator can't produce 1m candles from daily data — it produces nothing, and your rule never sees a candle close.

**Diagnose:**

```bash
qkt status my-strategy --verbose
```

Look at the candle source. If `candles_received: 0` despite the strategy running for minutes, the data feed doesn't match the timeframe.

**Fix:** either populate the data store with tick-level data (via `./scripts/fetch-dukascopy.sh` today; a `qkt fetch` CLI is on the roadmap — see [Planned features](../planned.md))…

…or adjust the strategy's timeframe to match what you have:

```qkt
btc = BACKTEST:BTCUSDT EVERY 1d
```

## 6. Wrong broker prefix

`BACKTEST:BTCUSDT` works for backtests but you forgot to change it before deploying live. The daemon then routes orders to the paper broker even though you wanted Exness.

**Diagnose:** check `qkt list`:

```text
NAME              KIND       PORT     TRADES   STATE
my-strategy       strategy   47291    0        running
```

If `TRADES` stays at 0 while the underlying market moves, check the SYMBOLS block.

**Fix:** swap the prefix:

```qkt
SYMBOLS
    eur = EXNESS:EURUSD EVERY 5m   # was BACKTEST:EURUSD
```

…and make sure the `EXNESS` profile exists in `qkt.config.yaml`:

```bash
qkt brokers list
```

## Last resort: turn up the log level

If none of the above explains it, enable DEBUG and watch every event:

```bash
qkt deploy my-strategy.qkt --log-level DEBUG
qkt logs my-strategy -f
```

You'll see every tick, every condition evaluation, every order decision. Grep for your strategy name. The reason will be in there.

## See also

- [Logging](../operations/logging.md) — MDC keys, log levels, structured fields
- [Phase 15 — DSL LOG](../phases/phase-15.md) — adding `LOG` to your strategy for inline debugging
- [Phase 19 — Pre-live confidence pack](../phases/phase-19.md) — the audit-ticks CLI for verifying live feed accuracy
