# Execution oracle (tranche A)

A reference engine in Python `decimal.Decimal`, written only from qkt's documentation, that replays a hand-authored
tape through a declaratively described strategy and produces the trade tape qkt must book: every fill's time, side,
quantity, price, realised P&L net of its commission and the reason it happened. `ExecutionVerificationTest` runs the
matching `.qkt` through the real `qkt backtest` path and compares fill by fill with no tolerance on money.

Nothing here imports or runs qkt. The expectations are regenerated only by `scripts/verification-fixtures.sh` and a
diff is reviewed like code. Standard library only; run with `python3 -I`.

```bash
scripts/verification-fixtures.sh            # author scenarios/ from cases.py, then write expected/
scripts/verification-fixtures.sh --check    # fail if expected/ is stale
```

| Path | What |
| --- | --- |
| `cases.py` | The tranche-A scenarios as compact Python: bars of four mid prices, the actions, the instrument, the run mode. Each is named for what it proves. |
| `author.py` | Writes `scenarios/<name>.json` (tape, instrument, flags, declarative actions) and `scenarios/<name>.qkt` (the strategy qkt runs) from `cases.py`. |
| `model.py` | The scenario and fill types. |
| `rules.py` | Every modelling rule, one function each, with the doc sentence it comes from in its docstring. |
| `engine.py` | The replay: bars close rules, orders fill on quotes, exits protect fills, costs book per fill; a tick path and a synthetic bar path. |
| `gen.py` | Writes `expected/<name>.json`: the tape, realised, commission, rejections. |

## The tape

Ticks are `[offsetSeconds, bid, ask]` from `t0` (2024-01-10 10:00 UTC, a Wednesday inside every session). Each
1-minute bar has four ticks at :00, :15, :30 and :45; the price is the mid, on a 0.01 grid, with a 0.02 spread. The
symbol is `BACKTEST:SYN`, declared in `instruments.yaml` with `contractSize 1` (10 or 100 where the case says),
`volumeStep 0.01`, `volumeMin 0.01`, `pointSize 0.01`, `digits 2`, costs per case. The run is
`qkt backtest <name>.qkt --from 2024-01-10 --to 2024-01-11 --starting-balance 10000 --instruments ...` plus
`--broker mt5-sim`, `--position-mode netting`, `--bars --no-tick-fills` (synthetic bar path, after
`qkt data build-bars`) or `--bars --tick-fills` as the scenario's `broker`, `positionMode` and `fillMode` say.

A rule is `WHEN syn.close = <price> [AND POSITION.syn = 0 | > 0 | < 0] THEN ...`; the trigger price appears exactly
once as a bar close in the tape, so each rule fires once.

## Modelling rules and where each comes from

Paths relative to the repo root; line numbers as of the doc answers to #1717.

| Rule | Doc |
| --- | --- |
| Rules evaluate on every candle close, edge-triggered. | `docs/reference/dsl/strategy-block.md:205,230` |
| A bar closes on the first tick at or after its end; a market entry from that close fills on that tick at its quotes (the next bar's open on the synthetic path). Orders the close places work from the next tick. | `docs/concepts/backtest-model.md:89-96`; `docs/reference/dsl/conditions.md:241` |
| `paper` fills at mid; a stop, limit or bracket exit triggers on the first tick whose quote for its side reaches the level (ask for a buy, bid for a sell) and fills at that tick's mid, up to half a spread better than its level or beyond it on a gap; no spread, slippage or latency. | `backtest-model.md:76-80` |
| Volume rounds down to `volumeStep`; below `volumeMin` the order is rejected and counted in the report's rejections, never sent at the minimum. | `backtest-model.md:80-81,120-121`; `docs/reference/dsl/sizing.md:240-243` |
| `mt5-sim`: a BUY fills at the ask, a SELL at the bid, plus slippage; fill prices round to `digits`, half to even, after slippage and the limit cap. | `backtest-model.md:120-124` |
| `mt5-sim` slips market and stop fills against you by `slippagePoints` x `pointSize`; a limit, take-profit included, is never filled worse than its price and is not slipped, even when `spreadPoints` would price it past its level. | `backtest-model.md:164-168` |
| `--tp-fill` defaults to `print`: a crossed take-profit fills at the crossing price when it beats the level. | `backtest-model.md:135-137` |
| `spreadPoints: N` prices fills at mid +- N/2 points whatever the tick's spread; stops and limits still trigger on the feed's quotes. | `backtest-model.md:179,183-184` |
| A symbol replayed from bars has no prints between open, low, high and close: any resting order, entry or exit, the bar trades through fills at its level, one the next bar opens beyond fills at that open. | `backtest-model.md:83-88` |
| A bar without ticks replays open, extremes (the open position's adverse one first), close. With no position: open, low, high, close. The prints fall at the bar's start, a quarter and a half of the bar later, and 1 ms before its end. | `backtest-model.md:97-101,105-107`; `docs/how-to/backtest-data.md:25-27` |
| `--bars` resolves fills on the recorded ticks inside each bar by default: signals from bars, fills from prints. | `backtest-model.md:102-104` |
| `mt5-sim` refuses the synthetic path. | `backtest-model.md:116-117` |
| `BRACKET`: when either side fills the other cancels. Levels: `BY <points>` from the fill, `BY <pct> PCT` percent of the entry price, `AT <price>` absolute. | `docs/reference/dsl/bracket.md:3,49-57,64-72,84-92` |
| Each bracket level goes on the price grid once the entry fills: nearest tick, an exact half tick toward the entry. | `bracket.md:40-47` |
| `OCO { STOP AT, LIMIT AT }` states the same two exits as raw prices. | `docs/reference/dsl/actions.md:143-152` |
| Entry types: `LIMIT AT`, `STOP AT` ("triggers when price hits"), `STOP AT ... LIMIT AT` (once the stop trades a limit rests at the limit price, and fills on the trigger tick when that tick already satisfies it). | `actions.md:112-141` |
| An opposite-side order smaller than the position closes that part of it (hedging: of the oldest leg, by ticket); a larger one ends with the excess open on the other side: hedging closes the legs and opens the excess, two deals at one price; netting reverses in one fill. | `actions.md:93-110` |
| `CLOSE` flattens the position at market; `SELL ... SIZING 0.5 * POSITION.x` is a partial close. | `actions.md:159-164`; `sizing.md:80-92` |
| Sizing price: a market order sizes at the signal bar's close, `LIMIT AT p` / `STOP AT p` at `p`, a stop-limit at its stop price; equity and stop distance are taken at that moment. | `sizing.md:61-73` |
| Sizing: fixed lots; `USD` notional / (price x contractSize x quote-to-account rate); `PCT OF EQUITY` of position value at that price; `PCT RISK` so a stop-out loses N % of equity; `RISK $ N` budget / (stop distance x contract size). | `sizing.md:7-16,19-57,73-78,94-107,183` |
| `commissionPerLot` is charged per lot per fill, entry and exit; realised is net of modelled commission. | `backtest-data.md:577`; `docs/reference/cli-commands.md:65`; `docs/reference/config-schema.md:739-742` |
| Realised = price difference x lots x contractSize (USD account, USD-quoted symbol, so the conversion rate is 1). | `backtest-model.md` "Equity", "Currency conversion"; release audit `scripts/release-proof/audit/verify.py` relation |
| Starting balance default 10000 (`--starting-balance`). | `docs/reference/cli-commands.md` (`qkt backtest --help`) |

## Questions the docs now answer

The first version of this oracle listed eleven places where the docs were silent or ambiguous, and took a reading
for each. Issue #1717 settled every one from qkt's code, wrote the answer into the docs, and pinned it with a
scenario; the oracle's rule now cites the answer. None of qkt's behaviour here was changed.

| # | Question | Answer (doc) | qkt code | Scenario |
| --- | --- | --- | --- | --- |
| Q1 | Which price `USD` / `PCT OF EQUITY` sizing uses. | A market order sizes at the signal bar's close, not the fill; `LIMIT AT p` / `STOP AT p` at `p`; a stop-limit at its stop price. [sizing.md](../../../docs/reference/dsl/sizing.md#the-price-and-the-notional-sizing-uses) | `dsl/compile/OrderTypeCompiler.kt:84-96,111,122,133,146`; `EntryOrderCompiler.kt:94-131` | `long-market-sizing-usd-gap-open-ticks-paper`, `long-limit-sizing-pct-equity-ticks-paper` |
| Q2 | Which tick a bar-close market entry fills on. | The tick that closes the bar (the first at or after its end), at that tick's quotes; a heartbeat close fills at the symbol's last quote. [backtest-model.md](../../../docs/concepts/backtest-model.md#fills) | `app/TickIngest.kt:77-87`; `app/CandleWindowCloser.kt:45-48`; `broker/PaperBroker.kt:258-273` | `long-market-sizing-usd-gap-open-ticks-paper` |
| Q3 | Whether notional divides by price x `contractSize`. | Yes, and by the quote-to-account rate. [sizing.md](../../../docs/reference/dsl/sizing.md#the-price-and-the-notional-sizing-uses) | `dsl/compile/SizingCompiler.kt:72-78,104-113,175-197` | `long-market-sizing-usd-ticks-paper` |
| Q4 | The time a synthetic print carries. | Open at the bar's start, extremes at a quarter and a half of the bar, close 1 ms before its end. [backtest-model.md](../../../docs/concepts/backtest-model.md#fills) | `marketdata/source/BarTickFeed.kt:77-92` | every `*-bars-synthetic-paper` scenario (now compared to the millisecond) |
| Q5 | The `digits` rounding mode. | Half to even, after slippage and the limit cap (`mt5-sim`). [backtest-model.md](../../../docs/concepts/backtest-model.md#fills) | `broker/MT5BrokerSimulator.kt:394` | `long-market-digits-half-even-stop-ticks-mt5` |
| Q6 | A stop-limit already marketable on its trigger tick. | It fills on that tick; on `paper` at that tick's mid, never worse than its limit (#1716). [actions.md](../../../docs/reference/dsl/actions.md#order-type-modifiers), [backtest-model.md](../../../docs/concepts/backtest-model.md#fills) | `broker/PaperBroker.kt:280-296,337-353`; `broker/MT5BrokerSimulator.kt:278-293` | `short-stoplimit-marketable-ticks-mt5`, `short-stoplimit-marketable-ticks-paper` |
| Q7 | The `--tp-fill` default. | `print` (`touch` is the same); `paper` always fills a gapped target at the crossing price. [backtest-model.md](../../../docs/concepts/backtest-model.md#fills) | `broker/TakeProfitFill.kt:7-24`; `backtest/ExecutionSimulation.kt:57`; `broker/MT5BrokerSimulator.kt:349-357` | `short-market-gap-target-ticks-mt5`, `short-market-gap-target-bars-synthetic-paper` |
| Q8 | Pending entries on the synthetic path. | The at-level / at-open rule holds for every resting order, entries included. [backtest-model.md](../../../docs/concepts/backtest-model.md#fills) | `broker/PaperBroker.kt:216-218,317-325` | `long-stop-entry-target-bars-synthetic-paper`, `short-limit-entry-gap-bars-synthetic-paper` |
| Q9 | A partial close on a hedging account. | It closes that part of the oldest leg by ticket; the rest stays open at its entry. [actions.md](../../../docs/reference/dsl/actions.md#buy-stream-and-sell-stream) | `app/HedgedEntryRouter.kt:38-69` | `long-market-partial-close-then-close-hedging-ticks-paper` |
| Q10 | An opposite-side entry over an open position. | Netting reverses in one fill; hedging closes the opposite legs then opens the excess; a bracketed opposite entry on hedging opens its own leg. [actions.md](../../../docs/reference/dsl/actions.md#buy-stream-and-sell-stream) | `app/HedgedEntryRouter.kt:34-83`; `app/LegIntentPlanner.kt:123-131` | `long-market-reverse-netting-ticks-paper`, `long-market-reverse-hedging-ticks-paper` |
| Q11 | `spreadPoints` against a limit. | The limit's price caps the fill. [backtest-model.md](../../../docs/concepts/backtest-model.md#slippage) | `broker/MT5BrokerSimulator.kt:349-357,366-371` | `long-market-spreadpoints-override-target-ticks-mt5` |

The doc contradiction the first version found (`backtest-model.md` listing FX conversion as not modelled while the
`usdjpy-conversion-paper` golden proves it is) is fixed: the page now has a
[Currency conversion](../../../docs/concepts/backtest-model.md#currency-conversion) section
(`accounting/Accounting.kt:138-148,196-265`).

## Results (2026-11-16)

All 58 scenarios agree with qkt to the digit. None is held in `ExecutionVerificationKnownBugsTest`; three
disagreed until their fixes:

- `long-market-oco-stop-ticks-paper`, `short-market-oco-target-ticks-paper` (fixed, #1715): `BUY syn SIZING 0.1 OCO
  { STOP AT 99.50, LIMIT AT 102.50 }` submitted no entry at all. qkt placed a `StandaloneOCO` of the two *exit*
  prices as opening orders (`legIntent: Open`), so the first leg to trigger opened an opposite-side position instead
  of closing a long (`actions.md:143-152`: OCO "states the same two exits as raw order prices" on a `BUY`/`SELL`
  entry). `OCO` now compiles to the same bracket as `BRACKET { STOP_LOSS AT, TAKE_PROFIT AT }`; both scenarios run
  in `ExecutionVerificationTest`.
- `short-stoplimit-marketable-ticks-paper` (fixed, #1716): a paper `STOP AT 100.00 LIMIT AT 99.90` whose trigger
  tick (bid 99.94, mid 99.95) already satisfied the limit filled at the bid 99.94, not the tick's mid. It now fills
  at the mid, as `backtest-model.md` states, and runs in the main set.

## Tranche B: composite orders

`cases_b.py` adds 42 scenarios for OTO (`ON_FILL`), STACK, STACK_AT, EXIT AFTER, trailing entries and armed trailing
stops, OCO_ENTRY, collisions of composite orders on one tick, and two rules firing on one bar. `composite.py` holds
their rules; `engine.py` now tracks every filled entry as its own leg. The tranche-A expectations are unchanged by
the rewrite (`gen.py --check` passes on them byte for byte). Line numbers at origin/dev `5cf2db50f`.

| Rule | Doc |
| --- | --- |
| Each filled entry is its own leg with its own exits; a netted account merges one side into one position at its volume-weighted average and an opposite fill reduces it. | `docs/concepts/backtest-model.md:45-47` |
| Rules are evaluated in order on every candle close and are independent: every rule reads the state the bar closed on, then the actions run in rule order. | `docs/reference/dsl/strategy-block.md:205` |
| OTO: children are placed only when the parent fills, dropped if it never does; a child is a plain BUY/SELL with its own sizing and order type, resting GTC with no exits. | `docs/reference/dsl/oto.md:3,17-22,51,58` |
| OTO `entry` is the parent's fill price: its price for a LIMIT/STOP parent, the signal-time estimate for a MARKET parent. | `oto.md:26,34` |
| STACK n includes the seed; rungs at seed fill +- k x SPACING; ABOVE adds on favourable moves for a buy (adverse for a sell), BELOW the mirror; an `AT` layer is a stop beyond the seed in the trade direction and a limit behind it. | `docs/reference/dsl/stack.md:16-18,33-37,74-77,198,200` |
| Each STACK layer carries its own copy of the bracket anchored on its own fill and exits on its own. | `stack.md:119-135,139` |
| Layers trigger in order; unfilled layers cancel when the trade exits or WITHIN after the seed fill (24 h when omitted). | `stack.md:3,19-20,146-156,179,201` |
| STACK_AT: the primary's MFE/MAE is the mark (mid) against its fill, evaluated from the tick after the fill; an MFE tier fires at `mfe >= threshold` while `elapsed <= within`; a recoil tier arms at `mae >= threshold`, floats its anchor with the worst adverse mark and fires on RECOVER; each fires once, all qualifying tiers on one tick; a tick that fills the primary's own stop or target ends its lifecycle before its tiers see it. | `docs/reference/dsl/stack-at.md:27-33,67-89,93-94,191-195` |
| A tier's leg is a market order priced like any market fill on the broker; its `BY` bracket anchors on its own fill; `SIZING ENTRY_QTY * x` sizes off the primary; closing the primary leaves the leg. | `stack-at.md:30,53,61,98-102,131` |
| EXIT AFTER closes the entry's own leg at market on the first tick at or after fill time + duration; a bracket is cancelled first, a stop or target filling first drops the timer; a pending entry's timer starts at its fill; each STACK_AT leg is timed from its own fill. | `docs/reference/dsl/exit-after.md:9-11,27-38` |
| A TRAILING entry ratchets its extreme (low-water for a buy, high-water for a sell) and fires a market order when price reverses by the distance; `PCT` is whole percentage points. | `docs/how-to/add-stop-loss.md:65,79-83`; `actions.md:46` |
| An armed trailing stop sits at entry -+ distance, arms once MFE reaches the threshold (0 trails from the fill), then sits at the running favourable extreme -+ distance; it never disarms. | `docs/reference/dsl/bracket.md:152-169` |
| OCO_ENTRY places two pending entries; the first to fill cancels the other; each carries its own bracket. Two legs reached together: "the leg with the closer trigger to the candle's open fills first". | `docs/reference/dsl/actions.md:244-259,172` |

### Questions where the docs are silent (tranche B)

The scenarios are built so the reading below does not change their fills; each is a question for the docs.

- **QB1 Order of work on one tick.** Nothing states whether resting entries, STACK_AT tiers or exits are worked
  first on a tick. The oracle works entries, then tiers of primaries already open, then exits, and the harness
  compares fills that share a timestamp as a set (the trade log's order of two fills on one tick is not documented).
- **QB2 OTO `entry` for a STOP parent on paper.** `oto.md:34` says a LIMIT/STOP parent "fills at its price", but a
  paper stop fills at the tick's mid (`backtest-model.md:76-79`). The scenario trips the stop on a tick whose mid is
  the stop price.
- **QB3 STACK rung anchor.** `stack.md:200` hangs every rung off the seed fill; `stack.md:53` says "200 points below
  the previous fill". The scenarios use STACK 2, or layers whose fill equals their rung, so both agree.
- **QB4 A STACK layer and the seed's stop on one tick.** `stack.md:3` cancels unfilled layers when the trade exits;
  nothing says whether a layer the same tick reaches is still unfilled. The oracle fills it: both orders rest when
  the tick arrives and `backtest-model.md:77-79` triggers each on the first tick that reaches it.
- **QB5 A STACK_AT tier on the primary's target tick** (answered). The docs did not say; qkt closes the primary
  first and no tier fires on that tick. `stack-at.md:87-89` now states it, and the oracle works tiers after exits.
- **QB6 Trailing entry price.** `add-stop-loss.md:79-85` does not say which quote the extreme tracks (mid, bid or
  ask), when tracking starts, whether "reverses by" is inclusive, or what `PCT` is a percent of. The oracle tracks the
  mid from the placement tick and takes `PCT` of the extreme; the tapes have a constant spread, no exact ties, and a
  reversal both PCT bases trip on the same tick.
- **QB7 Armed trailing extreme.** `bracket.md:165` "running favorable extreme since fill" does not name the quote.
  The oracle uses the mark (mid, as MFE in `stack-at.md:93-94`); every trigger tick is through the level on either.
- **QB8 OCO_ENTRY tie-break on ticks** (answered, #1748). `actions.md:277-283` now states the reference for both
  paths: the previous tick's price on ticks, the bar's open on the synthetic path; equal distances fall back to the
  order the legs are written.
- **QB9 OCO_ENTRY dual breach on the synthetic path.** `actions.md:277` (closer to the open fills first) and
  `backtest-data.md:25-27` (a flat bar replays its low before its high) disagree when the open is nearer the high.
  The scenario has the open nearer the low, where both pick the same leg.
- **QB10 Not covered on purpose:** a gap through two STACK rungs on one tick (`stack.md:179` "waits for the previous
  one's trigger"), an MFE tier after its primary has closed, EXIT AFTER and a bracket level on the same tick, CLOSE
  over several hedged legs (one fill per leg or one in all), WITHIN boundary ties (`stack.md:156` "before the
  deadline elapses" against `stack-at.md:79` `elapsed <= within`).

### Tranche B results

All 42 scenarios agree with qkt on every fill. One class of report columns is held for the STACK scenarios:

- **#1748 (fixed)**: when one price reached both OCO_ENTRY legs, qkt filled the leg written last. It now fills the
  leg nearer the price the move started from (`actions.md:277-283`): `oco-entry-gap-through-both-legs-ticks-paper`,
  `oco-entry-gap-closer-leg-listed-first-bars-synthetic-paper` and the tie case
  `oco-entry-gap-equal-distance-listing-order-ticks-paper` run in the main set.
- **#1747** every STACK scenario: the fills, prices and realised P&L agree, but `trades.csv` leaves
  `stopLossPrice`, `takeProfitPrice` and `riskUsd` empty on STACK entry rows, although each layer carries the
  bracket (`backtest-model.md:263`, `stack.md:119-135`). The main set checks these scenarios without the entry levels;
  a `@Disabled` factory checks them with the levels.
