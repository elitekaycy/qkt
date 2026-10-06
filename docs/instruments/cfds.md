# CFDs

Contracts for difference on FX, gold, indices and more, traded live through MT5
via the `mt5-gateway`. A CFD stream is an ordinary stream: every indicator,
bracket, `STACK` and `PORTFOLIO` works on it unchanged.

```haskell
SYMBOLS
    gold = EXNESS:XAUUSD EVERY 15m WARMUP 100 BARS
RULES
    WHEN ema(gold.close, 9) CROSSES ABOVE ema(gold.close, 21)
     AND POSITION.gold = 0
    THEN BUY gold SIZING 0.10 BRACKET { STOP LOSS BY 1.5, TAKE PROFIT BY 3.0 }
```

- **Broker prefixes** are MT5 profiles: `EXNESS`, `ICMarkets`, `FTMO`,
  `PEPPERSTONE`, plus any custom profile in `qkt.config.yaml`.
- **Symbol names** are qkt-side; the profile's `symbolPolicy` translates to the
  venue name (`XAUUSD` → `XAUUSDm` on Exness automatically).
- Backtest the same file with `BACKTEST:XAUUSD` — you change the data source,
  not the strategy.

## Getting data

Nothing to download by hand: a backtest auto-fetches missing days from
Dukascopy (FX majors, metals, major indices) into `~/.qkt/data`, and refuses
to run on holes rather than handing you a clean-looking result on incomplete
data. For broker-exact bars, pre-fetch with `qkt fetch EXNESS:XAUUSD --tf 5m`.

Deep dives: [Getting & storing data](../how-to/backtest-data.md) ·
[Streams](../reference/dsl/streams.md) ·
[Broker prefixes](../reference/dsl/streams.md#broker-prefixes) ·
[Deploy live on Exness](../how-to/deploy-exness.md) ·
[Config reference](../reference/config-schema.md)
