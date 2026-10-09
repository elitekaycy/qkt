# Free futures data — full validation report

Date: 2026-10-05

## Scope and verdict

Full data-engineering validation pass on the free data assembled across this research
thread (CME's own MBO FIX samples, and Kaggle's `choweric/*` per-expiry archives), before
treating any of it as backtest-ready. This is the closing record for that validation —
every check performed, every defect found, and how each was resolved or excluded.

**Verdict: five symbols (ES, NQ, CL, GC, NG) are validated and backtest-ready at daily-bar
granularity, 2000–2022, for free.** Two real defects were found and fixed in the chain-
construction logic (not in the underlying data); one symbol (JY/6J) was found to have a
genuine, unfixable scale defect in the source data itself and is excluded; one symbol (6E)
was already known to have shallower coverage (2018–2022 only) and remains excluded from
the "full depth" set. No cross-source gap-filling was needed — every apparent gap traced
back either to a real market closure (correctly present) or a bug in this session's own
scripts (fixed), not to genuinely missing underlying data.

## 1. Format/decode validation (CME FIX MBO samples)

Decoded all 12 of CME's free MBO FIX samples (2017-10-25, one day each: ES/GC/CL/6E/GE/corn
futures and options). Found the full `MDEntryType` (tag 269) picture is far richer than the
simple bid/offer/trade model — `E`/`F` (implied bid/offer, CME's spread-derived implied
book — a majority of book-side messages on GC/6E/GE, not a rare edge case), `N`/`O` (session
stats), `J` (empty-book/channel-reset), `W` (fixing price), and lowercase `e` (electronic
volume, case-sensitive and distinct from `E`). All confirmed against CME's own MDP 3.0
documentation, not guessed. Filed as design requirements for the future `adapter-cme`
module: [qkt-venue-gateway#54](https://github.com/elitekaycy/qkt-venue-gateway/issues/54),
[#56](https://github.com/elitekaycy/qkt-venue-gateway/issues/56).

**Cross-validation**: the FIX-reconstructed ESZ7 top-of-book (ask 2564.75, around 12:30 UTC
on 2017-10-25) matched Kaggle's independently-aggregated `CME_ESZ2017.csv` opening price for
the same date (2564.75) exactly — two unrelated free sources agreeing on the same contract,
same day, same price.

## 2. Roll-timing validation (22 years of real ES volume data)

Measured the real front→next volume-crossover date against each contract's own expiry, across
all 91 rolls in `choweric/cme-es` (2000–2022). First pass had a detection bug (naive "3-day
sustained dominance" check got fooled by noise when both contracts were thin early in a new
contract's life) producing a meaningless mean of 95 days; fixed by restricting the search to a
sensible window near expiry and requiring real volume on both sides. Corrected result:

```
mean: 7.2 days before expiry   median: 7   min: 2   max: 13   stdev: 1.4
```

Matches the two real 2024 examples already on record in this project (ESU→ESZ crossed 8 days
before expiry, ESZ→ESH crossed 7 days before) and sits almost exactly on the `daysBeforeExpiry:
8` value already used in qkt's one existing roll-policy test fixture (crypto, coincidental but
reassuring). A genuine secular trend was flagged, not acted on: recent years (2015, 2018, 2020,
2022) show the crossover compressing to 2–4 days — worth revisiting once more current (2023+)
data is available, since this archive can't see that by itself.

## 3. Price-scale validation (ES, NQ, CL, GC, NG)

Checked every contract's median close (volume>0 rows only) against its chronological
predecessor for a >3x jump — a real roll never moves price that much, so a jump that size is a
near-certain units/scale defect. **All five core symbols: zero flags**, and every 2022-era
median lands exactly on real historical price levels (ES ~4,083–4,422; NQ ~12,349–15,077; CL
~53–63; GC ~1,676–1,814; NG ~2.47–2.90).

**JY (6J) failed this check and is excluded.** Every contract expiring 2000–2021 uses one price
scale (~7,000–13,000); every contract expiring in 2022 switches to a completely different scale
(~0.7–0.9) — a ~10,000× discontinuity baked into the source data itself, not fixable by any
chain-construction logic. JY was a bonus symbol, not part of the original core watchlist
(ES/NQ/RTY/CL/GC/SI/NG/ZN/6E), so this doesn't block anything — it's simply excluded.

**Real, non-defect event confirmed intact**: CL's April 20, 2020 negative-price close
(-37.63, the real COVID storage-crisis event) is present and correct in the raw per-contract
file (`CME_CLK2020.csv`). It does **not** appear in the *continuous, rolled* series — which is
correct behavior, not missing data: a roll policy following the same ~7-day-before-expiry
discipline validated in §2 would have already rolled out of the expiring May contract before
its final, illiquid, crisis-driven session.

## 4. Chain-construction validation (gap/duplicate check) — two real bugs found and fixed

Checked every continuous series for duplicate dates and unexplained calendar gaps (>5 days).

**First pass**: NQ showed one gap (2001-09-11 → 2001-09-17) — real, correct: US markets closed
after 9/11, reopened Sept 17. Not a defect.

**GC showed 20 gaps, several enormous (up to 287 days, 2001-05-17 → 2002-02-28).** Root cause:
COMEX gold only actively trades six delivery months (G/J/M/Q/V/Z); the other six are listed but
structurally dead (`GCF2001` total lifetime volume: 39 contracts, vs `GCJ2001`: 1,245,157). A
chain-builder walking every listed month in calendar sequence tries to roll into these dead
months, which can never show a real volume crossover, and the chain silently breaks.

Two fix attempts failed before the correct one was found — both are worth recording since they're
real lessons, not just the final answer:
1. A fixed absolute lifetime-volume threshold (10,000 contracts) fixed 2001 but not 2008 (the
   same dead month, `GCH2008`, had organically grown to 13,988 lifetime volume from 22 years of
   market growth, and silently slipped back past the filter).
2. A threshold relative to the symbol's **all-time** median self-calibrated across eras for GC,
   but then wrongly filtered genuinely active **early** ES/NQ contracts (2000-era volume is
   legitimately far below a 22-year median that includes much higher later-era volume) — this
   dropped ES from 92 to 86 real contracts, a regression discovered immediately by re-running the
   same validation suite against ES/NQ after the GC-focused fix.

**What actually worked**: comparing each contract's lifetime volume against a **local window of
its nearest chronological neighbors** (3 before + 3 after) rather than any fixed number or
all-time statistic. This self-calibrates across both market growth over decades and seasonal
dead-month patterns simultaneously. Confirmed: GC's chain became fully gap-free (0 gaps >5 days,
down from 20), while ES/NQ were unaffected (both remained at their correct full 92-contract
chains). Filed as a core chain-construction requirement, not vendor-specific:
[qkt#1341](https://github.com/elitekaycy/qkt/issues/1341).

**Final state, all five symbols**: 0 duplicate dates, 0 unexplained gaps (the one remaining NQ
gap is the real 9/11 closure).

## 5. Was cross-source gap-filling needed?

No. Every apparent gap in this validation traced back to either a real, correctly-present market
event (9/11 closure) or a bug in this session's own chain-construction scripts (fixed, §4) — not
to genuinely missing data in the free sources themselves. Once the construction logic was
correct, the free Kaggle archive turned out to be complete for all five core symbols across the
full 2000–2022 window. No supplementary free source was required to patch real holes.

## Final validated dataset state

| Symbol | Status | Contracts (post-filter) | Rows | Date range | Known issues |
|---|---|---|---|---|---|
| ES | **Validated** | 92 | 5,926 | 1999-09-20 – 2022-12-16 | none |
| NQ | **Validated** | 92 | 5,915 | 1999-09-17 – 2022-12-16 | 1 real gap (9/11 closure) |
| CL | **Validated** | 276 | 6,337 | 1997-09-09 – 2022-11-21 | none (negative-price event correctly handled) |
| GC | **Validated** | 168 | 6,221 | 1998-03-31 – 2022-12-28 | none (post-fix; was 20 gaps, up to 287 days, pre-fix) |
| NG | **Validated** | 276 | 6,353 | 1997-08-25 – 2022-11-28 | none |
| 6J | **Excluded** | — | — | — | real ~10,000× price-scale discontinuity in source data at the 2022-contract boundary, unfixable |
| 6E | **Excluded from full-depth set** | — | — | 2018-2022 only | shallower coverage than advertised; usable but not at the same depth as the five above |

Raw per-expiry CSVs, continuous raw series, and continuous Panama-adjusted series for all five
validated symbols are built and sitting in the session scratchpad — not yet moved into qkt's
real data root or committed anywhere durable. That's the next concrete step once this report is
reviewed.

## Sources / builds on

- [2026-10-05-free-futures-data-for-qkt-pipeline-validation.md](2026-10-05-free-futures-data-for-qkt-pipeline-validation.md)
- [2026-10-05-futures-data-2018-to-now-coverage-check.md](2026-10-05-futures-data-2018-to-now-coverage-check.md)
- [2026-10-04-futures-options-data-pipeline.md](2026-10-04-futures-options-data-pipeline.md)
- Kaggle `choweric/cme-es`, `cme-nasdaq`, `nymex-cl`, `comex-gc`, `nymex-ng`, `cme-jpy`, `cme-euro`
- CME MBO FIX samples: `cmegroupclientsite.atlassian.net/wiki/spaces/EPICSANDBOX/pages/457223111/MBO+FIX`
- [qkt-venue-gateway#54](https://github.com/elitekaycy/qkt-venue-gateway/issues/54), [#56](https://github.com/elitekaycy/qkt-venue-gateway/issues/56)
- [qkt#1341](https://github.com/elitekaycy/qkt/issues/1341)
