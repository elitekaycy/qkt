# Mean-reversion — RSI fade with scale-out

Buy oversold dips and scale out at three price targets. The opposite philosophy from trend-following — this strategy thrives in choppy markets where price oscillates around a mean.

## What it does

- **Trades** EURUSD on 5-minute candles
- **Buys** when RSI(2) drops below 10 — extreme oversold reading
- **Exits in three layers** — 33% of the position at +0.1%, another 33% at +0.2%, the final 33% at +0.4%
- **Stop-loss** at 2× ATR(14) below entry — wider than the targets to give the trade room
- **No new entry** while a position is open

RSI(2) is a Larry Connors classic for short-term mean-reversion on liquid instruments. The scale-out is what differentiates this from a single take-profit — you bank certainty as the trade works, while leaving runners for the bigger move.

## The strategy file

```qkt title="strategies/mean-rev-rsi.qkt"
STRATEGY mean_rev_rsi VERSION 1

LET stopMul  = 2.0
LET t1       = 0.001     -- +0.1% target
LET t2       = 0.002     -- +0.2% target
LET t3       = 0.004     -- +0.4% target

SYMBOLS
    eur = BACKTEST:EURUSD EVERY 5m

RULES
    WHEN rsi(eur.close, 2) < 10
     AND position(eur) = 0           -- only enter when flat
    THEN BUY eur SIZING 0.1
         BRACKET {
           STOP_LOSS AT eur.close - atr(eur, 14) * stopMul,
           TAKE_PROFIT { -- scale out in three legs
             0.33 AT eur.close * (1 + t1),
             0.33 AT eur.close * (1 + t2),
             0.34 AT eur.close * (1 + t3)
           }
         }
         LOG INFO "oversold entry" rsi=rsi(eur.close, 2) price=eur.close
```

## How to run it

```bash
qkt fetch EURUSD --from 2024-01-01 --to 2024-04-01
qkt backtest strategies/mean-rev-rsi.qkt --from 2024-01-01 --to 2024-04-01
```

## What to expect

Mean-reversion on FX typically produces **many trades with a high win rate but small average winners**. The asymmetry is the opposite of trend-follow:

```text
Trades:           187
Final realized:   142.40
Win rate:         0.764
Sharpe (daily):   1.78
Max drawdown:     -68.50
Avg win:          1.21
Avg loss:         -3.85
```

Win rate is high (~76%) but the average loss is much larger than the average win — that's the cost of letting a stop-loss play out when mean-reversion fails (the rare "trend keeps going" event). A few bad days can eat a month of small wins.

## How to adapt it

### Different oversold threshold

`RSI < 10` is aggressive. For more conservative entries:

```qkt
WHEN rsi(eur.close, 2) < 5     -- only the most extreme readings
```

You'll get fewer trades but higher win rate.

### Short side (sell overbought)

```qkt
RULES
    WHEN rsi(eur.close, 2) > 90
     AND position(eur) = 0
    THEN SELL eur SIZING 0.1
         BRACKET {
           STOP_LOSS AT eur.close + atr(eur, 14) * stopMul,
           TAKE_PROFIT {
             0.33 AT eur.close * (1 - t1),
             0.33 AT eur.close * (1 - t2),
             0.34 AT eur.close * (1 - t3)
           }
         }
```

You'll typically want both sides — long-and-short on RSI extremes — running concurrently.

### Wider RSI period

`RSI(2)` is hair-trigger. Connors' original used `RSI(2)`; trying `RSI(3)` or `RSI(4)` reduces signal frequency without hurting accuracy much.

### Tighter scale-out

If the trade rarely reaches the third target, drop t3 and bump t1/t2 size:

```qkt
TAKE_PROFIT {
  0.5  AT eur.close * 1.001,
  0.5  AT eur.close * 1.002
}
```

### Add a trend filter (don't fade strong trends)

```qkt
WHEN rsi(eur.close, 2) < 10
 AND eur.close > sma(eur.close, 200)    -- only fade when above long MA
 AND position(eur) = 0
THEN BUY eur ...
```

This single filter often improves mean-reversion strategies dramatically — it stops you from buying oversold dips in genuine downtrends.

## Common gotchas

- **Mean-reversion fails in strong trends.** A 200-period MA filter (above) is the most common safeguard.
- **Slippage on scale-out targets.** Each target is a limit order; in fast markets, they may skip past without filling. The bracket retries until filled or stopped out.
- **Sample-size trap.** 50 trades is not enough to validate a mean-reversion strategy. Aim for 200+ across multiple market regimes.
- **Spread matters.** On EURUSD a 0.1% target is only 10 pips. If your broker's spread is 1.5 pips, that's 15% of your edge consumed by friction. Mean-reversion is brutal on high-spread venues.

## What this example demonstrates

- `position(stream)` for entry gating
- Multi-leg scale-out brackets
- ATR-based stop sizing
- Arithmetic in bracket prices (`eur.close * (1 + t1)`)
- LOG with structured key=value fields
