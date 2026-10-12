# Futures contract selection beyond ES/NQ

Date: 2026-10-05

## Scope and purpose

Read-only research to inform a buy/build decision on data sourcing and strategy
research priorities. Question: beyond ES and NQ, which CME-family futures
contracts are good candidates for systematic (intraday/swing) strategy research —
enough movement for an edge to clear transaction costs, but still liquid enough to
trade and to source decent historical data for.

Methodology note: this is desk research synthesized from CME Group's own press
releases/market-data pages (authoritative for volume), and from trading-education
and prop-firm blog sources (useful for range/ATR figures and style framing, but
**not independently verified** — flagged below). No qkt backtests were run for
this doc; it is a market-selection literature review, not an edge claim.

## 1. Volatility / liquidity comparison

### Volume — CME-reported, authoritative

CME Group's own press releases give real, dated ADV (average daily volume)
figures. From the September/Q3 2026 record release and related monthly releases:

- Overall CME ADV: 31.8M contracts/day in September 2026 (+22% YoY); Q3 2026 ADV
  29.4M. [CME Group press release, Sept/Q3 2026](https://investor.cmegroup.com/news-releases/news-release-details/cme-group-reports-record-average-daily-volume-september-and-q3)
- Interest rates: Interest Rate ADV +22% to 16.2M contracts/day; U.S. Treasury
  futures+options ADV +42% to 9.5M/day. **ZN (10-Year T-Note) specifically**:
  ADV ~2.5–2.9M contracts/day across different 2026 reporting periods (March
  +21% to 2.9M; Q3 +27% to 2.5M; September +51% to 2.6M) — ZN is one of the most
  liquid futures contracts on earth. [CME press releases, 2026](https://www.cmegroup.com/media-room/press-releases/2026/7/02/cme_group_reportsrecordjuneaveragedailyvolumeandsecond-highestq2.html)
- Energy: Energy ADV +37% to 3.1M/day; **Henry Hub Natural Gas (NG)** ADV +20% to
  602K/day; Micro WTI Crude Oil ADV +376% to 240K/day (micro growth story, see
  §3). **CL (crude oil)** full-size ADV is commonly cited around ~700–800K
  contracts/day (blog-sourced, consistent with CME's energy-complex totals but
  not a direct CME per-symbol citation — treat as approximate).
- Agriculture (context, not in this task's scope): corn ADV +62% to 461K/day,
  soybeans +35% to 289K/day — included only to show agriculture is a real
  volume category too, if ever considered.
- FX: CME FX futures+options hit 3.3M contracts on a single September 2026 day
  (3.2M of which futures) — fourth-highest ADV since 2014. **6E (Euro FX)** is
  the dominant FX future; third-party (non-CME) sources cite 6E trading
  **200K+ contracts/day** in active sessions — **unconfirmed against a primary
  CME per-symbol figure**, but directionally consistent with 6E being the most
  liquid currency future by a wide margin over 6J (yen) and 6B (pound).
- Equities: **ES** ADV cited at **~1.6M contracts/day** (blog-sourced
  aggregation, not a direct CME quote pulled in this pass, but plausible given
  ES's known dominance). **MNQ now regularly outtrades full-size NQ** per 2026
  reporting — a genuinely interesting liquidity-migration fact (see §3).
- Metals: Gold (GC) ADV cited around **300K+ contracts/day**.
- **RTY, YM, HG (copper), SI (silver), 6J, 6B, ZB** — no reliable per-symbol ADV
  figure was found in this pass from a primary CME release; these are
  meaningfully less liquid than ES/NQ/CL/GC/ZN/6E but are still exchange-top-30
  products traded by CTAs and prop firms daily. Treat any specific number for
  these as **unconfirmed** until pulled directly from CME's
  [exchange-volume](https://www.cmegroup.com/market-data/browse-data/exchange-volume.html)
  or monthly volume report PDFs.

**Actionable next step if precision matters**: CME publishes a monthly volume
report PDF (`Web_Volume_Report_CMEG.pdf`) and live
[exchange-volume](https://www.cmegroup.com/market-data/browse-data/exchange-volume.html)
/ [metals-volume](https://www.cmegroup.com/market-data/browse-data/metals-volume.html)
/ [equity-volume](https://www.cmegroup.com/market-data/browse-data/equity-volume.html)
pages with exact per-symbol ADV and open interest — worth a direct pull before
finalizing a data-vendor spend decision, since blog aggregations in this report
were inconsistent on CL/GC specifics.

### Daily range / ATR — blog-sourced, **unconfirmed**, directional only

No primary-source (CME) ATR/range dataset was found; the figures below are from
trading-education sites (atlasalgo.io, bulltraders.com, volatilitybox.com,
tradingsim.com) and should be treated as **rough, time-varying approximations**,
not research-grade constants:

| Contract | Typical daily range (cited) | Character (cited) |
|---|---|---|
| ES | ~40–80 index pts | steady, trendable but lower raw-point range than NQ |
| NQ | ~200–400 index pts (NQ moves ~1.3–1.5x ES on a % basis) | highest vol of the index complex, favored for trend/breakout |
| RTY | ~20–35 pts, but highest **% volatility** among index futures (small-cap beta) | choppier, gap-prone around macro releases |
| CL | ~$1–3/barrel ($1,000–$3,000/contract) | large absolute moves around EIA inventory, OPEC headlines; historically trend-until-it-doesn't, dangerous for naive mean reversion on outright price |
| GC | ~$20–50/oz ($2,000–$5,000/contract) | macro/inflation/dollar driven, distinct catalyst set from equities |

These numbers are directionally useful (NQ > ES in raw-point and %-range terms;
RTY has outsized %-volatility despite modest point range; CL and GC both move
enough per day to clear realistic commission+slippage on a well-sized
intraday/swing system) but should be re-derived from qkt's own backtest data
(ATR indicator over real history) before being used to size strategies, per
`qkt/docs/reference/dsl/*.md` indicator conventions.

## 2. Style classification — trend vs. mean-reversion vs. avoid

**Trend-following — well-documented, cross-sourced:**
- The managed-futures/CTA industry's live track record is built almost entirely
  on time-series momentum/trend-following across **commodities, currencies, and
  rates** (not single-stock-index trend). CASAM CISDM CTA index compounded
  ~14.5%/yr 1980–2010 vs ~7% for US stocks; Barclay CTA Index +~14% in 2008 while
  S&P 500 fell ~37%; SG Trend Index +~27% in 2022 while stocks and bonds both
  fell. [Candriam](https://www.candriam.com/en-us/professional/insight-overview/publications/qa/cta---commodity-trading-advisors-a-trendy-investment/),
  [ETF.com/Swedroe](https://www.etf.com/sections/index-investor-corner/swedroe-trend-following-managed-futures?nopaging=1),
  [CME CTA comparison paper](https://www.cmegroup.com/education/files/a-comparison-of-cta-indexes.pdf)
  — this is the strongest, best-cited evidence point in this report: **currencies
  and commodities (not equity indices) are the historically-documented home of
  systematic trend-following edge.**
- NQ is repeatedly cited in trading-education material as the equity-index
  contract most "favored for trend following" due to its higher volatility —
  but this is a style preference claim from blogs, not a CTA-index-level
  statistical claim like the one above.

**Mean-reversion — mixed, market-structure dependent:**
- Crude oil **outright price** mean-reverts unreliably ("trends until it
  doesn't" — naive dip-buying eventually hits 2014 or 2020 and doesn't
  recover). Reliable mean reversion in oil lives in **spreads** (WTI–Brent,
  calendar spreads vs. inventory), not in CL outright — relevant if considering
  CL for a mean-reversion system rather than trend/breakout.
  [Substack: Do Commodity Futures Trend or Mean Revert?](https://inthemoneybyzerodha.substack.com/p/do-commodity-futures-trend-or-mean)
- Academic literature (ScienceDirect) on energy-futures mean reversion reports
  Sharpe ratios >2 for WTI and Natural Gas in some specifications — **a
  peer-reviewed-adjacent source**, but methodology/period not verified here;
  treat as a lead for further reading, not a settled fact.
  [ScienceDirect: Trading on mean-reversion in energy futures markets](https://www.sciencedirect.com/science/article/abs/pii/S014098831500208X)
- Major FX pairs are commonly cited as the "most stationary" (mean-reverting)
  instruments among liquid futures — consistent with 6E/6J/6B being useful for
  range-bound/mean-reversion systems in calm regimes, though they also trend
  hard during macro (rate-divergence, carry-unwind) regimes.

**Illiquid/avoid for algo execution:**
- Lumber and orange juice (FCOJ) are the two most commonly named "stay away"
  thin markets in retail/prop-firm education: low open interest, bids/offers
  that evaporate during moves, large slippage from small orders. Neither was in
  this task's candidate list, but worth noting as the negative baseline — none
  of ES/NQ/RTY/YM/CL/NG/GC/SI/HG/6E/6J/6B/ZN/ZB/VX fall into this "avoid" tier;
  they are all CME-top-tier or CFE-flagship products.
- **VX/VIX futures do not trade on CME** — they trade on **Cboe Futures
  Exchange (CFE)**, a separate exchange from the CME-family products in this
  task. Flagging explicitly since the task scoped "CME-family." VIX futures are
  liquid (tens of thousands of contracts/day) and have a Mini-VIX (1/10 size)
  contract, but margin is high (~$4,000–$6,000/contract cited) and the term
  structure/contango dynamics make backtesting materially harder than a linear
  product — a reasonable "stretch" addition but outside strict CME scope and
  structurally different (cash-settled on a forward-implied-vol index, not a
  physical/financial underlying).
  [Optimus Futures VIX guide](https://optimusfutures.com/blog/vix-futures-guide/),
  [Macroption VIX futures](https://www.macroption.com/vix-futures/)

## 3. Micro contracts — confirmed specs

Confirmed from CME Group's own contract-spec pages:

- **MES (Micro E-mini S&P 500)**: $5 × S&P 500 index, tick 0.25 pts = $1.25/tick.
  [CME MES](https://www.cmegroup.com/markets/equities/sp/micro-e-mini-sandp-500.html)
- **MNQ (Micro E-mini Nasdaq-100)**: $2 × Nasdaq-100 index, tick 0.25 pts =
  $0.50/tick. [CME MNQ](https://www.cmegroup.com/markets/equities/nasdaq/micro-e-mini-nasdaq-100.contractSpecs.html)
- Both are exactly **1/10th the notional** of their full-size E-mini (ES/NQ)
  counterparts, same trading hours (Sun–Fri 5pm–4pm CT, 1hr daily halt).

Blog-sourced (not independently re-verified against CME spec pages in this
pass, but internally consistent and plausible) for the rest:

- **M2K** (Micro Russell 2000): $5 multiplier (1/10 of RTY).
- **MYM** (Micro Dow): $0.50 multiplier (1/10 of YM) — smallest $/tick of the
  micro-index suite.
- **MGC** (Micro Gold): $10 multiplier, tick 0.10 = $1.00/tick (1/10 of GC).
- **SIL** (Micro Silver): $1,000 multiplier, tick 0.005 = $5.00/tick.
- **MCL** (Micro WTI Crude): $100 multiplier, tick 0.01 = $1.00/tick (1/10 of
  CL).

**Notable liquidity-migration fact** (from CME's own 2026 reporting, cited
above): **MNQ now regularly out-trades full-size NQ**, and **Micro WTI (MCL)
ADV was up 376% YoY to 240K contracts/day in September 2026** — micros are not
a toy product anymore; for several contracts they are now where the retail/
small-systematic flow actually is. This directly supports testing at micro size
before scaling, both for realistic position sizing and because the micro order
book itself is now deep enough to be representative.

Recommendation for this task's framing: **confirm exact current specs (tick
size, margin, multiplier) directly against CME's contract-spec pages per
symbol before wiring into qkt's `instruments.yaml`**, since multipliers and
tick sizes are exchange-set and this doc's secondary sources should not be the
system of record for cost modeling.

## 4. Data/connectivity coverage

Databento's `GLBX.MDP3` dataset (CME Globex MDP 3.0) is the **single feed for
essentially everything in this comparison**: it covers all CME, CBOT, NYMEX, and
COMEX-listed futures, options, spreads and combinations in one feed —
650,000+ symbols, tick-level with full order-book depth. CME explicitly lists
ES, CL, 6B, ZN among its own example instruments on that feed.
[Databento GLBX.MDP3](https://databento.com/datasets/GLBX.MDP3)

Practical implication for the buy/build decision: **picking a vendor for ES
(e.g., Databento GLBX.MDP3) is picking a vendor for essentially the entire
candidate list** — NQ, RTY, YM, CL, NG, GC, SI, HG, 6E, 6J, 6B, ZN, ZB and all
their micro equivalents (MES, MNQ, M2K, MYM, MGC, SIL, MCL, etc.) are the same
exchange group (CME/CBOT/NYMEX/COMEX) and therefore the same feed/dataset —
there is **no per-contract vendor-selection problem** within CME-family
products. The one named exception is **VX/VIX**, which is CFE (Cboe), not CME —
if VIX futures are wanted, that is a **separate data source** from
GLBX.MDP3 (Cboe's own feed or a vendor with CFE coverage), not a line item
within the CME dataset purchase.

This was not independently cross-checked against a second vendor (e.g.
CQG, Barchart, IQFeed, Polygon) in this pass — Databento was the only vendor
researched because it was named in the task. If a second data-vendor quote is
wanted, same question (coverage breadth across the symbol list) should be
checked explicitly, but the structural point — one CME membership/feed =
coverage of essentially the whole non-VIX candidate list — should hold across
vendors since it reflects exchange structure, not vendor-specific packaging.

## 5. Recommendation

For a solo systematic trader already running ES + NQ wanting 1–2 more
contracts for diversification, the most consistently recommended pair across
prop-firm education and trading-literature sources is:

**CL (Crude Oil) and GC (Gold)** — or their micros (**MCL, MGC**) at
small-account size.

Why this combination, synthesizing the above:
1. **Different catalyst set, not just different beta.** ES/NQ move on equity-
   risk and rate-expectations flow; CL moves on EIA inventories, OPEC+
   supply headlines, and geopolitical risk; GC moves on real yields, dollar
   strength, and safe-haven flow. When equity indices chop sideways, CL or GC
   frequently still have a clean directional day — genuine diversification of
   *opportunity*, not just of *correlation statistics*.
2. **Enough raw movement to clear costs.** Both show daily ranges in the
   thousands of dollars per full-size contract (cited ranges above), well
   above typical commission+slippage for either full-size or micro contracts.
3. **Liquid enough to trade and to get clean data for.** Both are top-tier CME
   products (energy and metals volume leaders respectively), both have mature
   micro equivalents (MCL, MGC) for realistic small-size testing, and both are
   covered by the same GLBX.MDP3 feed already needed for ES/NQ — **zero
   incremental data-vendor cost** to add them.
4. **Historically documented edge type is different from equities.** The CTA/
   managed-futures literature's trend-following track record is built on
   commodities and currencies, not equity indices — so adding CL/GC is also
   adding exposure to the asset classes where systematic trend-following has
   the longest, best-documented live track record, as opposed to adding a
   third/fourth equity-index future that is highly correlated with ES/NQ
   (RTY and YM move with ES/NQ most of the time; marginal diversification
   value is lower).

**Runner-up candidates, with caveats:**
- **6E (Euro FX)** — most liquid currency future, different driver (FX/rate
  differential), commonly cited as mean-reversion-friendly in calm regimes;
  reasonable 3rd addition if currency exposure specifically is wanted, but the
  task's own sources suggest CL/GC is the more commonly cited "first two
  beyond ES/NQ" pairing.
- **ZN (10-Year Note)** — extremely liquid (one of the most liquid futures on
  earth per 2026 CME data), but behaves more like a slow-moving rates/macro
  instrument; good for rates-view strategies, less obviously suited to
  intraday breakout systems than CL/GC.
- **NG (Natural Gas)** — high realized volatility and decent liquidity (ADV
  602K/day per CME September 2026 data), but literature consistently flags it
  as prone to violent, news-driven (storage report, weather) gap moves that
  are harder to risk-manage systematically than CL; a plausible 3rd pick for a
  trader specifically wanting more volatility, with the explicit caveat that
  gap risk is higher.
- **VX/VIX** — interesting but out of CME-family scope (Cboe/CFE), different
  data vendor, and structurally harder to backtest (term structure, contango
  roll) — not recommended as a near-term addition given the stated goal of
  adding "1–2 more contracts" cleanly.

## Open questions / unconfirmed items to close out before committing spend

- Exact current per-symbol CME ADV for RTY, YM, HG, SI, 6J, 6B, ZB — pull
  directly from CME's monthly volume report PDF or exchange-volume page rather
  than relying on this doc's blog-sourced approximations.
- Independent ATR/daily-range dataset (ideally computed from qkt's own ingested
  history via the `atr`/range indicators in `qkt/docs/reference/dsl/indicators.md`)
  rather than trading-blog figures, before using range numbers for position
  sizing or strategy-fit decisions.
- Second data-vendor coverage check (CQG/Barchart/IQFeed/Polygon) if Databento
  pricing or terms end up being a blocker — the "one feed covers everything"
  conclusion in §4 should hold structurally but was only checked against
  Databento in this pass.

## Sources

- [CME Group: Record September/Q3 2026 ADV](https://investor.cmegroup.com/news-releases/news-release-details/cme-group-reports-record-average-daily-volume-september-and-q3)
- [CME Group: Record June 2026 ADV](https://www.cmegroup.com/media-room/press-releases/2026/7/02/cme_group_reportsrecordjuneaveragedailyvolumeandsecond-highestq2.html)
- [CME Group: July 2026 volume record](https://www.cmegroup.com/media-room/press-releases/2026/8/04/cme_group_july_volumehitsnewrecordof27millioncontractsup23yearov.html)
- [CME Group exchange volume data](https://www.cmegroup.com/market-data/browse-data/exchange-volume.html)
- [CME Group metals volume data](https://www.cmegroup.com/market-data/browse-data/metals-volume.html)
- [CME Group equity volume data](https://www.cmegroup.com/market-data/browse-data/equity-volume.html)
- [CME Group monthly volume report PDF](https://www.cmegroup.com/daily_bulletin/monthly_volume/Web_Volume_Report_CMEG.pdf)
- [CME Group: Micro E-mini S&P 500 (MES) contract page](https://www.cmegroup.com/markets/equities/sp/micro-e-mini-sandp-500.html)
- [CME Group: Micro E-mini Nasdaq-100 (MNQ) contract specs](https://www.cmegroup.com/markets/equities/nasdaq/micro-e-mini-nasdaq-100.contractSpecs.html)
- [CME: A Comparison of CTA Indexes (education PDF)](https://www.cmegroup.com/education/files/a-comparison-of-cta-indexes.pdf)
- [Candriam: CTA — a "trendy" investment?](https://www.candriam.com/en-us/professional/insight-overview/publications/qa/cta---commodity-trading-advisors-a-trendy-investment/)
- [ETF.com/Swedroe: Trend Following & Managed Futures](https://www.etf.com/sections/index-investor-corner/swedroe-trend-following-managed-futures?nopaging=1)
- [Do Commodity Futures Trend or Mean Revert? (Zerodha/Substack)](https://inthemoneybyzerodha.substack.com/p/do-commodity-futures-trend-or-mean)
- [ScienceDirect: Trading on mean-reversion in energy futures markets](https://www.sciencedirect.com/science/article/abs/pii/S014098831500208X)
- [Databento: CME Globex MDP 3.0 (GLBX.MDP3) dataset](https://databento.com/datasets/GLBX.MDP3)
- [Optimus Futures: VIX Futures Guide](https://optimusfutures.com/blog/vix-futures-guide/)
- [Macroption: VIX Futures](https://www.macroption.com/vix-futures/)
- [atlasalgo.io: Best Futures Markets to Trade — ES, NQ, CL, GC Compared](https://atlasalgo.io/blog-best-futures-markets-to-trade.html) (blog, range figures unconfirmed)
- [bulltraders.com: Best Futures Contracts to Day Trade](https://bulltraders.com/blog/best-futures-contracts-to-day-trade-2026/) (blog, range figures unconfirmed)
- [edgeful.com: best futures to trade — a data-driven comparison](https://www.edgeful.com/blog/posts/best-futures-to-trade) (blog, unconfirmed)
- [optimusfutures.com: Best Futures to Trade in 2026](https://optimusfutures.com/blog/best-futures-to-trade/) (blog, unconfirmed)
