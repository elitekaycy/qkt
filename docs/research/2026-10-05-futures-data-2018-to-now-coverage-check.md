# Does any free source cover ES/NQ 2018→now at usable granularity?

Date: 2026-10-05

## Scope

Follow-up to
[2026-10-05-free-futures-data-for-qkt-pipeline-validation.md](2026-10-05-free-futures-data-for-qkt-pipeline-validation.md).
Checked two named candidates (TurtleTrader, PortaraCQG) plus a broader sweep,
specifically for the 2018-to-present window at better than daily-bar
granularity — the earlier free sources found (Kaggle `choweric/*`) stop at
2022 and are daily-only.

## TurtleTrader (turtletrader.com/hpd) — ruled out

Genuinely free, no signup, daily bars + open interest back to the 1970s. But
it's an old-school commodity archive (~33 symbols: metals, energy, ags,
currencies, a few broad indices) — **it does not carry CME ES/NQ/RTY at all**,
and nothing on the page confirms it's maintained through 2026. Not usable for
this task regardless of granularity.

## PortaraCQG — plausible paid alternative, price unconfirmed

Commercial (Portara, under CQG). Daily data is their flagship, but they also
sell intraday, tick (trades + L1), and continuous futures, back to 1899 across
CBOT/CME/NYMEX/EUREX. Confirmed ES/NQ-class coverage (their ENQ page shows
E-mini Nasdaq daily data from June 1999) and both continuous and raw
per-expiry contracts are offered — structurally fine for qkt's
`ContractCatalog`/`RollPolicy` either way. **No price was found on the page** —
needs a direct sales inquiry before comparing against Firstrate's $99.95/yr.

## Broader 2018+ sweep — daily is the ceiling for free

- **Investing.com**: free (email signup only), daily CSV, continuous
  contract, undisclosed roll methodology, current (live commercial site).
- **Stooq**: reportedly free daily OHLCV for ES/NQ/CL/GC per a secondary
  source, but this session's direct fetch attempts came back empty — **not
  independently confirmed**, and "limited coverage, occasional data quality
  issues" per the same secondary source. Verify by actually downloading a
  file before trusting it.
- **Databento**: CME history goes back to 2010, so 2018–2026 is in range —
  confirmed the $0.50/GB floor rate again, but the $125 signup credit "would
  burn through quickly on full tick/MBO data for multiple symbols/years";
  fine for an OHLCV-level pull across years for one or two symbols, not for a
  multi-year tick dataset.
- **GitHub/Kaggle**: no new find beyond what the prior doc already surfaced.
  One promising-looking repo (`getdata-finance/nq-1m-ohlcv-stocks-historical-data`)
  is a false positive — it's the NQ *stock/ETF ticker*, not CME Nasdaq
  futures.
- **Non-IBKR brokers**: Tradovate's free demo has explicitly limited
  historical depth (not a bulk source). TradeStation gives free real-time +
  deep historical access (tick ~6mo, 1-min 10+yrs, daily 20+yrs) to a
  **funded account with ≥$40/mo futures commissions** (90-day grace period for
  new accounts) — but this is platform-viewing access, not confirmed as a
  clean bulk-export file/API, and the "free" status is conditional on
  maintained trading activity, not a no-strings tier.

## Bottom line

**No free source covers 2018–2026 at better than daily-bar granularity for
ES/NQ.** Every free option tops out at daily OHLCV, several have undisclosed
or unconfirmed roll methodology, and the one archive with the right vintage
(TurtleTrader) doesn't carry the right symbols at all. Getting real sub-daily
(1-min or tick) coverage across the full 2018–2026 window genuinely requires
paying — this doesn't change the recommendation already on record:

1. Kaggle `choweric/cme-es`/`cme-nasdaq` (free, 2000–2022, daily, per-expiry)
   remains the right zero-cost target for proving the `ContractCatalog`/
   `RollPolicy` pipeline mechanically works.
2. Firstrate Data ($99.95/yr, continuous since 2008, raw per-expiry +
   pre-built continuous) remains the cheapest real path to 2018–2026 at
   intraday granularity — it already covers the full window asked for here at
   a known, flat price, which no free or PortaraCQG option currently beats on
   confirmed terms.
3. PortaraCQG is worth one direct pricing inquiry before ruling out, given
   confirmed ES/NQ coverage and both contract-shape options, but shouldn't
   block proceeding with Firstrate in the meantime.

## Sources

- [TurtleTrader HPD](https://www.turtletrader.com/hpd/)
- [PortaraCQG Historical Daily Futures Data](https://portaracqg.com/historical-daily-futures-data/)
- [Databento CME Globex MDP 3.0](https://databento.com/datasets/GLBX.MDP3)
- [Databento $125 Sign-Up Credits](https://cloudcredits.io/providers/databento/programs/databento-sign-up-credits)
- [QuantVPS: free/cheap CME historical data guide](https://www.quantvps.com/blog/cme-historical-data-complete-guide)
- [Investing.com S&P 500 Futures Historical Data](https://www.investing.com/indices/us-spx-500-futures-historical-data)
- [TradeStation Extended Historical Data](https://help.tradestation.com/10_00/eng/tradestationhelp/data_network/extended_historical_data.htm)
- [Tradovate Free Simulated Trading](https://info.tradovate.com/simulated-trading)
- [2026-10-05-free-futures-data-for-qkt-pipeline-validation.md](2026-10-05-free-futures-data-for-qkt-pipeline-validation.md)
- [2026-10-05-es-nq-backtest-data-acquisition.md](2026-10-05-es-nq-backtest-data-acquisition.md)
