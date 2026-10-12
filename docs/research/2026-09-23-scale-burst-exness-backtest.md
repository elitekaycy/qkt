# Scale-burst candidates on Exness ticks — no edge

2026-09-23. Four candidates in `qkt-quant-live/examples/strategies/`
(`{gold,btc}_{ema,rsi}_scale_burst.qkt`): a 0.01-lot seed with no stop or target on a fast
EMA(5/13) or RSI(7)-50 cross, ten 0.05-lot `STACK_AT` legs when the seed's excursion reaches
$0.30 (gold) / $30 (BTC), each leg `TAKE PROFIT BY` $1.00 / $100 with a far dummy stop, and
`EXIT AFTER 4m` on the seed and every leg.

## Method
- Data: Exness quotes pulled from the local demo gateway (`/copy_ticks_range`), 2026-07-27 to
  2026-09-22 — 12.9M XAUUSD ticks, 6.9M BTCUSD ticks (store `/var/tmp/qkt-validation/exness-store`,
  fetcher `scale-burst-001/trace/fetch_exness_ticks.py`). Venue specs from `qkt instruments pull`;
  commission 0.
- Engine: qkt build with the stack-anchoring, `EXIT AFTER` and starting-balance fixes
  (PRs #1253, #1252, #1254), `--broker mt5-sim`, `--starting-balance 100000`, risk halts opened
  wide so no breaker truncates a run. Window 2026-07-28 to 2026-09-23, no parameter search.
- Summary script: `/var/tmp/qkt-validation/research-scale-burst/analyze.py`.

## Results

| Candidate | Total | PF | Win % | Bursts | Seed P&L | Leg P&L | Weeks + |
|---|---|---|---|---|---|---|---|
| gold EMA | -13,009 | 0.64 | 58.9 | 715 | -373 | -12,636 | 0/9 |
| gold RSI | -23,740 | 0.69 | 60.6 | 1,549 | -625 | -23,115 | 1/9 |
| BTC EMA | -11,854 | 0.68 | 40.9 | 2,012 | -528 | -11,325 | 0/9 |
| BTC RSI | -10,799 | 0.78 | 42.6 | 2,914 | -631 | -10,168 | 2/9 |

## Reading
- Neither entry has directional edge: seeds alone lose about the spread (-$0.35 to -$0.46 per
  gold seed). RSI is less bad per burst on both symbols but still negative.
- The loss is structural in the burst. Gold: a leg that hits target makes +$5.04; a leg that
  times out loses $11.24 on average. Break-even needs a ~69% target hit rate; realized is ~59%.
  The burst buys after a small up-move, and gold gives it back inside four minutes
  (consistent with the 2026-09-13 gold MFT noise work).
- Optimistic: mt5-sim fills all ten legs at one price; live fills them serially over 1-3 s at
  dispersed prices (parity row A20), so live results would be worse.

## Verdict
Do not deploy any of the four. A burst design would need an entry with real short-horizon
continuation, or a payoff where a leg's timed exit is not larger than its capped target.
