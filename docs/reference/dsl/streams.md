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

The broker prefix tells the engine which venue this stream lives on: `BACKTEST`, the name of a
`brokers:` entry in `qkt.config.yaml`, upper-cased, or, for paper trading on the TradingView feed,
the exchange TradingView lists the symbol on. Common ones:

| Prefix | What it means | When to use |
| --- | --- | --- |
| `BACKTEST` | The historical data store (`~/.qkt/data/`) | Backtesting (`qkt backtest`). Not live: `qkt run` and `qkt deploy` refuse it on the TradingView feed and name the symbol to write instead |
| `OANDA`, `BINANCE`, `COINBASE`, ... | A TradingView exchange, for paper trading on TradingView's free ticks (`source: tv`, the default) when no broker account has that name | `qkt run` and paper deploys: `OANDA:EURUSD`, `OANDA:XAUUSD`, `BINANCE:BTCUSDT` |
| `BYBIT_SPOT` | Bybit Spot, a `type: gateway` entry named `bybit_spot` on a gateway running the [Bybit adapter](https://github.com/elitekaycy/qkt-venue-gateway/blob/main/adapter-bybit/README.md) | Live trading spot crypto |
| `BYBIT_LINEAR` | Bybit USDT perpetuals, a `type: gateway` entry named `bybit_linear` | Live trading perpetuals |
| `DERIBIT` | Deribit, a `type: gateway` entry named `deribit` | Futures, perpetuals and options |
| `EXNESS`, `ICMARKETS`, `FTMO`, `PEPPERSTONE` | MT5 brokers via `mt5-gateway` | Live trading FX, indices, commodities |

Plus any custom profile you define in `qkt.config.yaml`:

```yaml title="qkt.config.yaml"
brokers:
  mybybit:
    type: gateway
    gateway_url: http://127.0.0.1:8444
    api_key: env:BYBIT_TRADER_TOKEN
    expected_adapter: bybit
    expected_account_login: "1234"
    expected_trade_mode: demo
```

You'd then write `MYBYBIT:BTCUSDT` in your strategy.

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

A symbol is one name of letters, digits and `_`. It may carry one decimal strike, digits `.` digits with no
spaces (`KALSHI:KXHIGHNY_26NOV06_B73.5`); it may not start with a digit or hold a `-`.

If a venue rejects a symbol you wrote, check the actual venue name vs the qkt-side name. The [config schema](../config-schema.md) covers `symbolPolicy` overrides.

## Event slots (`KALSHI:KXINXU@front.p70`)

An event family (a series of yes/no contracts, such as the hourly S&P 500 ladder `KALSHI:KXINXU`) lists new
contracts every hour and retires old ones. A **slot** is one stable stream of the family that follows whichever
contract its selectors pick, so a strategy never names a contract code and never goes stale:

```qkt
STRATEGY spx_ladder VERSION 1
SYMBOLS
    p70 = KALSHI:KXINXU@front.p70 EVERY 1m,
    atm = KALSHI:KXINXU@front.atm EVERY 1m,
    up2 = KALSHI:KXINXU@next.above2 EVERY 1m
RULES
    -- Buy the strike priced nearest 70% when it trades below 0.72 after the fee; sell what is held above 0.85.
    WHEN p70.ask + p70.taker_fee < 0.72 AND POSITION.p70 = 0
    THEN BUY p70 SIZING 10
    WHEN p70.bid > 0.85 AND POSITION.p70 > 0
    THEN CLOSE p70
```

<!-- qkt-doc: grammar -->
```qkt
<alias> = <VENUE>:<FAMILY>@<event>.<strike> EVERY <window>
```

| Part | Selector | Picks |
|---|---|---|
| event | `front` | the open event that stops trading first |
| event | `next` | the one after it |
| strike | `pNN` (`p05` … `p95`) | the contract whose YES mid is nearest NN %, ties to the lower strike |
| strike | `atm` | `p50` |
| strike | `aboveN`, `belowN` | N strikes above / below `atm` in strike order |
| strike | `topN` (`top1` …) | the contract with the Nth highest YES mid (the favourite), ties to the lower strike |

An event with one contract (a 15-minute up/down window, a single question) resolves every strike selector to it;
`@front.atm` is the usual spelling. A future's continuous stream (`CME:ES@front`) never has the `.<strike>` part,
which is how the two are told apart. The family must be declared under `events:` in `instruments.yaml` with its event
catalog imported ([backtest data](../../how-to/backtest-data.md)); a slot of an undeclared family does not start.

**Selection.** At each close of the slot's `EVERY` window the selectors read the family's quotes as they stood just
before that instant and pick a contract; the pick holds until the next close. Only contracts with a two-sided quote
(a bid at or below the ask) are candidates. Same data, same pick. The slot's bars are the followed contract's YES
prices, never back-adjusted: when the pick changes, the next bar is the new contract's, so `p70` is a
constant-probability series and its indicators keep their history across events. `p70.contract` names the contract
a bar came from (its code, `KXINXU_26NOV06H1500_T7425`), and the event contract fields below
(`floor_strike`, `minutes_left`, `taker_fee`, …) read that contract's terms.

**Orders land on real contracts.** `BUY p70` is placed, when the rule fires, on the contract whose prices the rule
just read. The slot keeps a book of the contracts it opened, and a contract stays held after the slot moves on,
until it settles at its outcome or the strategy closes it.

- An order opposite to what the slot holds reduces first: `SELL p70` while long closes its held contracts oldest
  first at their own quotes, and only what is left opens NO on the contract the slot follows now. A slot therefore
  never holds YES on one contract and NO on another, and while a held contract has stopped trading (awaiting its
  result) no opposite remainder opens.
- `POSITION.p70` is the signed total of the book: positive long YES, negative long NO, 0 when it holds nothing, so
  `POSITION.p70 = 0` does not re-enter while an earlier entry is still held.
- `CLOSE p70` closes every contract in the book and cancels their working orders.
- Market and limit orders translate; any other order type on a slot is suppressed with the reason.

Slots run in backtests and live. In a backtest a slot reads its family's stored quote ticks; it has no bars of its
own, so warmup starts cold, and `--tick-fills` is refused. Live, on a gateway account, a slot subscribes its whole
family (the gateway's `roots=<series>` quote subscription, every contract of the series including ones listed after
start) and picks the same way at each close of its window; the family is the gateway's `kind: event` listing of the
series, polled for changes each minute (the gateway lists only the series its account is scoped to, so a slot's series must be
in that scope), so a live slot needs no `events:` declaration or imported catalog (one that
is declared still resolves its catalogued contracts from the file). A contract the venue has paused (`trading:
disabled`) or closes out only is not picked, and orders on it are refused. Live warmup starts cold too. After a
restart, the contracts a slot held come back into its book from the venue's positions, oldest first (two slots of
one family share a netting position per contract, which returns to the first of them). The family's contracts are
traded and settled like any event contract, and trades and settlements report the real contract symbols.

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
does not start on a feed that serves none (MT5, a gateway without `mark_prices`), and a backtest
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

### Event contract fields (`<alias>.minutes_left`, `.floor_strike`, ...)

A binary event contract (a Kalshi-style "S&P 500 at 7,425 or above at 15:00" that pays 1 on YES and 0 on NO)
trades at prices from 0 to its payout, so `close`, `bid` and `ask` read as the YES probability. Its stream also
reads the contract's terms:

```qkt
k.floor_strike       -- the lower bound of the question ("7,425 or above": 7425); Undefined when it has none
k.cap_strike         -- the upper bound ("below 73", or the top of "73 to 74"); Undefined when it has none
k.close_at           -- when trading stops, epoch milliseconds
k.expiry_at          -- when the result is expected, epoch milliseconds
k.minutes_left       -- minutes until close_at, from the bar's close (fractions; negative once it passed)
k.occurs_at          -- when the thing asked about happens (a game's start), epoch ms; Undefined when unknown
k.minutes_to_occurs  -- minutes until occurs_at (negative once it started: in-game)
k.taker_fee          -- the fee one contract pays buying at the current ask
k.maker_fee          -- the fee one contract pays resting at the current bid
k.title              -- the contract's label (a string: compare with = or !=)
```

All of them are read as of the bar's close, never the wall clock, so a backtest and a live run see the same
`minutes_left`. The fees follow the contract's probability fee model, `rate × p × (1 − p)` per contract at price
`p` (on a payout other than 1, `p` is the price's share of the payout and the fee is scaled back to money). They
are **unrounded**: the venue rounds the fee of a whole order up to its unit, so one contract's share of an order
is this value, not the rounded fee of a one-contract order. A model without a maker rate reads 0 for `maker_fee`;
either fee is Undefined while the stream has no quote on that side.

A strategy that reads any of these on a stream that is not a catalogued event contract does not start, and one
that reads `taker_fee` or `maker_fee` on a contract whose fee model is unknown does not start either: a fee never
reads as a silent zero. A field the strategy does not read costs nothing.

Venue codes with a decimal strike keep the `.` in the symbol (see [symbol names](#symbol-names)):

```qkt
STRATEGY nyc_high_fade VERSION 1
SYMBOLS
    hi = KALSHI:KXHIGHNY_26NOV06_B73.5 EVERY 1m
RULES
    -- An expensive 73-74 bucket in the last half hour: sell it, if the taker fee leaves an edge.
    WHEN hi.minutes_left < 30 AND hi.bid > 0.85 + hi.taker_fee AND POSITION.hi = 0
    THEN SELL hi SIZING 10
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

## Symbol sets (`EXNESS:{EURUSD, XAUUSD}`, `ALPACA:* WHERE ...`)

A **symbol set** declares many streams of one venue at once, and `FOR EACH m IN <set> DO` runs a rule over every
member without naming them twice. Three forms, all in the stream position of `SYMBOLS`:

<!-- qkt-doc: grammar -->
```qkt
SYMBOLS
    <set>   = <VENUE>:{<symbol>, <symbol>, ...} EVERY <timeframe> [WARMUP <n> BARS]      -- picked
    <set>   = <VENUE>:* WHERE <filter> TOP <n> BY <field> [ASC|DESC] [DYNAMIC] EVERY <timeframe>   -- filtered
    <set>   = <VENUE>:*@<event>.<strike> WHERE <filter> TOP <n> BY <field> [ASC|DESC] EVERY <timeframe>   -- slot universe
    <set>   = <VENUE>:<*|FAMILY>@<front|next|each>.* WHERE <filter> TOP <n> BY <field> [ASC|DESC] EVERY <timeframe>   -- event ladder
    <union> = <set> + <set> [+ <set> ...]                                                 -- union
```

```qkt
SYMBOLS
    fx     = EXNESS:{EURUSD, XAUUSD, USDJPY} EVERY 15m
    crypto = BINANCE:{BTCUSDT, ETHUSDT} EVERY 15m
    majors = fx + crypto
RULES
    FOR EACH m IN majors DO
      WHEN rsi(m.close, 14) < 30 AND POSITION.m = 0
      THEN BUY m SIZING 0.1
```

- **Picked list.** `EXNESS:{EURUSD, XAUUSD}` is exactly the streams `EXNESS:EURUSD` and `EXNESS:XAUUSD` at the
  set's `EVERY` (and `WARMUP`). It expands when the file is parsed, so a strategy written this way backtests to the
  same trades as the same rules written out per symbol by hand; each name is listed once. Each member must have
  instrument metadata at start, as any declared stream must.
- **Filtered universe.** `ALPACA:* WHERE category = "us_equity" AND contract_size >= 1 TOP 50 BY contract_size`
  takes every instrument the venue's catalog lists, keeps those passing the filter, ranks them by one field
  (`DESC` is the default; ties and instruments missing the field sort by symbol, the latter last) and declares the
  top `n`. `TOP` is required and may not exceed 500. The members are resolved **once, when the run starts**, and do
  not change during it. Ending the spec with `DYNAMIC` (`... TOP 20 BY volume DYNAMIC EVERY 1m`) asks for members
  that join and leave during the run, re-evaluated at each close of the set's `EVERY` window from the rows listed
  then (see [members that join and leave](#members-that-join-and-leave)); it runs in backtests, and live sessions
  refuse it for now. Where the instruments come from is the only
  difference between a backtest and a live run; both rank them with the same code, so the same instruments give the
  same members. See [where membership comes from](#where-a-filtered-sets-members-come-from).
- **Union.** `majors = fx + crypto` names the members of sets declared above it, each keeping its own venue; the
  operands must share `EVERY`, and a symbol in two operands counts once. A union has no `EVERY` of its own.
- **`FOR EACH m IN <set> DO <rule>`** runs the rule once per member; `m` substitutes textually, as the bracket form
  does. The bracket form `FOR EACH s IN [a, b] DO` is unchanged. A set has no stream of its own: `fx.close` is an
  error, and buying every member on one condition is `FOR EACH m IN fx DO WHEN <condition> THEN BUY m`.

Each member's stream alias is `<set>.<symbol>` (`fx.EURUSD`), which reports and `risk.per_strategy.<name>.sets`
caps in the config refer to ([config schema](../config-schema.md#risk)).

### Where a filtered set's members come from

| Run | Catalog | As of |
|---|---|---|
| `qkt backtest` | the instruments file's `instruments:` list (`--instruments`, else `instruments.yaml` under the data root) | `--from` |
| live (`qkt deploy`, `qkt daemon`, `qkt bot session`) on a `type: gateway` account | the gateway's `GET /v1/instruments` listing | session start |
| live on an MT5 account | the mt5-gateway's `GET /symbols`, then `/symbol_info/<name>` per symbol | session start |

- **The venue is the account.** `GW:* WHERE ...` reads the listing of the account named `gw` in `brokers:`. A venue
  no account serves, or a connector that cannot list its instruments, is refused at deploy naming it; a picked list
  (`GW:{A, B}`) needs no listing and runs on any account.
- **Backtest equities.** A set over equities from the instruments file is refused unless the backtest passes
  `--survivorship-accepted`, because a flat file has no listing history. A live listing is the venue's today, so a
  live run needs no flag.
- **Gateway listings.** Spot, equity and event instruments are rows, with the same fields `qkt instruments pull`
  writes; a listed equity reads `kind = equity`. Futures and options are left out (a listing carries no exchange
  terms for them; their contract catalogs do), and a set that comes out empty says how many it left out. When the
  filter pins `kind = spot`, `equity` or `event` (alone or under `AND`), qkt reads the listing through the gateway's
  `kind` filter instead of the whole list; the filter still decides membership.
- **MT5 listings.** Only symbols this account trades are rows: a name the account's suffix and aliases do not map
  back to itself is skipped, and a symbol the gateway reports no info for is left out. Each row costs one
  `/symbol_info` read at start, so a broker listing thousands of symbols takes a moment to deploy.
- **What the run states.** Each set's members are printed at start (`symbol set 'm': 3 member(s) resolved at start:
  ...`), in the backtest's output and the daemon's log alike.

### Members that join and leave

A set ending in `DYNAMIC` is re-evaluated at each close of its `EVERY` window, epoch aligned: the catalog rows listed
at that close (an event contract once it opens, any contract until it expires) are filtered, ranked and cut to
`TOP n` again, and the members change by these rules.

| Change | When | What happens |
|---|---|---|
| join | ranked now, not a member before | the member's rules start; they first fire at the **next** close, never the one it joined at |
| leave | no longer ranked, flat, no working entry order | its rules and indicator state are dropped; a rejoin warms up afresh |
| stick | no longer ranked, but holds a position or a working entry order | its rules keep running, but an order that opens or adds is refused; reducing and closing orders pass |
| release | a stuck member is now flat | dropped at that close |
| stay | a stuck member is ranked again | a normal member again, without a new warmup |

- **Event catalogs.** In a backtest a moving set also ranks the contracts of the instruments file's `events:`
  families, read from their event catalogs (`contracts/<VENUE>/<FAMILY>.events.json`): a contract is a row from its
  listing (`openAtMs`) until its expiry, so `KALSHI:* WHERE family = KXINXU TOP 8 BY expiry_at ASC DYNAMIC EVERY 1h`
  rolls from one hourly event's contracts to the next as they list and expire.
- **Slot universes.** `KALSHI:*@front.p70 WHERE family GLOB "KXINXU*" TOP 5 BY expiry_at ASC EVERY 1h` filters and
  ranks the venue's event families instead of single contracts, each read through its front contract (earliest
  expiry, then symbol); a member is the family's slot (`KXINXU@front.p70`), followed and traded exactly as a slot
  declared by hand ([event slots](#event-slots-kalshikxinxufrontp70)). A family is a candidate while a contract of
  it is listed and unexpired. A slot universe always moves, and cannot read market fields yet.
- **Event ladders.** `KALSHI:KXINXU@front.* WHERE kind = event TOP 10 BY symbol ASC EVERY 1m` takes every contract of
  the family's front open event (the one closing first), filters and ranks them, and rolls to the next event's
  contracts when it closes; `@next.*` takes the event after it and `@each.*` every open event. `KALSHI:*@front.*`
  does the same for every family. An event is open from its contracts' listing until their `close_at`; the members
  are the contracts themselves, traded as declared streams.
- **Warmup.** A joining member reads the history the run already holds for its stream: a backtest declares every
  member some close of the run picks (printed as `symbol set 'm': DYNAMIC over 4 candidate(s): ...`) and keeps each
  one's last bars, as many as the member's indicators or `WARMUP` need, so a member that joins late is warm at once.
- **Backtest and live alike.** Membership steps at the first tick at or past each close, before that tick reaches the
  rules, in both modes, and reads only the catalog and the positions held; so a backtest and a live session over the
  same catalog and ticks join, stick and release the same members at the same closes (`DynamicSetParityTest`).
- **Market fields.** A set reading `close`, `volume` or `spread` watches every row whose catalog part passes (its
  market clauses aside): the run feeds those streams and keeps, per row, the figures of each window. At a close the
  set reads the window that ended at or before it, so it sees every tick before the close and none after.
- **The journal.** Each change is published as a `SetMembershipChanged` event (set, member, change, rank, window
  close), which the engine audit journal records.
- **Limits, for now.** Live sessions refuse moving sets (`DYNAMIC`, or reading market fields). A dynamic set cannot be part of a union, and cross-member
  functions (`RANK_OF(expr, set)`, `SUM_OF`, ...) over it are refused.

### The filter vocabulary

A filter is a boolean expression over the instrument metadata every venue already publishes: comparisons
(`=`, `!=`, `<`, `<=`, `>`, `>=`), `IN (a, b)`, `GLOB "pattern"` (the `*` glob calendar rules use), `AND`, `OR`,
`NOT` and parentheses. Numbers compare numerically, words ignoring case; a field the instrument lacks compares
false. `WHERE`, `TOP`, `GLOB`, `ASC`, `DESC` and `DYNAMIC` are words with meaning only inside a set declaration.

| Field | Value |
|---|---|
| `symbol` | the venue's bare symbol (`XAUUSD`) |
| `kind` | `spot`, `equity`, `future`, `perpetual`, `option` or `event` |
| `category` | the venue's folder or grouping (`Stocks\US`, `us_equity`) |
| `currency` | the quote currency when the instruments file states it |
| `contract_size`, `tick_size` | the contract multiplier; the smallest price step |
| `trade_mode` | `full`, `long_only`, `short_only`, `close_only`, `disabled` |
| `family`, `underlying` | a derivative's root (`ES`, `KXINXU`); an option's underlying index |
| `expiry_at`, `dte` | a dated contract's expiry in epoch milliseconds; its days to expiry as of `--from` |
| `close`, `volume`, `spread` | as-of market figures: the last price, the volume traded and the last ask minus bid in the last window of the set's `EVERY` that ended at or before the evaluation; never lifetime figures |

A set that filters or ranks on `close`, `volume` or `spread` always moves, with or without `DYNAMIC`: its members are
re-evaluated at each close ([members that join and leave](#members-that-join-and-leave)). An instrument with no
figure yet reads null, so it fails comparisons and ranks last. `open_interest` is not in this vocabulary yet. `qkt bot universe "<spec>"` lists what a spec
resolves to ([bot CLI](../bot-cli.md#universe)).

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
