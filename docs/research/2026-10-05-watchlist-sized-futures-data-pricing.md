# Pricing for a ~10-symbol watchlist, not the whole CME universe

Date: 2026-10-05

## Correction to prior research

[2026-10-05-es-nq-backtest-data-acquisition.md](2026-10-05-es-nq-backtest-data-acquisition.md)
stated Firstrate Data's $99.95/yr as a flat full-package price. **That was
wrong.** Firstrate's own FAQ states verbatim: *"Updates are approximately $99
per year for individual tickers, and between $59 and $119 per month for
bundles."* $99.95/yr is the **per-symbol** annual update fee; their 130-symbol
"Futures – Most Active" bundle is actually $59–$119/**month** ($720–$1,430/yr)
instead. Buying 10 symbols individually (~10 × $99.95 ≈ **$1,000/yr**, plus
one-time base-purchase fees per symbol not fully confirmed) is cheaper than
the bundle for a small watchlist, but nowhere near the "$10/symbol/yr"
framing in the prior doc. **Firstrate is deprioritized below where it was
previously ranked** for a watchlist-sized plan specifically.

## Scope

This doc answers the actual ask: cheap (not necessarily free) data for a
small, deliberately chosen watchlist of ~10 volatile, liquid futures (e.g. ES,
NQ, RTY, CL, GC, SI, NG, 6E, ZN + micros) — not full CME-universe access.

## Historical data — confirmed per-vendor economics for ~10 symbols

- **Kibot — the actual bargain here.** Confirmed via their site: tiered
  packages exist by symbol count (10, 25, 83, 3000+...), not one flat
  all-futures price. Their **"Top 10 Futures Continuous Contracts"** tier:
  **$800 one-time for tick+bid/ask**, or **$400 one-time for 1-minute**
  (30/15/5-min bundled free). Flat, no subscription, genuinely scoped to a
  10-symbol watchlist — this is the cleanest match to the actual ask.
- **Databento — proportional, no minimum, but rate still unconfirmed for
  real use.** Historical billing is metered by bytes of exactly what you
  request; a concrete real example was found: pulling the `trades` schema for
  one symbol (ESH4) over 5 days cost **$2.17**. That's an anchor, but not
  enough to extrapolate a 10-symbol, multi-year MBP-1 cost — get a
  `metadata.get_cost()` quote before committing. Best choice if you want
  tick/MBP-1 depth and prefer pay-exactly-for-what-you-use over a flat
  package.
- **PortaraCQG — confirmed per-symbol, explicit pricing found this time.**
  Their pricing page states "the price is the same whatever range you need,
  so the full history is always included," sold per commodity: **$330
  (currently $220 on special) per symbol, one-time**, full history included.
  10 symbols ≈ **$2,200–$3,300 one-time**. Real option, but costs more than
  Kibot's equivalent tier for the same use case.
- **CSI Data (Unfair Advantage)** — prices by category, not strictly per
  symbol: "North American Futures" license **$60 one-time** (10yr history,
  +$20/extra year) + platform fee **$26–$33/mo or $313/yr prepaid**. Appears
  to cover the whole futures category rather than metering by symbol count —
  worth a follow-up call to confirm scope before relying on this reading.
- **Firstrate** — per the correction above, ~$1,000/yr for 10 symbols
  individually; no longer the top recommendation for this specific ask.

## Live data — flat/bundle-based regardless of watchlist size (good news)

Both realistic live paths price by **exchange group, not symbol count** — so
a 10-symbol CME-family watchlist costs exactly the same as watching the whole
exchange:

- **IBKR**: the $10/mo "US Securities Snapshot and Futures Value Bundle"
  covers real-time top-of-book for **all of CBOT/CME/COMEX/NYMEX** in one
  flat fee (waived in any month with ≥$30 commissions). Confirmed: watchlist
  size doesn't change this cost at all.
- **Rithmic**: per-exchange-group fees (CME/Globex L1 $5/mo, L2 $17/mo) or
  the "CME Bundle (ALL CME Markets)" at L1 $15/mo / L2 $45/mo, covering
  CBOT+CME+COMEX+NYMEX together, plus a flat $25/mo connection fee and
  $0.10/contract routing when live. Same conclusion: flat regardless of how
  many symbols within the bundle you actually watch.

## Recommendation for the ~10-symbol watchlist specifically

1. **Historical**: Kibot's "Top 10 Futures Continuous Contracts" — **$400
   one-time** for 1-minute bars (the floor granularity worth paying for), or
   **$800 one-time** if tick+bid/ask fidelity is wanted from the start. No
   subscription, no per-symbol scaling surprise, and already matches "a
   couple 10" symbols exactly.
2. **If MBP-1/depth data becomes necessary later** for a specific strategy
   (not up front): Databento, pay-exactly-for-the-10-symbols-you-pick, after
   a direct `get_cost()` quote.
3. **Live**: IBKR's $10/mo bundle — cheapest, flat, already covers the whole
   watchlist's exchange family with zero per-symbol metering.
4. **Free pipeline-validation step is unchanged**: still start with the
   Kaggle `choweric/cme-es`/`cme-nasdaq` free data
   ([2026-10-05-free-futures-data-for-qkt-pipeline-validation.md](2026-10-05-free-futures-data-for-qkt-pipeline-validation.md))
   to prove the ingestion/roll pipeline before spending the $400–$800 on
   Kibot.

## Open items

- Databento's exact $/GB for MBP-1 over a multi-year, multi-symbol window —
  still needs a direct `get_cost()` check; the $2.17/5-day/1-symbol `trades`
  anchor doesn't extrapolate reliably to MBP-1 or to years of history.
- PortaraCQG's live/subscription dollar pricing — still custom-quoted only.
- CSI Data's actual license scope (true category coverage vs. per-symbol
  metering) — needs a follow-up call to confirm.

## Sources

- [Firstrate Data FAQ](https://firstratedata.com) (per-symbol update pricing, verbatim quote)
- [Kibot buy page](https://www.kibot.com/buy.html) (tiered symbol-count packages)
- [Databento GLBX.MDP3](https://databento.com/datasets/GLBX.MDP3)
- [PortaraCQG pricing](https://portaracqg.com)
- [CSI Data / Unfair Advantage](https://unfairadvantage.com)
- [IBKR Market Data Pricing](https://www.interactivebrokers.com/en/pricing/market-data-pricing.php)
- Rithmic reseller pricing (per prior research thread)
- [2026-10-05-es-nq-backtest-data-acquisition.md](2026-10-05-es-nq-backtest-data-acquisition.md)
- [2026-10-05-free-futures-data-for-qkt-pipeline-validation.md](2026-10-05-free-futures-data-for-qkt-pipeline-validation.md)
