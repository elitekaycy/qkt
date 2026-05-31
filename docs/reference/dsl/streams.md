# Streams (SYMBOLS)

A **stream** is a single instrument on a single venue at a single timeframe. The `SYMBOLS` block declares every stream a strategy listens to. Each stream gets an **alias** — a short name you use throughout the rest of the file.

## Shape

```qkt
SYMBOLS
    <alias> = <BROKER>:<symbol> EVERY <timeframe>
```

Three parts after `=`:

1. **Broker prefix** — uppercase, ASCII letters + underscores
2. **Symbol** — uppercase, the venue's name for the instrument
3. **`EVERY` + timeframe** — candle window

The `<alias>` is what your strategy code uses. Pick something short and meaningful.

## Single-stream example

```qkt
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m

RULES
    WHEN btc.close > 50000
    THEN LOG "above 50k"
```

`btc` becomes a first-class reference for the rest of the file. `btc.close`, `btc.high`, `btc.volume`, etc. all work.

## Multi-stream example

```qkt
SYMBOLS
    btc  = BACKTEST:BTCUSDT EVERY 1m
    eur  = BACKTEST:EURUSD  EVERY 15m
    gold = BACKTEST:XAUUSD  EVERY 1h
```

Three streams, three different timeframes. Rules can reference any of them, including in the same condition:

```qkt
RULES
    WHEN btc.close / btc.close[20] > 1.05    -- BTC up 5% in 20 minutes
     AND gold.close < sma(gold.close, 20)     -- gold below 1h average
    THEN BUY btc SIZING 0.1                  -- buy BTC
```

Strategies that combine signals across instruments are common — momentum on one asset gated by regime on another.

## Broker prefixes

The broker prefix tells the engine which venue this stream lives on. Built-in prefixes:

| Prefix | What it means | When to use |
| --- | --- | --- |
| `BACKTEST` | The historical data store (`~/.qkt/data/`) | Backtesting; `qkt backtest`, `qkt run` in paper mode |
| `BYBIT_SPOT` | Bybit Spot via REST + WebSocket | Live trading spot crypto |
| `BYBIT_LINEAR` | Bybit USDT-denominated perpetuals | Live trading futures |
| `EXNESS`, `ICMARKETS`, `FTMO`, `PEPPERSTONE` | MT5 brokers via `mt5-gateway` | Live trading FX, indices, commodities |

Plus any custom profile you define in `qkt.config.yaml`:

```yaml title="qkt.config.yaml"
brokers:
  myalpaca:
    type: alpaca           # (when supported)
    api_key: ${ALPACA_KEY}
```

You'd then write `MYALPACA:SPY` in your strategy.

Run `qkt brokers list` to see what's configured in the current environment.

## Symbol names

The symbol is whatever the venue calls the instrument. **Different brokers may use different names for the same underlying.**

| Underlying | BACKTEST | BYBIT | EXNESS (MT5) |
| --- | --- | --- | --- |
| Bitcoin/USD | `BTCUSDT` | `BTCUSDT` | `BTCUSDm` (suffix `m`) |
| Ether/USD | `ETHUSDT` | `ETHUSDT` | `ETHUSDm` |
| EUR/USD | `EURUSD` | n/a | `EURUSDm` |
| Gold | `XAUUSD` | n/a | `XAUUSDm` |
| S&P 500 | `SPX500` | n/a | `US500m` |

The DSL uses the **qkt-side** name. The broker integration layer (Phase 17, Phase 7) translates to the venue's actual symbol via the broker profile's `symbolPolicy` (suffix, alias map). Exness adds `m` automatically; ICMarkets/FTMO/Pepperstone don't.

If a venue rejects a symbol you wrote, check the actual venue name vs the qkt-side name. The [config schema](../config-schema.md) covers `symbolPolicy` overrides.

## Timeframes (`EVERY <window>`)

How often a candle closes for this stream. The candle aggregator collects ticks into OHLC bars; rules fire on candle close.

Supported windows:

| Token | Means |
| --- | --- |
| `1s`, `5s`, `15s`, `30s` | Sub-minute (mostly for HF testing) |
| `1m`, `5m`, `15m`, `30m` | Intraday |
| `1h`, `2h`, `4h`, `6h`, `12h` | Hourly |
| `1d` | Daily |
| `1w` | Weekly |

The parser is liberal — `EVERY 7m` and `EVERY 3h` work fine, even though they're non-standard. But your data fetcher may not have data at non-standard resolutions; check.

## Per-stream warmup (`WARMUP N BARS`)

```qkt
SYMBOLS
    gold = EXNESS:XAUUSD EVERY 5m WARMUP 50 BARS
```

Declares that any rule referencing this stream must wait for N closed candles before firing. In multi-stream strategies each stream gets its own counter; a rule that touches multiple streams fires only after **all** of its referenced streams are warm.

Use it when an indicator or rolling window needs lookback before its output is meaningful — e.g. `EMA(gold.close, 50)` is unreliable until the 50th closed candle.

Behavior:

- **Phase 25B (live):** on deploy, the engine fetches the requested history from the broker's historical API and seeds the candle hub + indicators. Rules fire on the first live closed candle. Indicator periods (e.g. `EMA(close, 50)`) also trigger prefetch implicitly — `WARMUP N BARS` is the explicit ceiling.
- **Backtest:** the entire backtest is sequential candle replay, so `WARMUP` just gates rule firing for the first N bars — no fetch needed.
- If the broker can't satisfy the fetch (rate limit, auth, missing data), deploy aborts with `WarmupFailedException` before any rule fires.

Limitations:

- Engine restart resets the live `WarmupGate` counter — but the next deploy re-runs auto-warmup, so this is transparent to operators.
- Nested indicators (`EMA(EMA(close, 9), 21)`) report only the outer period; set explicit `WARMUP` to override.
- Lookback `btc.close[N]` not yet derived; set explicit `WARMUP N+1 BARS`.
- `N` must be a positive integer.

## Stream field access

Every stream exposes these fields:

```qkt
btc.open          -- open price of the current closed candle
btc.high          -- high
btc.low           -- low
btc.close         -- close
btc.volume        -- volume
btc.bid           -- best bid from the last tick in the window (live feeds only)
btc.ask           -- best ask from the last tick in the window (live feeds only)
btc.spread        -- ask - bid (live feeds only)
btc.timestamp     -- candle start time (ms since epoch)
```

`bid`, `ask`, and `spread` are populated only on feeds that carry a quote — live MT5 streams do, backtest/historical feeds do not. When a quote is unavailable they resolve to undefined and a condition referencing them does not fire (the same null-tolerant behaviour as out-of-range lookback). They are the quote from the last tick before the candle closed — the freshest value the engine holds, not the live quote at order-placement instant.

For historical lookback (the N-th candle ago):

```qkt
btc.close[0]      -- current candle (same as btc.close)
btc.close[1]      -- previous candle
btc.close[20]     -- 20 candles ago
```

Negative indices and out-of-range indices return `null`; any comparison with `null` evaluates to `false` (so you don't get exceptions during warmup).

## Same symbol, different timeframes

If you want BTC on both 1m **and** 1h to detect short-term moves within long-term context, declare two aliases:

```qkt
SYMBOLS
    btc_1m = BACKTEST:BTCUSDT EVERY 1m
    btc_1h = BACKTEST:BTCUSDT EVERY 1h

RULES
    WHEN btc_1m.close CROSSES ABOVE btc_1m.close[5]      -- short-term up
     AND btc_1h.close > sma(btc_1h.close, 20)             -- long-term up too
    THEN BUY btc_1m SIZING 0.1
```

The candle hub deduplicates ticks — both aggregators read from the same underlying tick stream. There's no double cost.

## Multiple brokers, same symbol

Currently **not supported**. The DSL parser rejects:

```qkt
-- this fails to compile:
SYMBOLS
    btc_bybit  = BYBIT_SPOT:BTCUSDT EVERY 1m
    btc_exness = EXNESS:BTCUSDm EVERY 1m
```

Position reconciliation becomes ambiguous when the same underlying instrument has positions on two venues simultaneously. See [Broker integration](../../concepts/broker-integration.md) for the deferred limitation and the workarounds (separate strategies, separate daemons).

## `FOR EACH` over streams

To apply the same rule to many streams without copy-paste:

```qkt
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m
    eth = BACKTEST:ETHUSDT EVERY 1m
    sol = BACKTEST:SOLUSDT EVERY 1m

FOR EACH s IN btc, eth, sol DO
    WHEN ema(s.close, 9) CROSSES ABOVE ema(s.close, 21)
    THEN BUY s SIZING 0.1
```

`s` is a textual substitution at compile time, not a runtime variable. See [FOR EACH](foreach.md).

## Synchronizing streams

Strategies that act on **multiple streams together** — pairs trading, basket strategies, lead-lag rules — usually want the engine to evaluate the rule body once per *matched* bar window, with every member's candle current. Without synchronization, each stream's close triggers an independent evaluation that reads the other side's last-known value — which is the **previous** window's bar if the other stream hasn't closed yet. For a slow signal that's noise; for a tight spread that's the wrong trade.

Declare a sync group inside the `SYMBOLS` block with the `SYNCHRONIZE` keyword:

```qkt
SYMBOLS
    gold   = EXNESS:XAUUSD EVERY 1h,
    silver = EXNESS:XAGUSD EVERY 1h,
    btc    = BYBIT_SPOT:BTCUSDT EVERY 1h,
    eth    = BYBIT_SPOT:ETHUSDT EVERY 1h,
    vix    = TV:VIX EVERY 1d

    SYNCHRONIZE gold silver
    SYNCHRONIZE btc eth WITHIN 30s
    -- vix is not listed in any clause, so it fires independently on its own
    -- bar closes (same as if SYNCHRONIZE were absent entirely).
```

Each `SYNCHRONIZE` clause defines one independent group. The clause **must follow** the stream declarations within the `SYMBOLS` block. Streams not listed in any clause keep firing per-bar exactly as before.

Three or more members per group are fine:

```qkt
SYMBOLS
    gold     = EXNESS:XAUUSD EVERY 1h,
    silver   = EXNESS:XAGUSD EVERY 1h,
    platinum = EXNESS:XPTUSD EVERY 1h
    SYNCHRONIZE gold silver platinum
```

The rule body sees all three bars on the same window, so a basket condition like `gold.close + silver.close + platinum.close > THRESHOLD` reads same-window prices, not a mix.

### Timeouts

The optional `WITHIN <duration>` declares a per-group timeout. If the first member of a window closes but the others don't arrive within the timeout, the engine drops the partial window — no callback fires and the pending bars are released. Useful for cross-broker pairs where one venue can lag or temporarily disconnect. Same-broker pairs almost never need a timeout: leave it off and the engine waits forever (in practice, microseconds).

```qkt
SYNCHRONIZE btc eth WITHIN 30s    -- give up if eth doesn't print within 30 seconds of btc
SYNCHRONIZE gold silver           -- wait forever (same venue, will always print)
```

Drop-on-timeout is intentionally conservative: it's better to skip a window than to fire a rule on stale half-data and trade on it. If you want a partial-fire variant, file an issue with the use case.

### Rules and validation

- Every `SYNCHRONIZE` clause must list at least two aliases.
- Every listed alias must be declared above in the `SYMBOLS` block.
- An alias appears in at most **one** group — overlapping groups are rejected at parse.
- Every member of a group must share the same `EVERY` timeframe. Different timeframes have no shared window boundaries, so sync would silently never fire.

### What stays the same

- Per-stream tick-fed indicators (e.g. `vwap`) still update on every raw tick.
- `WARMUP N BARS` works the same — the sync callback only fires once the warmup gate is satisfied for every member.
- Non-grouped aliases in the same strategy keep their pre-#45 per-close evaluation. Mixing a sync pair with a standalone stream is fine.

See [Phase 35 — Bar-Level Synchronized Publish](../../phases/phase-35-bar-sync.md) for the worked examples, known limitations, and migration notes.

## Common gotchas

- **Forgetting `EVERY`.** `btc = BACKTEST:BTCUSDT` (no timeframe) is a parse error.
- **Lowercase broker prefix.** `bybit_spot:btcusdt` is a parse error — broker prefixes must be uppercase.
- **Mixing case in the symbol.** `BTCusdt` or `btcusdt` will fail at the broker boundary because the venue's symbol is case-sensitive (typically all-uppercase).
- **Forgetting the `m` suffix on Exness.** The `exness` broker profile auto-adds it via `symbolPolicy.suffix: "m"`. You write `EURUSD` in the DSL; the broker sees `EURUSDm`. If your broker profile doesn't have this set, the order fails at submission.
- **Stream alias collisions in `FOR EACH`.** Picking `s` as the iterator and also having a stream named `s` causes shadowing — change the iterator name.
- **Forgetting commas between stream decls.** Up through #196 the parser silently dropped everything after the first stream when commas were missing. Both styles work now, but be consistent within a file.
- **Mixed-timeframe `SYNCHRONIZE`.** `SYNCHRONIZE gold silver` where `gold` is `1h` and `silver` is `1m` is rejected at construction — the windows have no shared boundaries so it would never fire.

## What this composes with

- [Conditions](conditions.md) — references like `btc.close` and the `POSITION.btc` function expect stream aliases declared here
- [Indicators](indicators.md) — indicator function calls take stream-field expressions
- [FOR EACH](foreach.md) — iterates over streams
- [SIZING](sizing.md) and [BRACKET](bracket.md) — refer to the stream's price for percent/absolute calculations
