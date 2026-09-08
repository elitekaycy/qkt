# TIMES — repeat an entry

Emit the same entry N times in one evaluation.

```qkt
BUY gold SIZING 0.01 BRACKET { STOP LOSS BY 3, TAKE PROFIT BY 6 } TIMES 30
```

Thirty separate orders leave in that instant, each with its own ticket and its own copy of every
clause on the action. It is exactly what writing the action out thirty times separated by `;`
produces — same orders, same ids, same fills, same P&L — with the count in one place.

## Shape

```qkt
BUY|SELL <stream> ... TIMES <expression>
```

`TIMES` sits anywhere among the action's clauses; `TIMES 3 BRACKET { ... }` and
`BRACKET { ... } TIMES 3` are the same action. One `TIMES` per action.

## The count is an expression

It is evaluated when the rule fires, so the number of entries can follow whatever the strategy
knows at that moment.

```qkt
TIMES 30                                        -- a fixed burst
TIMES CASE WHEN strongSignal THEN 20 ELSE 5 END -- conviction tiers
TIMES floor(ACCOUNT.balance / 25000)            -- one leg per $25k of account
TIMES min(1 + floor(atr(gold.candle, 14)), 8)   -- more legs when the range is wider
```

| Value | Result |
|---|---|
| `2.9` | 2 — fractions truncate |
| `0` or negative | nothing is emitted |
| undefined (indicator warming) | nothing is emitted, warned once |
| above 1000 | suppressed, not sent |

## Each repetition is a whole entry

Every clause on the action is repeated with it, not shared across the burst.

```qkt
BUY gold SIZING 0.01 STACK 3 SPACING 200 ABOVE WITHIN 4h
    BRACKET { STOP LOSS BY 300, TAKE PROFIT BY 1000 }
    TIMES 3
```

That is three independent three-layer stacks — nine potential fills — each stack anchored to its
own seed fill, not one stack of nine. The same is true of `STACK_AT` tiers and of `BRACKET`: each
repetition carries its own.

## Sizing versus repeating

`SIZING 0.30` and `SIZING 0.01 TIMES 30` both put thirty hundredths of a lot on. They are not the
same trade. One position exits once, on one stop and one target. Thirty positions each carry
their own bracket, exit independently, and pay commission each — which is the point when the
strategy wants to scale out of a burst rather than all-or-nothing, and the cost when it does not.

## Different symbols in one rule

Actions chain with `;`, and each carries its own `TIMES`.

```qkt
WHEN breakout AND POSITION.gold = 0 AND POSITION.eur = 0
THEN BUY gold SIZING 0.01 BRACKET { STOP LOSS BY 3, TAKE PROFIT BY 6 } TIMES 10
   ; SELL eur SIZING 0.01 BRACKET { STOP LOSS BY 0.0030, TAKE PROFIT BY 0.0060 } TIMES 10
```

## Measured against a live venue

A `TIMES 30` burst on an Exness MT5 demo account, EURUSD at 0.01 lots:

| | |
|---|---|
| Positions opened, closed, net flat | 30 / 30 / yes |
| Wall time to place all thirty | 7.0 s (about 3.6 orders a second) |
| Venue or risk rejections | 0 |
| Entry drift, live versus mt5-sim replay | 1-2 points on every leg |
| Exit drift, tick versus bar replay | 0 points across all thirty |

An earlier run of the same strategy under a `max_position_size` of 0.25 lots filled 25 and had
the last five rejected pre-trade, cleanly and with nothing sent to the venue -- the aggregate
position cap counts every leg of the burst.

## Common gotchas

- **The live runaway breaker counts closing fills.** It defaults to ten per strategy per ten
  minutes and halts the strategy *persistently* — an operator must run `qkt resume`. A burst of
  thirty entries that exits is thirty round trips, so it halts live while the backtest sails
  through. Raise `max_round_trips_10m` to cover the busiest ten minutes the strategy can have.
- **The gateway places orders serially,** at roughly four market orders a second. A `TIMES 50`
  burst takes about twelve seconds to reach the venue, and `http_timeout_ms` must exceed that or
  the tail of the burst fails on the qkt side while the venue still fills it. See
  [config schema](../config-schema.md).
- **A cross-stream entry needs its stream to have closed a bar.** An order on a stream other than
  the one whose bar triggered the rule prices itself from that stream's last closed candle. On the
  very first bar there is none and a bracketed order is skipped silently. Declare `WARMUP` on every
  stream a rule can trade.
- **`CLOSE` nets.** Thirty legs closed by a rule become one closing order in the netting model
  used by backtests and the paper session; a hedging MT5 account closes each ticket separately.
- **Risk limits apply per order, and per burst.** `max_position_size`, `max_open_positions` and
  `max_trades_per_day` all see N orders, not one. A burst sized for the venue can still be
  rejected by a per-strategy limit written for single trades.
- **The count is not a stack.** `STACK <n>` needs an integer literal and builds one laddered
  position; `TIMES <expr>` repeats a whole action and takes any expression.

## What this composes with

- [Actions](actions.md) — the clause order on `BUY`/`SELL`
- [SIZING](sizing.md) — per-order size, including ATR- and account-derived sizes
- [STACK](stack.md) — laddering one position, as opposed to repeating whole entries
- [STACK_AT](stack-at.md) — conditional independent legs off one parent
