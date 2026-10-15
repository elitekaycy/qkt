# Multi-timeframe gold

A 4h trend filter, a 1h RSI dip, a 15m trigger and a volatility-regime gate, one position at a time,
with an attached stop-loss and take-profit bracket.

```qkt title="gold_multi_tf.qkt"
STRATEGY gold_multi_tf VERSION 1

DEFAULTS { SIZING = 0.10 }

SYMBOLS
  gold_m15 = BACKTEST:XAUUSD EVERY 15m WARMUP 100 BARS
  gold_h1  = BACKTEST:XAUUSD EVERY 1h WARMUP 100 BARS
  gold_h4  = BACKTEST:XAUUSD EVERY 4h WARMUP 100 BARS
  gold_d1  = BACKTEST:XAUUSD EVERY 1d WARMUP 100 BARS

RULES
  WHEN ema(gold_h4.close, 50) > ema(gold_h4.close, 200)
   AND rsi(gold_h1.close, 14) < 30
   AND gold_m15.close > ema(gold_m15.close, 20)
   AND percentile_rank(atr(gold_h1.candle, 14), 200) < 0.5
   AND POSITION.gold_m15 = 0

  THEN BUY gold_m15
       BRACKET { STOP LOSS BY 2.0, TAKE PROFIT BY 4.0 }
```

```bash
qkt parse gold_multi_tf.qkt
qkt backtest gold_multi_tf.qkt --from 2026-07-06 --to 2026-08-29 --no-fetch
qkt run gold_multi_tf.qkt          # paper-trade it in the foreground
```

qkt 0.56.2, eight weeks of `XAUUSD` ticks, the default paper broker and a 10,000 USD account:

```text
Backtest   2026-07-06 to 2026-08-28 (47 trading days)
Account    10,000.00 USD

Result
  Profit             -98.18 USD   (-1.0%)
  Round trips        5 closed, 0 won (0.0%)
  Open at the end    none
  Worst drop         1.0%    biggest fall from a peak
  Worst day          1.0%
  Sharpe ratio       -5.84     return per unit of risk
  Costs              none modeled (no spread, commission or swap)
```

Four filters at once trade rarely: five entries in eight weeks, and all five closed at their stop, for losses of 18.75 to 21.10 USD
each. That is far too few trades to judge the idea; the file is here to show how streams on four
timeframes of one symbol combine in one rule.

The same file runs against history, a paper broker or a real venue: you change the data source, not the
strategy.

!!! note "Why not VWAP"
    An earlier version of this page triggered on `gold_m15.close > vwap(gold_m15.tick, 1000)`. It
    parses, but VWAP needs traded volume and gold CFD ticks carry none, so the condition is never
    true and the backtest made no trades at all. With a volume-bearing feed, swap the trigger back;
    MT5 tick-count volume is refused, see the [indicator catalog](../reference/dsl/indicators.md).
