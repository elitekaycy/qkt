# Option chain analytics (`CHAIN:`)

A `CHAIN:` stream reads an implied-volatility analytic of a stored option chain as an ordinary
read-only stream. Its value is in its candle fields (`iv.close`), and every indicator applies to it.

## Declaring a chain stream

<!-- qkt-doc: grammar -->
```qkt
<alias> = CHAIN:<VENUE>.<ROOT>.<metric>.<tenor> EVERY <window>
```

```qkt
STRATEGY vol_spike VERSION 1
SYMBOLS
    iv = CHAIN:DERIBIT.BTC_USDC.atm_iv.30d EVERY 1h,
    skew = CHAIN:DERIBIT.BTC_USDC.skew_25d.30d EVERY 1h,
    put = DERIBIT:BTC_USDC_25DEC26_80000_P EVERY 1h
RULES
    -- Buy downside protection when 30-day volatility is cheap and puts are not yet bid.
    WHEN iv.close < 35 AND skew.close < 2 AND POSITION.put = 0
    THEN BUY put SIZING 0.1
```

`<ROOT>` is an option root declared under `options:` in `instruments.yaml` with a chain series
(`chains: trade | book`), and the chain days must be stored (`qkt fetch <VENUE>:<ROOT> --chains`).
`<tenor>` is a whole number of days (`7d`, `30d`).

## Metrics

| Metric | Value |
|---|---|
| `atm_iv` | At-the-money implied volatility, in percent: per expiry the mean mark IV at the listed strike nearest the forward, interpolated in total variance (`IV²·T`) between the expiries around the tenor |
| `skew_25d` | 25-delta put IV less 25-delta call IV, in IV points: per expiry each wing is interpolated linearly in Black-76 delta, and the skew linearly in time across expiries |

Only quotes with a mark IV no older than the root's `maxQuoteAgeMinutes` count. The forward per
expiry is the median `underlying` of those quotes: the expiry's forward on a book series, the index
at each trade on a trade series.

## When a value is absent

A stream has a value only at the chain's snapshot instants where the metric can be computed
honestly. A tenor outside the listed expiries is never extrapolated. An expiry without both 25-delta
wings gives no skew, and a chain of stale marks gives nothing. On free trade-built chains this is
common (often every other hour). Rules on the stream simply do not fire at those instants.

## Rules

- Chain streams are observations, like `HUB:` and `MACRO:`: each value closes as its own event
  candle, and negative values (a skew) are valid.
- They are read-only: `BUY iv` fails to compile.
- They run in backtests; live chain streams arrive with the phase 44 gateway.
