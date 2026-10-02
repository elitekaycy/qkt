# Expressions

The values you can compute inside conditions, action parameters, and `LET` bindings. Everything in qkt — numbers, booleans, indicator values, account state — composes the same way.

## Literals

```qkt
100               -- integer
1.5               -- decimal
0.001             -- decimal
1e-3              -- scientific notation
"hello"           -- string (mostly for LOG)
'BUY'             -- single-quoted string also works
TRUE              -- boolean
FALSE
```

There is no `NULL` literal. A missing value comes from data (a lookback past the start of history, an indicator still warming up); test for it with [`IS NULL`](#is-null-is-not-null).

A string literal is one line long and knows five escapes: `\'`, `\"`, `\\`, `\n` and `\t`. Any other backslash sequence, or a newline inside the quotes, is a lexer error:

```qkt
WHEN btc.close > 0 THEN LOG "quote \" tab\t newline\n backslash \\" ; LOG 'it\'s fine'
```

<!-- qkt-doc: illegal -->
```qkt
WHEN btc.close > 0 THEN LOG "no \x escapes"
```

<!-- qkt-doc: illegal -->
```qkt
WHEN btc.close > 0 THEN LOG "strings stop
at the end of the line"
```

Numbers are parsed as exact decimals internally (BigDecimal). No floating-point drift over thousands of trades.
Strings support exact, case-sensitive `=` / `==` and `!=` / `<>` comparisons. Ordered comparisons
(`<`, `<=`, `>`, `>=`) remain numeric-only.

## Arithmetic

<!-- qkt-doc: grammar -->
```qkt
a + b             -- addition
a - b             -- subtraction
a * b             -- multiplication
a / b             -- division
-a                -- unary negation
```

Standard precedence: `* /` before `+ -`. There is no `%` operator; use `mod(<a>, <b>)` (see [Indicators](indicators.md#math-helpers)). Use parentheses for clarity:

```qkt
WHEN (btc.high - btc.low) / btc.close > 0.02     -- 2% range
THEN LOG "volatile bar"
```

## Comparison and boolean

Covered in [Conditions](conditions.md):

- `=` / `==`, `!=` / `<>`, `<`, `<=`, `>`, `>=`
- `AND`, `OR`, `NOT`
- `BETWEEN ... AND ...`
- `IN [...]`
- `CROSSES ABOVE`, `CROSSES BELOW`
- `<expr> IS NULL`, `<expr> IS NOT NULL`

### `IS NULL` / `IS NOT NULL`

```qkt
gold.bid IS NULL                            -- true on a feed that carries no quotes
gold.bid IS NOT NULL AND gold.bid < ASK     -- gate that only fires when a quote is available
```

Tests whether the inner expression evaluates to "missing" — the internal `Value.Undefined` sentinel produced by indicators that haven't warmed, snapshots that haven't been captured, missing optional fields (`btc.bid` on a no-quote feed), and any arithmetic that propagated an `Undefined`.

A rule is not evaluated until the streams it references are warm (see [conditions](conditions.md)), so inside a rule an indicator on those streams has already received its warmup bars.

`IS NULL` always returns a boolean — it never propagates `Undefined` itself, so it composes safely with `AND` / `OR`. Binds tighter than `AND`, so `fast IS NOT NULL AND slow IS NOT NULL AND CROSSES(fast, slow) ABOVE` parses without parentheses.

## Stream field access

```qkt
btc.open
btc.high
btc.low
btc.close
btc.price         -- alias of close
btc.volume
btc.bid           -- optional (quote feeds only)
btc.ask
btc.spread        -- ask - bid, computed when both present
```

### Instrument meta fields

A stream also exposes the static instrument metadata qkt holds for its symbol (from the
instrument catalog, verified against the venue by `qkt instruments verify`). They compile like
any other field and are resolved when the strategy binds to its feed, so a symbol with no
catalog entry fails at deploy, not on the first tick:

```qkt
btc.tick_size          -- minimum price increment (point size)
btc.contract_size      -- units per lot
btc.volume_step        -- lot increment the venue accepts
btc.volume_min         -- smallest order the venue accepts
btc.swap_long_points   -- overnight swap for a long, in points
btc.swap_short_points  -- overnight swap for a short, in points
btc.tick_value         -- tick_size x contract_size: what one tick is worth per lot
btc.multiplier         -- alias of contract_size (the futures term)
```

### Futures contract fields

A futures stream — a listed contract (`BINANCE_UM:BTCUSDT_241227`) or a continuous one
(`BINANCE_UM:BTCUSDT@front`, `@next`) — also tells you which contract it follows right now. On any
other stream these fields are Undefined, so a rule that reads them does not fire:

```qkt
btc.contract       -- the followed contract's code, e.g. 'BTCUSDT_241227' (a string: compare with = or !=;
                   -- any other operator, arithmetic or indicator on it is Undefined)
btc.dte            -- days until that contract expires, with fractions
btc.days_to_roll   -- days until the stream moves to the next contract (a listed contract: its expiry)
```

```qkt
-- Be flat through every roll instead of carrying the position (and paying the roll).
WHEN btc.days_to_roll < 0.5 AND POSITION.btc != 0
THEN CLOSE btc
```

The fields are read as of the bar's close. The bar that closes at the roll instant already names
the new contract (its prices are still the old contract's), so `days_to_roll` never reaches 0 while
the old contract is followed: the last bar that does follow it closes one bar before the roll, with
`days_to_roll` equal to the bar length. A flat-through-roll threshold must therefore be longer than one
bar — `< 0.5` for bars shorter than 12 hours, `< 1` for `EVERY 12h`, `< 2` for `EVERY 1d`.

```qkt
-- Round a computed stop to the venue grid and refuse to size below the minimum lot.
LET stopDist = round_to(atr(gold, 14) * 2, gold.tick_size)
WHEN gold.close > gold.open AND 0.01 / stopDist >= gold.volume_min
THEN BUY gold SIZING 0.01 BRACKET { STOP_LOSS BY stopDist, TAKE_PROFIT BY stopDist * 2 }
```

`btc.timestamp` is the bar's start time in epoch milliseconds, and `btc.timestamp[n]` is the start of the bar `n` bars ago. It is not a price series: `ema(btc.timestamp, 9)` is an error. For the strategy's clock use [`NOW`](now.md), with fields such as `NOW.hour_utc` and `NOW.weekday`. There is no `btc.mid` either; compute `(btc.bid + btc.ask) / 2`.

Lookback:

```qkt
btc.close         -- current closed candle's close
btc.close[0]      -- same as btc.close
btc.close[1]      -- previous candle
btc.close[20]     -- 20 bars ago
```

Out-of-range returns `null` (which makes any containing comparison `false`).

## Indicator calls

See [Indicators](indicators.md) for the full catalog.

```qkt
ema(btc.close, 9)            -- numeric series (closed-candle field)
rsi(btc.close, 14)
atr(btc.candle, 14)          -- candle series (needs OHLC)
highest(btc.close, 20)
vwap(btc.tick, 100)          -- tick series (per-tick price + volume, not candle close)
```

Treat them as numbers — they slot into any arithmetic context.

### Series argument shape per indicator kind

- **Numeric** (`ema`, `sma`, `rsi`, …): take a stream field like `btc.close`. Strategy-author picks which OHLCV field to feed.
- **Candle** (`atr`): take `<stream>.candle` (the whole OHLCV record). The indicator reads multiple fields internally.
- **Tick** (`vwap`): take `<stream>.tick` (raw ticks, not candle-aggregated). Updates on every tick, not only on candle close. Requires `Tick.volume` to be present — live MT5/Bybit feeds carry it; backtest feeds need to provide it explicitly.

## Account references

```qkt
ACCOUNT.equity               -- cash + open P&L
ACCOUNT.balance              -- cash only
ACCOUNT.realized_pnl         -- realized P&L since strategy start
ACCOUNT.unrealized_pnl       -- open-position P&L right now
ACCOUNT.total_pnl            -- realized + unrealized
ACCOUNT.equity_peak          -- high-water mark of this strategy's equity
ACCOUNT.open_positions_count -- open positions held by this strategy, across symbols
```

```qkt
LET riskUsd = ACCOUNT.equity * 0.01            -- 1% of equity at risk
LET riskQty = riskUsd / (atr(btc, 14) * 2)     -- size that loses riskUsd on a 2-ATR stop
```

### Trade-history accessors (Phase 25-followup #132)

```qkt
ACCOUNT.last_trade_at    -- epoch ms of the most recent fill on this strategy; null before any trade
ACCOUNT.last_trade_pnl   -- realized P&L of the most recent closed trade; null before any close
ACCOUNT.win_streak       -- consecutive closed wins (0 if last close was a loss / no trades yet)
ACCOUNT.loss_streak      -- consecutive closed losses
ACCOUNT.dd_pct           -- current drawdown from this strategy's equity peak, as a percent (5.0 = 5%)
ACCOUNT.realized_today   -- this strategy's closed-trade P&L since UTC midnight
ACCOUNT.realized_month   -- this strategy's closed-trade P&L since the 1st of the UTC month
ACCOUNT.trades_today     -- closed trades recorded for this strategy since UTC midnight
ACCOUNT.wins_today       -- of those, the ones that closed with realized_pnl > 0
ACCOUNT.losses_today     -- of those, the ones that closed with realized_pnl < 0
```

```qkt
-- Two losses in a day is enough: stop opening, keep managing.
WHEN btc.close > btc.open AND POSITION.btc = 0
 AND ACCOUNT.losses_today < 2 AND ACCOUNT.open_positions_count = 0
 AND ACCOUNT.equity >= ACCOUNT.equity_peak * 0.97
THEN BUY btc SIZING 0.1
```

`realized_today` and `realized_month` reset at their UTC boundary and survive a daemon restart within the same day or month. They are in account currency; a monthly loss gate in risk units multiplies your per-trade risk:

```qkt
STRATEGY monthly_gate VERSION 1
SYMBOLS
    eur = EXNESS:EURUSD EVERY 30m
PARAM riskUsd = 50
RULES
    -- Stop opening new trades once this month's closed losses reach 3R; exits still run.
    WHEN eur.close > eur.open AND POSITION.eur = 0 AND ACCOUNT.realized_month > -3 * riskUsd
    THEN BUY eur SIZING 0.1
```

`STREAK` exposes the same outcome stream through the issue-facing ladder namespace:

```qkt
STREAK.wins       -- consecutive closed wins
STREAK.losses     -- consecutive closed losses
STREAK.banked     -- realized P&L banked during the current win streak

TRADES.today            -- entry fills recorded for this strategy since UTC midnight
COOLDOWN.remaining_s    -- seconds left in the configured after-loss cooldown, or 0
```

`last_trade_at` and `last_trade_pnl` return `Value.Undefined` until the strategy has closed at least one trade — compose with `IS NULL` for safe gating:

```qkt
-- Cooldown between entries: don't re-enter for an hour after a trade.
WHEN signal AND POSITION.btc = 0
 AND (ACCOUNT.last_trade_at IS NULL OR NOW.epoch_ms - ACCOUNT.last_trade_at > 3600000)
THEN BUY btc SIZING 0.5 PCT RISK BRACKET { STOP_LOSS BY 300, TAKE_PROFIT BY 600 }

-- Self-halt on drawdown: stop trading at 5% DD until equity recovers.
WHEN signal AND ACCOUNT.dd_pct < 5
THEN BUY btc SIZING 0.5 PCT RISK BRACKET { STOP_LOSS BY 300, TAKE_PROFIT BY 600 }

-- Loss-streak-aware sizing: scale down after consecutive losses.
WHEN signal AND STREAK.losses < 2
THEN BUY btc SIZING 1.0 PCT RISK BRACKET { STOP_LOSS BY 300, TAKE_PROFIT BY 600 }
WHEN signal AND STREAK.losses >= 2
THEN BUY btc SIZING 0.5 PCT RISK BRACKET { STOP_LOSS BY 300, TAKE_PROFIT BY 600 }
```

Win and loss streaks are exclusive — `STREAK.losses >= 1` implies `STREAK.wins = 0` and vice versa. Both return `0` until the strategy has closed at least one trade. The bounded trade-history buffer is persisted with engine state when persistence is enabled; otherwise a fresh process starts with empty streak state.

A "win" is `realized_pnl > 0` for the closing fill; "loss" is `< 0`. Position-opening fills (zero realized) are skipped entirely — they don't count toward either streak.

`STREAK.banked` sums only the consecutive winning closes at the end of history. A loss resets it to `0`, so it can be used to press with banked profit without increasing base risk after a losing close:

```qkt
THEN BUY btc SIZING RISK $ (100 + 0.30 * STREAK.banked) BRACKET { STOP_LOSS BY 300, TAKE_PROFIT BY 600 }
```

`TRADES.today` and `COOLDOWN.remaining_s` read the same PACER ledger used by configured per-strategy throttles. `TRADES.today` counts entry fills, not closed round trips.

## Position references

<!-- qkt-doc: grammar -->
```qkt
POSITION.<stream>                           -- net quantity (signed) — same as POSITION.<stream>.quantity
POSITION.<stream>.quantity                  -- explicit form; .qty is the same accessor
POSITION.<stream>.entry_price               -- average entry price; .avg_price and .avg_entry_price are aliases
POSITION_AVG_PRICE.<stream>                 -- the same average entry price, as its own keyword
POSITION.<stream>.pnl                       -- strategy realized + this-symbol unrealized
POSITION.<stream>.realized_pnl              -- strategy-level realized P&L (see note)
POSITION.<stream>.unrealized_pnl            -- open P&L on this position, marked at the closing price
POSITION.<stream>.holding_duration          -- seconds since the position was opened
POSITION.<stream>.mfe                       -- max favorable excursion of the entry leg (price units)
POSITION.<stream>.mae                       -- max adverse excursion of the entry leg (price units)
POSITION.<stream>.count                     -- open legs on this stream (hedging venues); .open_count is the same
POSITION.<stream>.longs                     -- open long legs; .long_count is the same
POSITION.<stream>.shorts                    -- open short legs; .short_count is the same
POSITION.<stream>.gross                     -- sum of |quantity| over the open legs
POSITION.<stream>.trades_today              -- fills on this stream since UTC midnight
POSITION.<stream>.last_trade_at             -- epoch ms of the last fill on this stream; null before any
OPEN_ORDERS.<stream>                        -- active risk-increasing entry-order count
```

On an option structure alias (`OPEN ps = OPTIONS ON …`), `POSITION.ps` is the structure's size and
`.pnl`, `.credit`, `.max_loss`, `.pnl_pct`, `.dte`, `.delta`, `.gamma`, `.vega` and `.theta` read the
structure; see [Option structures](structures.md#reading-a-structure). The structure fields compile
only on a structure alias, and the stream-only accessors only on a stream.

Every accessor above compiles; an unknown one (`POSITION.btc.size`) is a parse error:

```qkt
WHEN POSITION.btc.qty = 0 AND POSITION.btc.count = 0 AND POSITION.btc.gross = 0
 AND POSITION.btc.trades_today < 3
 AND (POSITION.btc.last_trade_at IS NULL OR NOW.epoch_ms - POSITION.btc.last_trade_at > 3600000)
THEN BUY btc SIZING 0.1

WHEN POSITION.btc.longs > 0 AND POSITION.btc.shorts > 0
THEN LOG "hedged" avg=POSITION.btc.avg_price same=POSITION_AVG_PRICE.btc
```

<!-- qkt-doc: illegal -->
```qkt
WHEN POSITION.btc.size > 0 THEN CLOSE btc
```

```qkt
WHEN POSITION.btc > 0
 AND POSITION.btc.unrealized_pnl > POSITION.btc.entry_price * 0.05    -- 5% in profit
THEN CLOSE btc
```

`POSITION.<stream>.unrealized_pnl` marks every open leg at the price that would close it right now: a long at the bid, a short at the ask. When the feed carries no quotes (single-price ticks, most backtests) it marks at the last price. Marking at mid would count the half-spread the venue charges on the way out as open profit.

`POSITION.<stream>.mfe` reads the high-water mark of `current_price - entry_price` (for BUY) or `entry_price - current_price` (for SELL) on the entry leg since it opened. The entry leg is the PRIMARY on a netting venue; on a hedging venue, where every plain `BUY`/`SELL` opens an independent leg, it is the oldest open leg. Returns `0` if the strategy holds nothing on the stream. Same value the stack engine uses for `STACK_AT MFE >= ...` threshold checks; see [STACK_AT](stack-at.md).

`POSITION.<stream>.mae` reads the high-water mark of `entry_price - current_price` (for BUY) or `current_price - entry_price` (for SELL) on the same entry leg since it opened. Returns `0` if the strategy holds nothing on the stream. Same value the stack engine uses for `STACK_AT MAE >= ... RECOVER ...` arming checks.

Both marks survive a daemon restart: the live session saves each new extreme (at most once a second per stream) and restores it with the position, then extends it with the bars the warmup loaded for the downtime, so a position held across a restart reads the same or higher `mfe`/`mae` afterwards — never `0`.

`POSITION.<stream>` returns a signed quantity. `POSITION.btc > 0` means long; `POSITION.btc < 0` means short; `POSITION.btc = 0` means flat. Most entry rules guard with `POSITION.btc = 0`.

`OPEN_ORDERS.<stream>` is scoped to the current strategy and resolved stream symbol. It counts active risk-increasing entries in pending, submitted, working, or partially-filled states. It excludes dormant composite children and protective or otherwise risk-reducing exits. Terminal fills, cancellations, rejections, and GTD expiry remove the entry from the count through the same order lifecycle used in replay and live execution.

```qkt
WHEN setup
 AND POSITION.gold = 0
 AND OPEN_ORDERS.gold = 0
THEN SELL gold ORDER_TYPE = LIMIT AT gold.close + 2 SIZING 1
```

!!! note "realized_pnl is currently strategy-level"
    `POSITION.<stream>.realized_pnl` returns the strategy's total realized P&L, not the per-symbol slice. True per-symbol realized requires lot-level accounting — tracked on the [backlog](../../planned.md#phase-28-exploratory).

## Conditional expressions (`CASE`)

<!-- qkt-doc: grammar -->
```qkt
CASE
  WHEN <cond1> THEN <expr1>
  WHEN <cond2> THEN <expr2>
  ELSE <default_expr>
END
```

```qkt
LET size = CASE
  WHEN atr(btc, 14) > 200 THEN 0.05        -- volatile: small size
  WHEN atr(btc, 14) > 100 THEN 0.10        -- normal
  ELSE 0.15                                -- quiet: bigger
END

RULES
    WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
    THEN BUY btc SIZING size
```

`CASE` is an **expression**, not a control-flow statement. It evaluates and returns a value; the surrounding context (here `LET size = ...`) decides what to do with it.

`ELSE` is required — a `CASE` with no default branch is a parse error (`CASE requires an ELSE
branch`), so every evaluation yields a value. Return a sentinel and test for it if you need
"no match":

<!-- qkt-doc: illegal -->
```qkt
LET size = CASE
  WHEN atr(btc, 14) > 200 THEN 0.05
  WHEN atr(btc, 14) > 100 THEN 0.10
END
```

## Math helpers

<!-- qkt-doc: grammar -->
```qkt
abs(<expr>)
max(<a>, <b>, ...)                -- largest of two or more values
min(<a>, <b>, ...)
sqrt(<expr>)
log(<expr>)
exp(<expr>)
floor(<expr>)
ceil(<expr>)
round(<expr>)
pow(<base>, <exp>)
mod(<a>, <b>)
round_to(<x>, <step>)
rank_of(<self>, <peer>, ...)      -- 1-based rank of <self> among the values, 1 = highest
normalize(<self>, <peer>, ...)    -- min-max scale of <self> among the values, in [0, 1]
softmax(<self>, <peer>, ...)      -- softmax weight of <self> among the values, in (0, 1)
```

`max`/`min` with two or more arguments are plain scalar functions; with one argument and a
`SINCE` window they are the [aggregates](#aggregates) below. The three cross-sectional helpers
score the **first** argument against the rest, so the same call written from each stream's rule
ranks that stream among its peers:

```qkt
LET mom_btc = btc.close / btc.close[20] - 1
LET mom_eth = eth.close / eth.close[20] - 1
LET mom_sol = sol.close / sol.close[20] - 1

RULES
    -- Long the top-2 by 20-bar momentum, sized by softmax weight of that momentum.
    WHEN rank_of(mom_btc, mom_eth, mom_sol) <= 2 AND POSITION.btc = 0
    THEN BUY btc SIZING 0.3 * softmax(mom_btc, mom_eth, mom_sol)

    WHEN normalize(mom_eth, mom_btc, mom_sol) > 0.5 AND POSITION.eth = 0
    THEN BUY eth SIZING max(0.05, min(0.2, abs(mom_eth)))
```

```qkt
LET vol_norm = (btc.close - sma(btc.close, 20)) / atr(btc, 14)
LET signal_strength = abs(vol_norm)

WHEN signal_strength > 2 THEN LOG "strong dislocation" z=vol_norm
```

## Aggregates

An aggregate folds a series over a window. The function name is followed by one series in parentheses and a `SINCE` window:

<!-- qkt-doc: grammar -->
```qkt
sum(<expr>)  SINCE OPEN | T-<N>     -- total
mean(<expr>) SINCE OPEN | T-<N>     -- average
max(<expr>)  SINCE OPEN | T-<N>     -- largest value
min(<expr>)  SINCE OPEN | T-<N>     -- smallest value
```

- `SINCE T-N` covers the last `N` closed bars of the stream the series is evaluated on. It is `null` until `N` bars have been seen, so it composes with the usual null handling.
- `SINCE OPEN` covers the bars since the position on that stream was opened. It resets whenever the position opens, closes or flips.

A rolling window also has a two-argument shorthand. Each form is exactly the `SINCE T-N` aggregate beside it, and `N` must be a positive integer literal:

<!-- qkt-doc: grammar -->
```qkt
sum(<expr>, N)          -- sum(<expr>) SINCE T-N
mean(<expr>, N)         -- mean(<expr>) SINCE T-N
avg(<expr>, N)          -- mean(<expr>) SINCE T-N
count(<condition>, N)   -- sum(CASE WHEN <condition> THEN 1 ELSE 0 END) SINCE T-N
```

`max(a, b)` and `min(a, b)` keep meaning the larger and smaller of two values; for a rolling extreme use `max(<expr>) SINCE T-N` or the `highest` / `lowest` indicators (which exclude the current bar). A `SINCE T-N` window adds `N` bars to its stream's automatic warmup.

```qkt
STRATEGY rolling_count VERSION 1
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1d
LET pct_up_days = count(btc.close > btc.close[1], 20) / 20
RULES
    WHEN pct_up_days > 0.7 THEN LOG "70%+ of last 20 bars up"
```

## Null handling

Expressions return `null` when:

- An indicator isn't warm yet
- A lookback `[N]` is out of range
- A division has denominator 0
- A function gets unexpected input

`null` propagates through arithmetic: `null + 5 = null`. Comparisons with `null` always return `false`. This means **conditions short-circuit safely during warmup** — your rule simply doesn't fire while data is missing.

To test for a missing value explicitly, use [`IS NULL` / `IS NOT NULL`](#is-null-is-not-null)
above; it always yields a boolean, so it composes with `AND` / `OR` where a bare comparison with
`null` would just be `false`.

## Type rules (loose)

The DSL is dynamically typed at the expression level. Most operations coerce sensibly:

- Number + Number → Number
- Number + Null → Null
- Boolean AND/OR Boolean → Boolean
- Comparing Number to Number → Boolean
- Comparing Number to Null → False
- String concat is not supported in conditions; strings are only valid in `LOG` action arguments

Mixing types in arithmetic is not caught at parse or compile time: `5 + "hello"` compiles, and at
runtime the mismatched operation evaluates to `null` (`Value.Undefined`), so a comparison built on
it is `false` and `IS NULL` is `true`. Keep strings to `LOG` fields and equality tests.

```qkt
LET oops = 5 + "hello"          -- compiles; evaluates to null on every tick
RULES
    WHEN oops IS NULL THEN LOG "type mismatch is a runtime null, not a parse error"
```

## Common gotchas

- **Division by zero returns `null`, not infinity or an error.** A divide-by-zero in a condition makes the condition `false`. Be aware.
- **Operator precedence.** `AND` binds tighter than `OR`. Parentheses are free.
- **`a == b` vs `a = b`** — both work. Pick one and stick with it for the project.
- **No string operations in conditions.** Strings are for `LOG` only. Don't try `WHEN btc.symbol = "BTCUSDT"` (the parser doesn't expose `symbol` on streams).
- **`null` is opinionated.** Treating null-comparisons as false simplifies most code but can hide bugs. Use `IS NULL`/`IS NOT NULL` when the distinction matters.

## What this composes with

- [Conditions](conditions.md) — the most common host for expressions
- [Indicators](indicators.md) — produce numbers your expressions consume
- [SIZING](sizing.md) and [BRACKET](bracket.md) — arithmetic in action parameters
- [LET](let-defaults.md) — name a complex expression for reuse
