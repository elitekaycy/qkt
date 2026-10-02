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

- A leg with a `DTE` window takes the nearest expiry whose days to expiry fall in it and that has a
  usable quote of its right. `SAME EXPIRY` legs use the first leg's expiry, and the first leg must
  give a window.
- Within that expiry, the quote whose Black-76 |delta| is nearest the target wins, ties going to the
  lower strike. Delta uses the expiry's forward (the median `underlying`) and rate 0.
- Everything is judged at the moment the rule fires, not when the snapshot was taken. A contract
  expired by then is skipped. Days to expiry count from then. A quote's age is its age in the
  snapshot plus the time since, and it must be within the root's `maxQuoteAgeMinutes`. Only quotes
  with a positive mark IV count.

A leg that finds nothing means the structure does not open at all, and the log says why. Part of a
structure is never opened.

## Sizing

- `SIZING <qty>` buys or sells that many contracts on every leg.
- `SIZING n PCT RISK` sizes so the structure's maximum loss is n% of equity. The maximum loss per
  contract is the legs' mark value less their worst expiry payoff, judged per expiry. A structure with unbounded loss,
  such as a naked short call, cannot be sized by risk.

The size is floored to the venue's volume step. A size below the minimum opens nothing.

## Submission and failure

- The legs reach the venue together as market orders, buys published before sells. If the venue
  refuses a leg as it arrives, the legs behind it are not sent, so a short never leaves without its
  wing. Each leg still fills on its own quotes, so publication order does not guarantee fill order. Their margin is judged
  as one position, so a credit spread needs its width less its credit even though its short leg
  alone would need more. If any leg is refused, none is sent.
- While a structure's legs are still pending, a later order's margin judges each pending leg on its
  own, not as the structure. This is conservative: it can refuse an order the filled structure would
  allow, never the reverse.
- Each leg fills at the next snapshot's bid or ask, like any option order. If a leg is then
  cancelled, for example when its quote has no side, the structure is unwound: still-working legs are
  cancelled, and filled legs are closed at market, shorts first.

## Reading a structure

An alias holds one live structure at a time. It is live from the moment its legs are accepted until
nothing of it is held and no order of it is working. While it is live, another `OPEN` of the same
alias fires nothing.

<!-- qkt-doc: grammar -->
```qkt
POSITION.ps            -- contracts per leg while ps is live, else 0 (.qty and .quantity are the same)
POSITION.ps.credit     -- opening premium received, Σ −quantity × contract size × entry price; negative for a debit
POSITION.ps.max_loss   -- worst expiry loss from opening; Undefined when unbounded
POSITION.ps.pnl        -- premium P&L before fees: closed and settled legs plus held legs at their marks
POSITION.ps.pnl_pct    -- 100 × pnl ÷ |credit|: the share of the credit kept, or the gain on the debit paid
POSITION.ps.dte        -- days, fractional, to the nearest expiry of a held leg
POSITION.ps.delta      -- Σ quantity × contract size × Black-76 delta, in units of the underlying
POSITION.ps.gamma      -- the same for gamma, per unit of the underlying's price
POSITION.ps.vega       -- in account currency per volatility point
POSITION.ps.theta      -- in account currency per calendar day
```

- Every field except `POSITION.ps` itself is `Undefined` unless every leg has filled. Rules that test a
  field therefore wait for the structure to open.
- The Greeks come from the latest snapshot at or before the clock. Each leg uses its own quote's
  mark IV, its expiry's forward (the median `underlying`), rate 0, and time to expiry from the clock.
  A held leg without a usable quote (IV > 0, within `maxQuoteAgeMinutes`) makes them `Undefined`,
  never 0.
- `pnl` uses the same marks as equity. Fees are excluded: the account's P&L includes them.
- `pnl_pct` divides by the credit or debit, however small. A structure opened for almost nothing (a
  risk reversal near zero cost) reads very large percentages; test `pnl` for such structures.
- `max_loss` judges each expiry on its own, as the margin does. A calendar whose long leg expires
  first is measured with its short leg alone.

## Closing a structure

```qkt
STRATEGY managed_put_spread VERSION 1
SYMBOLS
    chain = OPTIONS:DERIBIT.BTC_USDC EVERY 1h,
    iv = CHAIN:DERIBIT.BTC_USDC.atm_iv.30d EVERY 1h
RULES
    WHEN POSITION.ps = 0 AND iv.close > 55
    THEN OPEN ps = OPTIONS ON DERIBIT:BTC_USDC {
        SELL PUT DELTA 0.25 DTE 30 TO 45,
        BUY PUT DELTA 0.10 SAME EXPIRY
    } SIZING 1 PCT RISK
    -- Take half the credit, or leave three weeks before expiry.
    WHEN POSITION.ps.pnl_pct >= 50 OR POSITION.ps.dte <= 21
    THEN CLOSE ps
```

- `CLOSE ps` closes every held, unexpired leg with market orders, as one group. Their margin is
  judged together, so closing the wing never fails for leaving the short alone. The group passes a
  closed portfolio gate, because it only removes risk.
- `CLOSE ps` on a structure still opening, unwinding or closing fires nothing and logs why, so the
  rule tries again. With no live structure it does nothing, like `CLOSE` on a flat stream.
- A closing leg the venue cancels for lack of quotes is sent again, on each snapshot, until it fills
  or its contract expires and settles. One the venue rejects stays held:
  once nothing of the structure is working, a later `CLOSE` (or `FLATTEN`) can retry. This holds for
  an unwind as well as a `CLOSE`.
- `FLATTEN` (`CLOSE_ALL`) closes open structures as groups and cancels the working legs of opening
  ones; their filled legs are then unwound. It never closes a structure's leg a second time.
  Deactivating a portfolio child does the same.
- An expired leg is never closed: it settles at its intrinsic value from the catalog's delivery
  price on the first tick at or after expiry, which is added to `pnl`. That holds even when two
  structures hold one contract long and short and the account nets them away.
- Ending a structure cancels only this strategy's working legs, never another strategy's orders on the
  same contract.

## Requirements

- The root must be fed by an `OPTIONS:<VENUE>.<ROOT>` stream. The strategy refuses to compile
  otherwise, so legs can never reach another broker.
- A structure alias cannot be the name of a stream or basket, and one rule cannot open the same
  alias twice.
- A strategy that trades a root through structures cannot also order that root's contracts
  directly. A declared contract stream may still be read. Two traders of one contract could not
  tell whose fill was whose.
- A rule that reads no stream runs on the first stream with candles. An `OPTIONS:` feed has none, so
  declare at least one other stream, such as a `CHAIN:` metric.
- Two legs that select the same contract refuse the structure. Ratio structures are not supported.
- Structures run in backtests and live on a `type: gateway` account. Live, legs are chosen from the
  chain the account records from its quotes (declare the root `chains: book`), and a strategy's
  structures survive a restart.

## Reports

A backtest with structures writes `structures.csv`: one row per structure with its legs and entries,
when it opened and closed, how it ended (`CLOSED`, `UNWOUND`, `SETTLED`), its credit and its premium
P&L before fees. Each leg's fills are also in `trades.csv`, and expiries in `settlements.csv`.
