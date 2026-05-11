# Breakout — Donchian channels

Classic Turtle-style breakout. Buy when price closes above the highest high of the last N days; exit when it closes below the lowest low of the last M days. This is the strategy that powered Richard Dennis's Turtle Traders in the 1980s.

## What it does

- **Trades** Bitcoin on 1-hour candles
- **Buys** when the close exceeds the highest close of the prior 20 candles
- **Exits** when the close drops below the lowest close of the prior 10 candles
- **Risk-sized** at 0.5% of equity per trade using ATR(14) for stop distance
- **One position at a time** — won't add to winners

Breakout systems make most of their money in 2–3 trades per year — long sustained moves. The other 90% of trades are small losers ("noise"). Win rate is low (30–40%) but the winners are huge.

## The strategy file

```qkt title="strategies/breakout-donchian.qkt"
STRATEGY breakout_donchian VERSION 1

LET entryWindow = 20
LET exitWindow  = 10
LET riskPct     = 0.5    -- risk 0.5% of equity per trade

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1h

RULES
    WHEN btc.close > highest(btc.close, entryWindow)
     AND position(btc) = 0
    THEN BUY btc SIZING riskPct PCT RISK
         STOP_LOSS AT btc.close - atr(btc, 14) * 2
         LOG INFO "breakout long" high=highest(btc.close, entryWindow) atr=atr(btc, 14)

    WHEN btc.close < lowest(btc.close, exitWindow)
     AND position(btc) > 0
    THEN CLOSE btc
         LOG INFO "exit on lowest-low"
```

A few things to notice:

- **`SIZING riskPct PCT RISK`** — sizes the position so that hitting the stop loses exactly 0.5% of equity. The engine computes size from `riskPct * equity / (entry - stop)`.
- **No bracket here** — the take-profit logic is the exit rule (lowest-low), not a price target. We use a bare `STOP_LOSS` instead of a full bracket.
- **`highest(stream.close, N)`** and **`lowest(stream.close, N)`** are the Donchian channel functions; they return the rolling max/min over the last N closed candles.

## How to run it

```bash
qkt fetch BTCUSDT --from 2023-01-01 --to 2024-12-31 --resolution 1h
qkt backtest strategies/breakout-donchian.qkt --from 2023-01-01 --to 2024-12-31
```

A 2-year backtest is the minimum for a breakout system — they need enough time to catch at least a few real moves.

## What to expect

```text
Trades:           48
Final realized:   3,420.00
Win rate:         0.354
Sharpe (daily):   0.78
Max drawdown:     -680.50
Avg win:          218.40
Avg loss:         -64.20
Largest win:      1,240.00
```

Note the asymmetry — average win is 3.4× average loss. Sharpe is modest (0.78) because the equity curve is lumpy: long flat periods punctuated by big winners. This is the breakout signature.

## How to adapt it

### Shorter / longer breakout windows

The original Turtle System 1 used `(20, 10)`. System 2 used `(55, 20)`:

```qkt
LET entryWindow = 55
LET exitWindow  = 20
```

Longer windows = fewer signals, larger captured moves, longer drawdowns between winners.

### Add a long-trend filter

The Turtle System never entered against the long-term trend. Add a 200-period filter:

```qkt
WHEN btc.close > highest(btc.close, entryWindow)
 AND btc.close > sma(btc.close, 200)        -- in long-term uptrend
 AND position(btc) = 0
THEN BUY btc SIZING 0.5 PCT RISK
     STOP_LOSS AT btc.close - atr(btc, 14) * 2
```

### Short side

```qkt
WHEN btc.close < lowest(btc.close, entryWindow)
 AND btc.close < sma(btc.close, 200)
 AND position(btc) = 0
THEN SELL btc SIZING 0.5 PCT RISK
     STOP_LOSS AT btc.close + atr(btc, 14) * 2

WHEN btc.close > highest(btc.close, exitWindow)
 AND position(btc) < 0
THEN CLOSE btc
```

### Add a pyramid

The Turtles added to winners every 0.5 ATR of favorable move. This is what [STACK pyramiding](pyramiding.md) is for — see that example.

### Different asset

Breakouts work especially well on commodities (oil, gold), futures indices, and crypto. Less well on mean-reverting instruments like equity-index ETFs. Try gold:

```qkt
SYMBOLS
    xau = BACKTEST:XAUUSD EVERY 1h
```

## Common gotchas

- **Long flat periods are normal.** A 6-month drawdown without new highs is part of breakout. Don't quit during the dry spell — that's usually right before the winner.
- **Slippage on entry.** A breakout fires at the close above the high. The market open the next bar may be much higher. Paper-test against your real broker's slippage profile.
- **`highest(close, N)` excludes the current bar.** When you ask "highest close of the last 20 bars," qkt looks at the 20 prior closes, not including the bar you're checking against. This is the correct definition — otherwise the rule could never fire (current close can't exceed itself).
- **Survivorship bias on long histories.** Bitcoin's history is a long uptrend with two crashes. A breakout strategy looks great on 2017–2024 data; it may not work the same in 2025+ if the trend regime changes.

## What this example demonstrates

- `highest()` / `lowest()` rolling-extreme indicators
- Risk-based sizing (`SIZING N PCT RISK`)
- Bare `STOP_LOSS` (no `BRACKET` because the take-profit is rule-driven, not price-driven)
- Multiple rules cooperating (one for entry, another for exit)
- `position(stream) = 0` / `> 0` for entry/exit gating
