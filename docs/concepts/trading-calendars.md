# Trading calendars

How qkt knows when each market is open, how a symbol gets its calendar, and what the calendar changes in a
backtest and in live trading.

## What a calendar answers

A calendar answers questions about one symbol at one moment, and one question about a whole run:

| Question | Used by |
|---|---|
| Is the market open now? | the live market-data gate (a quote gap while closed is not a fault), feed polling, data coverage |
| Does the venue take an order now? | the session rule and the backtest fills ([Trading only in session](#trading-only-in-session)) |
| Is this a planned daily pause? | the live market-data gate (a gap in the metals pause is `PAUSED`, not `STALE`) |
| Is this day a holiday, or can it close early? | data coverage: which days must hold data |
| When does this trading day end? | `DAY` orders expire at their symbol's session end |
| How many bars of this size does a year hold? | annualizing Sharpe, Sortino and book volatility |

Bars do **not** use a calendar: they sit on a fixed UTC grid, so a 1h bar always runs 13:00-14:00 UTC.
`vwap_session`, `session_range_*` and the pivots use the UTC hours written in the strategy.

## The built-in calendars

| Calendar | Open | Trading day | Closed |
|---|---|---|---|
| `fx` | Sunday 17:00 to Friday 17:00 New York | 17:00 to 17:00 New York | weekends; Dec 25, Jan 1 and Good Friday need no data |
| `crypto` | always | midnight to midnight UTC | never |
| `nyse` | 09:30-16:00 New York, 09:30-13:00 on early-close days | the regular session | weekends, exchange holidays and one-off closures |
| `nyse[HH:MM-HH:MM]` | NYSE trading days in that New York window; on an early-close day it ends at 13:00 when the window ends later | the window | as `nyse` |
| `cme_globex` | Sunday 17:00 to Friday 16:00 Chicago, halted 16:00-17:00 daily | 17:00 to 16:00 Chicago | weekends |
| `<base> pause HH:MM-HH:MM [Zone]` | as its base, with a daily pause | as its base | as its base |

Every calendar follows daylight saving through its time zone. NYSE closes early at 13:00 on July 3 and December 24
when they fall Monday to Thursday and on the Friday after Thanksgiving; its one-off closures are the September 2001
attacks, the national days of mourning (2004-06-11, 2007-01-02, 2018-12-05, 2025-01-09) and Hurricane Sandy
(2012-10-29 and 30). A venue that quotes stocks outside the regular session takes a window: Exness quotes its US
stock CFDs 06:00-15:45 New York (`nyse[06:00-15:45]`, measured from its own bars and ticks), a broker with pre- and
after-market hours `nyse[04:00-20:00]`. A window must open before 13:00, so every trading day has a session.

## How a symbol gets its calendar

One rule, in backtest and live alike. qkt takes the first step that answers:

1. **A futures root's `calendar:`** in the instrument catalog, for a futures contract or continuous stream.
2. **The venue's calendars**: the broker's `calendars:` rules over its built-in preset and default, exactly what
   the live account trades on.
3. **qkt's built-in rules**, when no venue is known: `BTC*`, `ETH*` and `*USDT` crypto; `SPX`, `NDX`, `DJI` and
   `RUT` NYSE; everything else FX.

Which venue applies:

| | Venue |
|---|---|
| Live | the account serving the stream's prefix (`EXNESS:XAUUSD` is the `exness` broker entry) |
| Backtest with `--venue <broker>` | that broker, for every stream (`--venue none`: no venue, built-in rules only) |
| Backtest without `--venue` | the broker the stream's prefix names, when the config or a built-in profile knows it |
| Backtest of a `BACKTEST:` stream | none: the built-in rules |

So a backtest of a live strategy file, run with the live config, judges every symbol by the same calendar as the
live account. A `BACKTEST:` research file can borrow a broker's hours with `--venue`, or with `venue:` under
`backtest:` in `qkt.config.yaml`.

`--venue` changes the calendars, not the data. A stored BTC series with weekend ticks still replays them under
`--venue the5ers`, whose BTC stops at the weekend; trim the window or use the venue's own data where that matters.

Check what a file resolves to:

```console
$ qkt calendars mixed.qkt --config qkt.config.yaml
symbol     calendar                                   source
HG:EURUSD  fx pause 16:50-17:10 America/New_York      hg default
HG:BTCUSD  crypto pause 16:50-17:10 America/New_York  hg calendars 'BTC*'

samples a year at 1h:
  run sharpe (every symbol's bar closed)  6261.43
  book risk  (any symbol's bar closed)    8766.00
```

The live daemon logs the same choice for every symbol when a strategy starts
(`calendar HG:BTCUSD -> crypto pause ... (hg calendars 'BTC*')`).

### Stocks and other venue hours

A rule's pattern is a glob on the symbol, or `category:<glob>` on the folder the venue files the instrument under:
MT5's `path` (`Standard\Stocks\AAPLm` is `Stocks`), a gateway listing's `category` (`us_equity`). One category rule
covers a broker's whole stock list; a symbol rule is tried first, so one stock can still have its own calendar.
The category is part of the instrument's spec, read live from the venue and in a backtest from `category:` in
`instruments.yaml`, which `qkt instruments pull` writes.

The `exness` profile ships `category:Stocks -> nyse[06:00-15:45]`, so `EXNESS:AAPL` trades Exness's stock session
with no configuration. Another venue declares its hours on its broker entry, which both modes read:

```yaml
brokers:
  stocks:
    type: gateway
    # ...
    calendars:
      "category:us_equity": nyse[04:00-20:00]
```

## Trading only in session

A venue refuses every order outside a symbol's session, closing ones included: Exness quotes US stock CFDs from
06:00 New York but answers MT5 retcode 10044 "session closed" to every order until the regular open. qkt follows
the venue in both modes:

- **Orders a strategy sends** outside the symbol's session are refused before the venue, in backtest and live
  (`VenueSession`, one of the always-on pre-trade controls). A refused close is decided again on the strategy's next
  bar, as after the venue's refusal.
- **Resting orders** (limits, stops, bracket legs) do not fill on a quote outside the session in a backtest; they
  fill on the first in-session price, as the venue fills them at the open. **Engine-held triggers** (trailing stops,
  engine-held stops and stack layers) wait for the session in both modes instead of firing an order the venue would
  refuse.
- **Strategies still evaluate** every bar the data holds, out-of-session bars included: live receives those quotes
  too, so indicators see the same bars in both modes. Only trading waits.

A backtest judges a quote replayed from a recording at its own time. A bar replayed as synthetic ticks (`--bars`, or a
symbol with no ticks) is judged by its bar, whose ticks carry made-up times inside it: the bar trades when any minute
of it is in session. A one-minute pre-market stock bar is outside; a daily FX bar is inside although its close is
stamped after the 17:00 New York close.

Live, an MT5 account answers from its profile's calendars (`calendars:` on the broker entry, else the profile's own).
A venue gateway decides for itself: qkt sends its orders whatever the hour, and futures and options on the exchange
stack are not gated in a backtest either. Time exits, halt flattening and margin liquidation send closes at the time
they fall due; outside a session a backtest fills them where the venue would refuse them.

## Strategies that trade several markets

Each symbol keeps its own calendar inside one strategy or book, whatever order the streams are listed in. On a
Saturday, a strategy holding EURUSD and BTCUSD:

- forms BTCUSD bars and runs its BTC rules, while EURUSD forms none;
- treats EURUSD's silence as the market being closed, and a silent BTC feed as a fault;
- expires a `DAY` order on EURUSD at 17:00 New York and one on BTCUSD at midnight UTC.

### Annualization

Per-bar returns are annualized by the number of samples a year the sampler really takes:

- **Run and per-strategy Sharpe and Sortino.** The equity curve samples a bar boundary once every traded symbol,
  including currency-conversion feeds, has closed a bar there. The count is the **overlap** of their sessions:
  EURUSD + BTCUSD samples on FX hours, 6,261 times a year at 1h.
- **Book volatility, ERC, inverse-vol and vol-target weights, rebalance cadence.** The book samples once per
  boundary on any of its symbols' bars. The count is the **union** of their sessions: 8,766 a year at 1h for the
  same pair. Live samples a book with a controller on the same feed as its backtest: every child's traded symbols
  at the first child's timeframe.

A calendar's own count is the bars its session forms on the UTC grid. For FX and CME Globex at windows that divide an
hour that is the share of open time (1h FX: 6,261.43); longer windows also count the bars the open and close cut
short, so a daily FX series counts six bars a week (313 a year) and 4h FX 1,616.75. When the combined session
equals one member's (FX inside crypto, NYSE inside FX and inside CME Globex), that member's own count is used. Other combinations are measured: the
UTC-grid bars the combined session touches over 2021-2024, per year.

## Data coverage

A backtest's pre-flight check asks which days must hold data. That is a property of the data's own market, so
coverage takes the calendar an import recorded (`qkt data import --calendar`), else the built-in rules for the
symbol's name; `qkt data verify` judges the same way.
