# DSL grammar

The qkt DSL is a small declarative language for trading strategies. This page is the one-page
sketch of every accepted shape; the [DSL reference](dsl/index.md) explains each construct.

!!! note "Living reference"
    Every ```qkt block on this page is compiled by `DslReferenceCodeBlocksTest`, exactly as
    `qkt parse` would. Grammar sketches with `<placeholders>` are marked and skipped; every
    other block must compile, and blocks marked illegal must be rejected.

## File-level shapes

Every `.qkt` file is one of:

- `STRATEGY` — a single strategy, the most common case
- `PORTFOLIO` — a composition of strategies with regime-gated activation

## STRATEGY

<!-- qkt-doc: grammar -->
```qkt
STRATEGY <name> VERSION <int>

[ DEFAULTS { <KEY> = <value> ... } ]

SYMBOLS
    <alias> = <BROKER>:<symbol> EVERY <timeframe>
    [ ... more streams ... ]

[ PARAM <name> = <literal> ]
[ ... more PARAMs ... ]

[ LET <name> = <expression> [, <name> = <expression> ...] ]
[ ... more LETs ... ]

[ SCHEDULE ... ]
[ SEQUENCE ... ]

[ RULES
    WHEN <condition>
    THEN <action> [ ; <action> ... ]
    [ ... more rules ... ]
    [ FOR EACH <ident> IN [ <alias>, ... ] DO WHEN ... THEN ... ]
]
```

The blocks come in this fixed order: `DEFAULTS`, `SYMBOLS`, `PARAM`, `LET`, `SCHEDULE`,
`SEQUENCE`, `RULES`. A section keyword after `RULES` is a located parse error
(`LET must come before RULES`). `RULES` is optional to the parser, but `SYMBOLS` is required
to compile: a rule that names a stream the file never declared fails with
`Unknown stream alias`. `FOR EACH` lives inside `RULES` and can interleave with plain `WHEN`
rules; names (the strategy, aliases, `LET`s, `PARAM`s) may start with a letter or `_`.

```qkt
STRATEGY _grammar_tour VERSION 1

DEFAULTS {
    SIZING = 0.1
    TIF = GTC
}

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1h
    gold = BACKTEST:XAUUSD EVERY 1h

PARAM fast = 9
PARAM slow = 21

LET fastMa = ema(btc.close, fast), slowMa = ema(btc.close, slow)

RULES
    WHEN fastMa CROSSES ABOVE slowMa AND POSITION.btc = 0
    THEN BUY btc ; LOG "long btc"

    FOR EACH s IN [btc, gold] DO
      WHEN s.close < s.open AND POSITION.s > 0
      THEN CLOSE s

    WHEN gold.close > gold.open AND POSITION.gold = 0
    THEN BUY gold SIZING 0.2
```

`PARAM` declares an overridable scalar (number, boolean, or string) with a default. Use the name
in conditions and actions. A portfolio can override it via `RUN <alias> OVERRIDE { key = value }`
— e.g. `PARAM riskPct = 0.01` in the child becomes `0.008` in an aggressive portfolio slot.

## PORTFOLIO

<!-- qkt-doc: grammar -->
```qkt
PORTFOLIO <name> VERSION <int> [ CAPITAL <number> ]

[ SYMBOLS ... ]

IMPORT '<path>' AS <alias> [ HOLD ]
[ ... more imports ... ]

[ REGIMES ... ]
[ ALLOCATE METHOD regime_weighted [ REBALANCE EVERY <duration> ] ... ]

RULES
    [ WHEN <condition> ] RUN <alias> [ WEIGHT <number> ] [ OVERRIDE { <key> = <literal>, ... } ]
    [ ... more rules ... ]
```

`HOLD` keeps a child's positions when the supervisor deactivates it. Without HOLD, deactivation
flattens. `OVERRIDE` retunes a child's `PARAM` values for this portfolio deployment without
editing the child file. Keys must match `PARAM` names declared in the child strategy; types must
match. `WEIGHT` needs `CAPITAL` on the header and the weights must sum to at most `1.0`; a
portfolio has no `LET` or `DEFAULTS` block. See [PORTFOLIO files](dsl/portfolio.md).

```qkt
PORTFOLIO book VERSION 1 CAPITAL 10000

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1h

IMPORT 'trend.qkt' AS trend
IMPORT 'meanrev.qkt' AS meanrev HOLD

RULES
    RUN trend WEIGHT 0.6 OVERRIDE { riskPct = 0.008 }
    WHEN adx(btc, 14) < 20 RUN meanrev WEIGHT 0.4
```

## Stream declaration

<!-- qkt-doc: grammar -->
```qkt
<alias> = <BROKER>:<symbol> EVERY <timeframe> [ WARMUP <N> BARS ]
```

- `<BROKER>` resolves against the broker registry: `BACKTEST` plus any `brokers:` entry in
  `qkt.config.yaml`, upper-cased (e.g. `EXNESS`, `BYBIT_LINEAR`, `DERIBIT`). The parser
  accepts the prefix in any case; the registry lookup is what decides whether it exists.
- `<symbol>` is the canonical symbol qkt sees (`EURUSD`, `BTCUSDT`). Per-broker translation
  (suffix, alias) happens at the broker boundary — see
  [broker integration](../concepts/broker-integration.md).
- `<timeframe>` is `1m`, `5m`, `15m`, `1h`, `1d`, etc. — units `s`, `m`, `h`, `d` only, there is
  no `1w`. Drives the candle aggregator.
- `WARMUP <N> BARS` (optional) gates every rule that touches this stream until N closed candles
  arrive — see [streams](dsl/streams.md#per-stream-warmup-warmup-n-bars).

```qkt
SYMBOLS
    gold = BACKTEST:XAUUSD EVERY 5m WARMUP 50 BARS
    eur = backtest:EURUSD EVERY 15m
```

## Actions

| Action | Effect |
|---|---|
| `BUY <stream> SIZING ... [ORDER_TYPE = ...] [BRACKET ...] [OCO ...] [STACK ...] [TIF ...] [TIMES <n>] [EXIT AFTER <duration>]` | Long entry |
| `SELL <stream> ...` (same clauses) | Short entry |
| `CLOSE <stream>` | Flatten position on the stream's symbol |
| `CLOSE_ALL` / `FLATTEN` | Flatten every open position (aliases) |
| `CANCEL <stream>` | Cancel pending orders on the stream's symbol |
| `CANCEL_ALL` | Cancel all pending orders |
| `RESIZE <stream> TO <sizing>` | Set the position to a target size |
| `OCO_ENTRY { <entry>, <entry> }` | Two pending entries, one cancels the other |
| `LATCH <stream> OFFSET <d> ARM <duration> { ENTER ... }` | Directional trip-wire entry |
| `LOG [WARN\|ERROR\|DEBUG] "<msg>" [field=expr ...]` | Emit a log line — see [logging](../operations/logging.md) |

Actions in one rule are separated by `;` only; a newline does not separate them. `BUY`/`SELL`
need a `SIZING`, on the action or in `DEFAULTS`:

<!-- qkt-doc: illegal -->
```qkt
STRATEGY no_sizing VERSION 1
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1h
RULES
    WHEN btc.close > 0 THEN BUY btc
-- compile error: BUY/SELL requires SIZING
```

### Sizing

<!-- qkt-doc: grammar -->
```qkt
SIZING <quantity>
SIZING <pct> PCT OF (EQUITY | BALANCE)
SIZING <N> PCT RISK                    -- sugar over SIZING RISK N/100; N is a numeric literal;
                                       -- requires a BRACKET STOP_LOSS
SIZING <N> PCT RISK OF BOOK            -- same, but risks N% of the portfolio book (CAPITAL + realized
                                       -- PnL of every child) instead of this strategy's own equity;
                                       -- only valid for PORTFOLIO children, rejected at deploy otherwise
SIZING RISK $ <expr>                   -- account-currency risk budget
SIZING <usd> USD
SIZING POSITION.<alias>                -- full current position
```

```qkt
BUY btc SIZING 0.1
BUY btc SIZING 5 PCT OF EQUITY
BUY btc SIZING 1000 USD
SELL btc SIZING POSITION.btc
```

### Bracket

<!-- qkt-doc: grammar -->
```qkt
BRACKET { STOP_LOSS BY <pct> PCT, TAKE_PROFIT BY <pct> PCT }
BRACKET { STOP_LOSS AT <price-expr>, TAKE_PROFIT AT <price-expr> }
BRACKET { STOP_LOSS BY <distance>, TAKE_PROFIT RR <ratio> }
```

```qkt
BUY btc SIZING 0.1 BRACKET { STOP_LOSS BY 1 PCT, TAKE_PROFIT BY 2 PCT }
BUY btc SIZING 0.5 PCT RISK BRACKET { STOP_LOSS BY atr(btc, 14) * 2, TAKE_PROFIT RR 3 }
```

The compiler routes `BRACKET` to native broker support if the broker has the `BRACKET`
capability (MT5, PaperBroker), else falls back to engine-managed SL/TP via separate orders. Both
legs are required; a bare `STOP_LOSS` outside a `BRACKET` is a parse error.

### Order types

<!-- qkt-doc: grammar -->
```qkt
ORDER_TYPE = MARKET
ORDER_TYPE = LIMIT AT <price-expr>
ORDER_TYPE = STOP AT <price-expr>
ORDER_TYPE = STOP AT <price-expr> LIMIT AT <price-expr>
ORDER_TYPE = TRAILING BY <distance> | TRAILING PCT <percent>
TIF GTC | IOC | FOK | DAY | GTD [UNTIL] <epoch-ms-expr>
```

```qkt
BUY btc SIZING 0.1 ORDER_TYPE = STOP AT btc.close + 100 LIMIT AT btc.close + 120 TIF GTD NOW + 1h
```

### Stack

```qkt
BUY btc SIZING 0.1 STACK 3 SPACING 100 ABOVE WITHIN 1h
BUY btc STACK [ 0.1, 0.2 AT entry + 100, 0.3 LIMIT AT entry + 200 ]
```

Pyramiding — one signal becomes N price-triggered entries. See [STACK](dsl/stack.md).

### Timed exit

```qkt
BUY gold SIZING 0.01 EXIT AFTER 4m
```

Closes the entry's own leg at market once it has been open for the duration, timed from the
fill and checked every tick. Also applies to its `STACK_AT` legs. See
[EXIT AFTER](dsl/exit-after.md).

## Expressions

### Literals

- Number: `100`, `1.5`, `0.001`, `1e-3`
- Boolean: `TRUE`, `FALSE`
- String: `'BUY'`, `"BUY"`; single-line, escapes `\'`, `\"`, `\\`, `\n`, `\t`; supports exact,
  case-sensitive equality and inequality

### Stream fields

- `<stream>.close` (alias `.price`), `.open`, `.high`, `.low`, `.volume`, `.bid`, `.ask`,
  `.spread`, `.timestamp`; `.close[n]` is the value `n` bars ago
- Instrument meta: `.tick_size`, `.contract_size`, `.volume_step`, `.volume_min`,
  `.swap_long_points`, `.swap_short_points`, `.tick_value`, `.multiplier`
- Futures contract: `.contract` (string), `.dte`, `.days_to_roll`; Undefined on non-futures streams
- Mark and index: `.mark`, `.index`, the contract's venue mark and index price; refused at start on a feed
  that serves none (see [streams](dsl/streams.md#stream-field-access))
- Trade flow, one bar back or more: `.buy_volume[n]`, `.sell_volume[n]` (aggressor volume),
  `.long_liq_volume[n]`, `.short_liq_volume[n]` (liquidated volume); the bar closing (`n` = 0) is refused (see
  [streams](dsl/streams.md#trade-flow-and-liquidations-aliasbuy_volume1-))
- Option contract: `.iv` (mark implied volatility), `.delta`, `.gamma`, `.vega`, `.theta` (per contract);
  refused at start on any other stream or on a feed without option marks (see
  [streams](dsl/streams.md#stream-field-access))
- Order-book depth: `.bid_depth`, `.ask_depth` (quantity on the ten best levels a side), `.book_imbalance`
  (−1 to 1); a venue stream only, refused at start on a feed whose gateway does not declare `depth` (see
  [streams](dsl/streams.md#order-book-depth-aliasbid_depth-ask_depth-book_imbalance))

### Indicators

- `ema(<value>, <period>)`, `sma(...)`, `rsi(...)`, `atr(<stream>, <period>)`,
  `vwap(<stream>.tick, <ticks>)`, ... — the full catalog with arities and warmup is in
  [Indicators](dsl/indicators.md).

### Operators

- Arithmetic: `+ - * /` and unary `-`; there is no `%` — use `mod(<a>, <b>)`
- Comparison: numeric `< <= > >= = == != <>`; strings support `=` and `!=`
- Boolean: `AND OR NOT`
- Null test: `<expr> IS NULL`, `<expr> IS NOT NULL` — binds at comparison precedence; always
  yields a boolean
- Crosses: `<a> CROSSES ABOVE <b>`, `CROSSES BELOW`
- Ranges: `<x> BETWEEN <lo> AND <hi>`, `<x> IN [<a>, <b>, <c>]`
- Conditional: `CASE WHEN <cond> THEN <expr> [WHEN ...] ELSE <expr> END` — `ELSE` is required

```qkt
LET size = CASE WHEN atr(btc, 14) > 200 THEN 0.05 ELSE 0.1 END
LET rem = mod(btc.close, 10)
RULES
    WHEN rem < 1 AND ema(btc.close, 50) IS NOT NULL AND btc.close BETWEEN 100 AND 200
    THEN BUY btc SIZING size
```

<!-- qkt-doc: illegal -->
```qkt
WHEN btc.close % 2 > 0 THEN LOG "no modulo operator"
```

### Account / position references

- `ACCOUNT.equity`, `.balance`, `.realized_pnl`, `.unrealized_pnl`, `.total_pnl`, `.dd_pct`,
  `.equity_peak`, `.open_positions_count`, `.trades_today`, `.wins_today`, `.losses_today`, ...
- `POSITION.<stream>` — current quantity (signed); `POSITION.<stream>.qty` is the same value
- `POSITION.<stream>.entry_price` (`avg_price`, `avg_entry_price`, or `POSITION_AVG_PRICE.<stream>`),
  `.pnl`, `.unrealized_pnl`, `.realized_pnl`, `.holding_duration`, `.mfe`, `.mae`, `.count`,
  `.longs`, `.shorts`, `.gross`, `.trades_today`, `.last_trade_at`
- `POSITION.<structure>` and `.pnl`, `.credit`, `.max_loss`, `.pnl_pct`, `.dte`, `.delta`, `.gamma`,
  `.vega`, `.theta` on an option structure alias ([Option structures](dsl/structures.md))

See [Expressions](dsl/expressions.md#position-references) for the full list.

## Defaults

`DEFAULTS` keys are the clause keywords `SIZING`, `STOP_LOSS`, `TAKE_PROFIT`, `TIF`,
`ORDER_TYPE` and `TRAILING`; a stop or target takes a child-price form (`AT`, `BY`, `PCT`, `RR`)
and `SYMBOL` stands for the acting rule's stream.

```qkt
DEFAULTS {
    SIZING = 1.0
    STOP_LOSS = BY atr(SYMBOL, 14) * 2
    TAKE_PROFIT = RR 3.0
    TIF = GTC
}
```

Apply to every action that doesn't override. An unknown key is a parse error:

<!-- qkt-doc: illegal -->
```qkt
DEFAULTS {
    stopLoss = childBy(atr(SYMBOL, 14) * 2)
}
```

## LET clauses

```qkt
LET dailyAtr = atr(btc, 14)
RULES
    WHEN btc.close > btc.close[1] + dailyAtr
    THEN BUY btc SIZING 0.1
```

Reusable expression aliases, inlined at compile time and evaluated per tick. `LET`s are resolved
by name, so one may reference another declared later; a `LET` that references itself is a
compile error.

## FOR EACH

```qkt
RULES
    FOR EACH s IN [btc, gold, aapl] DO
      WHEN s.close > s.open
       AND POSITION.s = 0
      THEN BUY s SIZING 0.1
```

Iterates over streams; the loop variable substitutes textually into the rule body, so this is one
rule per stream. The stream list needs its square brackets: `FOR EACH s IN btc, gold DO` is a parse
error (`expected '[' to open stream alias list`).

## See also

- [Architecture](../concepts/architecture.md) — what happens when a rule fires
- [Backtest model](../concepts/backtest-model.md) — what the engine guarantees
- <a href="/qkt/api/">API reference</a> — every parser AST node, every action class (built by CI)
