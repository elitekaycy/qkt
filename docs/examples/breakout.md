# Breakout — Donchian channels

Classic Turtle-style breakout. Buy when price closes above the highest high of the last N days; exit when it closes below the lowest low of the last M days. This is the strategy that powered Richard Dennis's Turtle Traders in the 1980s.

## What it does

- **Trades** Bitcoin (`BTCUSD`) on 1-hour candles
- **Buys** when the close exceeds the highest close of the prior 20 candles
- **Exits** when the close drops below the lowest close of the prior 10 candles
- **Risk-sized** at 0.5% of equity per trade, with the stop 2× ATR(14) below the signal close
- **One position at a time** — won't add to winners

Breakout systems make most of their money in a few long sustained moves. Most trades are small losers ("noise"); the win rate is low but the winners are large.

## The strategy file

```qkt title="strategies/breakout-donchian.qkt"
STRATEGY breakout_donchian VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSD EVERY 1h

PARAM entryWindow = 20
PARAM exitWindow  = 10

LET stopDist = atr(btc, 14) * 2

RULES
    WHEN btc.close > highest(btc.close, entryWindow)
     AND POSITION.btc = 0
    THEN BUY btc SIZING 0.5 PCT RISK
         BRACKET {
           STOP_LOSS AT btc.close - stopDist,
           TAKE_PROFIT AT btc.close + stopDist * 10   -- a far backstop; the rule below is the real exit
         }
       ; LOG "breakout long" high=highest(btc.close, entryWindow) atr=atr(btc, 14)

    WHEN btc.close < lowest(btc.close, exitWindow)
     AND POSITION.btc > 0
    THEN CLOSE btc
       ; LOG "exit on lowest-low"
```

A few things to notice:

- **`SIZING 0.5 PCT RISK`** sizes each entry so that a stop-out loses 0.5% of equity: qkt divides
  the risk budget by the distance from the fill to the bracket's stop. It needs a `BRACKET` with a
  stop, and the instrument's contract size (below). See [SIZING](../reference/dsl/sizing.md#risk-percent-sizing).
- **The exit is a rule, the target is a backstop.** A bracket always carries a stop and a target;
  there is no bare `STOP_LOSS`. The target sits 10 stop-distances away so the lowest-low rule does
  the exiting.
- **`highest(stream.close, N)`** and **`lowest(stream.close, N)`** are the Donchian channel functions; they return the rolling max/min over the last N closed candles.

## How to run it

Risk sizing needs `BTCUSD`'s contract size. A backtest reads it from `instruments.yaml` (by default
`<data-root>/instruments.yaml`); this run used a file with one entry:

```yaml title="instruments.yaml"
instruments:
  - qktSymbol: BACKTEST:BTCUSD
    contractSize: 1
    volumeStep: 0.01
    volumeMin: 0.01
    pointSize: 0.01
    digits: 2
    tradeStopsLevelPoints: 0
```

```bash
qkt parse strategies/breakout-donchian.qkt
qkt backtest strategies/breakout-donchian.qkt --from 2026-01-02 --to 2026-09-15 \
    --instruments instruments.yaml --no-fetch
```

Without it the run stops with `no InstrumentMeta for BACKTEST:BTCUSD`; with a real account,
`qkt instruments pull` writes the entry from your broker. A breakout system needs a long window —
it has to catch at least a few real moves — so this one runs eight and a half months.

## What to expect

qkt 0.56.2, `BTCUSD` ticks from 2 January to 14 September 2026, the default paper broker and a
10,000 USD account:

```text
Backtest   2026-01-02 to 2026-09-15 (257 trading days)
Account    10,000.00 USD

Result
  Profit             +768.80 USD   (+7.7%)
  Round trips        134 closed, 49 won (36.6%)
  Open at the end    BACKTEST:BTCUSD +0.08  worth +47.76 USD
  Worst drop         4.3%    biggest fall from a peak
  Worst day          1.2%
  Sharpe ratio       1.14     return per unit of risk
  Costs              none modeled (no spread, commission or swap)
```

The report bundle's `result.json` has the rest, under `global`:

```json
{
  "winRate": "0.36567164",
  "profitFactor": "1.27213288",
  "avgWin": "68.78780000",
  "avgLoss": "-31.17138471",
  "largestWin": "479.05560000",
  "largestLoss": "-54.02760000",
  "maxConsecutiveLosses": 11
}
```

That asymmetry — the average winner more than twice the average loser, at a win rate near a
third — is the breakout signature. No loss is much bigger than 50 USD because every entry risks
0.5% of equity at the stop; many exit earlier on the lowest-low rule. The paper broker fills at mid
with no costs, so treat this as an upper bound.

## How to adapt it

### Shorter / longer breakout windows

The original Turtle System 1 used `(20, 10)`. System 2 used `(55, 20)`:

```qkt
PARAM entryWindow = 55
PARAM exitWindow  = 20
```

or, without editing the file, `--param entryWindow=55 --param exitWindow=20`.

Longer windows = fewer signals, larger captured moves, longer drawdowns between winners.

### Add a long-trend filter

The Turtle System never entered against the long-term trend. Add a 200-period filter:

```qkt
WHEN btc.close > highest(btc.close, entryWindow)
 AND btc.close > sma(btc.close, 200)        -- in long-term uptrend
 AND POSITION.btc = 0
THEN BUY btc SIZING 0.5 PCT RISK
     BRACKET { STOP_LOSS AT btc.close - stopDist, TAKE_PROFIT AT btc.close + stopDist * 10 }
```

### Short side

```qkt
WHEN btc.close < lowest(btc.close, entryWindow)
 AND btc.close < sma(btc.close, 200)
 AND POSITION.btc = 0
THEN SELL btc SIZING 0.5 PCT RISK
     BRACKET { STOP_LOSS AT btc.close + stopDist, TAKE_PROFIT AT btc.close - stopDist * 10 }

WHEN btc.close > highest(btc.close, exitWindow)
 AND POSITION.btc < 0
THEN CLOSE btc
```

### Add a pyramid

The Turtles added to winners every 0.5 ATR of favorable move. This is what [STACK pyramiding](pyramiding.md) is for — see that example.

### Different asset

Breakouts work especially well on commodities (oil, gold), futures indices, and crypto. Less well on mean-reverting instruments like equity-index ETFs. Try gold:

```qkt
SYMBOLS
    xau = BACKTEST:XAUUSD EVERY 1h
```

## Common gotchas

- **Long flat periods are normal.** A 6-month drawdown without new highs is part of breakout. Don't quit during the dry spell — that's usually right before the winner.
- **Slippage on entry.** A breakout fires at the close above the high. The market open the next bar may be much higher. Paper-test against your real broker's slippage profile.
- **`highest(close, N)` excludes the current bar.** When you ask "highest close of the last 20 bars," qkt looks at the 20 prior closes, not including the bar you're checking against. This is the correct definition — otherwise the rule could never fire (current close can't exceed itself).
- **Survivorship bias on long histories.** Bitcoin's history is a long uptrend with several crashes. A breakout strategy that looks good on one stretch of it may not work the same when the trend regime changes. Check more than one window.

## What this example demonstrates

- `highest()` / `lowest()` rolling-extreme indicators
- Risk-based sizing with `SIZING N PCT RISK` and a bracket stop
- A far bracket target as a backstop when the real exit is a rule
- Multiple rules cooperating (one for entry, another for exit)
- `POSITION.stream = 0` / `> 0` for entry/exit gating
