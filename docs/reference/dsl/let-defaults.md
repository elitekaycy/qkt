# LET and DEFAULTS

Two ways to factor out repeated values so your strategy stays readable as it grows.

- **`LET`** — name an expression, reuse it in conditions and actions
- **`DEFAULTS`** — pre-set fields that every action inherits unless overridden

They look similar but serve different purposes. `LET` is for values you compute and reference in conditions; `DEFAULTS` is for repeated action parameters (sizing, bracket, time-in-force).

## `LET <name> = <expression>`

Binds a name to an expression. The expression is evaluated each time the name is referenced (lazy), so it always sees fresh data.

### Basic — name an indicator

```qkt
LET fastMa = ema(btc.close, 9)
LET slowMa = ema(btc.close, 21)

RULES
    WHEN fastMa CROSSES ABOVE slowMa
    THEN BUY btc SIZING 0.1
```

Without `LET` the rule reads `ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)` — fine for two indicators, ugly for more.

### Tunable parameters

```qkt
LET fast = 9
LET slow = 21
LET rrRatio = 3.0

RULES
    WHEN ema(btc.close, fast) CROSSES ABOVE ema(btc.close, slow)
    THEN BUY btc SIZING 0.1
         BRACKET {
           STOP_LOSS BY 50 PCT,
           TAKE_PROFIT BY 50 * rrRatio PCT
         }
```

The `--param` CLI flag overrides any `LET`:

```bash
qkt backtest strategy.qkt --param fast=12 --param slow=26 --param rrRatio=2.5
```

The file's `LET` value is the default; `--param` only applies to the run. This is how parameter sweeps work — see [Run a parameter sweep](../../how-to/parameter-sweep.md).

### Composing `LET`s

A `LET` can reference earlier `LET`s and any declared streams:

```qkt
LET vol      = atr(btc, 14)
LET volStop  = btc.close - vol * 2     -- references vol
LET volTarget= btc.close + vol * 4

RULES
    WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
    THEN BUY btc SIZING 0.1
         BRACKET {
           STOP_LOSS AT volStop,
           TAKE_PROFIT AT volTarget
         }
```

Order matters — declare `vol` before `volStop` references it. Forward references (using a `LET` defined later) is a parse error.

### Booleans

`LET` works for boolean expressions too:

```qkt
LET inUptrend  = ema(btc.close, 20) > ema(btc.close, 100)
LET notHalted  = account.equity > 5000
LET canTrade   = inUptrend AND notHalted

RULES
    WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21) AND canTrade
    THEN BUY btc SIZING 0.1
```

This factors out repeatable filter logic. You can reuse `canTrade` in every rule.

### What `LET` can't do

- **No state.** Each evaluation is fresh; you can't accumulate.
- **No recursion.** A `LET` can't reference itself.
- **No side effects.** `LET` doesn't do I/O, doesn't emit signals.
- **No conditional definition.** No `LET fast = IF something THEN 9 ELSE 12`. (Use a `CASE` expression on the RHS instead — see [Expressions](expressions.md).)

## `DEFAULTS { ... }`

Pre-sets parameters for every action in the file. Anything an action doesn't explicitly state falls back to the default.

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
    THEN BUY btc                          -- no explicit SIZING/BRACKET/TIF
                                          -- → all come from DEFAULTS
```

### Available default keys

| Key | What it sets | Maps to |
| --- | --- | --- |
| `sizing` | Default position size | `SIZING <value>` |
| `stopLoss` | Default stop-loss distance/price | inside `BRACKET { STOP_LOSS ... }` |
| `takeProfit` | Default take-profit | inside `BRACKET { TAKE_PROFIT ... }` |
| `tif` | Default time-in-force | `TIF GTC / IOC / FOK / DAY` |
| `magic` | Default broker magic number | broker-side audit tag |

### The `SYMBOL` placeholder

Inside `DEFAULTS`, the literal `SYMBOL` substitutes for whatever stream alias the action is acting on.

```qkt
DEFAULTS {
  stopLoss = atr(SYMBOL, 14) * 2     -- → atr(btc, 14) * 2 for rules on btc,
                                     --   atr(eur, 14) * 2 for rules on eur
}
```

This is how one default works across multiple streams in a multi-asset strategy. Without `SYMBOL`, you'd have to write per-stream defaults.

**`SYMBOL` is illegal outside `DEFAULTS`.** Using it in a rule directly produces a parse error.

### Override at the action level

Action-level values always win over defaults:

```qkt
DEFAULTS { sizing = 0.1 }

RULES
    WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
    THEN BUY btc                              -- uses default sizing = 0.1

    WHEN strong_signal_condition
    THEN BUY btc SIZING 0.5                    -- overrides default with 0.5
```

### Partial defaults

You can set just one default and let others stay required:

```qkt
DEFAULTS {
  tif = IOC                          -- always use IOC time-in-force
}

RULES
    WHEN ... THEN BUY btc SIZING 0.1   -- SIZING is per-rule; TIF comes from DEFAULTS
```

## When to use which

- **`LET`** — for **values you reference in conditions or in arithmetic inside actions.** "The fast MA," "the volatility scalar," "the trend-up flag."
- **`DEFAULTS`** — for **action parameters that repeat across many rules.** "Every entry sizes at 0.1," "every stop is 2× ATR."

If you find yourself writing the same `BRACKET { ... }` clause in five rules, hoist it into `DEFAULTS`. If you find yourself writing `ema(btc.close, 9)` in three conditions, hoist it into `LET`.

## Common gotchas

- **`SYMBOL` only in `DEFAULTS`.** Don't try to use it in `RULES`.
- **Forward references in `LET` fail.** Order matters; declare what you reference before you reference it.
- **`DEFAULTS` doesn't apply to engine-managed wrappers.** A `STACK` layer-list with explicit per-layer overrides shadows the default `sizing`. A `TIME_EXIT` wrapper around a market order takes the inner's `tif`, not the default.
- **Default `magic` is broker-specific.** Most strategies leave this alone; the broker profile in `qkt.config.yaml` sets it. Only override if you have a multi-strategy account where you need to tag orders distinctly within one daemon.

## See also

- [STRATEGY block](strategy-block.md) — where `LET` and `DEFAULTS` sit in the file
- [Conditions](conditions.md) — the most common place to use `LET`-bound booleans
- [Actions](actions.md) — what `DEFAULTS` keys map to
- [Run a parameter sweep](../../how-to/parameter-sweep.md) — `--param` override flow
