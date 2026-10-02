# Options

Multi-leg structures on a stored option chain, opened as one position and
sized by quantity or by % of equity at risk. Chain analytics arrive as
read-only `CHAIN:` streams; the chain a rule reads is the same chain the
gateway recorded, so backtest and live see identical snapshots.

```qkt
SYMBOLS
    chain = OPTIONS:DERIBIT.BTC_USDC EVERY 1h,
    iv = CHAIN:DERIBIT.BTC_USDC.atm_iv.30d EVERY 1h
RULES
    -- Sell a 25-delta put, buy the 10-delta put below it, risking 1% of equity.
    WHEN iv.close > 55
    THEN OPEN ps = OPTIONS ON DERIBIT:BTC_USDC {
        SELL PUT DELTA 0.25 DTE 30 TO 45,
        BUY PUT DELTA 0.10 SAME EXPIRY
    } SIZING 1 PCT RISK
```

- **Analytics**: `atm_iv` (at-the-money IV, in percent) and `skew_25d`
  (put-less-call wing IV) per tenor (`7d`, `30d`). Every indicator applies to
  them; `BUY iv` fails to compile — they are observations.
- **Leg choice** happens when the rule fires: nearest expiry in the `DTE`
  window, nearest Black-76 delta to target. A leg that finds nothing means the
  structure does not open at all — never partially.
- **Backtest reports** list every structure in `structures.csv` with legs,
  outcome (`CLOSED`, `UNWOUND`, `SETTLED`), credit and premium P&L.

Deep dives: [Option chain analytics](../reference/dsl/chain.md) ·
[Option structures](../reference/dsl/structures.md) ·
[qkt-venue-gateway](https://github.com/elitekaycy/qkt-venue-gateway)
