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
| `atm_iv` | At-the-money implied volatility, in percent. Per expiry, IV is interpolated linearly in strike between the nearest strikes at or below and at or above the forward, both within 10% of it. Across expiries it is interpolated in total variance (`IV²·T`) around the tenor |
| `skew_25d` | 25-delta put IV less 25-delta call IV, in IV points. Per expiry, each wing is interpolated linearly in Black-76 delta (at rate 0) between quotes within 0.15 of ±0.25. Across expiries the skew is interpolated linearly in time |

Only catalogued quotes with a positive mark IV no older than the root's `maxQuoteAgeMinutes` count. The forward per
expiry is the median `underlying` of those quotes: the expiry's forward on a book series, the index
at each trade on a trade series.

## When a value is absent

A stream has a value only at the chain's snapshot instants where the metric can be computed
honestly. A tenor outside the expiries that carry the value is never extrapolated, and a value is
never read from a distant strike or delta. An expiry without quotes on both sides of the forward
gives no ATM IV, one without both 25-delta wings gives no skew, and stale marks give nothing. On free
trade-built chains this is common: on 25–26 September 2026, 1-day ATM IV was defined at 9 of 48
hours. Rules on the stream simply do not fire at those instants. A stream that is never defined in a
run is not an error.

## Rules

- Chain streams are observations, like `HUB:` and `MACRO:`: each value closes as its own event
  candle, and negative values (a skew) are valid.
- They are read-only: `BUY iv` fails to compile.
- They run in backtests and live. Live, the values come from the chain the gateway account records
  from a fed root (a root declared `chains: book`), up to 5 seconds after each snapshot, so a live
  strategy that reads `CHAIN:<V>.<R>...` must also declare `OPTIONS:<V>.<R>`; it is refused at start
  otherwise.

## Feeding a whole root (`OPTIONS:`)

`chain = OPTIONS:<VENUE>.<ROOT> EVERY <window>` feeds every quoted contract of an option root into the
run. Each one is marked at its own quotes, and contracts picked at run time (by structures) are
routed to the option venue. The stream itself carries no candles and cannot be traded.

```qkt
STRATEGY fed_root VERSION 1
SYMBOLS
    chain = OPTIONS:DERIBIT.BTC_USDC EVERY 1h,
    put = DERIBIT:BTC_USDC_25DEC26_80000_P EVERY 1h
RULES
    WHEN put.close > 0 AND POSITION.put = 0
    THEN BUY put SIZING 0.1
```

- A contract that is both declared and in a fed root receives each quote once, from the root feed.
- Contracts held to expiry get a settlement print at their intrinsic value, as with a declared
  contract.
- The run checks the root's chain days the same way as for a chain analytics stream.

## Event analytics (`CHAIN:<VENUE>.<FAMILY>.<metric>.<event>`)

An event family (see event slots in [streams](streams.md)) has its own `CHAIN:` metrics,
read over every contract of one event: `front` (the open event that stops trading first) or `next`. They follow
the same rules as the option metrics above: read-only, observations, a value only where it can be computed
honestly.

<!-- qkt-doc: grammar -->
```qkt
<alias> = CHAIN:<VENUE>.<FAMILY>.<metric>.<front|next> EVERY <window>
```

```qkt
STRATEGY ladder_median VERSION 1
SYMBOLS
    median = CHAIN:KALSHI.KXINXU.implied_median.front EVERY 1m,
    breaks = CHAIN:KALSHI.KXINXU.ladder_breaks.front EVERY 1m,
    atm = KALSHI:KXINXU@front.atm EVERY 1m
RULES
    -- A ladder whose quotes contradict each other: the coin-flip strike is mispriced against its neighbours.
    WHEN breaks.close > 0 AND POSITION.atm = 0
    THEN BUY atm SIZING 5
```

| Metric | Value | Events |
|---|---|---|
| `implied_median` | the strike where the ladder's YES mids cross 0.5, linear between strikes | ladders |
| `implied_mean` | Σ bucket midpoint × YES mid, over Σ YES mid (the mids normalised to sum to 1) | exclusive, bounded buckets |
| `overround` | Σ YES asks − 1 (negative: buying every bucket costs less than the payout) | exclusive |
| `underround` | 1 − Σ YES bids, an absent bid counting 0 | exclusive |
| `ladder_breaks` | adjacent strikes where the higher strike's YES bid exceeds the lower strike's YES ask | ladders |

A ladder is an event of two or more non-exclusive contracts whose strikes are all "above" (`greater`,
`greater_or_equal`) a floor; an exclusive event is one whose contracts partition the outcomes (range buckets,
candidates, teams). A value is taken after every instant at which the family quotes, from each contract's latest
quote. It is Undefined, and the instant has no tick, when the event does not have the shape the metric needs, when a
mid is needed and a contract lacks a two-sided quote (`implied_mean`), when an ask is needed and one is absent
(`overround`), when a contract has never quoted (`underround`), when no adjacent pair has both sides
(`ladder_breaks`), or when the mids never cross 0.5 (`implied_median`). An open-ended tail bucket ("below 70") has no
midpoint, so `implied_mean` is Undefined on an event that has one. Event streams run in backtests, from the family's
stored quote ticks.
