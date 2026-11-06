# Symbol sets and universes — design

**Status:** design, 2026-11-06. Part two of qkt's prediction-market work, and venue-neutral: it serves FX, CFDs,
crypto, equities, futures, options and event contracts alike. Part one is `2026-11-06-event-contracts-design.md`
(event kind, families, slots); the gateway half is
`qkt-venue-gateway/docs/plans/2026-11-06-event-contracts-and-kalshi.md`.

Code references are to `origin/dev` at bdc7b40e2 (v0.66.0). `Q/` is `src/main/kotlin/com/qkt/`.

## 1. Why

A strategy today names every symbol it trades, and that list is frozen at start. That blocks three things people
want:

- **One rule over a hand-picked list** without writing it N times: "this rule on EURUSD, XAUUSD and BTCUSDT".
  `FOR EACH s IN [a, b, c]` helps, but every symbol is still declared by hand.
- **One rule over everything matching a filter**, as the venue lists it: every Forex symbol on Exness, every US
  share above a volume, every hourly financial series on Kalshi, every perpetual listed in the last two days.
- **Members that come and go during a run**: Kalshi lists and settles thousands of contracts a day; equities list
  and delist; perpetuals launch.

Issue #70 (acquire a symbol at runtime) was closed because "a symbol unknown until runtime has no history for a
deterministic replay", and its closing note named the version worth building: "lazy activation of a known
universe". This design is that version. Membership is a pure function of a **point-in-time catalog** plus as-of
market data, so a backtest replays it exactly and nothing has to be recorded to reproduce it.

### Goals

1. Two symbol-set forms in `SYMBOLS`: a picked list and a filtered universe, on any venue, combinable across venues.
2. A universe filter over the metadata every venue already publishes, and over as-of market data.
3. A universe whose members are **families with a slot** (part one): one stable, warm stream per family.
4. One rule run per member, with each member's own indicator state, members joining and leaving at runtime.
5. Ranking and aggregation across a set's members.
6. Caps over a set, enforced at order time.
7. The same filters in `qkt bot`.

### Non-goals

- Arbitrary symbol acquisition from outside the catalog (a scanner or news feed naming new symbols). Membership
  comes only from the catalog.
- A query language. The filter is a boolean expression over catalog fields and stream fields, nothing more.

### Anti-goals

- Any existing strategy backtesting differently or changing fingerprint.
- Silent survivorship: a backtest over a universe whose catalog has no listing history must say so, never quietly
  use today's list for the past.
- Unbounded work: a filtered universe without a cap is refused.

## 2. The language

Two new forms in the stream position of `SYMBOLS`, and `FOR EACH` without brackets. That is the whole grammar
budget of this work. Everything else is fields and functions.

```qkt
SYMBOLS
    fx     = EXNESS:{EURUSD, XAUUSD, USDJPY} EVERY 15m                            -- picked
    crypto = BINANCE:{BTCUSDT, ETHUSDT} EVERY 15m
    majors = fx + crypto                                                          -- union, across venues
    stocks = ALPACA:* WHERE category = "us_equity" AND close > 5
             TOP 50 BY volume EVERY 15m                                           -- filtered, capped
    hourly = KALSHI:*@front.p80 WHERE category = "Financials" AND tenor = 1h
             TOP 40 BY open_interest EVERY 1m                                     -- one slot per family
    late   = KALSHI:* WHERE kind = event AND minutes_left <= 15
             TOP 100 BY volume EVERY 1m                                           -- raw contracts

RULES
    FOR EACH m IN majors DO
      WHEN rsi(m.close, 14) < 30 AND POSITION.m = 0
      THEN BUY m SIZING 1 PCT RISK
```

- `VENUE:{A, B, …}`: a picked list. Each name must exist in the venue's catalog at start, or the run refuses to
  start naming it.
- `VENUE:* WHERE <filter> TOP <n> BY <field> [ASC|DESC]`: a filtered universe. `TOP` is required; `n` is capped by
  `sets.max_members` (default 500).
- `VENUE:*@<event>.<strike> WHERE …`: the filter runs over **families**, and each member is that family's slot.
- `a + b`: union of sets declared above, each keeping its own venue. Members must share `EVERY`.
- `FOR EACH m IN <set> DO <rule>`: the rule runs once per member. The bracket form `FOR EACH s IN [a, b]` keeps its
  current compile-time meaning, unchanged.

### 2.1 Why `FOR EACH`, and no shortcut

`WHEN rsi(fx.close, 14) < 30 THEN BUY fx` is deliberately not supported. It is ambiguous (every member, or those
under 30?), and `BUY <basket>` already means "buy every constituent" (`docs/reference/dsl/basket.md`). `FOR EACH`
makes "per member" explicit at the cost of one line. Buying every member on one condition is
`FOR EACH m IN s DO WHEN <condition on anything> THEN BUY m`, with no new verb.

## 3. Decisions

### 3.1 The catalog: what a filter can read

Every venue already publishes instrument metadata; the work is one vocabulary over it.

| Field | Source today | Venues |
|---|---|---|
| `kind` | instrument kind | all |
| `category` | `InstrumentMeta.category` (`Q/instrument/InstrumentMeta.kt:48-50`): MT5 folder, gateway `category`, `instruments.yaml` | all |
| `currency`, `contract_size`, `tick_size` | instrument | all |
| `trade_mode` | `TradeMode` (`Q/instrument/TradeMode.kt:10-40`) | all |
| `family`, `underlying` | futures/option roots, event series | derivatives, events |
| `expiry_at`, `dte` | catalogs | futures, options, events |
| `event`, `open_at`, `close_at`, `minutes_left`, `tenor`, `strike_type`, `floor_strike`, `cap_strike` | event catalog (part one §3.7) | events |

`tenor` is `close_at − open_at` rounded to the venue grid (`15m`, `1h`, `1d`, …), so "hourly" is a fact, not a
venue word.

A filter may also read **as-of stream fields** (`close`, `volume`, `spread`, `open_interest`, `bid_depth`, …),
evaluated from the light tier (§3.3). Lifetime figures (a venue's cumulative `volume` on a contract) are never
exposed to filters, because they leak the future in a backtest.

Glob membership (`category GLOB "Stocks*"`) reuses the matcher calendar rules already use
(`Q/common/SymbolCalendars.kt:41-65`).

### 3.2 Point-in-time membership

`members(t)` = catalog rows alive at t (listed, not expired, not delisted) that pass the filter on data as of t,
ranked, top n. Re-evaluated at each close of the set's `EVERY` window.

| Venue type | Catalog with time | Backtest membership |
|---|---|---|
| Event contracts | event catalog (`openAt`, `expiry`) | exact |
| Futures, options | contract catalogs / chain days | exact |
| FX, CFDs, crypto spot | `instruments.yaml` (static) | exact for picked lists; a filtered set uses the static list, recorded as such in the report |
| Equities | none yet (no delisting history) | refused for filtered sets unless `--survivorship-accepted`, which the report records |

Live, membership is recomputed from the gateway catalog (`/v1/instruments` with change polling, gateway design
§3.2) and every join and leave is written to the run journal, so a live run can be compared with its backtest.

### 3.3 Two tiers: watched and active

Thousands of full pipelines is the wrong design. Every catalog row that passes the metadata filter is **watched**:
it gets a light quote record (last, bid, ask, volume for the window, open interest) and nothing else. Only the top
`n` after the data filter are **active**: they get candles, indicators and rules.

- Live, the watched tier rides the gateway's family/`roots=` quote subscription with change-only refresh (gateway
  §3.4); MT5 subscribes symbol by symbol, so MT5 sets are practical in the hundreds, not thousands.
- In a backtest the watched tier reads the same stored bars the active tier uses.
- A member that leaves the active set while it holds a position or a working order stays active (**sticky while
  held**) so its exit rules keep running; it is released when flat.

### 3.4 Runtime `FOR EACH`

Today `FOR EACH` is textual substitution at parse time (`Q/dsl/parse/RuleParser.kt:78-95`). The bracketless form
compiles the rule **once, as a template**, and instantiates it per member when the member becomes active:

- the member's stream is registered with `CandleHub` (registration is already allowed mid-run,
  `Q/dsl/compile/HubSlots.kt:42-66`);
- indicator bindings are created per member (today they bind by alias at compile time,
  `Q/dsl/compile/IndicatorBinding.kt:28-30`; the template binds by `(alias, member)`);
- the member's warmup is loaded before its rules evaluate: from the store in a backtest, from gateway bars live (the
  same warmup paths declared streams use). A member with no history (a fresh event contract) starts cold and its
  long lookbacks stay Undefined.
- On release the instance and its state are freed.

Data and routing follow the fed-option-root precedent, which already runs undeclared symbols end to end:

| Need | Precedent |
|---|---|
| one declared feed emitting ticks for many symbols | `OptionRootMarketSource` (`Q/marketdata/source/OptionRootMarketSource.kt:23-86`) |
| any symbol marked on sight | `Engine.onTick` has no declared-symbol filter (`Q/engine/Engine.kt:31-35`) |
| routes as predicates | `SymbolPattern` (`Q/app/SessionBrokers.kt:119-122`, `Q/research/ReplayOptionRoutes.kt:41-44`) |
| positions on undeclared symbols restored at restart | `StartupReconcile` (`Q/app/StartupReconcile.kt:102-110`) |
| live re-subscription when the followed set changes | `ContinuousLiveFeed` |

### 3.5 Cross-member functions

Functions, not grammar (`Q/dsl/stdlib/FuncRegistry.kt:24-131`). `RANK_OF` already exists (`FuncRegistry.kt:88-92`)
over explicit peers; it gains a set argument. New:

| Function | Value |
|---|---|
| `RANK_OF(expr, set)` | the rank of this member's `expr` among the set's active members (1 = highest) |
| `SUM_OF(expr, set)`, `AVG_OF`, `MAX_OF`, `MIN_OF` | aggregate of `expr` over active members |
| `COUNT_OF(condition, set)` | members where the condition holds |
| `next_strike(m)`, `prev_strike(m)` | the neighbouring contract in the same event (events only) |

`expr` is evaluated per member at the same bar close, then combined, so each member sees the same cross-section.
Inside `FOR EACH`, `expr` refers to the loop variable.

### 3.6 Set caps (config, not grammar)

Per-strategy caps already live in config (`risk.per_strategy.<name>.max_open_positions`,
`docs/reference/config-schema.md:326`) and are enforced with in-flight reservation by `MaxStrategyOpenPositions`
(`Q/risk/rules/MaxStrategyOpenPositions.kt:22-49`), the burst-race fix. Set caps follow:

```yaml
risk:
  per_strategy:
    rotation:
      sets:
        stocks: { max_open_positions: 5, max_position_size: "2000" }
```

Compiled to the same rule filtered to the set's members, so 40 members triggering on one bar open at most five.
Book-level caps (`BookRiskController.checkAndReserve`) apply unchanged on top.

### 3.7 Fingerprints and the AST

Adding a property to `StrategyAst` or `StreamDecl` changes every strategy's fingerprint (`AstCompiler.kt:166`,
pinned by `DslCorpusPinTest`). So:

- a set declaration is a `StreamDecl` with a reserved broker prefix whose canonical spec (venue, list or filter,
  `TOP`, slot) is carried in `StreamDecl.symbol`, the trick `@front` uses (`Q/dsl/parse/StreamDeclParser.kt:21-24`);
- the runtime `FOR EACH` is a new rule AST type with a hand-written `toString`, the `WhenThen` pattern
  (`Q/dsl/ast/RuleAst.kt:16-31`);
- no new `TokenKind`: `WHERE`, `TOP`, `BY`, `ASC`, `DESC` are contextual identifiers inside a set declaration, so
  no existing name becomes reserved.

### 3.8 Picked lists first, as sugar

A picked list (`EXNESS:{EURUSD, XAUUSD}`) with a bracketless `FOR EACH` over it has fixed members. It can compile to
declared streams plus today's textual `FOR EACH`, needing none of the runtime machinery. That ships first and alone.

### 3.9 Venue catalog sources

| Connector | Catalog source | Gap |
|---|---|---|
| gateway (`type: gateway`) | `GET /v1/instruments` (`Q/connector/gateway/GatewayClient.kt:55-57`) | paging and change polling (gateway §3.2); the qkt client caches it instead of re-reading per symbol |
| MT5 | per-symbol only (`/symbol_info/<sym>`) | an all-symbols listing on `mt5-gateway` (its own repo) for filtered MT5 sets; picked lists work without it |
| backtest | `instruments.yaml`, contract and event catalogs | equities listing history (data pipeline) |

### 3.10 `qkt bot`

```
qkt bot universe "KALSHI:* WHERE category = \"Financials\" AND tenor = 1h TOP 40 BY open_interest" --json
qkt bot universe "EXNESS:* WHERE category GLOB \"Stocks*\" TOP 100 BY volume" --json
qkt bot eval "rsi(close, 14)" --universe "ALPACA:* WHERE category = \"us_equity\" TOP 50 BY volume" --tf 15m --json
```

The same parser and evaluator the strategy uses, so an agent's scan and a strategy's members agree.

### 3.11 Insights

A set strategy's symbols are not fixed. The `strategy.started` envelope lists the set declarations; joins and
leaves are emitted as events so Insights can show members over time. Trades already carry real symbols.

## 4. What lives where

| Layer | Owns | Never holds |
|---|---|---|
| qkt `dsl` | set declarations, bracketless `FOR EACH`, cross-member functions | venue names |
| qkt `instrument` | catalog vocabulary, point-in-time membership | venue fields |
| qkt `app` / `marketdata` / `backtest` | set feeds (watched + active), lazy registration, predicate routes, warmup on join | per-venue branches |
| qkt `risk` | set caps on `MaxStrategyOpenPositions` | |
| qkt `connector/*` | one catalog read per connector, cached | |
| gateway host | catalog paging, change polling, family quote subscription | |
| `mt5-gateway` | an all-symbols listing | |
| data pipelines | listing history where venues have it (equities) | |

## 5. Proof that nothing existing changes

As part one §6: `DslCorpusPinTest`, `GoldenBacktestTest`, release-proof `audit`/`docs`/`compat`, and a pre/post jar
diff over `attestation/cases/derivatives/*` on every PR. New goldens: a picked-list strategy (identical trades to
the same rules written out by hand, which proves §3.8), a filtered event universe with joins, leaves and a sticky
member, and a set cap under a burst.

## 6. Delivery

| Step | Ships | Unlocks |
|---|---|---|
| 1 | picked lists and unions, bracketless `FOR EACH` over them (§3.8) | one rule over a hand-picked cross-venue list |
| 2 | catalog vocabulary, filtered sets resolved once at start (no runtime churn), `TOP n`, set caps, `qkt bot universe` | screener strategies whose members are fixed per run |
| 3 | runtime membership: watched/active tiers, joins and leaves, sticky while held, warmup on join, journal events | Kalshi universes, new listings, rotation |
| 4 | cross-member functions | ranking, ladders, bucket sums |
| 5 | `mt5-gateway` listing; equities listing history | filtered MT5 and survivorship-free equity universes |

Step 3 needs part one's families and slots for slot universes, and the gateway's catalog paging.

## 7. Open questions

1. `sets.max_members` default (500 proposed) and whether it is per set or per strategy.
2. Whether a filtered set resolved at start (step 2) should refresh at each session open for long-running live
   deployments, or only at restart, until step 3 lands.

## 8. Examples

```qkt
STRATEGY fx_rotation VERSION 1
SYMBOLS
    fx = EXNESS:{EURUSD, GBPUSD, AUDUSD, USDJPY, XAUUSD} EVERY 1h
RULES
    FOR EACH m IN fx DO
      WHEN RANK_OF(m.close / m.close[20] - 1, fx) <= 2 AND POSITION.m = 0
      THEN BUY m SIZING 1 PCT RISK
    FOR EACH m IN fx DO
      WHEN RANK_OF(m.close / m.close[20] - 1, fx) > 3 AND POSITION.m != 0
      THEN CLOSE m
```

```qkt
STRATEGY kalshi_hourly_carry VERSION 1
SYMBOLS
    hourly = KALSHI:*@front.p80 WHERE category IN ("Financials", "Crypto") AND tenor = 1h
             TOP 40 BY open_interest EVERY 1m
RULES
    FOR EACH m IN hourly DO
      WHEN m.minutes_left > 10 AND m.minutes_left < 40
       AND ema(m.close, 20) > ema(m.close, 60)
       AND POSITION.m = 0
      THEN BUY m SIZING 0.5 PCT RISK ORDER_TYPE = LIMIT AT m.bid + 0.01
```

```qkt
STRATEGY bucket_sum VERSION 1
SYMBOLS
    buckets = KALSHI:*@front.* WHERE family = "KXINX" TOP 60 BY open_interest EVERY 1m,
    over    = CHAIN:KALSHI.KXINX.overround.front EVERY 1m
RULES
    FOR EACH m IN buckets DO
      WHEN over.close < -0.03 AND POSITION.m = 0
      THEN BUY m SIZING 10 ORDER_TYPE = LIMIT AT m.ask
```

```qkt
STRATEGY new_perps VERSION 1
SYMBOLS
    fresh = BYBIT_LINEAR:* WHERE kind = perpetual AND listed_for < 2d TOP 20 BY volume EVERY 5m
RULES
    FOR EACH m IN fresh DO
      WHEN m.close > highest(m.high, 12) AND m.buy_volume[1] > 2 * m.sell_volume[1] AND POSITION.m = 0
      THEN BUY m SIZING 0.5 PCT RISK BRACKET { STOP_LOSS BY 3 PCT, TAKE_PROFIT BY 6 PCT }
```

`@front.*` selects every contract of the front event as members; `listed_for` is `now − open_at` from the catalog.
Both join the vocabulary in step 2.
