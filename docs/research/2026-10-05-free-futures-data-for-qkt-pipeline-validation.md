# Free futures data for validating qkt's ingestion pipeline

Date: 2026-10-05

## Scope and purpose

Read-only research: before spending on Firstrate/Databento/IBKR (per
[2026-10-05-es-nq-backtest-data-acquisition.md](2026-10-05-es-nq-backtest-data-acquisition.md)),
is there genuinely free futures data good enough to exercise qkt's existing
`ContractCatalog`/`RollPolicy`/`PriceAdjustment`/`ContinuousMarketSource`
machinery end to end — i.e. to prove the ingestion pipeline and roll logic work
correctly — before paying anyone? This also tested one specific hope: that
Interactive Brokers' own TWS API might give free historical data, which would
make the backtest ingestion and the eventual live connector literally the same
API. **That hope doesn't hold up — see §1.**

## 1. IBKR's "free" historical pull — corrected

This is the important correction to the prior doc's framing. **IBKR's
`reqHistoricalData`/`reqHistoricalTicks` require the same paid market-data
subscription as live L1 streaming** — per IBKR's own docs, "the API always
requires Level 1 streaming real time data to return historical data," with no
API-side workaround (the free-delayed-chart experience is a TWS-desktop-only
behavior, not available over the API). The subscription is the **"US
Securities Snapshot and Futures Value Bundle," $10/month**, waived in any
month the account generates ≥$30 in commissions — this is the *same* $10/mo
already budgeted for live data in the prior doc, not an additional free tier
on top of it.

So: **there is no free IBKR bootstrap.** If you go the IBKR route, you're
paying $10/mo from day one regardless of whether you start with backtest or
live — the "free first, pay once it's proven" sequencing doesn't apply there.
The one thing that *does* still hold: once you are paying, backtest ingestion
and live connector genuinely share one API/format, which is real, structural
parity rather than two separately-maintained translators.

Useful operational detail if you do go this route later: `reqHistoricalTicks`
caps at 1,000 ticks/request and reportedly reaches back ~2 years for expired
contracts (unconfirmed, community-reported, not an official IBKR figure); bar
lookback is capped per granularity (1 year for 1-min bars, 8h for 30-sec bars,
down to a 60s window for 1-sec bars); pacing is ≤60 requests/10min, max 50
concurrent, no duplicate identical request within 15s.

## 2. Databento's free sample — no reliable free tier beyond the $125 credit

No confirmed free, no-signup sample file for `GLBX.MDP3`. A claim of a "free
preview key" serving OHLCV schemas without consuming credit surfaced from a
third-party wrapper's description, not Databento's own docs — **unconfirmed,
don't rely on it.** The real free offer remains the $125 signup credit already
known from the prior doc.

## 3. CME's own free data — exists, but thin and format-unconfirmed

CME's public FTP (`ftp.cmegroup.com`, no login) carries SPAN risk files,
settlement files, and Daily Bulletins — genuinely free, no signup, and
structurally exactly what `ContractCatalog`'s settlement/delivery-price field
wants (per-contract settlement price, volume, open interest). **Current file
format and archive depth could not be confirmed** (fetch attempts were
blocked/timed out) — worth a direct check, but don't assume clean CSV; CME
bulletins have historically been PDF/fixed-width. CME DataMine's full EOD
archive (1982+) and Time & Sales remain paid; only per-dataset sample files and
record-layout guides are free there.

## 4. Nasdaq Data Link / Quandl — not a real candidate

The old CHRIS continuous-futures tables are deprecated and no longer
maintained as a live free feed. A "Wiki Continuous Futures" dataset reportedly
still exists free after signup, but its current freshness for ES/NQ is
unconfirmed and likely stale. Only useful, if at all, as a last-resort
schema-shakeout source.

## 5. The actual best free candidates — bulk, no payment required

**[Kaggle `choweric/cme-es` and `choweric/cme-nasdaq`](https://www.kaggle.com)**
— the strongest match found. Per-contract (not continuous) daily OHLC +
Volume + Open Interest, **2000–2022**, one CSV per expiry (`CME_ESH2000.csv`
style), open interest reported next-day to avoid lookahead bias. This is
**structurally exactly the input `ContractCatalog`/`RollPolicy`/
`PriceAdjustment` expect** — raw per-expiry series you stitch yourself through
qkt's own roll logic, rather than someone else's pre-built continuous series
hiding whether the stitching is correct. Free, Kaggle account signup only, no
payment.

**[GitHub `axb0306/cme-futures-ohlc`](https://github.com)** — much broader
symbol coverage (51 CME/COMEX/NYMEX/CBOT symbols including ES, NQ, CL, GC, RTY
and their micros), CSV `datetime,open,high,low,close,volume`, 8 granularities
including sub-minute/tick bars, UTC timestamps, nightly auto-updates, freely
clonable with no signup. Caveats: sourced from **TopstepX's ProjectX Gateway
API**, history is shallow (hourly/4h data only from ~March 2025, intraday only
from ~January 2026) — useless for a multi-year backtest, but fine for
exercising the sub-minute/tick ingestion path specifically. Redistribution
terms from TopstepX are unclear — worth a license sanity-check before treating
this as more than a local pipeline-testing fixture.

Two more Kaggle datasets exist but weren't independently verified in this
pass: `tgtanalytics/nq-futures-1min-bar-2022-2025` (~1.05M rows, Dec 2022–Dec
2025) and `youneseloiarm/nasdaq-cme-future-nq`.

## Recommendation — what to actually do before spending money

1. **Pull `choweric/cme-es`/`cme-nasdaq` from Kaggle first.** Write the
   Firstrate-shaped `MarketSource` adapter (per
   [2026-10-05-futures-contract-selection-beyond-es-nq.md](2026-10-05-futures-contract-selection-beyond-es-nq.md)'s
   companion doc) against this instead, initially — same CSV-per-expiry shape,
   zero cost, and it directly exercises `ContractCatalog`'s settlement-price
   field (OI is present) and `RollPolicy`'s roll-instant math against *real*
   2000–2022 ES/NQ expiries rather than synthetic test fixtures.
2. **Use `axb0306/cme-futures-ohlc` only to shake out the sub-minute/tick code
   path** (`BinaryTickFeed`/`CsvTickFeed` style ingestion) — not for strategy
   validation, since its history is too shallow to mean anything.
3. **Only once the pipeline is proven against free data**, spend the
   $99.95/yr on Firstrate for the real multi-year continuous series, and only
   then make the IBKR-vs-Rithmic live-connectivity call — since IBKR's $10/mo
   gate means there's no reason to start that spend early just to "test for
   free."

## Open items to verify directly before relying on them

- CME public FTP's current Daily Bulletin file format and real archive depth.
- Databento's claimed "free preview key" — check `databento.com/docs` directly
  rather than trusting a third-party description.
- TopstepX ProjectX Gateway's redistribution terms, if `axb0306/cme-futures-ohlc`
  ends up used beyond local pipeline testing.
- IBKR's "~2 years of free tick history for expired contracts" figure —
  community-reported, not an official IBKR number.

## Sources

- [IBKR: Historical Options & Futures Data using TWS API](https://www.interactivebrokers.com/campus/ibkr-quant-news/historical-options-futures-data-using-tws-api/)
- [IBKR Market Data Pricing](https://www.interactivebrokers.com/en/pricing/market-data-pricing.php)
- [IBKR API historical data limitations](https://interactivebrokers.github.io/tws-api/historical_limitations.html)
- [CME Group DataMine](https://www.cmegroup.com/datamine.html)
- [CME public FTP](ftp://ftp.cmegroup.com)
- Kaggle: `choweric/cme-es`, `choweric/cme-nasdaq`, `tgtanalytics/nq-futures-1min-bar-2022-2025`, `youneseloiarm/nasdaq-cme-future-nq`
- GitHub: `axb0306/cme-futures-ohlc`
- [2026-10-05-es-nq-backtest-data-acquisition.md](2026-10-05-es-nq-backtest-data-acquisition.md)
- [2026-10-05-futures-contract-selection-beyond-es-nq.md](2026-10-05-futures-contract-selection-beyond-es-nq.md)
