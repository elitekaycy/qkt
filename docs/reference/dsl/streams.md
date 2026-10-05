# Streams (SYMBOLS)

A **stream** is a single instrument on a single venue at a single timeframe. The `SYMBOLS` block declares every stream a strategy listens to. Each stream gets an **alias** — a short name you use throughout the rest of the file.

## Shape

<!-- qkt-doc: grammar -->
```qkt
SYMBOLS
    <alias> = <BROKER>:<symbol> EVERY <timeframe>
```

Three parts after `=`:

1. **Broker prefix** — ASCII letters + underscores, conventionally uppercase; the parser accepts any case and the broker registry lookup decides whether the profile exists
2. **Symbol** — uppercase, the venue's name for the instrument
3. **`EVERY` + timeframe** — candle window

The `<alias>` is what your strategy code uses. Pick something short and meaningful.

Synthetic read-only streams also live in `SYMBOLS`. For account equity, use
`<alias> = SERIES ACCOUNT.EQUITY EVERY <timeframe>`; see [Synthetic series](series.md).

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

There is no weekly window: the duration units are `s`, `m`, `h` and `d` only. `EVERY 1w` is rejected
(`Unknown TimeWindow unit 'w'`); use `EVERY 7d`.

<!-- qkt-doc: illegal -->
```qkt
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1w
```

The parser is liberal — `EVERY 7m` and `EVERY 3h` work fine, even though they're non-standard. But your data fetcher may not have data at non-standard resolutions; check.

### How live warmup finds history for each window

MT5 serves bars only at its native timeframes (M1, M5, M15, M30, H1). When a stream needs
history before its first live bar (an indicator period or `WARMUP N BARS`), qkt rebuilds
other windows on the UTC epoch grid from the finest native source that fits:

| Window | Warmup source |
| --- | --- |
| `1s` … `30s` | The venue's tick record (`/copy_ticks_range`), aggregated by the same candle builder the live feed uses. Capped at six hours of ticks per stream. |
| `2m`, `7m`, other whole-minute windows | M1 bars |
| `2h`, `4h`, `1d`, other whole-hour windows | H1 bars |
| `90s`, `90m` and other windows that fit no native grid | Not servable; deploy aborts naming the window. Change `EVERY`, not `WARMUP`. |

A plain lookback such as `x.close[1]` does not fetch history on its own; only indicators and
`WARMUP` do.

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
- `N` must be a positive integer.

## Stream field access

Every stream exposes these fields:

```qkt
btc.open          -- open price of the current closed candle
btc.high          -- high
btc.low           -- low
btc.close         -- close
btc.volume        -- traded volume; on quote-only venues (MT5 FX, metals) the number of ticks in the bar
btc.bid           -- best bid from the last tick in the window (quote feeds only)
btc.ask           -- best ask from the last tick in the window (quote feeds only)
btc.spread        -- ask - bid (quote feeds only)
btc.mark          -- the venue's mark price: what it values positions and liquidates at (gateway feeds)
btc.index         -- the spot index the contract tracks (gateway feeds)
```

An option contract's stream also has its mark implied volatility and Greeks:

```qkt
c.iv              -- mark implied volatility, in volatility points (45.5 is 45.5%)
c.delta           -- per contract, in units of the underlying
c.gamma           -- per contract, delta's change per unit of the underlying's price
c.vega            -- per contract, quote currency per volatility point
c.theta           -- per contract, quote currency per calendar day
```

`btc.timestamp` is the bar's start time in epoch milliseconds; `btc.timestamp[1]` is the previous bar's start. For the strategy's clock, use [`NOW`](now.md).

`bid`, `ask`, and `spread` are populated only on feeds that carry a quote: live MT5 streams, and tick backtests over quote data (bid/ask ticks). A backtest over bars, or over trade-only ticks, has no quote. When a quote is unavailable they resolve to undefined and a condition referencing them does not fire (the same null-tolerant behaviour as out-of-range lookback). They are the quote from the last tick before the candle closed — the freshest value the engine holds, not the live quote at order-placement instant.

`mark` and `index` are the contract's own, as its venue reports them; a perpetual's premium is
`btc.mark - btc.index`. Live they are the newest the venue quoted, on a `type: gateway` account whose
gateway declares the `mark_prices` capability. In a backtest they are the stored series
(`qkt fetch <SYMBOL> --marks --tf <window> --from <date> --to <date>`, sampled at the stream's window), each
value seen only after its time: at a bar's close, the last one inside the bar. A strategy that reads them
does not start on a feed that serves none (MT5, Bybit, a gateway without `mark_prices`), and a backtest
whose stored marks miss a day of the run is refused naming the fetch; until a first value is known they are
Undefined. A continuous futures stream (`@front`) has none: read a listed contract or a perpetual.

```qkt
WHEN perp.mark - perp.index > 25 AND POSITION.perp = 0   -- rich premium: fade it
THEN SELL perp SIZING 0.01
```

`iv` is the venue's mark IV of the contract; the Greeks are Black-76 on it and the forward the venue values
the contract against, at rate 0, times the contract size: the same model `POSITION.<structure>.delta`
uses, so a one-contract structure's delta is its leg's. Live they come from the newest quote, on a
`type: gateway` account whose gateway declares the `option_marks` capability. In a backtest they come from
the root's declared chain series (`chains: book | trade`), the newest snapshot at or before the bar's close;
a trade-built series carries the trade's IV and index, not the mark IV and forward. They are Undefined until
a usable quote is known (a positive mark IV no older than the root's `maxQuoteAgeMinutes`) and from the
contract's expiry. A strategy reading them on a stream that is not a catalogued option contract, or on a feed
that serves no option marks, does not start.

```qkt
SYMBOLS
    c = DERIBIT:BTC_USDC_30OCT26_90000_C EVERY 1h
RULES
    WHEN c.iv < 40 AND c.delta > 0.3 AND c.delta < 0.5 AND POSITION.c = 0   -- cheap vol, near the money
    THEN BUY c SIZING 0.1
```

### Trade flow and liquidations (`<alias>.buy_volume[1]`, ...)

A venue stream's public trade tape gives four volumes per bar of the stream's timeframe, in the contract's
quantity (base coins on a Deribit USDC-linear contract):

```qkt
perp.buy_volume[1]        -- traded by buyers taking liquidity (aggressor buys), one bar back
perp.sell_volume[1]       -- traded by sellers taking liquidity
perp.long_liq_volume[1]   -- longs the venue liquidated (liquidation orders that sold)
perp.short_liq_volume[1]  -- shorts the venue liquidated (liquidation orders that bought)
```

They are read **one bar back or more**: `perp.buy_volume[1]` is the bar before the one closing, `[n]` the bar `n`
before it. A bare `perp.buy_volume` (the bar closing) is refused when the strategy compiles. A bar's flow is
known only after it closes, and live reads it from the gateway's tape after that, so at the close itself its
last prints may not have arrived; a bar later they have, and live and backtest read the same sums. A bar with
no print is `0`. `[n]` reads the series directly, so it needs no warmup of its own, and it works inside
indicators and rolling functions:

```qkt
STRATEGY flow_imbalance VERSION 1
SYMBOLS
    perp = DERIBIT:BTC_USDC_PERPETUAL EVERY 5m
RULES
    -- Aggressive buying, well above its recent pace, after a flush of liquidated longs.
    WHEN perp.buy_volume[1] > 2 * perp.sell_volume[1]
     AND perp.buy_volume[1] > avg(perp.buy_volume[1], 12) * 1.5
     AND perp.long_liq_volume[2] > 0 AND POSITION.perp = 0
    THEN BUY perp SIZING 0.01
```

- **Live:** read from the account's gateway (`/v1/trades`, `/v1/liquidations`) in the background, each bar a
  couple of seconds after it closes; the gateway must declare `trades` (for `buy_volume`, `sell_volume`) or
  `liquidations` (for the `_liq_` fields). A strategy reading them anywhere else fails at start. On the first
  bars after a start, and while the gateway cannot be reached, a bar not yet read is Undefined.
- **Backtest:** read from the stored tape, `tape/<VENUE>/<NAME>/<day>.csv.gz` and
  `liquidations/<VENUE>/<NAME>/<day>.csv.gz`, written by `qkt fetch <VENUE>:<NAME> --tape` and
  `--liquidations` (`--from <date> --to <date>`). A run is refused, naming that fetch, when a day its reads need
  is not stored, from the day its first bar's warmup and lookback reach back to.
- A continuous futures stream (`@front`) has no tape of its own: read a listed contract or a perpetual.
- Deribit marks liquidations on its tape. Its testnet keeps about a day of prints and rarely liquidates; mainnet's
  whole history is served by a mainnet or paper gateway.

### Open interest (`<alias>.open_interest`)

`perp.open_interest` is the open interest of the contract the stream trades: the contracts outstanding, in
the unit the strategy's quantities are written in (base coins on a Deribit USDC-linear or Binance USDⓈ-M
contract). It is a series of its own, the venue's published figures, each visible from the instant the venue
made it known, never earlier: a figure Binance stamps with the start of its five minutes is visible at their
end. It reads like a candle field, so indicators and lookback work on it:

```qkt
STRATEGY oi_breakout VERSION 1
SYMBOLS
    perp = DERIBIT:BTC_USDC_PERPETUAL EVERY 15m
RULES
    -- Open interest rising fast while price breaks out.
    WHEN perp.open_interest > ema(perp.open_interest, 20) * 1.05 AND perp.close > perp.high[1]
     AND POSITION.perp = 0
    THEN BUY perp SIZING 0.01
```

- **Backtest:** read from `open_interest/<VENUE>/<NAME>.csv` under the data root, stored by
  `qkt fetch <VENUE>:<NAME> --open-interest --from <date> --to <date>`. A backtest whose stored figures do
  not cover the run (no gap over three of the series' own intervals) is refused with that fetch.
- **Live:** read from the account's gateway every minute, which must declare the `open_interest`
  capability; a strategy reading it on any other account fails at start, naming the account.
- Each new figure is an observation of its own, like a `HUB:` record: from its arrival every evaluation
  reads it (a backtest test proves a rule fires on the first bar close after the figure that crosses, not
  before). Until the first figure arrives the field is undefined.
- Venues differ in what they publish: Binance keeps 30 days of five-minute history; Deribit publishes no
  history, so its gateway records the present figure each time it is read and serves what it recorded
  (a series that starts when the gateway first read it).

### Order-book depth (`<alias>.bid_depth`, `.ask_depth`, `.book_imbalance`)

Three fields read the order book of the contract the stream trades, from its ten best levels a side:

| Field | Value |
|---|---|
| `perp.bid_depth` | the quantity resting on the ten best bid levels, in the unit the strategy's quantities are written in |
| `perp.ask_depth` | the same on the ask side |
| `perp.book_imbalance` | `(bid_depth − ask_depth) / (bid_depth + ask_depth)`: 1 when only bids rest, −1 when only offers, 0 when balanced or empty |

All three come from one snapshot of the book, each visible from the instant the venue stamped it, never
earlier. They read like candle fields, so indicators and lookback work on them:

```qkt
STRATEGY book_lean VERSION 1
SYMBOLS
    perp = DERIBIT:BTC_USDC_PERPETUAL EVERY 1m
RULES
    -- Buy when the book leans to the bid and enough rests on the offer to fill.
    WHEN perp.book_imbalance > 0.3 AND perp.ask_depth > 0.5 AND POSITION.perp = 0
    THEN BUY perp SIZING 0.01
```

- **Live:** read from the account's gateway every 10 seconds, which must declare the `depth` capability; a
  strategy reading depth on any other account fails at start, naming the account. A rule therefore sees the
  book as of the last read, up to 10 seconds old, not the book at the instant it evaluates.
- **Backtest:** read from `depth/<VENUE>/<NAME>/<day>.csv.gz` under the data root, stored by
  `qkt fetch <VENUE>:<NAME> --depth --from <date> --to <date>`. No venue publishes book history: the gateway
  records the book each time a live strategy reads it and serves what it recorded, so a backtest replays
  exactly the snapshots live saw, and history exists only from when something first read the contract on
  that gateway. A backtest whose stored snapshots do not cover the run (no gap over three of the series' own
  intervals) is refused with that fetch.
- Each snapshot is an observation of its own, like a `HUB:` record. Until the first one arrives the fields
  are undefined.

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

The DSL accepts the same symbol on two venues as two ordinary streams — each is keyed by
`venue:symbol`, so they never collide:

```qkt
SYMBOLS
    btc_bybit  = BYBIT_SPOT:BTCUSDT EVERY 1m
    btc_exness = EXNESS:BTCUSD EVERY 1m

RULES
    WHEN btc_bybit.close - btc_exness.close > 50 THEN LOG "venue spread" bybit=btc_bybit.close exness=btc_exness.close
```

Trading both sides from one strategy is where the caveats start: positions are reconciled per
venue, so the strategy's `POSITION.<alias>` reads stay separate and a daemon-level risk halt
covers both. See [Broker integration](../../concepts/broker-integration.md) before running a
cross-venue strategy live.

## `FOR EACH` over streams

To apply the same rule to many streams without copy-paste:

```qkt
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m
    eth = BACKTEST:ETHUSDT EVERY 1m
    sol = BACKTEST:SOLUSDT EVERY 1m

FOR EACH s IN [btc, eth, sol] DO
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
- **Lowercase broker prefix.** `bybit_spot:BTCUSDT` parses — the prefix is matched case-insensitively against the registry — but write it uppercase so it reads like the profile name in `qkt.config.yaml`.
- **Mixing case in the symbol.** `BTCusdt` or `btcusdt` parses too, but fails at the broker boundary because the venue's symbol is case-sensitive (typically all-uppercase).

```qkt
SYMBOLS
    btc = backtest:BTCUSDT EVERY 1m    -- accepted; BACKTEST is the conventional spelling
```
- **Forgetting the `m` suffix on Exness.** The `exness` broker profile auto-adds it via `symbolPolicy.suffix: "m"`. You write `EURUSD` in the DSL; the broker sees `EURUSDm`. If your broker profile doesn't have this set, the order fails at submission.
- **Stream alias collisions in `FOR EACH`.** Picking `s` as the iterator and also having a stream named `s` causes shadowing — change the iterator name.
- **Forgetting commas between stream decls.** Up through #196 the parser silently dropped everything after the first stream when commas were missing. Both styles work now, but be consistent within a file.
- **Mixed-timeframe `SYNCHRONIZE`.** `SYNCHRONIZE gold silver` where `gold` is `1h` and `silver` is `1m` is rejected at construction — the windows have no shared boundaries so it would never fire.

## What this composes with

- [Conditions](conditions.md) — references like `btc.close` and the `POSITION.btc` function expect stream aliases declared here
- [Indicators](indicators.md) — indicator function calls take stream-field expressions
- [FOR EACH](foreach.md) — iterates over streams
- [SIZING](sizing.md) and [BRACKET](bracket.md) — refer to the stream's price for percent/absolute calculations
