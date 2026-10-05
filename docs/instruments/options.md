# Options

Multi-leg structures on a stored option chain, opened as one position and
sized by quantity or by % of equity at risk. Chain analytics arrive as
read-only `CHAIN:` streams; the chain a rule reads is the same chain the
gateway recorded, so backtest and live see identical snapshots.

```haskell
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
- **One contract's IV and Greeks**: an option contract stream reads `c.iv`, `c.delta`, `c.gamma`,
  `c.vega` and `c.theta` before anything is held (see
  [streams](../reference/dsl/streams.md#stream-field-access)); live from a gateway declaring
  `option_marks`, in a backtest from the root's chain series.
- **Leg choice** happens when the rule fires: nearest expiry in the `DTE`
  window, nearest Black-76 delta to target. A leg that finds nothing means the
  structure does not open at all — never partially.
- **Backtest reports** list every structure in `structures.csv` with legs,
  outcome (`CLOSED`, `UNWOUND`, `SETTLED`), credit and premium P&L.

## Getting data

Deribit's public API serves chains without an account (linear `<COIN>_USDC`
options only). Fetch the catalog (every listed and expired contract plus
delivery prices), then history built from the venue's trade history — or
snapshot the live book on a schedule to build bid/ask history:

```bash
qkt fetch DERIBIT:BTC_USDC --catalog
qkt fetch DERIBIT:BTC_USDC --chains --from 2026-09-24 --to 2026-09-30
qkt fetch DERIBIT:BTC_USDC --chains --live
```

Deep dives: [Getting & storing data, Scenario 2c (option chains)](../how-to/backtest-data.md) ·
[Option structures](../reference/dsl/structures.md) ·
[qkt-venue-gateway](https://github.com/elitekaycy/qkt-venue-gateway)
