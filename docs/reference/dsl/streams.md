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

## Common gotchas

- **Forgetting `EVERY`.** `btc = BACKTEST:BTCUSDT` (no timeframe) is a parse error.
- **Lowercase broker prefix.** `bybit_spot:btcusdt` is a parse error — broker prefixes must be uppercase.
- **Mixing case in the symbol.** `BTCusdt` or `btcusdt` will fail at the broker boundary because the venue's symbol is case-sensitive (typically all-uppercase).
- **Forgetting the `m` suffix on Exness.** The `exness` broker profile auto-adds it via `symbolPolicy.suffix: "m"`. You write `EURUSD` in the DSL; the broker sees `EURUSDm`. If your broker profile doesn't have this set, the order fails at submission.
- **Stream alias collisions in `FOR EACH`.** Picking `s` as the iterator and also having a stream named `s` causes shadowing — change the iterator name.

## What this composes with

- [Conditions](conditions.md) — references like `btc.close` and the `POSITION.btc` function expect stream aliases declared here
- [Indicators](indicators.md) — indicator function calls take stream-field expressions
- [FOR EACH](foreach.md) — iterates over streams
- [SIZING](sizing.md) and [BRACKET](bracket.md) — refer to the stream's price for percent/absolute calculations
