# Option structures (`OPEN … = OPTIONS ON …`)

A structure opens several option legs as one position. Its contracts are chosen from the stored chain
when the rule fires.

<!-- qkt-doc: grammar -->
```qkt
OPEN <alias> = OPTIONS ON <VENUE>:<ROOT> { <leg>, … } SIZING <sizing>
<leg> := BUY|SELL CALL|PUT DELTA <0..1> (DTE <n> TO <m> | SAME EXPIRY)
```

```qkt
STRATEGY put_spread VERSION 1
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

## Choosing the legs

When the rule fires, each leg is chosen from the root's latest stored snapshot at or before that
moment:

- The first leg takes the nearest expiry whose days to expiry fall in its `DTE` window and that has a
  usable quote of its right. `SAME EXPIRY` legs use the first leg's expiry.
- Within that expiry, the quote whose Black-76 |delta| is nearest the target wins, ties going to the
  lower strike. Delta uses the expiry's forward (the median `underlying`) and rate 0.
- Only quotes with a positive mark IV, no older than the root's `maxQuoteAgeMinutes`, count.

A leg that finds nothing means the structure does not open at all, and the log says why. Part of a
structure is never opened.

## Sizing

- `SIZING <qty>` buys or sells that many contracts on every leg.
- `SIZING n PCT RISK` sizes so the structure's maximum loss is n% of equity. The maximum loss per
  contract is the legs' mark value less their worst expiry payoff. A structure with unbounded loss,
  such as a naked short call, cannot be sized by risk.

The size is floored to the venue's volume step. A size below the minimum opens nothing.

## Submission and failure

- The legs reach the venue together as market orders, buys published before sells. Each leg still
  fills on its own quotes, so publication order does not guarantee fill order. Their margin is judged
  as one position, so a credit spread needs its width less its credit even though its short leg
  alone would need more. If any leg is refused, none is sent.
- While a structure's legs are still pending, a later order's margin judges each pending leg on its
  own, not as the structure. This is conservative: it can refuse an order the filled structure would
  allow, never the reverse.
- Each leg fills at the next snapshot's bid or ask, like any option order. If a leg is then
  cancelled, for example when its quote has no side, the structure is unwound: still-working legs are
  cancelled, and filled legs are closed at market, shorts first.

## Requirements

- The root must be fed by an `OPTIONS:<VENUE>.<ROOT>` stream. The strategy refuses to compile
  otherwise, so legs can never reach another broker.
- Structures run in backtests; live structures arrive with the phase 44 gateway.
