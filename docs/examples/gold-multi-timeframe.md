# Multi-timeframe gold

A 4h trend filter, a 1h RSI dip, a 15m VWAP trigger and a volatility-regime gate, one position at a time,
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
   AND gold_m15.close > vwap(gold_m15.tick, 1000)
   AND percentile_rank(atr(gold_h1.candle, 14), 200) < 0.5
   AND POSITION.gold_m15 = 0

  THEN BUY gold_m15
       BRACKET { STOP LOSS BY 2.0, TAKE PROFIT BY 4.0 }
```

```bash
qkt parse gold_multi_tf.qkt
qkt backtest gold_multi_tf.qkt --from 2024-01-01 --to 2024-04-01
qkt run gold_multi_tf.qkt          # paper-trade it in the foreground
```

The same file runs against history, a paper broker or a real venue: you change the data source, not the
strategy. `vwap` needs a volume-bearing feed; MT5 tick-count volume is refused, see the
[indicator catalog](../reference/dsl/indicators.md).
