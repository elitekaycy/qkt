# Tutorial 3 — Composing strategies into a portfolio

You have one strategy that works. Now you want **two** running side by side — different markets, different logic, sharing one risk budget. This tutorial walks through qkt's `PORTFOLIO` files: how they compose child strategies, how the daemon fans out into per-child observability, and when to use regime-gated activation. About 40 minutes.

By the end you'll know:

- The difference between running two strategies independently and composing them into a portfolio
- How `IMPORT`, `RUN`, and `HOLD` work
- How regime-gating switches between strategies based on market conditions
- How operators monitor and control children individually

You need everything from Tutorials 1 and 2.

## The two ways to run multiple strategies

**Option A: independent deployments.** Each strategy is its own `.qkt` file deployed separately. The daemon hosts them; they don't know about each other.

```bash
qkt deploy strategies/momentum.qkt --as momo
qkt deploy strategies/meanrev.qkt  --as mean
```

**Option B: a portfolio.** One `.qkt` file declares both strategies as children. The daemon still runs each child as its own session, but they share a parent identity and the portfolio file can route between them.

```qkt
PORTFOLIO mybook VERSION 1
IMPORT 'momentum.qkt' AS momo
IMPORT 'meanrev.qkt'  AS mean
RULES
    WHEN TRUE  RUN momo
    WHEN TRUE  RUN mean
```

```bash
qkt deploy strategies/mybook.qkt --as mybook
```

Both options host two strategies in one daemon. The portfolio adds three things:

1. **Single deployable unit** — one file, one name, one cascade-stop
2. **Regime-gated activation** — `WHEN <condition> RUN <child>` activates a child only when its condition holds
3. **HOLD vs flatten on deactivation** — choose whether child positions close or stay open when their gate goes false

If you don't need any of those, Option A is simpler. The rest of this tutorial covers the portfolio case.

## Part 1 — Two children

Start by writing two simple strategies that we'll later compose.

### `strategies/trend.qkt`

```qkt title="strategies/trend.qkt"
STRATEGY trend VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1h

RULES
    WHEN ema(btc.close, 12) CROSSES ABOVE ema(btc.close, 48)
     AND POSITION.btc = 0
    THEN BUY btc SIZING 0.05
         BRACKET { STOP_LOSS BY 200, TAKE_PROFIT BY 600 }
         LOG "trend long"

    WHEN ema(btc.close, 12) CROSSES BELOW ema(btc.close, 48)
     AND POSITION.btc > 0
    THEN CLOSE btc
         LOG "trend exit"
```

This is a slower version of Tutorial 1's strategy — same idea, 1-hour candles, attached bracket.

### `strategies/meanrev.qkt`

```qkt title="strategies/meanrev.qkt"
STRATEGY meanrev VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1h

RULES
    WHEN rsi(btc.close, 2) < 10
     AND POSITION.btc = 0
    THEN BUY btc SIZING 0.05
         BRACKET {
           STOP_LOSS AT btc.close - atr(btc, 14) * 2,
           TAKE_PROFIT AT btc.close + atr(btc, 14) * 2
         }
         LOG "meanrev long" rsi=rsi(btc.close, 2)
```

This is the textbook Connors RSI(2) fade — buy when RSI drops below 10, exit via bracket.

**These two strategies are complementary.** Trend-following makes money in trending markets and loses small in ranges. Mean-reversion is the opposite. Running both simultaneously gives you all-weather coverage — but they conflict (both want to trade the same symbol).

Composing them into a portfolio with regime-gated activation lets only the **right one** run at a time.

## Part 2 — A regime-gated portfolio

Create `strategies/mybook.qkt`:

```qkt title="strategies/mybook.qkt"
PORTFOLIO mybook VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1h

IMPORT 'trend.qkt'    AS trend
IMPORT 'meanrev.qkt'  AS mean

# Regime detector: MA-spread scaled by ATR.
# Big positive or negative = strong trend; near zero = ranging.
LET maSpread = (ema(btc.close, 20) - ema(btc.close, 50)) / atr(btc, 14)

RULES
    # Strong trend in either direction: run the trend-follower
    WHEN maSpread > 1.5 OR maSpread < -1.5  RUN trend

    # Ranging market: run mean-reversion
    WHEN maSpread BETWEEN -0.5 AND 0.5  RUN mean

    # Transitional zone (0.5 to 1.5, or -1.5 to -0.5): no child active
```

Read it as:

- `PORTFOLIO mybook VERSION 1` — the parent's name
- `SYMBOLS` — the portfolio's own data feed (used by its regime detector, not by children)
- `IMPORT '...' AS alias` — pull in a child strategy and name it locally
- `LET maSpread = ...` — name a regime indicator
- `RULES` — `WHEN <condition> RUN <child>` activates a child when the condition holds

When `maSpread > 1.5` (strong uptrend) or `maSpread < -1.5` (strong downtrend), `trend` is active and `mean` is inactive. When `maSpread` sits near zero, `mean` is active and `trend` is inactive. In the transitional zone, **neither** runs — the regime is unclear and the portfolio chooses to sit out.

## Part 3 — How children behave when activated/deactivated

The default is **flatten on deactivate**. When a child's gate goes from true to false, the daemon:

1. Stops feeding ticks to that child
2. Sends `CLOSE_ALL` for every position the child holds
3. Marks the child inactive in `qkt list`

When the gate goes from false to true:

1. Marks the child active
2. Resumes feeding ticks
3. The child starts evaluating its rules from scratch — no carry-over state

If you'd rather **keep positions open** when a child deactivates (useful for long-horizon strategies whose entries take days to play out), add `HOLD`:

```qkt
IMPORT 'longterm.qkt' AS longTerm HOLD
```

A held child keeps its positions and lets them close via the child's own exit rules — the gate change just pauses new signal generation.

## Part 4 — Deploy and watch

Drop all three files into `./strategies/` and deploy the parent:

```bash
qkt daemon &
qkt deploy strategies/mybook.qkt --as mybook
qkt list
```

Expected output:

```text
NAME              KIND        PORT     TRADES   STATE
mybook            portfolio   47291    -        running
  trend           child       47292    0        inactive
  mean            child       47293    0        inactive
```

Children appear indented under the parent. Each has its own port, log file, and state. At startup both are inactive — the regime detector needs warmup before it can decide.

After ~50 1h candles (the longest indicator window), the parent's `maSpread` becomes computable. The active child flips based on the regime:

```text
NAME              KIND        PORT     TRADES   STATE
mybook            portfolio   47291    -        running
  trend           child       47292    3        active
  mean            child       47293    8        inactive
```

In this snapshot, `trend` is the active child (some sustained move detected) and `mean` is paused. Its 8 trades happened during an earlier regime when it was active.

## Part 5 — Operator control over children

Tail one child's log:

```bash
qkt logs mybook/trend -f
```

Status for a child:

```bash
qkt status mybook/trend
```

Force a child active (override the regime gate):

```bash
qkt start mybook/mean
```

This keeps `mean` active even when `maSpread` exits its band. Useful for testing one child in isolation. To clear the override:

```bash
qkt stop mybook/mean
```

Stop the whole portfolio (cascades to all children):

```bash
qkt stop mybook --flatten
```

## Part 6 — When portfolios are the wrong tool

Don't use a portfolio when:

- **Children trade different symbols and you want all of them running concurrently.** That's just multiple independent deployments — no need for the `RUN` machinery.
- **You need per-child risk rules.** Daemon-level risk applies to the whole portfolio today. Per-strategy risk rules land in Phase 25.
- **The children fight over the same position.** If `trend` and `meanrev` both try to long BTC on the same candle, the order arrival is undefined and you'll get one fill, not two. Regime-gating exists precisely to make this impossible — but only if your gate conditions are mutually exclusive.

## What you learned

- A `PORTFOLIO` file composes N child strategies into one deployable unit
- `IMPORT 'path' AS alias [HOLD]` declares a child
- `WHEN <condition> RUN <child>` activates the child when the condition holds; deactivates (and by default flattens) when it goes false
- `HOLD` preserves child positions on deactivation
- The daemon fans out into per-child sessions visible in `qkt list`
- Operators can override gates with `qkt start` / `qkt stop` on `parent/child` names
- Portfolios shine for **regime-gated** switching between competing strategies on one symbol

## What's next

- **[Examples: Regime-gated portfolio](../examples/portfolio.md)** — three-child portfolio example with more sophisticated regime detection
- **[Examples: Cross-broker portfolio](../examples/cross-broker.md)** — Bybit + MT5 children running concurrently (no regime gating)
- **[DSL reference: PORTFOLIO files](../reference/dsl/portfolio.md)** — every option, every gotcha
- **[Phase 14 — Portfolio v2](../phases/phase-14.md)** — the daemon's portfolio fan-out internals
