# STRATEGY block

The outermost envelope of every `.qkt` strategy file. Declares the strategy's name, version, and what it listens to.

## Shape

```qkt
STRATEGY <name> VERSION <integer>

[ DEFAULTS { <key> = <value> ... } ]

SYMBOLS
    <alias> = <BROKER>:<symbol> EVERY <timeframe>
    [ ... more streams ... ]

[ LET <name> = <expression> ]
[ ... more LETs ... ]

RULES
    WHEN <condition>
    THEN <action> [ ; <action> ... ]
    [ ... more rules ... ]

[ FOR EACH <iter_var> IN <stream-list> DO
    <rule body using iter_var>
]
```

Required blocks: `STRATEGY <name> VERSION <int>`, `SYMBOLS`, `RULES`. Optional: `DEFAULTS`, `LET`, `FOR EACH`. The order matters — `SYMBOLS` must precede `RULES` because rules reference stream aliases.

## Minimum valid strategy

```qkt
STRATEGY hello VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m

RULES
    WHEN btc.close > 0
    THEN LOG INFO "tick received"
```

This compiles and runs. It does nothing useful, but every part the parser requires is present.

## The header

```qkt
STRATEGY <name> VERSION <integer>
```

- `<name>` — identifier (letters, digits, underscores; must start with a letter). Becomes the strategy ID used by the daemon (`qkt list` shows it in the `NAME` column).
- `VERSION <integer>` — bump when you change the strategy semantically. Lets you keep multiple revisions in production with different IDs while preserving history.

**Naming convention:** snake_case lowercase, descriptive. `ema_cross_v2` not `MyStrat` or `s1`.

The version isn't enforced — there's no SemVer check or auto-migration. It's a marker for **you** to track changes between deployed revisions.

## `DEFAULTS { ... }` (optional)

Pre-sets values that any action in `RULES` can use without restating them.

```qkt
STRATEGY momo VERSION 1

DEFAULTS {
  sizing = 0.1
  stopLoss = atr(SYMBOL, 14) * 2
  takeProfit = atr(SYMBOL, 14) * 4
  tif = GTC
}

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m

RULES
    WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
    THEN BUY btc                          -- no explicit SIZING/BRACKET/TIF;
                                          -- defaults from above apply
```

The `SYMBOL` keyword inside `DEFAULTS` is a placeholder that gets substituted at compile time for each rule's stream. So `atr(SYMBOL, 14)` becomes `atr(btc, 14)` when the rule fires on `btc`.

See [LET and DEFAULTS](let-defaults.md) for full details on what's allowed inside.

## `SYMBOLS` block (required)

Declares every stream the strategy reads from. One alias per line.

```qkt
SYMBOLS
    btc  = BACKTEST:BTCUSDT EVERY 1m
    eur  = EXNESS:EURUSD EVERY 15m
    gold = EXNESS:XAUUSD EVERY 1h
```

Each entry:

- **Alias** (`btc`, `eur`, `gold`) — the name you use elsewhere in the strategy
- **Broker prefix** (`BACKTEST`, `EXNESS`, `BYBIT_SPOT`...) — resolves against the broker registry
- **Symbol** (`BTCUSDT`, `EURUSD`) — the venue-side instrument
- **Timeframe** (`EVERY 1m`, `EVERY 15m`...) — drives the candle aggregator

Multiple streams = a multi-asset strategy. See [Streams](streams.md) for the full broker prefix / timeframe / multi-stream details.

## `PARAM` declarations (optional)

Declare an overridable scalar with a default value. A portfolio can retune it via `OVERRIDE` without touching the child file.

```qkt
PARAM riskPct = 0.01      -- default; a portfolio OVERRIDE can change this per-alias
PARAM fastPeriod = 9
```

Use the name anywhere an expression is valid — conditions, sizing, indicator arguments:

```qkt
PARAM riskPct = 0.01

RULES
    WHEN btc.close > 100
    THEN BUY btc SIZING riskPct
```

Constraints:
- The value must be a literal: number, `TRUE`/`FALSE`, or a quoted string. No expressions.
- The type is fixed at declaration. A portfolio override that changes a number to a string is a compile-time error.
- `PARAM` names must be unique within a strategy.

## `LET` clauses (optional)

Name an expression once, reuse it in many rules. Evaluated lazily per tick.

```qkt
LET fastMa = ema(btc.close, 9)
LET slowMa = ema(btc.close, 21)
LET tradeable = account.equity > 5000

RULES
    WHEN fastMa CROSSES ABOVE slowMa AND tradeable
    THEN BUY btc SIZING 0.1
```

`LET` aliases are pure expressions — no side effects. They're substituted at compile time, so there's no runtime cost.

`LET` is also where you parameterize a strategy for sweeps:

```qkt
LET fastPeriod = 9          -- override with --param fastPeriod=12
LET slowPeriod = 21
```

The CLI's `--param key=value` flag overrides `LET` values at backtest time. Anything not overridden uses the literal in the file.

## `RULES` block (required)

The decision logic. A list of `WHEN ... THEN ...` pairs.

```qkt
RULES
    WHEN <condition>
    THEN <action> [ ; <more actions> ]
    [ WHEN ... THEN ... ]
```

Multiple rules are evaluated in order on every candle close. Each rule is **independent** — they don't share state and don't chain.

Multiple actions per rule are separated by `;` (or newline-separated, parser accepts both):

```qkt
WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
THEN
    CLOSE eur ;                      -- close any open EUR position
    BUY btc SIZING 0.1 ;             -- enter BTC long
    LOG INFO "switched to BTC"       -- audit log
```

Conditions are **edge-triggered by default**: the rule fires on the first tick where the condition transitions from false to true. See [Conditions](conditions.md) for level-triggered patterns.

## `FOR EACH` (optional, end-of-file)

Macro expansion that emits N independent rules from one template, one per stream in the list.

```qkt
SYMBOLS
    btc  = BACKTEST:BTCUSDT EVERY 1m
    eth  = BACKTEST:ETHUSDT EVERY 1m
    sol  = BACKTEST:SOLUSDT EVERY 1m

FOR EACH s IN btc, eth, sol DO
    WHEN ema(s.close, 9) CROSSES ABOVE ema(s.close, 21)
    THEN BUY s SIZING 0.1 BRACKET { STOP_LOSS BY 50 PCT, TAKE_PROFIT BY 100 PCT }
```

Compiles to three separate rules — one each for btc, eth, sol. The substitution is textual at AST level; no runtime cost.

See [FOR EACH](foreach.md) for caveats and limits.

## Common gotchas

- **`SYMBOLS` must come before `RULES`.** The parser reads top-down and validates stream references in rules against the declared symbols.
- **No forward references in `LET`.** A `LET` can use earlier `LET`s and any declared symbols, but not later `LET`s.
- **`VERSION` is informational.** Bumping it doesn't trigger migrations or warnings. It's a label you choose to maintain manually.
- **Comments**: `--` line comments (SQL-style) and `#` line comments both work. `/* ... */` block comments work too. Use whichever fits your aesthetic.

## Light vs heavy strategies

A minimal strategy fits in 8 lines (see "minimum valid" above). A complex one — see [the risk-managed example](../../examples/risk-managed.md) — runs ~30 lines with `LET`, `BRACKET`, conditional sizing, multi-condition entries. The DSL scales with the complexity of what you're doing; neither end is privileged.

## See also

- [Streams](streams.md) — broker prefixes, timeframes, multi-symbol
- [LET and DEFAULTS](let-defaults.md) — value reuse, action defaults, the SYMBOL placeholder
- [Conditions](conditions.md) — what goes after `WHEN`
- [Actions](actions.md) — what goes after `THEN`
- [PORTFOLIO files](portfolio.md) — composing multiple strategies into one
