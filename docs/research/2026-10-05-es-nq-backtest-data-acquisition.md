# Getting real ES/NQ data for backtesting — acquisition options

Date: 2026-10-05

## Scope and purpose

Read-only research to answer: concretely, how does a solo/small-shop trader get
usable historical data for ES/NQ (and by extension the rest of the CME-family
list in
[2026-10-05-futures-contract-selection-beyond-es-nq.md](2026-10-05-futures-contract-selection-beyond-es-nq.md))
to build a real backtest, and then carry the same symbols into live/paper
trading? This is a buy/build decision input, not a final vendor choice — several
numbers below are flagged unconfirmed and need a direct quote before spending
money. Builds on
[2026-10-04-futures-options-data-pipeline.md](2026-10-04-futures-options-data-pipeline.md)
(Databento's schema/format model) — this doc is about cost and access, not
format.

## 1. Databento for ES/NQ history — what it actually costs

Historical data is **pay-as-you-go even under a subscription** — the Standard/
Plus/Unlimited tiers are mostly about live access and how much history is
*included before usage billing kicks in* (Standard: 16+ yrs of L0, only 1 yr of
L1 included). Anything beyond the included window, or any pull without a
subscription at all, is billed **per GB of uncompressed DBN**, regardless of
whether you export it as DBN, CSV, or JSON.

**The per-GB rate is genuinely unclear from public sources** — one citation says
CME historical data is priced "from $0.50/GB," another cites "$10.00/GB" for CME
historical streaming. Likely explanation: $0.50/GB is the floor for cheap
schemas (OHLCV/trades/definition); MBO/MBP tick schemas — what you'd actually
want for ES/NQ backtesting — probably cost more. **Do not budget off either
number** — call Databento's own `metadata.get_cost()` API (or the pricing
calculator on their site) with your exact symbol/schema/date-range request
before committing. That call is free and gives an exact quote; this doc
intentionally does not present a final dollar figure because none was reliably
sourced.

Similarly, **no verified GB-per-year figure for ES MBP-1 was found** — treat any
estimate (including "a few GB/month for MBP-1 on one liquid future") as a rough
guess pending that same cost-calculator check.

What's solid: new accounts get **$125 in free credit**, good for 6 months,
enough for a sample pull/data-quality check on ES+NQ but not for assembling a
full multi-year tick dataset on two symbols.

## 2. Retail-friendly alternatives — concrete pricing, compared

| Vendor | Granularity | ES/NQ history | Price | Gives you continuous contracts? |
|---|---|---|---|---|
| **Firstrate Data** | tick, 1s/1min/5min/30min/1hr | ~19 yrs intraday, continuous since Jan 2008 | **$99.95/yr** flat, covers full futures package | **Yes** — pre-built back-adjusted continuous series, plus raw per-expiry |
| **Kibot** | tick+bid/ask, 1s/10s/1min(+bid/ask) | continuous since 2009, 83 symbols | **one-time purchase**: tick+bid/ask $1,420; 1-min $520; 1-min+bid/ask $800; 10s $870; 1s $1,120 | **Yes** — dedicated continuous-contract product line |
| **Norgate Data** | EOD only, no intraday | ~1980 onward, ~100 markets | **$148.50/6mo or $270/yr** | **Yes**, both unadjusted and back-adjusted, documented roll rule |
| **IQFeed (DTN)** | true tick, full depth | only 180 days raw tick (10+ yrs of 1-min bars) | **~$105–115/mo** base + CME exchange fees (~$3–15/mo) + API add-on | No — it's a live feed with shallow tick history, not a backtest archive |
| **Polygon.io** | trades/quotes, minute aggregates | 2 yrs at $79/mo tier, 7+ yrs at higher tiers | **$79/mo** (CME/CBOT/NYMEX/COMEX, unlimited calls, 10-min delayed) | unconfirmed — not established either way |
| **CME DataMine** (direct) | depth, time & sales, EOD | full exchange archive | priced per data type/duration, no flat number published | no — raw expiry-coded exchange data, you build your own continuous series |
| **Tiingo** | — | — | — | **ruled out — no CME futures coverage at all** |

**For ES+NQ specifically**, Firstrate Data is the standout on price-to-value: a
flat **$100/yr** gets pre-built continuous series plus raw tick/1-min data back
to 2008, with no per-GB billing surprise and no continuous-contract construction
work on your end. Kibot is the next option if Firstrate's granularity doesn't
suffice, at meaningfully higher one-time cost. Norgate is EOD-only — fine for
daily-bar strategy research, not for anything needing intrabar fidelity.

## 3. Free options — none usable for a real backtest

No legitimately free tick or 1-minute ES/NQ data was found. What exists:
Yahoo Finance (`ES=F`/`NQ=F`) gives free continuous daily/intraday data but with
an undocumented roll methodology and known gaps — explicitly not designed for
programmatic reliability. Stooq/Investing.com, same caveat. TurtleTrader has
long free history but daily bars only. CME's own site gives shallow free
settlement samples, not bulk history. **Conclusion: budget for Firstrate Data at
minimum ($100/yr) — there is no free tier that survives contact with a real
backtest.**

## 4. Continuous-contract construction, if you end up with raw per-expiry data

If you buy from a vendor that only gives raw expiry-coded contracts (CME
DataMine, raw IQFeed/Polygon pulls), there's no mature turnkey pip package —
it's pandas scripts following one of a few named, well-documented methods:

- **Panama back-adjustment** — shift each prior contract's series by the price
  gap observed at the roll date (the method referenced throughout the
  [2026-10-04 doc](2026-10-04-futures-options-data-pipeline.md)'s roll-gap
  discussion). A standalone implementation exists as a public GitHub gist.
- **Backtrader**'s built-in rollover feature, if you adopt it as the backtest
  engine rather than building your own chaining logic.
- **Hudson & Thames** (`mlfinlab`) `multi_product.get_futures_roll_series` —
  supports both absolute and ratio adjustment.
- **QuantStart**'s widely-cited `futures_rollover_weights` pandas pattern.

Firstrate/Kibot/Norgate hand you the continuous series pre-built, which is the
practical reason to prefer them over assembling this yourself from raw exchange
data.

## 5. Live connectivity — carrying ES/NQ from backtest into real execution

Three realistic paths for a Kotlin-based engine wanting live CME futures quotes
+ order execution, at retail/small-shop budget:

- **Interactive Brokers (TWS API)** — cheapest real path. Real-time Level 1
  futures data ~**$10–15/mo**; historical access included/reasonable, with
  request-pacing limits (~60 requests/10min) to design around. Likely the best
  starting point for a solo trader wanting both data and execution in one
  account without separate exchange licensing.
- **Rithmic (via a reseller)** — the "pro-grade" low-latency futures-specific
  path many serious retail/small-shop algo traders and prop firms actually run
  on. Typical: **$125/mo flat** (User ID $25 + R|API+ $100) + **$0.10/contract**
  when live; cheaper resellers (e.g. EdgeClear) advertise ~$20/mo + the same
  per-contract fee. R|API+ gives server-side bracket/OCO/trailing-stop support —
  relevant since qkt already has bracket-order DSL support, so this maps
  cleanly onto existing engine primitives. Broad third-party platform support
  (Sierra Chart, MultiCharts, Quantower) if a reference implementation is ever
  needed to check a Kotlin connector against.
- **Tradovate API** — looks cheap ($25/mo API add-on) but isn't once you need
  real market data over the API: that requires becoming a CME sub-vendor under
  a CME Information License Agreement, **$290–500/mo** on top. Total
  **~$315–530/mo** — actually the most expensive of the three for a raw
  websocket/API integration. (Tradovate's own higher-level platform automation,
  without the raw API, runs ~$50/mo total, but that's a different integration
  shape than a Kotlin engine talking to a websocket directly.)
- **Databento live** — requires the same $199+/mo subscription tier as
  historical; no separate cheap live-only path.

**Bottom line**: IBKR is the cheapest way to get both live data and execution
in one place; Rithmic is the better-fitting path if the bracket/OCO
server-side order primitives matter and $125/mo + per-contract is acceptable;
Tradovate's raw-API route should probably be avoided given the CME licensing
cost stacks on top of the API fee.

## Decision for ES/NQ specifically

1. **Historical backtest data**: start with Firstrate Data ($99.95/yr) for
   pre-built continuous ES+NQ series back to 2008 — cheapest, least
   engineering effort, no per-GB billing surprise. Use Databento's $125 free
   credit separately as a cross-check/data-quality sample against Firstrate's
   series before trusting it for strategy research, not as the primary
   dataset.
2. **If strategy research later needs real book depth** (not just trades/
   bars) that Firstrate doesn't provide: that's the point to get an exact
   Databento MBP-1/MBP-10 quote via `metadata.get_cost()` for the specific
   date range needed, rather than estimating up front.
3. **Live/paper carry-forward**: IBKR TWS API first (cheapest, combines data +
   execution); evaluate Rithmic specifically if/when the bracket-order DSL
   parity with qkt's existing engine primitives becomes the deciding factor.

## Open questions / unconfirmed items to close before spending

- Exact Databento per-GB rate for MBP-1/MBP-10/MBO on GLBX.MDP3 — conflicting
  sources ($0.50/GB vs $10/GB cited); get a direct `metadata.get_cost()` quote.
- GB-size of multi-year ES/NQ MBP-1 history — no verified figure found anywhere;
  don't budget off an estimate.
- Polygon.io's continuous-contract handling (pre-built vs raw) — unconfirmed.
- Barchart OnDemand and TradeStation standalone data pricing — not found in
  this pass, would need a dedicated look if either becomes a real contender.

## Sources

- [Databento pricing](https://databento.com/pricing)
- [Databento usage-based pricing & data credits](https://docs.databento.com/knowledge-base/new-users/usage-based-pricing-and-data-credits)
- [Databento GLBX.MDP3 dataset](https://databento.com/datasets/GLBX.MDP3)
- [Databento: Introducing new CME pricing plans](https://databento.com/blog/introducing-new-cme-pricing-plans)
- [Firstrate Data futures](https://firstratedata.com/it/futures), [ES](https://firstratedata.com/i/futures/ES), [NQ](https://firstratedata.com/i/futures/NQ)
- [Kibot continuous futures tick/bid-ask data](https://www.kibot.com/historical-data/continuous-futures-contracts-tick-and-bid-ask-data.html), [Kibot pricing](https://www.kibot.com/buy.html)
- [DTN IQFeed](https://www.dtn.com/refined-fuels/trader/iqfeed/), [IQFeed data services](https://www.iqfeed.net/index.cfm?displayaction=data&section=services)
- [Polygon.io futures](https://polygon.io/futures)
- [Norgate futures package](https://norgatedata.com/futurespackage.php)
- [Barchart OnDemand API](https://www.barchart.com/ondemand/api)
- [CME DataMine](https://www.cmegroup.com/datamine.html)
- [quantvps: free/cheap CME historical data guide](https://www.quantvps.com/blog/cme-historical-data-complete-guide)
- [PyQuantNews: Yahoo Finance data reliability](https://www.pyquantnews.com/free-python-resources/insiders-guide-to-clean-financial-market-data-with-python-and-yahoo-finance)
- [QuantStart: Continuous Futures Contracts for Backtesting Purposes](https://www.quantstart.com/articles/Continuous-Futures-Contracts-for-Backtesting-Purposes/)
- [Backtrader rollover docs](https://www.backtrader.com/docu/data-rollover/rolling-futures-over/)
- [Hudson & Thames: The Single Futures Roll](https://hudsonthames.org/the-single-futures-roll/)
- [GitHub: Panama back-adjustment gist](https://gist.github.com/drSeeS/ebb7c699972216cfa25240e999561fb9)
- [Tradovate API access](https://support.tradovate.com/s/article/Tradovate-API-Access?language=en_US)
- [Tradovate CME license cost discussion](https://blog.pickmytrade.trade/tradovate-automation-skip-the-api-fee-and-cme-license/)
- [IBKR TWS API market data](https://interactivebrokers.github.io/tws-api/top_data.html)
- [IBKR historical options/futures data via API](https://www.interactivebrokers.com/campus/ibkr-quant-news/historical-options-futures-data-using-tws-api/)
- [Rithmic pricing discussion](https://community.optimusfutures.com/t/rithmic-api-and-api-differences-and-cost/3274)
- [EdgeClear Rithmic](https://edgeclear.com/rithmic/)
- [2026-10-04-futures-options-data-pipeline.md](2026-10-04-futures-options-data-pipeline.md)
- [2026-10-05-futures-contract-selection-beyond-es-nq.md](2026-10-05-futures-contract-selection-beyond-es-nq.md)
