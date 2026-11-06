# Event contracts, families and slots — design

**Status:** design, 2026-11-06. Part one of qkt's prediction-market work. Part two, venue-neutral symbol sets and
universes, is `2026-11-06-symbol-sets-and-universes-design.md`. The gateway half is
`qkt-venue-gateway/docs/plans/2026-11-06-event-contracts-and-kalshi.md`. The venue research behind both is
`docs/research/2026-11-06-prediction-markets.md`. Amends `2026-10-01-vgp-v1-wire.md` additively.

Code references are to `origin/dev` at bdc7b40e2 (v0.66.0). `Q/` is `src/main/kotlin/com/qkt/`.

## 1. Why

Prediction markets (Kalshi, Polymarket, IBKR ForecastEx, CME event contracts, Hyperliquid outcome markets) list
**binary event contracts**: a yes/no question that pays 1 per contract if it resolves YES and 0 if not, priced
between 0 and 1, so the price reads as the crowd's probability. They are deep enough to trade systematically, they
settle on numbers qkt already reads (index levels, BTC, CPI, temperatures), and their probabilities are useful
inputs to strategies on other markets (Fed odds as a signal on gold).

qkt should trade them the way it trades everything else: the same strategy language, the same risk rails, the
same backtest-equals-live pipeline, through a gateway adapter, with nothing in qkt that names a venue.

### Goals

1. A binary event contract is a first-class instrument kind, correct in P&L, fees, margin, settlement, backtest and
   live.
2. A strategy follows a recurring series without naming the contracts that expire every hour: **families and
   slots**, extending the continuous-futures idea (`CME:ES@front`).
3. A strategy reads everything the venue publishes about a contract as ordinary stream fields (price, depth,
   aggressor flow, open interest, time left, strikes) and runs any indicator on them.
4. Event-wide analytics (implied level, overround, ladder breaks) are read the way option-chain analytics are
   read today (`CHAIN:`).
5. `qkt bot` lists, inspects and trades event contracts.
6. The next venue costs one adapter, not a qkt change.

### Non-goals

- News, sentiment or text understanding of questions. qkt works on numbers.
- Parsing venue tickers for meaning. Meaning comes from structured fields; tickers are names.
- Automatic matching of "the same question" across venues. Resolution rules differ (Polymarket's 15-minute BTC
  markets settle on Chainlink averages, Kalshi's on CF Benchmarks); cross-venue pairs are declared by the author.
- Combo / multivariate contracts (Kalshi `KXMVE…`).

### Anti-goals (failure even if it works)

- Any existing strategy backtesting to different trades, or any existing strategy fingerprint changing.
- Venue words (`KALSHI`, `yes_sub_title`, `price_ranges`) in qkt core. They live in the adapter.
- A grammar per venue. New markets add **data** (instrument kinds, fields, metrics, functions), not keywords.
- A silent zero: a strategy that reads data a venue does not serve does not start, as for funding, marks and
  depth today.

## 2. The model

### 2.1 How a venue organises event contracts (Kalshi as the example)

| Level | Kalshi | Meaning | qkt concept |
|---|---|---|---|
| Category | `Financials` | an aisle | `category` (exists on every instrument) |
| Series | `KXINXU` | a recurring question: "S&P 500 above X, hourly" | **family** |
| Event | `KXINXU-26NOV06H1500` | one instance: the 15:00 ET close on 6 Nov | an expiry of the family |
| Market | `KXINXU-26NOV06H1500-T7425` | one yes/no contract: "≥ 7425" | a **contract** (one qkt symbol) |

An event's markets either partition its outcomes (exactly one resolves YES: range buckets) or form a ladder of
nested predicates ("≥ 7400", "≥ 7425", …). The structured fields say which. Polymarket's events → markets →
YES/NO tokens, ForecastEx's option-shaped contracts and Hyperliquid's daily binaries map onto the same table.

### 2.2 One contract, one symbol, YES-side prices

- A contract is one qkt symbol: the gateway's venue code with `-` written `_`
  (`Q/connector/gateway/GatewaySymbols.kt:73`): `KALSHI:KXINXU_26NOV06H1500_T7425`.
- Its stream's `bid`, `ask`, `close`, `spread` are **YES prices**. NO is the other side of the same book: buying NO
  at q is selling YES at 1−q.
- `BUY` is YES, `SELL` is NO, on a netting account, so a position is one signed number per contract, as Kalshi
  reports it. Venues where YES and NO are separate tokens (Polymarket) list two long-only symbols per market
  (`trading: long_only`); the same strategy then buys the NO symbol instead of selling.
- No new verbs.

### 2.3 Families and slots

A **family** is a group of contracts with an order: series → events by close time → strikes. Futures roots and
option roots are families already; this design adds event families and gives them **slots**.

A **slot** is one stable stream inside a family, chosen by a selector, that follows whichever real contract the
selector picks as contracts come and go:

```qkt
SYMBOLS
    p70 = KALSHI:KXINXU@front.p70 EVERY 1m    -- current event, the strike whose YES is nearest 0.70
    atm = KALSHI:KXINXU@front.atm EVERY 1m    -- the coin-flip strike
    up2 = KALSHI:KXINXU@next.above2 EVERY 1m  -- next event, two strikes above at-the-money
```

The strategy file never goes stale, it has a handful of symbols instead of thousands, and indicators keep their
history across events because the slot's series is continuous. A probability slot (`p70`) is a
**constant-probability series**, the prediction-market analogue of a constant-maturity rate.

### 2.4 Every market type on the venue

Measured on Kalshi's public API on 2026-11-06: 15,835 series in 20 categories (Sports 4,155, Entertainment 2,602,
Elections 2,461, Politics 2,341, Financials 1,130, Economics 866, Climate and Weather 501, Mentions 465, Science and
Technology 389, Crypto 294, Companies 192, World 137, Commodities 108, Health 94, Social 48, Transportation 34,
Exotics 14, …), at frequencies `fifteen_min` (38 series), `hourly` (80), `daily`, `weekly`, `monthly`,
`quarterly`, `annual`, `custom` and `one_off`. Every open market sampled was `market_type: binary`. The design
covers all of them with one model; what differs is shape, not kind:

| Shape | Examples | Contracts per event | Predicate | How a strategy picks a contract | What is special |
|---|---|---|---|---|---|
| **Up/down window** | `KXGOLD15M`, `KXBTC15M`, `KXEURUSD15M`, `KXINX15M`, `KXWTI15M`, rates | 1 | `greater_or_equal` the price at open (`floor`) | `@front.atm` (the only contract) | 15-minute life; settles minutes after close on a published reference |
| **Strike ladder** | `KXINXU` (S&P hourly), `KXBTCD`, CPI, Fed rate, weather highs, NBA totals | 5–80 | nested `greater`/`less` | `pNN`, `atm`, `aboveN`/`belowN` | monotonic: `ladder_breaks` |
| **Range buckets** | `KXINX` daily, temperature ranges, Economics `between` | 10–30, exclusive | `between` | `pNN`, `topN` | exactly one pays: `overround` |
| **Head-to-head** | `KXNBAGAME`, `KXNFLGAME` (one contract per side) | 2 (3 with a draw), exclusive | `custom` (a team) | `top1` (favourite), `top2`; a side by `title` in a set filter | trades live in-game; closes early when the game ends; game start in `occurs_at` |
| **Lines on one occasion** | `KXNBASPREAD`, `KXNBATOTAL` beside `KXNBAGAME` | ladders | `greater` (the line) | as ladders | several families share one occasion (§3.1 `occasion`), so winner, spread and total can be read together |
| **Candidate field** | Elections, Politics, awards, "who will host" | 2–80, exclusive | `custom` | `top1`…`topN` | long-dated (months to years); cash locked until resolution |
| **Single question** | Mentions, Entertainment, Science, Companies | 1 | none | `@front.atm` | often resolves during a live broadcast; can close early |
| **Career / season totals** | player career yards, championships before a date | ladders | `greater`/`greater_or_equal` | as ladders | years to resolve |
| **Combos** | `KXMVE…` (Exotics) | created on request | `custom` | not selectable | quoted by request-for-quote only; listed read-only until an `rfq` capability exists (§8) |

Many events of one family are open at once (every NBA game this week, every race this cycle, several hourly
closes), so the `each` event selector in symbol sets (part two) makes every open event a member; `front`/`next`
pick one.

### 2.5 How event contracts meet the markets qkt already trades

The financial short-dated series settle on prices qkt already streams, so a strategy can hold both sides in one file:

| Event family | Settles on | qkt stream already available |
|---|---|---|
| `KXGOLD15M`, `KXSILVER15M` | gold / silver reference price | `EXNESS:XAUUSD`, `EXNESS:XAGUSD` (CFD) |
| `KXEURUSD15M`, `KXUSDJPY15M`, `KXGBPUSD15M`, `KXAUDUSD15M` | FX reference rate | `EXNESS:EURUSD`, … (CFD) |
| `KXINX15M`, `KXINXU`, `KXINX`; `KXNDQ15M`; `KXDJIA15M` | S&P 500, Nasdaq-100, Dow | `EXNESS:US500`, `US100`, `US30` (CFD); CME futures |
| `KXWTI15M`, `KXBRENT15M`, `KXNATGAS15M` | oil, gas | `EXNESS:USOIL`, `UKOIL` (CFD); CME futures |
| `KXBTC15M`, `KXBTCD`, `KXETH15M`, `KXSOL15M` | crypto reference index | `BYBIT_LINEAR:BTCUSDT` (perpetual), `DERIBIT` futures/options, `BYBIT_SPOT` |

What that enables, all with existing verbs:

- **Fair value from the underlying:** a window or ladder contract is a digital option; `norm_cdf` of the distance
  to the strike over the volatility left prices it.
- **Hedged binaries:** buy a mispriced contract and offset its sensitivity on the CFD or perpetual (a digital's
  delta is its density times the payout), closing the hedge as the contract settles.
- **Lead-lag:** the CFD or perpetual reprices first; contracts whose quotes lag are taken before they move.
- **Signals the other way:** event odds as inputs (Fed, CPI, election probabilities) to trades on gold, indices or
  FX, without trading the event (example in §9).
- **Cross-venue:** the same window on Polymarket's 15-minute markets, declared as a pair (settlement sources differ,
  so never matched automatically).

## 3. Decisions

### 3.1 `EventTerms`: a new derivative kind

`EventTerms` is a new subtype of `sealed interface DerivativeTerms` (`Q/instrument/DerivativeTerms.kt:10-22`, whose
KDoc already says "new derivative kinds are new subtypes"). Fields:

| Field | Meaning |
|---|---|
| `root` | the family, `VENUE:SERIES` |
| `event` | the event code |
| `predicate` | `StrikePredicate(type, floor?, cap?)`, `type` ∈ `greater, greater_or_equal, less, less_or_equal, between, custom, none` (`none`: a plain question with no strike) |
| `openAtMs`, `closeAtMs`, `expiryMs` | trading opens, trading stops (latest), the result is expected |
| `occursAtMs` | when the thing being asked about happens (a game's start, a release time, a window's end), or null |
| `occasion` | the venue's key shared by families about the same occasion (one game's winner, spread and total), or null |
| `canCloseEarly` | trading may stop before `closeAtMs` (a game ends, a question resolves early) |
| `title` | the human label of this contract ("Detroit", "7,425 or above") |
| `exclusive` | the event's contracts partition its outcomes |
| `payout` | 1 (a scalar for venues that pay otherwise; validated > 0) |
| `fee` | `ProbabilityFee(takerRate, makerRate, roundingUnit)` or null (§3.5) |

`contractSize` = 1, `pointSize` = the finest tick, `currency` = the settlement currency. The `DerivativeTerms`
interface members are fixed for events: `margin = null` (events use the premium rule of §3.2, never `MarginTerms`),
`exchangeFeePerContract = 0`, `takerFeeRate = 0` (the fee lives in `fee`).

Where existing code meets the new subtype:

- The exhaustive `when` in `Q/observe/insights/InsightsVenues.kt:88-90` gains an `EventTerms` branch.
- `ContractFeeCommission` (`Q/pnl/ContractFeeCommission.kt`) prices any `DerivativeTerms` from the interface fields,
  which are zero for events. Replay wiring puts `ProbabilityFeeCommission` ahead of it for event symbols, so an
  event is never priced by the futures model.
- The ~31 `is`/`as?` checks for `OptionTerms`/`FutureTerms` fall through for `EventTerms` (verified as a group in
  the PR, one by one).
- `ProductType.EVENT` on the connector contract (`Q/connectivity/Connector.kt:60-72`), added to the gateway
  connector's product types.
- Insights quantity unit `contracts`; instrument kind `event` in the `strategy.started` envelope.

### 3.2 Kind-scoped rule changes (nothing else moves)

Each is reached only through `derivative is EventTerms`, so no existing symbol sees a change:

| Rule today | For `EventTerms` |
|---|---|
| Tick floor drops a zero price unless the symbol is a catalogued option (`Q/app/TickIngest.kt:32-42,97-110`) | zero is legal; prices above `payout` are dropped |
| Outlier gate (`Q/marketdata/MarketDataGate.kt:5-30`) | off: prices are bounded, and a 0.30 → 0.95 jump is the news, not an error |
| Margin (`MarginTerms`, `Q/instrument/MarginTerms.kt:5-32`) | full premium: a YES buy needs qty·p, a NO (YES sell) needs qty·(payout − p); nothing else |
| Expiry entry guard (24 h default on futures) | none; orders are refused at and after `closeAtMs` |
| `PCT RISK` sizing without a stop | risk = the premium at stake (qty·p long, qty·(payout − p) short), the structures' `max_loss` pattern |
| Catalog `deliveryPrice > 0` (`Q/instrument/ContractCatalog.kt:26-28`) | not used: events have their own catalog (§3.7) whose settlement value may be 0 |

### 3.3 Settlement

- **Live:** already generic. A gateway `settlement {symbol, price}` becomes `ContractSettled` and closes every
  holder at `price` with `ExitReason.EXPIRY`, whatever the kind (`Q/app/ContractSettlement.kt:8-31`,
  `Q/broker/SettlementCloses.kt:11-37`); 0 is accepted on the whole path. No change.
- **Backtest:** a new `EventExpiry` beside `OptionExpiry` (`Q/broker/options/OptionExpiry.kt:15-76`). At a
  contract's `expiryMs` it cancels working orders and settles every holder at the catalogued settlement value.
  **A contract that expired without a recorded value fails the run**, as options do; it never falls back to the
  last price (that fallback is why futures settle a resolved-NO contract at 0.01–0.03).
- Settlement value may be any number in [0, payout]: 1, 0, or a fair-value settlement after a void.
- Cash is released at the settlement time, not at `closeAtMs`, so a backtest cannot reuse money the venue still
  holds.

### 3.4 Event stream fields (data, not grammar)

Added to `DslVocabulary` (`Q/dsl/DslVocabulary.kt:26-57`) as `eventFields`, compiled by a new `EventFieldCompiler`
dispatched from `StreamFieldCompiler` (`Q/dsl/compile/StreamFieldCompiler.kt:20-37`). Refused at start unless the
stream's instrument (or slot's family) is an event, as option fields are (`Q/app/StrategyCapabilityChecks.kt:100`).

| Field | Value |
|---|---|
| `floor_strike`, `cap_strike` | the predicate bounds (Undefined when absent) |
| `close_at`, `expiry_at` | epoch ms |
| `minutes_left` | `(close_at − now) / 60000`, judged at the bar's close |
| `taker_fee`, `maker_fee` | the fee one contract pays at the current ask / bid under the instrument's fee model |
| `contract` | on a slot: the code it follows now (string, as on `@front`) |
| `occurs_at`, `minutes_to_occurs` | the occasion's time and the minutes until it (negative once it started: in-game) |
| `title` | the contract's label (string; `=`/`!=` only, as `contract`) |

Everything else already exists and is venue-neutral, served when the gateway declares the capability:

| Existing field | On an event contract | Capability |
|---|---|---|
| `bid`, `ask`, `close`, `spread`, OHLC | YES prices | `quotes`, `bars` |
| `buy_volume[1]`, `sell_volume[1]` | taker YES buys / taker NO buys (YES sells), one bar back | `trades` |
| `bid_depth`, `ask_depth`, `book_imbalance` | YES bids / NO bids as YES asks, ten levels | `depth` |
| `open_interest` | contracts outstanding | `open_interest` |
| `index` | the reference the series settles on, on a gateway that declares it (not in the first Kalshi adapter, §8) | `mark_prices` |

"How many people are on each side" is answered by money (depth, taker flow, open interest), the only side
information venues publish.

### 3.5 Fees

- **The parameters travel with the instrument, live and in backtests.** The gateway's event instrument carries
  optional `fee_model` (`probability`), `taker_fee_rate`, `maker_fee_rate` and `fee_rounding` (gateway design
  §3.1); the adapter fills them from the venue's own fee data (Kalshi: the series' `fee_type` and
  `fee_multiplier`). The event catalog row stores the same four fields. So `EventTerms.fee`, and with it the
  `taker_fee`/`maker_fee` fields, is known in both modes. A strategy reading `taker_fee` on an instrument whose fee
  is unknown does not start (never a silent zero).
- **Live P&L:** no model. Each fill carries the venue's real fee as an `exchange_fee` cost on the wire; the
  parameters only feed the `taker_fee`/`maker_fee` fields.
- **Backtest:** a new venue-neutral `ProbabilityFeeCommission`: per order,
  `roundUp(rate × contracts × p × (1 − p), roundingUnit)`, charged as the order's cost grows across partial fills.
  `EquityCostCommission` (`Q/pnl/EquityCostCommission.kt:7-43`) is the template for per-order rounding through the
  order-aware `CommissionModel` overload (`Q/pnl/CommissionModel.kt:7-52`). Taker rate by default; maker rate when
  the fill rested. A fee schedule change is a data change in the catalog.
- Named for the shape, not the venue: Polymarket's taker fee (`shares × rate × p × (1 − p)`) uses the same model.

### 3.6 Families and slots

**Spelling.** `VENUE:FAMILY@<event>.<strike>`. A second `:` is illegal in stored symbols
(`Q/instrument/QktSymbols.kt:8`), and `.` and `@` are file-safe, so the slot symbol is a valid qkt symbol
(`KALSHI:KXINXU@front.p70`). The parser branch runs only when a selector is followed by `.`
(`Q/dsl/parse/StreamDeclParser.kt:69-80`); no existing symbol contains that, and the slot spec stays inside
`StreamDecl.symbol`, so no AST type changes and no fingerprint moves (the trick `@front` already uses,
`StreamDeclParser.kt:21-24`).

**Selectors** (v1), held in one `SlotSelectors` table so a new one is one entry:

| Part | Selector | Picks |
|---|---|---|
| event | `front` | the open event with the earliest `closeAtMs` after now |
| event | `next` | the one after it |
| strike | `pNN` (`p05` … `p95`) | the contract whose YES mid is nearest NN %, ties to the lower strike |
| strike | `atm` | `p50` |
| strike | `aboveN`, `belowN` | N strikes above / below `atm` in the ladder |
| strike | `topN` (`top1` …) | the contract with the Nth highest YES mid (the favourite, the frontrunner) |
| event (sets only) | `each` | every open event of the family, one member each (part two) |

On an event with one contract (up/down windows, single questions) every strike selector resolves to it;
`@front.atm` is the conventional spelling.

**Selection is deterministic.** At each close of the slot's `EVERY` window, the selector reads the family's quotes
as of that instant and picks a contract; the pick holds until the next close. Same data, same pick, backtest and
live. Copy `OptionSelector.select` (`Q/derivatives/options/chain/OptionSelector.kt:48-90`): one snapshot, nearest
target, ties to the lower strike, a contract without a usable two-sided quote skipped.

**Prices.** The slot's bars are the followed contract's YES prices; when the pick changes, the next bar is the new
contract's. No back-adjustment: probabilities are not prices with a carry, and a constant-probability series is
the point. `contract`, `floor_strike` and `cap_strike` show which contract a bar came from.

**Orders land on the real contract.** `BUY p70` is translated when the rule fires into an order on the contract the
slot follows at that moment, as structures do (`Q/dsl/compile/StructureCompiler.kt:89-142`). Futures `@front` keeps
the position on the stream instead (`Q/broker/continuous/ContinuousContractBroker.kt:22-45`); that model is wrong
here, because an event contract settles, it never rolls.

**The slot book.** The slot alias keeps a book of the contracts it opened, like a structure alias. A held contract
stays held after the slot moves on, until it settles or the strategy closes it.

- **An order that reduces goes to what the slot holds first.** `SELL p70` while the slot is long closes its held
  contracts, oldest first, at their own quotes; only a remainder beyond what it holds opens NO on the contract the
  slot follows now. `BUY` while short does the same in reverse. So a slot never holds YES on one contract and NO on
  another at once, and its book always has one sign.
- `POSITION.p70` is the signed total of that book: positive long YES, negative long NO, 0 when it holds nothing.
  Because the book has one sign, this is the slot's real exposure, and `POSITION.p70 = 0` does not re-enter while an
  earlier entry is still held.
- `CLOSE p70` closes every contract in the book.

**Live data.** A slot (or an event `CHAIN:` stream) subscribes its whole family: the gateway's `roots=` quote
subscription extended to match `series`/`event` (gateway design §3.4), the pattern fed option roots use today
(`Q/connector/gateway/GatewayMarketSource.kt:96-129`). Routing is a predicate over the family
(`SymbolPattern`, `Q/app/SessionBrokers.kt:119-122`), so contracts listed after start are tradable.

**Declaring a family.** An `events:` block in `instruments.yaml`, beside `futures:` and `options:`, names each
family a backtest may read (`root: KALSHI:KXINXU`, `currency`, `tick`); its contracts come from the event catalog
(§3.7). Live, the gateway's `kind: event` listing declares families by itself. The parser tells a slot from a
continuous future by spelling: an event slot always carries a strike part after `.` (`@front.p70`), a future never
does; `qkt` then checks at start that the root is an event family, so capability checks run before the first tick.

**Backtest data.** Each contract's history is stored as **quote ticks** (time, YES bid, YES ask; either side may be
absent) under its own symbol, through the existing `qkt data import --kind ticks`. A pipeline built on Kalshi's
1-minute candlesticks writes one quote per contract per minute from each candle's YES bid and ask close; a recorder
writes every change. Trade prints, when present, go through `--tape` as for any contract. Slot selection reads mids
from these quotes and fills happen at the quoted bid and ask, as everywhere in qkt. A family feed beside
`OptionRootMarketSource` (`Q/marketdata/source/OptionRootMarketSource.kt`) reads the event catalog for the run window
and merges the quotes of every contract alive at each instant, stamped with the contract's own symbol (one declared
feed, many symbols, as fed roots do). Minute quotes make fills coarse; the backtest report says which resolution a
run used. Tick import already takes `bid` and `ask` columns (`docs/how-to/import-data.md`); it must also keep a
quote with one empty side for event symbols (some importers skip such rows today), kind-scoped like §3.2.

**Machinery.** A `FamilyDirectory` beside `InstrumentRegistry.futures()/options()`
(`Q/instrument/InstrumentRegistry.kt:17-32`) answers "which contracts of this family are alive at t" from the event
catalog; a `SlotMarketSource` wrapper (pattern: `ContinuousWiring.kt:81-95`) builds the slot's bars. The futures
roll path is untouched.

### 3.7 Event catalog: the family's history

`contracts/<VENUE>/<FAMILY>.events.json` under the data root, one row per contract: code, event, predicate, open,
close and expiry times, exclusive flag, fee parameters, settlement value (null until known). It is:

- the backtest's instrument metadata for every contract of the family;
- the settlement source for `EventExpiry`;
- the **point-in-time membership** of the family: which contracts existed at t is `openAt ≤ t < expiry`, so slots
  and universes replay exactly without recording any decision.

Written two ways, both venue-neutral:

- `qkt data import --kind events --map <description>` from any file a pipeline produced (the data-import design's
  "sources are descriptions" rule: no per-venue code in qkt). Rows carry the fields above, one per contract; a
  row's settlement value may be filled by a later import of the same code. **This is the source for history**:
  venues keep settled contracts and their results (Kalshi's `/historical/*`), and the pipeline reads them.
- `qkt fetch <VENUE>:<FAMILY> --catalog` from a gateway, for the recent window. Settled events leave the gateway
  listing after its keep window, so `fetch` also asks the gateway by code for each catalogued contract still missing
  a value, and the gateway asks the adapter (gateway design §3.2, by-code lookup of unlisted events). A value the
  venue cannot give stays null, and a backtest touching that contract fails naming it.

### 3.8 Event-wide analytics (`CHAIN:` for events)

`CHAIN:<VENUE>.<FAMILY>.<metric>.<event>` with `<event>` = `front` or `next`, beside the option metrics
(`docs/reference/dsl/chain.md`). Same rules: read-only, values only where they can be computed honestly, Undefined
otherwise.

| Metric | Value | Events |
|---|---|---|
| `implied_median` | the strike where the ladder's YES mids cross 0.5, linear between strikes | ladders |
| `implied_mean` | Σ midpoint × probability over buckets | exclusive |
| `overround` | Σ YES asks − 1 (negative = buying every bucket costs under the payout) | exclusive |
| `underround` | 1 − Σ YES bids | exclusive |
| `ladder_breaks` | count of adjacent strikes where the higher strike's YES bid exceeds the lower strike's YES ask | ladders |

Metrics are a registry; a new one is one entry plus its doc row.

### 3.9 New functions

- `norm_cdf(x)` in `FuncRegistry` (`Q/dsl/stdlib/FuncRegistry.kt:24-131`), reusing
  `Q/derivatives/options/pricing/NormalDistribution.kt:41`. Fair value of "≥ K" from an underlying is then one
  expression.
- `next_strike(alias)` / `prev_strike(alias)`: the neighbouring contract in the same event, read as a stream
  (for ladder rules). Part of the symbol-sets work (part two) because it needs runtime members.

### 3.10 Symbols that hold a `.`

Some venue codes carry decimal strikes (`KXHIGHNY-26NOV06-B73.5`). The qkt symbol keeps the `.` (file-safe), but
the DSL lexer only allows `.` in dotted `CHAIN:`/`OPTIONS:`/`HUB:` names (`Q/dsl/parse/Lexer.kt:23,75-82`).
`parseStream` accepts `<digits>.<digits>` inside a venue symbol; no existing file has that, so old files parse
unchanged. Most strategies use slots and never type a contract code.

### 3.11 `qkt bot`

Read and trade through the same pipeline as today (`docs/reference/bot-cli.md`):

```
qkt bot markets KALSHI --category Financials --family KXINXU --open --json   # the catalog, filtered
qkt bot chain   KALSHI:KXINXU@front --json                                   # every contract of one event, with quotes
qkt bot quote   KALSHI:KXINXU@front.p70 --json                               # a slot resolves to its contract
qkt bot buy  10 KALSHI:KXINXU@front.p70 --limit 0.71                         # YES on the contract the slot follows now
qkt bot sell 10 KALSHI:KXINXU_26NOV06H1500_T7425 --limit 0.74                # NO
qkt bot eval "ema(close, 20)" KALSHI:KXINXU@front.p70 --tf 1m --json
```

`markets` uses the same filter vocabulary as universes (part two), so an agent sees exactly what a strategy would.

### 3.12 Insights and qkt-backtester

- Insights: `event` kind, unit `contracts`, `EXPIRY` exits at 0 or 1 shown as settlements. No chart changes are
  needed for one contract; a slot's trades carry the real contract symbol.
- qkt-backtester: its derivatives scaffold discovers `contracts/<VENUE>/*.json` as futures roots
  (`qkt-backtester/packages/server/src/scaffold-derivatives.ts`); it learns the `.events.json` suffix. Its own
  repo, its own change.

## 4. What lives where

| Layer | Owns (for this work) | Never holds |
|---|---|---|
| qkt core (`instrument`, `broker`, `pnl`, `risk`, `dsl`, `marketdata`, `backtest`) | `EventTerms`, kind-scoped rules, `EventExpiry`, `ProbabilityFeeCommission`, families/slots, event `CHAIN:` metrics, fields, functions, event catalog store, `--kind events` import | any venue name or venue field |
| qkt `connector/gateway` (the one wire boundary) | reading `kind: event` and its fields into `EventTerms`; `roots=` by family; by-code fallback for settled events | strategy logic |
| gateway host / `adapter-api` / wire | `InstrumentKind.EVENT`, catalog paging and change polling, `tick_ranges`, `stop_orders` capability, kit rules | venue words |
| `adapter-kalshi` (later `adapter-polymarket` + sidecar) | signing, clients, mapping, YES/NO book translation, order emulation, fees on fills, settlement | journal, kill switch, idempotency |
| data pipeline repo (outside qkt) | downloading venue history into files `qkt data import` reads; live WebSocket recorder for depth/flow history | anything qkt reads directly |
| qkt-data-hub | non-market inputs (weather models, economic consensus) as `HUB:` datasets | prices |

**Extension points**, each a one-place addition:

| To add | Touch |
|---|---|
| a venue | one gateway adapter (and a data pipeline) |
| a slot selector | one `SlotSelectors` entry + doc row |
| an event metric | one metric registry entry + doc row |
| a fee shape | one `CommissionModel` |
| an instrument field | an optional wire field + `EventTerms` field + `DslVocabulary` entry |

## 5. Edge cases the implementation must handle

1. Empty sides: a venue's "0 = no bid" arrives as an absent side, never 0 (adapter rule); a quote with neither
   side is no quote.
2. Look-ahead: slot selection and every field read as of the bar close; the catalog's settlement value is invisible
   before `expiryMs`; lifetime volume is never used as a filter (universes use as-of volume).
3. Survivorship: the event catalog includes voided and cancelled contracts with their settlement value.
4. Fractional contracts and sub-cent ticks: `volumeStep` from the instrument; prices on `tick_ranges` when present.
5. Netting: a NO buy against a YES long reduces it; the account is netting.
6. Per-contract position limits: venue refusals surface as rejected orders, as today.
7. Time zones: venue codes use local exchange time; qkt uses the catalog's UTC epoch times only.
8. Warmup: a fresh contract has no history; slots carry history across contracts, single contracts start cold
   (rules on long lookbacks stay silent).
9. Early close (`can_close_early`) and revised results: the adapter reports the settlement it observes; a revision
   is a new settlement record (the journal keys on symbol and time).
10. Market orders: limit-only venues fill them as marketable IOC limits (adapter); qkt sees a market order that
    filled what it could.
11. Stops: refused at start on a gateway that does not declare `stop_orders` when the strategy has stop orders or
    stop brackets on an event contract.
12. Capital per venue shard: one gateway per shard (gateway design §3.7).
13. Live in-game and live-broadcast markets: prices jump on a score or a sentence; quotes may vanish while the venue
    pauses a market. A pause arrives as `trading: disabled` (orders refused, held positions kept), and resumes as
    `full`; rules see no quote while paused.
14. Early close: a contract with `canCloseEarly` may stop trading before `closeAtMs`; orders after the venue
    closes it are refused, and its settlement arrives whenever the venue settles it.
15. Long-dated contracts (elections, career totals): cash is locked for months; the backtest holds it until the
    settlement time (§3.3). Interest a venue pays on held positions, if any, is a credit on the account, reported by
    the venue, not modelled.
16. Combos: listed with `trading: disabled` until request-for-quote trading exists; a strategy naming one does not
    start.
17. Several open events per family: `front` is the earliest-closing open event; strategies that want every game or
    every race use the `each` selector in a set.
18. Draws and three-way events: an exclusive event may have more than two contracts; `top1` is still the
    favourite, and `overround` covers all of them.

## 6. Proof that nothing existing changes

Every PR in this work passes, unchanged:

- `DslCorpusPinTest` (138 pinned fingerprints) and `GoldenBacktestTest` (11 byte-identical cases).
- The release proofs' `audit`, `docs` and `compat` fingerprints (`scripts/release-proof/run.sh`).
- A pre/post jar diff over `attestation/cases/derivatives/*`, because the goldens are all CFD and this work sits
  next to the futures and option paths.

New behaviour gets its own golden: one event-contract backtest (a family, a slot, settlements at 1 and 0, fees) and
one live-parity attestation case on the Kalshi demo environment, following
`docs/contributing/live-parity-attestation.md`.

## 7. Delivery

| Step | Repo | Ships | Usable after |
|---|---|---|---|
| 1 | data pipeline (new, outside qkt) | Kalshi candles, trades, catalog with outcomes → files; live depth/flow recorder | history accumulates; no account needed |
| 2 | gateway `feat/event-kind` + qkt | `kind: event`, `EventTerms`, §3.2, §3.3, §3.5, event catalog, `--kind events`, §3.4 fields, `norm_cdf`, §3.10 | backtests on explicit contracts |
| 3 | gateway `feat/catalog-scale`, `feat/quote-roots-by-family` | paging, change polling, shelf policy, by-code lookup; `roots=` by series/event | large listings; whole-family quotes |
| 4 | qkt | families and slots (§3.6), event `CHAIN:` metrics (§3.8), bot verbs (§3.11) | continuous strategies, backtest and live |
| 5 | gateway `adapter/kalshi` | the adapter, demo contract test | live on demo, then production |
| 6 | qkt | event golden + attestation case | release |
| 7 | part two | symbol sets and universes | scanning strategies |
| 8 | gateway | `adapter-polymarket` + sidecar | second venue |

## 8. Open questions

1. Kalshi account eligibility and funding from the operator's country (decides whether Kalshi is first).
2. Whether Kalshi orders can be found by `client_order_id` (the gateway requires it).
3. The repo for the data pipeline: a new `qkt-prediction-data`, or a module of an existing data repo.
4. Whether `PCT RISK` on a slot should size on the premium at the moment of firing (proposed) or on a declared
   worst case.
5. A settlement-reference `index` for event contracts (Kalshi streams its CF Benchmarks BTC index). Capabilities
   are per gateway, not per instrument, so declaring `mark_prices` would promise an index for every series. Deferred
   until per-instrument availability is designed; strategies take the underlying from its own venue meanwhile.

6. Request-for-quote trading for combos (`rfq` capability): a separate design once a strategy needs combos.

## 8a. Calls made in this design (check these)

- Slot reduce-first routing and the one-sign slot book (§3.6).
- Fee parameters on the wire and in the catalog, so `taker_fee` works live (§3.5).
- Minute quote ticks from candlesticks as the first history format (§3.6).
- An `events:` block in `instruments.yaml`; event slots always carry a strike part (§3.6).
- History comes from a pipeline import; `fetch --catalog` covers the recent window (§3.7).
- Backtest settlement never falls back to the last price (§3.3).

## 9. Examples

```qkt
STRATEGY spx_hourly_fair VERSION 1
SYMBOLS
    spx = EXNESS:US500 EVERY 1m,
    atm = KALSHI:KXINXU@front.atm EVERY 1m
LET vol  = stddev(log(spx.close / spx.close[1]), 60)
LET fair = norm_cdf(log(spx.close / atm.floor_strike) / (vol * sqrt(atm.minutes_left)))
RULES
    WHEN fair - atm.ask - atm.taker_fee > 0.04 AND POSITION.atm = 0
    THEN BUY atm SIZING 2 PCT RISK
    WHEN atm.bid - fair - atm.taker_fee > 0.04 AND POSITION.atm = 0
    THEN SELL atm SIZING 2 PCT RISK          -- NO
```

```qkt
STRATEGY fed_odds_gold VERSION 1
SYMBOLS
    gold = EXNESS:XAUUSD EVERY 5m,
    fed  = CHAIN:KALSHI.KXFED.implied_median.front EVERY 5m   -- the market's median expected rate
RULES
    WHEN fed.close < fed.close[12] - 0.10 AND POSITION.gold = 0   -- cut odds jumped within the hour
    THEN BUY gold SIZING 1 PCT RISK BRACKET { STOP_LOSS BY 1 PCT, TAKE_PROFIT BY 2 PCT }
```

```qkt
STRATEGY carry_with_flow VERSION 1
SYMBOLS
    p80 = KALSHI:KXINXU@front.p80 EVERY 1m
RULES
    WHEN p80.minutes_left > 10 AND p80.minutes_left < 40
     AND ema(p80.close, 20) > ema(p80.close, 60)
     AND p80.buy_volume[1] > 1.5 * p80.sell_volume[1]
     AND p80.book_imbalance > 0.2
     AND POSITION.p80 = 0
    THEN BUY p80 SIZING 1 PCT RISK ORDER_TYPE = LIMIT AT p80.bid + 0.01   -- held to settlement
```

```qkt
STRATEGY gold_window_vs_cfd VERSION 1
SYMBOLS
    xau = EXNESS:XAUUSD EVERY 1m,
    win = KALSHI:KXGOLD15M@front.atm EVERY 1m        -- "gold ≥ the price at open", 15-minute window
LET vol  = stddev(log(xau.close / xau.close[1]), 30)
LET fair = norm_cdf(log(xau.close / win.floor_strike) / (vol * sqrt(win.minutes_left)))
RULES
    -- The CFD moved; the window's quotes have not caught up.
    WHEN fair - win.ask - win.taker_fee > 0.05 AND win.minutes_left > 2 AND POSITION.win = 0
    THEN BUY win SIZING 1 PCT RISK
    WHEN win.bid - fair - win.taker_fee > 0.05 AND win.minutes_left > 2 AND POSITION.win = 0
    THEN SELL win SIZING 1 PCT RISK
```

```qkt
STRATEGY nba_favourite_drift VERSION 1
SYMBOLS
    fav = KALSHI:KXNBAGAME@front.top1 EVERY 1m       -- the favourite of the next game to finish
RULES
    -- Pre-game only: money keeps arriving on the favourite.
    WHEN fav.minutes_to_occurs > 30 AND fav.minutes_to_occurs < 240
     AND fav.close > ema(fav.close, 30) AND fav.buy_volume[1] > 2 * fav.sell_volume[1]
     AND POSITION.fav = 0
    THEN BUY fav SIZING 0.5 PCT RISK ORDER_TYPE = LIMIT AT fav.bid + 0.01
    -- Out before tip-off: no in-game exposure.
    WHEN fav.minutes_to_occurs < 5 AND POSITION.fav != 0
    THEN CLOSE fav
```
