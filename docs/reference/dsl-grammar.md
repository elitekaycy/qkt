# DSL grammar

The qkt DSL is a small declarative language for trading strategies. This page documents every accepted keyword and shape.

!!! note "Living reference"
    Maintained alongside the parser. Discrepancies are bugs — file an issue.

## File-level shapes

Every `.qkt` file is one of:

- `STRATEGY` — a single strategy, the most common case
- `PORTFOLIO` — a composition of strategies with regime-gated activation (Phase 13b/14)

## STRATEGY

```qkt
STRATEGY <name> VERSION <int>

[ DEFAULTS { ... } ]

SYMBOLS
    <alias> = <BROKER>:<symbol> EVERY <timeframe>
    [ ... more streams ... ]

[ LET <name> = <expression> ... ]

RULES
    WHEN <condition>
    THEN <action> [ ; <action> ... ]
    [ ... more rules ... ]

[ FOR EACH <ident> IN <stream-list> DO ... ]
```

## PORTFOLIO

```qkt
PORTFOLIO <name> VERSION <int>

[ SYMBOLS ... ]

IMPORT '<path>' AS <alias> [ HOLD ]
[ ... more imports ... ]

RULES
    [ WHEN <condition> ] RUN <alias>
    [ ... more rules ... ]
```

`HOLD` keeps a child's positions when the supervisor deactivates it. Without HOLD, deactivation flattens.

## Stream declaration

```qkt
<alias> = <BROKER>:<symbol> EVERY <timeframe> [ WARMUP <N> BARS ]
```

- `<BROKER>` resolves against the broker registry: built-ins (`BACKTEST`, `BYBIT`, `INTERACTIVE`, `ALPACA`) plus any profile in `qkt.config.yaml` (e.g. `EXNESS`, `ICMARKETS`).
- `<symbol>` is the canonical symbol qkt sees (`EURUSD`, `BTCUSDT`). Per-broker translation (suffix, alias) happens at the broker boundary — see [broker integration](../concepts/broker-integration.md).
- `<timeframe>` is `1m`, `5m`, `15m`, `1h`, `1d`, etc. Drives the candle aggregator.
- `WARMUP <N> BARS` (optional) gates every rule that touches this stream until N closed candles arrive — see [streams](dsl/streams.md#per-stream-warmup-warmup-n-bars).

## Actions

| Action | Effect |
|---|---|
| `BUY <stream> [SIZING ...] [BRACKET ...] [STACK ...]` | Long entry |
| `SELL <stream> [SIZING ...] [BRACKET ...] [STACK ...]` | Short entry |
| `CLOSE <stream>` | Flatten position on the stream's symbol |
| `CLOSE_ALL` / `FLATTEN` | Flatten every open position (aliases) |
| `CANCEL <stream>` | Cancel pending orders on the stream's symbol |
| `CANCEL_ALL` | Cancel all pending orders |
| `LOG [LEVEL] "<msg>" [field=expr ...]` | Emit a log line — see [logging](../operations/logging.md) and [Phase 15](../phases/index.md) |

### Sizing

```qkt
SIZING <quantity>
SIZING <pct> PCT OF (EQUITY | BALANCE)
SIZING <N> PCT RISK                    -- sugar over SIZING RISK N/100; requires a BRACKET STOP_LOSS
SIZING <usd> USD
SIZING POSITION(<stream>)              -- full current position
```

### Bracket

```qkt
BRACKET STOP_LOSS BY <pct> PCT TAKE_PROFIT BY <pct> PCT
BRACKET STOP_LOSS AT <price-expr> TAKE_PROFIT AT <price-expr>
BRACKET STOP_LOSS BY ATR(<symbol>, 14) * 2
```

The compiler routes `BRACKET` to native broker support if the broker has the `BRACKET` capability (MT5, PaperBroker), else falls back to engine-managed SL/TP via separate orders.

### Stack (Phase 13a)

```qkt
BUY btc SIZING 0.1 STACK 3 SPACING 100 ABOVE WITHIN 1h
BUY btc STACK [ 0.1, 0.2 AT entry + 100, 0.3 LIMIT AT entry + 200 ]
```

Pyramiding — one signal becomes N price-triggered entries.

## Expressions

### Literals

- Number: `100`, `1.5`, `0.001`
- Boolean: `TRUE`, `FALSE`
- String (in `LOG` field positions only): `'BUY'`, `"BUY"`

### Stream fields

- `<stream>.close`, `.open`, `.high`, `.low`, `.volume`

### Indicators

- `ema(<stream>, <period>)`, `sma(...)`, `rsi(...)`, `atr(<symbol>, <period>)`, `vwap(...)`, etc.
- See `com.qkt.indicators.catalog.*` for the full list (linked from the <a href="/qkt/api/">API reference</a>)

### Operators

- Arithmetic: `+ - * / %`
- Comparison: `< <= > >= = !=`
- Boolean: `AND OR NOT`
- Null test: `<expr> IS NULL`, `<expr> IS NOT NULL` — binds at comparison precedence; always yields a boolean
- Crosses: `<a> CROSSES ABOVE <b>`, `CROSSES BELOW`
- Ranges: `<x> BETWEEN <lo> AND <hi>`, `<x> IN [<a>, <b>, <c>]`
- Conditional: `CASE WHEN <cond> THEN <expr> [ELSE <expr>] END`

### Account / position references

- `ACCOUNT.equity`, `ACCOUNT.balance`, `ACCOUNT.realized_pnl`, `ACCOUNT.unrealized_pnl`, `ACCOUNT.total_pnl`
- `POSITION.<stream>` — current quantity (signed)
- `POSITION.<stream>.entry_price`, `.pnl`, `.unrealized_pnl`, `.realized_pnl`, `.holding_duration`

## Defaults

```qkt
DEFAULTS {
    sizing = 1.0
    stopLoss = childBy(atr(SYMBOL, 14) * 2)
    takeProfit = childRr(3.0)
    tif = GTC
}
```

Apply to every action that doesn't override.

## LET clauses

```qkt
LET dailyAtr = atr(btc, 14)
RULES
    WHEN btc.close > btc.close - dailyAtr
    THEN BUY btc
```

Reusable expression aliases. Evaluated lazily per tick.

## FOR EACH

```qkt
FOR EACH s IN btc, gold, aapl DO
    rule { whenever(s.close gt 0) then { buy(s) } }
```

Iterates over streams; the loop variable substitutes textually into the rule body. Phase 11.

## See also

- [Architecture](../concepts/architecture.md) — what happens when a rule fires
- [Backtest model](../concepts/backtest-model.md) — what the engine guarantees
- <a href="/qkt/api/">API reference</a> — every parser AST node, every action class (built by CI)
