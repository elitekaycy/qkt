# Mean-reversion — RSI fade with three targets

Buy oversold dips and bank the trade in three pieces. The opposite philosophy from trend-following — this strategy wants choppy markets where price oscillates around a mean.

## What it does

- **Trades** gold (`XAUUSD`) on 5-minute candles
- **Buys** when RSI(2) drops below 10 — an extreme oversold reading
- **Enters as three positions** of 0.03, 0.03 and 0.04 lots, each with its own bracket: targets at +0.1%, +0.2% and +0.4%
- **Stop-loss** at 2× ATR(14) below the signal close, the same level on all three
- **No new entry** while any of the three is still open

RSI(2) is a Larry Connors classic for short-term mean-reversion on liquid instruments. Splitting the entry is what differentiates this from a single take-profit — the first piece banks a small gain early while the last one waits for the bigger move.

!!! note "There is no multi-leg take-profit"
    A `BRACKET` carries exactly one stop and one target. A fractional scale-out such as
    `TAKE_PROFIT { 0.33 AT ..., 0.33 AT ... }` does not parse; earlier versions of this page showed one
    that qkt never implemented. To scale out, enter as several positions, one bracket each, as below.
    See [BRACKET](../reference/dsl/bracket.md#scale-out-multi-leg-take-profit-not-implemented).

## The strategy file

```qkt title="strategies/mean-rev-rsi.qkt"
STRATEGY mean_rev_rsi VERSION 1

SYMBOLS
    gold = BACKTEST:XAUUSD EVERY 5m

PARAM stopMul = 2.0
PARAM t1 = 0.001     -- +0.1% target
PARAM t2 = 0.002     -- +0.2% target
PARAM t3 = 0.004     -- +0.4% target

LET stopPrice = gold.close - atr(gold, 14) * stopMul

RULES
    WHEN rsi(gold.close, 2) < 10
     AND POSITION.gold = 0          -- only enter when flat
    THEN BUY gold SIZING 0.03
         BRACKET { STOP_LOSS AT stopPrice, TAKE_PROFIT AT gold.close * (1 + t1) }
       ; BUY gold SIZING 0.03
         BRACKET { STOP_LOSS AT stopPrice, TAKE_PROFIT AT gold.close * (1 + t2) }
       ; BUY gold SIZING 0.04
         BRACKET { STOP_LOSS AT stopPrice, TAKE_PROFIT AT gold.close * (1 + t3) }
       ; LOG "oversold entry" rsi=rsi(gold.close, 2) price=gold.close
```

The three `BUY` actions are joined with `;`, so they leave in the same instant as three separate
orders. Each fill gets its own stop and target and exits on its own. When the pieces are identical,
`TIMES` says the same thing in one action: `BUY gold SIZING 0.03 BRACKET { ... } TIMES 3` is three
positions with the same bracket. A scale-out needs different targets, so it spells the three out.
See [TIMES](../reference/dsl/times.md).

## How to run it

```bash
qkt parse strategies/mean-rev-rsi.qkt
qkt backtest strategies/mean-rev-rsi.qkt --from 2026-07-06 --to 2026-08-29 --no-fetch
```

## What to expect

qkt 0.56.2, eight weeks of `XAUUSD` ticks (6 July to 28 August 2026), the default paper broker and
a 10,000 USD account:

```text
Backtest   2026-07-06 to 2026-08-28 (47 trading days)
Account    10,000.00 USD

Result
  Profit             -2,234.79 USD   (-22.3%)
  Round trips        1183 closed, 603 won (51.0%)
  Open at the end    BACKTEST:XAUUSD +0.07  worth +8.74 USD
  Worst drop         23.8%    biggest fall from a peak
  Worst day          9.7%
  Sharpe ratio       -3.03     return per unit of risk
  Costs              none modeled (no spread, commission or swap)
```

Each piece counts as its own round trip, so 1,183 round trips are about 400 signals. Half of the
pieces won, and the strategy still lost a fifth of the account before any cost: the targets are
0.1% to 0.4% away, the stop is two ATRs away, and gold trended through these weeks, so the losers
were much larger than the winners. This is the classic failure of fading every dip. The trend
filter below is the usual first fix; the [full-stack bundle](full-stack/index.md#strategiesgold-meanrevqkt)
runs this idea with it on 15-minute candles.

## How to adapt it

### Different oversold threshold

`RSI < 10` is aggressive. For more conservative entries:

```qkt
WHEN rsi(gold.close, 2) < 5     -- only the most extreme readings
 AND POSITION.gold = 0
THEN BUY gold SIZING 0.1
     BRACKET { STOP_LOSS AT stopPrice, TAKE_PROFIT AT gold.close * (1 + t2) }
```

You'll get fewer trades.

### Short side (sell overbought)

```qkt
LET shortStop = gold.close + atr(gold, 14) * stopMul

RULES
    WHEN rsi(gold.close, 2) > 90
     AND POSITION.gold = 0
    THEN SELL gold SIZING 0.03
         BRACKET { STOP_LOSS AT shortStop, TAKE_PROFIT AT gold.close * (1 - t1) }
       ; SELL gold SIZING 0.03
         BRACKET { STOP_LOSS AT shortStop, TAKE_PROFIT AT gold.close * (1 - t2) }
       ; SELL gold SIZING 0.04
         BRACKET { STOP_LOSS AT shortStop, TAKE_PROFIT AT gold.close * (1 - t3) }
```

Long and short on RSI extremes usually run together.

### Wider RSI period

`RSI(2)` is hair-trigger. Connors' original used `RSI(2)`; `RSI(3)` or `RSI(4)` fires less often.

### Two targets instead of three

If the trade rarely reaches the third target, drop it and split the size over two:

```qkt
THEN BUY gold SIZING 0.05
     BRACKET { STOP_LOSS AT stopPrice, TAKE_PROFIT AT gold.close * 1.001 }
   ; BUY gold SIZING 0.05
     BRACKET { STOP_LOSS AT stopPrice, TAKE_PROFIT AT gold.close * 1.002 }
```

### Add a trend filter (don't fade strong trends)

```qkt
WHEN rsi(gold.close, 2) < 10
 AND gold.close > sma(gold.close, 200)    -- only fade when above the long MA
 AND POSITION.gold = 0
THEN BUY gold SIZING 0.1
     BRACKET { STOP_LOSS AT stopPrice, TAKE_PROFIT AT gold.close * (1 + t2) }
```

This one filter stops you from buying oversold dips in genuine downtrends.

## Common gotchas

- **Mean-reversion fails in strong trends.** The run above is what that looks like; a 200-period MA filter is the most common safeguard.
- **Targets are limit orders.** In fast markets price can skip past a target without filling it.
- **Sample-size trap.** 50 trades is not enough to validate a mean-reversion strategy. Aim for 200+ across multiple market regimes.
- **Costs matter.** A 0.1% target on gold near 4,000 USD is about 4 USD. The ticks in this window quote gold 0.24 to 0.26 USD wide, about 6% of that target, paid on every piece. Rerun with `--broker mt5-sim` and your venue's costs.
- **Three pieces, three commissions.** Each piece is its own order on entry and on exit.

## What this example demonstrates

- `POSITION.stream` for entry gating
- Scaling out with several entries, one bracket each, joined with `;`
- ATR-based stop placement through a `LET`
- Arithmetic in bracket prices (`gold.close * (1 + t1)`)
- LOG with structured key=value fields
