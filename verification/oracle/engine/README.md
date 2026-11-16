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

Paths relative to the repo root; line numbers at origin/dev `0ef93788a`.

| Rule | Doc |
| --- | --- |
| Rules evaluate on every candle close, edge-triggered. | `docs/reference/dsl/strategy-block.md:205,230` |
| A market entry from a bar-close signal fills on the first executable price after that close: the first tick at or after the bar's end on ticks, the next bar's open on the synthetic path. | `docs/reference/dsl/conditions.md:231` |
| `paper` fills at mid; a stop, limit or bracket exit triggers on the first tick whose quote for its side reaches the level (ask for a buy, bid for a sell) and fills at that tick's mid, up to half a spread better than its level or beyond it on a gap; no spread, slippage or latency. | `docs/concepts/backtest-model.md:76-80` |
| Volume rounds down to `volumeStep`; below `volumeMin` the order is rejected and counted in the report's rejections, never sent at the minimum. | `backtest-model.md:80-81,104-105`; `docs/reference/dsl/sizing.md:219-224` |
| `mt5-sim`: a BUY fills at the ask, a SELL at the bid, plus slippage; fill prices round to `digits`. | `backtest-model.md:104-106` |
| `mt5-sim` slips market and stop fills against you by `slippagePoints` x `pointSize`; a limit, take-profit included, is never filled worse than its price and is not slipped. | `backtest-model.md:144-147` |
| `spreadPoints: N` prices fills at mid +- N/2 points whatever the tick's spread; stops and limits still trigger on the feed's quotes. | `backtest-model.md:158,162-163` |
| A symbol replayed from bars has no prints between open, low, high and close: an exit the bar trades through fills at its level, one the next bar opens beyond fills at that open. | `backtest-model.md:83-85` |
| A bar without ticks replays open, extremes (the open position's adverse one first), close. With no position: open, low, high, close. | `backtest-model.md:90-91`; `docs/how-to/backtest-data.md:25-27` |
| `--bars` resolves fills on the recorded ticks inside each bar by default: signals from bars, fills from prints. | `backtest-model.md:86-88` |
| `mt5-sim` refuses the synthetic path. | `backtest-model.md:100-101` |
| `BRACKET`: when either side fills the other cancels. Levels: `BY <points>` from the fill, `BY <pct> PCT` percent of the entry price, `AT <price>` absolute. | `docs/reference/dsl/bracket.md:3,49-57,64-72,84-92` |
| Each bracket level goes on the price grid once the entry fills: nearest tick, an exact half tick toward the entry. | `bracket.md:40-47` |
| `OCO { STOP AT, LIMIT AT }` states the same two exits as raw prices. | `docs/reference/dsl/actions.md:127-136` |
| Entry types: `LIMIT AT`, `STOP AT` ("triggers when price hits"), `STOP AT ... LIMIT AT` (once the stop trades a limit rests at the limit price). | `actions.md:104-123` |
| `CLOSE` flattens the position at market; `SELL ... SIZING 0.5 * POSITION.x` is a partial close. | `actions.md:143-149`; `sizing.md:61-73` |
| Sizing: fixed lots; `USD` notional / price; `PCT OF EQUITY` of position value at the current price; `PCT RISK` so a stop-out loses N % of equity; `RISK $ N` budget / (stop distance x contract size). | `sizing.md:7-16,19-57,75-88,164` |
| `commissionPerLot` is charged per lot per fill, entry and exit; realised is net of modelled commission. | `backtest-data.md:577`; `docs/reference/cli-commands.md:65`; `docs/reference/config-schema.md:739-742` |
| Realised = price difference x lots x contractSize (USD account, USD-quoted symbol). | `backtest-model.md` "Equity"; release audit `scripts/release-proof/audit/verify.py` relation |
| Starting balance default 10000 (`--starting-balance`). | `docs/reference/cli-commands.md` (`qkt backtest --help`) |

## Where the docs are silent or ambiguous

The oracle takes the reading stated here and the matching scenario carries a `notes` entry; each is a question for
the docs, not a guess the harness hides.

- **Q1 Sizing price.** `sizing.md:36,57` say "at current price" but not which: the signal bar's close, or the tick the
  order fills on. Every scenario quotes the first tick after the signal bar at the signal bar's close, so both
  readings give one number.
- **Q2 Bar-close tick.** `conditions.md:231` says a market entry fills "a moment later on ticks". The oracle takes
  the first tick at or after the bar's end (the tick that completes the bar) and fills at that tick's quotes.
- **Q3 `USD` and `PCT OF EQUITY` with `contractSize` other than 1.** `sizing.md:51-57` only shows BTC (contract size
  1). The oracle divides the notional by price x `contractSize` so the notional is the fill notional qkt reports
  (`fillNotional` in `trades.csv`).
- **Q4 Synthetic-print timestamps.** The time a synthetic open/low/high/close fill carries inside its bar is not
  documented; the harness compares those fills at bar granularity.
- **Q5 `digits` rounding mode.** `backtest-model.md:105` says fill prices round to `digits` without the mode; every
  scenario keeps fill prices on the grid (even spreads, whole-point slippage) so no rounding happens.
- **Q6 Stop-limit already marketable on its trigger tick.** `actions.md:114-119` does not say whether the resting
  limit fills on the same tick that trips the stop when that tick's quote already satisfies it. The oracle fills it.
- **Q7 Gapped take-profit default.** `backtest-model.md:118` names `--tp-fill print|level` without the default;
  `:84-85` say a gapped exit fills at the open. The oracle uses the open (print). `BacktestFlagHelp` spells the
  values `level or touch`, the doc `print|level`.
- **Q8 Pending entries on the synthetic path.** `backtest-model.md:84-85` states the at-level rule for *exits*; the
  oracle applies it to a pending `STOP AT` entry too.
- **Q9 Partial close on a hedging account.** `actions.md:93-97` say an opposite-side market order "closes the
  strategy's open opposite legs by ticket, oldest first"; a fraction of one leg is not described. The partial-close
  scenarios run with `--position-mode netting`, where `sizing.md:71` documents the partial close.
- **Q10 Opposite-side entry over an open position.** Out of scope for tranche A (netting reverses,
  `backtest-model.md:45-47`); the oracle refuses it.
- **Q11 `spreadPoints` against a limit.** `backtest-model.md:158` prices fills at mid +- N/2 points and `:162` keeps
  triggers on the feed's quotes, so a target the feed's bid just reaches would be priced below its limit;
  `:146` says a limit is never filled worse than its price. The oracle lets the limit price win (qkt agrees).
- **Doc contradiction.** `backtest-model.md:243` lists "FX conversion of cross-currency positions" as not modelled
  while the `usdjpy-conversion-paper` golden proves it is (audit §4).

## Results (2026-11-16, origin/dev 0ef93788a)

46 of 49 scenarios agree with qkt to the digit. Three disagree and are held `@Disabled` in
`ExecutionVerificationKnownBugsTest`, asserting the documented behaviour:

- `long-market-oco-stop-ticks-paper`, `short-market-oco-target-ticks-paper`: `BUY syn SIZING 0.1 OCO { STOP AT
  99.50, LIMIT AT 102.50 }` submits no entry at all. qkt places a `StandaloneOCO` of the two *exit* prices as
  opening orders (`legIntent: Open`), so the first leg to trigger opens an opposite-side position instead of closing
  a long (`actions.md:127-136`: OCO "states the same two exits as raw order prices" on a `BUY`/`SELL` entry).
- `short-stoplimit-marketable-ticks-paper`: a paper `STOP AT 100.00 LIMIT AT 99.90` whose trigger tick (bid 99.94,
  mid 99.95) already satisfies the limit fills at the bid 99.94, not the tick's mid, and anchors its bracket on
  99.94; `backtest-model.md:76-79` has every paper stop and limit fill at the mid. The same-tick case itself is
  undocumented (Q6); the resting case (`long-stoplimit-rests-then-fills-ticks-paper`) fills at the mid as documented.
