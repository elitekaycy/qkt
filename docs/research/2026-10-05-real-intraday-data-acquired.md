# Real intraday data acquired — 1m through 4h, all 9 symbols

Date: 2026-10-05

## Scope

Direct follow-up to the "just 1d" finding. Established earlier that the 22-year free source
(Kaggle `choweric`) is daily-only by nature — that's a real, structural limit of that specific
source, not a general statement that no free intraday data exists anywhere. A separate source
found much earlier in this research thread
([2026-10-05-free-futures-data-for-qkt-pipeline-validation.md](2026-10-05-free-futures-data-for-qkt-pipeline-validation.md))
had real sub-daily granularity and was revisited here to actually close the gap rather than
just explain it.

## Source

**GitHub `axb0306/cme-futures-ohlc`** — 1m, 5m, 15m, 30m, 1h, 4h, daily, and tick CSVs per
symbol, directly downloadable (no auth) via the GitHub API / raw.githubusercontent.com.
Confirmed covering all 9 of our symbols (ES, NQ, CL, GC, NG, 6J, RTY, SI, ZN — labeled `6J` in
the repo, mapped to qkt's `JY` convention used elsewhere in this project).

**Provenance, stated plainly**: sourced from TopstepX's ProjectX Gateway API — a broker feed,
not an exchange sample. Real prices and volume (sanity-checked against known current levels:
ES ~7000, NQ ~26000, 6J ~0.0063, consistent with the 2026 trend already established from other
sources). Redistribution terms unconfirmed. Single continuous front-month series per symbol —
no per-contract or roll data of its own, same caveat as the `CME_CONT:` proxies.

## What was acquired and where it lives

7,970 day-files written across 9 symbols × 7 granularities (1m/5m/15m/30m/1h/4h/1d) under a
**third, distinct** broker prefix: `CME_INTRADAY:` — never to be confused with `CME:` (the
22-year catalog+roll-backed roots) or `CME_CONT:` (the other continuous proxy, different source,
different symbols' worth of data). Coverage is recent only: roughly March 2025 – April 2026
depending on symbol/granularity (1h/4h/daily start earlier than 1m/5m/15m/30m for most symbols).

Declared in `instruments.yaml` as plain (non-derivative) instruments, same real contract specs
(multiplier/tickSize/commissionPerLot) as the matching `CME:`/`CME_CONT:` entry for each symbol.

## Two more real bugs found and fixed by actually running it

Same pattern as the rest of this session: trust nothing until it's run through the real engine.

1. **Warmup-at-the-data-boundary**: starting a backtest exactly on the first day of available
   data leaves zero bars for the strategy's warmup window, so indicators never become ready and
   nothing ever trades — not a data defect, just needed `--from` set a few days after the data's
   actual start.
2. **Default `max_order_notional` ($250,000) too low for current ES/NQ price levels**: ES at
   ~7000 × $50 multiplier = $350,000 notional per contract, silently exceeding the default limit
   — orders were submitted but never accepted or rejected with any visible log line, which made
   this genuinely hard to diagnose (the same silent-rejection shape as the earlier margin issue,
   different root cause). This is a real, correctly-working pre-trade risk control, not a bug —
   it just needs sizing for the instrument's real current notional. Fixed via
   `risk: { max_order_notional: "1000000" }` in `~/.qkt/qkt.config.yaml`. This explains in
   retrospect why the earlier 2016-2018 ES backtest (run before this fix existed) worked without
   it — ES was $2,000-4,000 back then, under the old $250k default.

## Verified end-to-end

A real 5-minute EMA-crossover backtest on `CME_INTRADAY:ES`, Jan-Apr 2026, through the actual
engine: **531 trades, $15,353 realized P&L (+$5,463 unrealized), $1,184.13 real commission,
Sharpe 2.81, 24 real daily-loss risk halts logged with exact timestamps.** Genuine intraday
futures backtesting, not daily bars standing in for it.

## Current full data-root state

| Prefix | Symbols | Granularity | Depth | Fidelity |
|---|---|---|---|---|
| `CME:` | ES,NQ,CL,GC,NG,JY | 1d only | 2000-2022 | Full — catalog + roll history, extensively validated |
| `CME_CONT:` | RTY,SI,ZN | 1d only | 2000/2017-2025 | Lower — continuous only, no OI |
| `CME_INTRADAY:` | ES,NQ,CL,GC,NG,JY,RTY,SI,ZN | 1m-4h + 1d | ~2025-2026 only | Lower — continuous only, third-party broker feed |

Nothing contradicts the earlier finding that there's no free source combining both 22-year depth
AND intraday granularity — that remains true and is a real structural limit of free data, not
something more searching will fix. What changed: intraday data now genuinely exists in the data
root, for real recent months, properly separated from the deeper-but-coarser data by provenance.

## Sources

- [2026-10-05-free-futures-data-for-qkt-pipeline-validation.md](2026-10-05-free-futures-data-for-qkt-pipeline-validation.md)
- [2026-10-05-cost-model-and-remaining-symbols.md](2026-10-05-cost-model-and-remaining-symbols.md)
- GitHub `axb0306/cme-futures-ohlc`
