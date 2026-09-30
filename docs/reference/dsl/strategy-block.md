# STRATEGY block

The outermost envelope of every `.qkt` strategy file. Declares the strategy's name, version, and what it listens to.

## Shape

<!-- qkt-doc: grammar -->
```qkt
STRATEGY <name> VERSION <integer>

[ DEFAULTS { <KEY> = <value> ... } ]

SYMBOLS
    <alias> = <BROKER>:<symbol> EVERY <timeframe>
    [ ... more streams ... ]

[ PARAM <name> = <literal> ]
[ ... more PARAMs ... ]

[ LET <name> = <expression> ]
[ ... more LETs ... ]

[ SCHEDULE ... ]
[ SEQUENCE ... ]

[ RULES
    WHEN <condition>
    THEN <action> [ ; <action> ... ]
    [ ... more rules ... ]

    [ FOR EACH <iter_var> IN [ <alias>, ... ] DO
        <rule body using iter_var> ]
]
```

Required: `STRATEGY <name> VERSION <int>` and, for anything that compiles, `SYMBOLS` — a rule
that names a stream the file never declared is rejected with `Unknown stream alias`. Everything
else is optional, including `RULES` (a strategy with no rules parses and compiles; it just never
acts). The blocks come in the fixed order above; a section keyword after `RULES` is a located
parse error:

<!-- qkt-doc: illegal -->
```qkt
STRATEGY late_let VERSION 1
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m
RULES
    WHEN btc.close > 0 THEN LOG "tick"
LET threshold = 100
-- parse error: LET must come before RULES (line 6)
```

```qkt
STRATEGY quiet VERSION 1
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m
```

## Minimum valid strategy

```qkt
STRATEGY hello VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m

RULES
    WHEN btc.close > 0
    THEN LOG "tick received"
```

This compiles and runs. It does nothing useful, but every part the parser requires is present.

## The header

<!-- qkt-doc: grammar -->
```qkt
STRATEGY <name> VERSION <integer>
```

- `<name>` — identifier (letters, digits, underscores; starts with a letter or `_`). Becomes the strategy ID used by the daemon (`qkt list` shows it in the `NAME` column). The same rule applies to stream aliases, `LET` and `PARAM` names:

```qkt
STRATEGY _scratch VERSION 1
SYMBOLS
    _btc = BACKTEST:BTCUSDT EVERY 1m
LET _last = _btc.close
RULES
    WHEN _last > 0 THEN LOG "underscore names are fine"
```
- `VERSION <integer>` — bump when you change the strategy semantically. Lets you keep multiple revisions in production with different IDs while preserving history.

**Naming convention:** snake_case lowercase, descriptive. `ema_cross_v2` not `MyStrat` or `s1`.

The version isn't enforced — there's no SemVer check or auto-migration. It's a marker for **you** to track changes between deployed revisions.

## `DEFAULTS { ... }` (optional)

Pre-sets values that any action in `RULES` can use without restating them.

```qkt
STRATEGY momo VERSION 1

DEFAULTS {
  SIZING = 0.1
  STOP_LOSS = BY atr(SYMBOL, 14) * 2
  TAKE_PROFIT = BY atr(SYMBOL, 14) * 4
  TIF = GTC
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

<!-- qkt-doc: grammar -->
```qkt
RULES
    WHEN <condition>
    THEN <action> [ ; <more actions> ]
    [ WHEN ... THEN ... ]
```

Multiple rules are evaluated in order on every candle close. Each rule is **independent** — they don't share state and don't chain.

Multiple actions per rule are separated by `;`. A newline on its own does not separate actions:

```qkt
WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
THEN
    CLOSE eur ;                      -- close any open EUR position
    BUY btc SIZING 0.1 ;             -- enter BTC long
    LOG "switched to BTC"       -- audit log
```

<!-- qkt-doc: illegal -->
```qkt
STRATEGY missing_semicolon VERSION 1
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m
    eur = BACKTEST:EURUSD EVERY 1m
RULES
    WHEN btc.close > btc.open
    THEN CLOSE eur
         BUY btc SIZING 0.1
-- parse error: expected WHEN or FOR EACH in RULES, got 'BUY' (line 8)
```

Conditions are **edge-triggered by default**: the rule fires on the first tick where the condition transitions from false to true. See [Conditions](conditions.md) for level-triggered patterns.

## `FOR EACH` (optional, inside `RULES`)

Macro expansion that emits N independent rules from one template, one per stream in the list. It
sits inside `RULES` and may come before, between or after plain `WHEN` rules:

```qkt
SYMBOLS
    btc  = BACKTEST:BTCUSDT EVERY 1m
    eth  = BACKTEST:ETHUSDT EVERY 1m
    sol  = BACKTEST:SOLUSDT EVERY 1m

RULES
    FOR EACH s IN [btc, eth, sol] DO
      WHEN ema(s.close, 9) CROSSES ABOVE ema(s.close, 21)
      THEN BUY s SIZING 0.1 BRACKET { STOP_LOSS BY 1 PCT, TAKE_PROFIT BY 2 PCT }

    WHEN ACCOUNT.dd_pct > 5
    THEN CLOSE_ALL

    FOR EACH s IN [btc, eth, sol] DO
      WHEN ema(s.close, 9) CROSSES BELOW ema(s.close, 21) AND POSITION.s > 0
      THEN CLOSE s
```

The first `FOR EACH` compiles to three separate entry rules — one each for btc, eth, sol — and the
second to three exit rules, with the drawdown rule kept in between. The substitution is textual at
AST level; no runtime cost.

See [FOR EACH](foreach.md) for caveats and limits.

## Common gotchas

- **`SYMBOLS` must come before `RULES`.** The parser reads top-down; the compiler then checks every stream alias a rule mentions — in a condition, an action target or a `POSITION.<alias>` read — against the declared symbols and rejects an undeclared one as a located compile error.
- **`LET`s resolve by name, not by position.** A `LET` may reference one declared later in the file; what it cannot do is reference itself, directly or through another `LET` — recursion is a compile error. See [LET and DEFAULTS](let-defaults.md#composing-lets).
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
