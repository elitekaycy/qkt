# Staged plan: build the futures data pipeline from free MBO samples first

Date: 2026-10-05

## Scope

A third-party research summary (pasted into the working conversation, treated
as an unverified claim set, not fact) proposed building qkt's futures data
pipeline in stages, starting from free CME/Databento order-book *samples*
rather than buying years of history up front. Each concrete claim was checked
against primary sources before adopting any of it. This doc records what
verified, what didn't, and the resulting plan.

## Verification results

| # | Claim | Verdict |
|---|---|---|
| 1 | CME publishes free MBO sample files (ES, CL, GC, 6E, rates, options) | **Confirmed**, with a correction — real page, real `.gz` files, anonymous access, no login/payment. Sample *duration* (how many days/hours per file) could not be confirmed programmatically (CME's host returns a 403 to automated requests via Akamai bot-protection) — needs a real-browser check. |
| 2 | CME DataMine GC MBO full history priced at "$34,155" | **Unconfirmed, likely fabricated specificity** — DataMine pricing is never posted publicly; you must create an account and configure an order to see any number. Don't plan budget around this figure. |
| 3 | Databento has downloadable free sample files for CME Globex L1/L2/MBO (claimed ~387MB/1.8GB/855MB) | **Confirmed to exist and genuinely free** (no signup, plain unauthenticated GET, verified HTTP 200) — more generous than prior research found (which only knew about the $125 signup credit). **The sizes in the claim were wrong** — those numbers belong to Databento's *equities* (Nasdaq TotalView-ITCH) samples. The real CME futures (`GLBX.MDP3`) sample sizes are **L1/MBP-1: 350MB, L2/MBP-10: 1.5GB, L3/MBO: 493MB**. |
| 4 | GitHub `axb0306/cme-futures-ohlc` covers 51 symbols with deep history | Symbol list **confirmed** (ES/NQ/GC/CL/6E/ZN + micros + more). History depth **reconfirms the prior finding, contradicts the claim's framing** — checked actual files via GitHub API: daily data starts **2025-03-24**, 1-min starts **2026-01-20**, tick starts **2026-03-09**. Not years of history; useful only for near-term pipeline/format testing. |
| 5 | A public KRX futures (KS200/KQ150) LOB dataset, 13 days, Nov–Dec 2022, downloadable | Research artifact **exists and is described accurately** in `Jeonghwan-Cheon/lob-deep-learning`, but the raw data **is not actually included or linked** in the repo — only preprocessing code for a copy you'd have to already own. Not usable as claimed. |

## The actual Stage 1 plan — grounded in what's verified

**Stage 1 (now, $0): prove the MBO decoder + `MarketSource` adapter against real CME data.**

1. Pull Databento's free CME futures samples directly —
   `L1/MBP-1` (350MB), `L2/MBP-10` (1.5GB), `L3/MBO` (493MB) from
   `databento.com/tick-data`. These are real DBN-format files, confirmed
   downloadable with no account. This is the better of the two free sources
   for this stage because Databento's schemas are the ones already mapped out
   in
   [2026-10-04-futures-options-data-pipeline.md](2026-10-04-futures-options-data-pipeline.md)
   (struct fields verified against their `dbn` source) — building the
   decoder against the real wire format, not a guess.
2. Cross-check against CME's own MBO sample `.gz` files (ES, CL, GC, 6E,
   rates, options) from the Confluence-hosted sample page — these are the
   exchange's own raw MDP3 output, useful as a second, independent reference
   to confirm the Databento-derived decoder reconstructs the same book state
   a direct-from-exchange capture would.
3. Build and prove, against these samples only (no money spent): the MBO
   decoder, book reconstruction, L2 aggregation from L3, trade reconstruction
   — i.e. exercise the same pipeline shape described in
   [2026-10-04-futures-options-data-pipeline.md](2026-10-04-futures-options-data-pipeline.md)'s
   data-ladder, but against real exchange bytes instead of a prose
   description of them.
4. Use the free Kaggle `choweric/cme-es`/`cme-nasdaq` daily per-expiry data
   (per
   [2026-10-05-free-futures-data-for-qkt-pipeline-validation.md](2026-10-05-free-futures-data-for-qkt-pipeline-validation.md))
   in parallel to prove the separate, simpler concern: `ContractCatalog`/
   `RollPolicy`/`PriceAdjustment` roll-stitching, which doesn't need book
   depth at all.

**What Stage 1 deliberately does not attempt**: a multi-year backtest. The
free samples are single-file snapshots, not continuous history — this stage
is purely "does the decoder/adapter/roll logic work," answered against real
bytes, at zero cost.

**Stage 2 (small spend, once Stage 1 passes): a short real-history slice.**
Per
[2026-10-05-watchlist-sized-futures-data-pricing.md](2026-10-05-watchlist-sized-futures-data-pricing.md),
Kibot's $400–800 one-time 10-symbol package is the known, price-transparent
option for this; Databento's metered pricing (confirmed real anchor: $2.17
for one symbol/5 days of `trades`) is the alternative if book depth over a
short window is specifically wanted, after a direct `get_cost()` quote.
Explicitly do not buy CME DataMine's full MBO archive at this stage — its
real price is unconfirmed and likely far beyond what a short validation slice
needs; the $34,155 figure should not be assumed real or used to rule DataMine
out either, since the actual catalog price was never independently seen.

**Stage 3 (ongoing, after Stage 1+2 prove the pipeline): capture your own
live data on bot2 going forward.** Once a live CME connector exists (IBKR or
Rithmic, per
[2026-10-05-es-nq-backtest-data-acquisition.md](2026-10-05-es-nq-backtest-data-acquisition.md)),
every day captured live is a day you never have to buy historically again —
this is the same "forge bar store" pattern bot2 already runs for crypto/CFD
data, just extended to a new venue.

## Open items before Stage 1 execution

- Confirm actual sample file *duration* (how many trading days/hours) for
  both the CME `.gz` files and the Databento CME samples with a real browser
  session — automated fetches were blocked by CME's Akamai bot-protection.
- Decide file layout under qkt's data root for futures samples/captures,
  mirroring how CFD data is laid out today — this is the next concrete design
  question once Stage 1's decoder work starts.

## Sources

- CME MBO sample page: `cmegroupclientsite.atlassian.net/wiki/spaces/EPICSANDBOX/pages/457223111/MBO+FIX`
- Databento tick-data samples: `databento.com/tick-data`
- [GitHub axb0306/cme-futures-ohlc](https://github.com/axb0306/cme-futures-ohlc)
- [GitHub Jeonghwan-Cheon/lob-deep-learning](https://github.com/Jeonghwan-Cheon/lob-deep-learning)
- [2026-10-04-futures-options-data-pipeline.md](2026-10-04-futures-options-data-pipeline.md)
- [2026-10-05-free-futures-data-for-qkt-pipeline-validation.md](2026-10-05-free-futures-data-for-qkt-pipeline-validation.md)
- [2026-10-05-watchlist-sized-futures-data-pricing.md](2026-10-05-watchlist-sized-futures-data-pricing.md)
- [2026-10-05-es-nq-backtest-data-acquisition.md](2026-10-05-es-nq-backtest-data-acquisition.md)
