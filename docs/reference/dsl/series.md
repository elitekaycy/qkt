# Synthetic series

`SERIES` declarations expose engine-owned state as read-only candle streams. The v1 source is account equity.

```qkt
SYMBOLS
  gold = BACKTEST:XAUUSD EVERY 1m
  eq = SERIES ACCOUNT.EQUITY EVERY 1h

RULES
  WHEN gold.close > ema(gold.close, 20)
   AND eq.close > ema(eq.close, 24)
  THEN BUY gold SIZING 0.5 PCT RISK
```

## Account equity

```qkt
<alias> = SERIES ACCOUNT.EQUITY EVERY <timeframe>
```

- `<timeframe>` must be at least `1m`.
- The stream is read-only: `BUY eq`, `SELL eq`, `CLOSE eq`, and `CANCEL eq` are compile errors.
- The runtime samples the account equity tracker into synthetic OHLC candles on the engine clock.
- `eq.close`, `eq.open`, `eq.high`, `eq.low`, indicators, `CASE`, `LET`, and math expressions work like they do on market streams.
- The v1 series is account-level equity. Per-strategy equity series are not implemented yet.

## Warmup

Indicators over equity series use normal warmup behavior. For example, `ema(eq.close, 24)` stays unavailable until 24 closed equity bars have been sampled.

Restart behavior in v1 is fresh warmup: persisted equity history is not replayed into the synthetic series. The strategy resumes with an empty equity-series buffer and warms up again from live/replayed samples.

## Feedback

Strategies conditioning on their own equity is intentional, but it is a feedback loop: sizing affects equity, and equity affects future sizing. Keep the series interval slow enough for the meta-filter you are expressing.
