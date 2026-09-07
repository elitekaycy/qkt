# Hub Stream Binding (`HUB:`) — Design

> Scoping spec. Companion to the qkt-data-hub design
> (`qkt-data-hub/docs/spec/2026-09-07-qkt-data-hub-design.md`), which owns the record format,
> the pipeline and the snapshot/journal artifacts. This document owns exactly one thing: how
> qkt reads those artifacts and exposes them to strategies **without changing a single byte of
> any run that does not bind a hub stream**. Supersedes the live-fetch half of the macro-series
> design (`2026-06-14-macro-series-data-path-design.md`); the point-in-time replay half is the
> template this extends.

---

## 1. Purpose

Let a `.qkt` strategy bind a hub dataset by name and read its typed fields as ordinary stream
fields, in backtest and live, with the same visibility instant in both modes:

```
SYMBOLS
    gold = EXNESS:XAUUSD EVERY 5m
    cpi  = HUB:macro.us.cpi EVERY 1d
    cal  = HUB:cal.high_impact.USD EVERY 1d
    hub  = HUB:hub.health EVERY 1m

RULES
    WHEN NOW.epoch_ms - hub.last_heartbeat_at > 900000 AND POSITION.gold != 0
    THEN CLOSE gold ; LOG "hub stale"

    WHEN cal.next_high_at - NOW.epoch_ms < 1800000 AND POSITION.gold != 0
    THEN CLOSE gold

    WHEN cpi.surprise_z > 1.0 AND cpi.surprise IS NOT NULL
     AND NOW.epoch_ms - cal.last_high_at > 900000 AND POSITION.gold = 0
    THEN SELL gold SIZING 0.5 PCT RISK
```

The engine gains a **reader**. It gains no fetcher, no parser, no derivation and no knowledge of
any provider. Fetching leaves the engine (Section 9).

### 1.1 Non-goals

- No new event type, bus event, or engine queue. Hub records enter as `Tick`s through the
  existing `TickFeed` → `TradingPipeline.ingest` path.
- No change to the `Tick` or `Candle` data classes.
- No new DSL grammar for field access. Fields are `alias.field`, exactly as candles are.
- No strings to strategies. Enum fields arrive as ordinals; string fields are not bound.
- No change to any run that binds no `HUB:` stream — see the invariant in Section 2.

---

## 2. The invariant this spec is built around

> **A strategy that declares no `HUB:` stream produces a byte-identical trade tape, evidence
> record and report before and after this change, in every mode and tier.**

Everything below is arranged so this holds by construction, and Section 10 names the tests that
pin it. The mechanism is simple: hub feeds are constructed **only** for `HUB:` aliases that a
loaded strategy declares. If none are declared, no hub source is routed, no hub tick is ever
produced, the merge sees the same per-symbol feeds it sees today, and no code path in this spec
runs. Every special case introduced here is keyed on the stream *kind*, resolved at compile
time, never on a runtime string test that could match something else.

For a strategy that **does** bind a hub stream, the changes to its own behaviour are enumerated
in Section 7 as declared divergences with tests. There are exactly two, and both are the same
class of effect that adding any second symbol to a backtest already has.

---

## 3. What exists and is reused unchanged

The 2026-09-06 data-plane audit confirmed the seams this design lands on. Nothing in this list
is modified:

| Seam | Where | Reused for |
|---|---|---|
| Single input abstraction `TickFeed.next()` | `marketdata/TickFeed.kt` | hub records become ticks |
| K-way merge by `(timestamp, feedIndex)` | `marketdata/MergingTickFeed.kt` | backtest ordering of hub vs market ticks |
| Live push→pull adaptor with bounded queue, shed-oldest, reconnect budget | `marketdata/live/LiveTickFeed.kt` | hub tailer is a `LiveTickSource` |
| Per-vendor fan-in for multiple live sources | `CompositeMarketSource.liveTicks` → `FanInTickFeed` | hub joins the MT5 feed in live |
| Prefix routing of symbols to sources | `CompositeMarketSource` + `SymbolPattern.prefix` in `Backtest.kt:326-330` and `MarketSourceFactory.composite` | `HUB:` route |
| One shared pipeline, injected `Clock`, event time from the tick being processed | `TradingPipeline.ingest`, `ReplayEngine.ingest`, `LiveSession` engine loop | unchanged |
| Immediately-closed event candle for non-price observations | `CandleHub.publishMacroEvent` | hub fields visible at `known_at` |
| Read-only alias enforcement at compile time | `AstCompiler.rejectReadOnlyOrders` | hub aliases untradeable |
| Shared candle-hub slots keyed by `HubKey`, one per symbol across strategies | `CandleHub.register` | many strategies, one hub tick stream |
| Warmup seeding from `MarketSource.bars()` | `PerStreamWarmupCoordinator` / `WarmupHistoryLoader` | pre-window hub history |
| Dataset pin + evidence envelope | `DatasetSnapshot`, `evidence/Evidence.kt` | snapshot hashes in evidence |
| Parity harness | `parity/BacktestLiveParityTest`, `DslParityHarness` | hub variants |

Two things the audit found as **hard-coded string prefixes** are the only places existing
behaviour is touched, and they are touched by generalisation, not by adding a second string
(Section 5.4):

- `CandleHub.feed`: `if (tick.symbol.startsWith("MACRO:")) publishMacroEvent(...)`
- `TradingPipeline.ingest`: `val isMacroObservation = tick.symbol.startsWith("MACRO:")`
  gating the malformed-tick floor and the `MarketDataGate` outlier judgment.

---

## 4. Data flow

### 4.1 Backtest

```
snapshot/<dataset>/<schema8>/<window>.qkh   (hub artifact, read-only)
        │  verify sha256 against manifest; refuse on mismatch
        ▼
HubSnapshotFeed : TickFeed        one per (dataset, field) hidden stream
        │  records with known_at in [from, to), policy applied, sorted
        │  → Tick(symbol="HUB:<dataset>/<field>", price=value, timestamp=known_at)
        ▼
MergingTickFeed  (existing)       hub feeds appended AFTER market feeds in the feed list
        ▼
ReplayEngine.ingest → clock.time = known_at → TradingPipeline.ingest (existing)
        ▼
CandleHub: event candle closed at known_at → rules read alias.field via hub.latest(key)
```

### 4.2 Live

```
journal/<dataset>/<date>.ndjson   (hub artifact, growing, read-only mount)
        │  one thread per qkt process: "qkt-hub-tail"
        │  tail subscribed datasets by (file, byte offset, seq); validate; policy
        ▼
HubTailSource : LiveTickSource    emits one Tick per field per record
        ▼
LiveTickFeed (existing)  →  FanInTickFeed (existing, alongside MT5)  →  qkt-live-feed thread
        ▼
Inbound.FeedTick queue → engine thread → TradingPipeline.ingest (existing)
```

No new queue. No new thread touches the bus, `OrderManager`, positions or the schedule runner.
The tail thread produces ticks and nothing else, exactly like the MT5 poller threads.

### 4.3 Time semantics

A hub tick's `timestamp` is the record's `known_at`. In backtest the merge places it exactly;
the strategy sees the field on the first rule evaluation whose event time is `>= known_at`. In
live the tick is emitted when the tail thread reads the appended line, so visibility lags
`known_at` by tail latency (sub-second at the default poll interval). This is the same class of
inherent divergence as A7 (tick sampling) and is recorded as a new catalogue row (Section 7).

Event time in live is `SystemClock`; `advanceTo` is a no-op, as today. In backtest the hub tick
advances `clock.time` to `known_at` like any other tick. Because `known_at` is by construction
`<=` the wall-clock instant the record was written, a live consumer never sees a record whose
`known_at` is in the future; the reader additionally refuses records with
`known_at > clock.now() + skewToleranceMs` (default 5 s) as a defence against a mis-clocked hub.

---

## 5. Engine changes

### 5.1 `HubMarketSource` (new, `marketdata/source/hub/`)

```kotlin
class HubMarketSource(
    private val root: Path,                 // hub_root, read-only
    private val policy: HubReadPolicy,
    private val clock: Clock,
) : MarketSource {
    override val name = "Hub"
    override val capabilities = setOf(TICKS, LIVE_TICKS, BARS)
    override fun supports(symbol: String) = symbol.startsWith("HUB:")
    override fun ticks(symbol: String, range: TimeRange): Sequence<Tick>       // snapshot path
    override fun liveTicks(symbols: List<String>): TickFeed                    // journal tail
    override fun bars(symbol: String, window: TimeWindow, range: TimeRange): Sequence<Candle> // warmup seed
}
```

- `symbol` is the **hidden per-field stream** symbol `HUB:<dataset>/<field>` (5.3). The source
  parses dataset and field, reads the manifest once (cached, re-read on mtime change), and
  refuses an undeclared dataset or field with a `SetupError` naming the schema hash it read.
- `ticks()` opens the snapshot windows covering `range`, **verifies each file's SHA-256 against
  the manifest** and its trailer, and fails closed on mismatch. It yields, per record, the
  highest-revision-so-far semantics naturally: every revision is its own tick at its own
  `known_at`, so the latest closed event candle is always the latest known revision.
- `bars()` returns one `Candle(O=H=L=C=value, start=known_at, end=known_at+window)` per
  record — the shape `MacroMarketSource.bars` already produces — so
  `PerStreamWarmupCoordinator` seeds pre-window hub history with no change.
- `liveTicks()` returns a `LiveTickFeed` over a single `HubTailSource` for all requested hub
  symbols; the tailer groups them by dataset so each journal file is opened once.
- `HubReadPolicy(minLagMs, refuseDerived, staleAfterMs, skewToleranceMs)` is applied
  identically in `ticks()` and `liveTicks()`; a unit test feeds the same records through both
  and asserts identical emitted tick lists.

### 5.2 `HubTailSource` (new, `marketdata/live/hub/`)

- Implements `LiveTickSource.start(onTick, onError, onDisconnect, onReconnect)` / `stop()`.
- One daemon thread `qkt-hub-tail`. Per dataset: current file path, byte offset, last `seq`.
  Loop: poll each file's size every `pollIntervalMs` (default 250 ms); read appended bytes;
  split on `\n`; **ignore a trailing partial line**; parse JSON; drop `seq <= lastSeq`
  (duplicate on restart); validate envelope; apply policy; expand to ticks; `onTick` each in
  field order declared by the schema.
- Day roll: when `<today>.ndjson` appears, finish the previous file then switch; a record's
  `known_at` decides its file, so the reader never misses one across midnight.
- Heartbeat: `hub_root/heartbeat` mtime older than `staleAfterMs` → `onDisconnect("hub stale")`
  once, `onReconnect` when it recovers. `LiveTickFeed` already turns a disconnect that outlives
  its `reconnectBudgetMs` into feed end; for the hub that budget is set very large by default
  (hours) because **a stale hub must not end the market feed** — the strategy gates on
  `hub.health` instead (Section 6.4). This is the one place hub semantics differ from a market
  vendor and it is explicit configuration, not a code branch.
- Errors: a malformed line is counted, logged at a cadence, and skipped; the thread never dies
  on data. A missing file or unreadable mount is an `onError` and a retry, never an exception
  into the feed.
- Determinism note: the tail thread produces ticks in `(seq)` order per dataset; across datasets
  the interleaving is arrival order, as with any two live vendors. This is why backtest
  ordering (5.5) uses `known_at` then feed index, and why the parity catalogue row exists.

### 5.3 Compiler: one alias, hidden per-field streams

Today `StreamDecl(alias, broker, symbol, timeframe)` yields one `HubKey(broker, symbol,
timeframe)` and `compileCandleField` resolves `alias.field` against the fixed `CANDLE_FIELDS`
set. For `broker == "HUB"`:

1. **Resolution at compile time.** `AstCompiler` asks the `HubSchemaResolver` (a small
   interface; production impl reads `manifest.json`, tests use a map) for the dataset's schema
   and hash. Unknown dataset → compile error. The strategy's compiled artifact records the
   schema hash (Section 8).
2. **Expansion.** The alias expands to one hidden `HubKey("HUB", "<dataset>/<field>", tf)` per
   *strategy-visible* field in the schema, plus three envelope streams `known_at`,
   `effective_at`, `revision`. Hidden keys are registered in the `CandleHub` exactly like
   declared streams (retention from warmup requirements, default 2).
3. **Field access.** `StreamFieldRef(alias, field)` on a hub alias compiles to a candle-field
   read of the hidden key for `field`, reading `.close`. `alias.value` is legal only when the
   schema declares `value_alias`; every other field name not in the schema is a **compile
   error** with the list of valid fields. `bid/ask/spread/open/high/low/volume` are not
   valid on hub aliases (compile error).
4. **Types.** number → `Value.Num`; bool → `Value.Num(0|1)` with `= TRUE`/`= FALSE` sugar
   resolved at compile time; timestamp → `Value.Num(epochMs)`; enum → `Value.Num(ordinal)`,
   and a comparison against a string literal that names a declared enum value is rewritten to
   the ordinal at compile time (`cpi.direction = "UP"` → `= 2`); any other string literal
   comparison on a hub field is a compile error. `strategy: false` fields are not expanded.
5. **Read-only.** `readOnlyAliases` gains `broker == "HUB"`; orders, resize, cancel and latch
   on a hub alias are compile errors, as for `MACRO`.
6. **Warmup.** `WarmupRequirements` treats hidden hub keys like any stream: an indicator over
   `cpi.surprise` with window N requires N closed event candles, i.e. N records. With no
   indicator, warmup is 0 and the alias is warm from tick zero; a missing latest candle then
   reads `Undefined`, so a rule referencing it does not fire — fail-closed for entries,
   unchanged semantics for everyone else.
7. **Subscription set.** The set of hidden hub symbols across all loaded strategies is what
   `LiveSession` passes to `source.liveTicks(feedSymbols)` and what `Backtest` puts into
   `request.symbols`. Nothing else in either assembly changes.

Every existing DSL construct then works on hub fields with no further code: indicators, `LET`,
`CASE`, snapshots, sizing expressions, bracket prices, `GTD UNTIL`, exit hooks, portfolio
`REGIMES`. Three-valued logic, edge-triggered firing and `IS NULL` behave exactly as for a
warming indicator or a missing cross-stream bar.

**Lexer check (open item from the hub spec).** Dataset names are dotted (`macro.us.cpi`) and
may carry a scope suffix (`cal.high_impact.USD`); the symbol position in `parseStream` is a
single `IDENT` today. Options, to be settled in the plan: (a) extend the symbol token to accept
`.` and `/` when the broker token is `HUB`; (b) accept a string literal in symbol position for
any broker. (a) is smaller and keeps `qktSymbol` a plain string; it is the default.

### 5.4 Stream kind replaces two hard-coded prefixes

Introduce `enum class StreamKind { MARKET, OBSERVATION }` on `HubKey` (or a registry lookup
keyed by `qktSymbol`, whichever the plan finds cheaper on the hot path — it must be a field
read, never a string scan per tick). `MACRO:` and `HUB:` keys are `OBSERVATION`; everything
else is `MARKET`. Then:

- `CandleHub.feed`: `if (slot.kind == OBSERVATION) publishObservation(slot, tick) else
  aggregator.onTick(tick)`. `publishMacroEvent` is renamed, body unchanged.
- `TradingPipeline.ingest`: `val isObservation = kindOf(tick.symbol) == OBSERVATION` replaces
  the `startsWith("MACRO:")` test, gating the same two checks (malformed floor and
  `marketDataGate.observe`). A per-symbol `HashMap<String, StreamKind>` built at pipeline
  construction makes this an O(1) lookup with no allocation.

Behaviour for `MACRO:` symbols is identical before and after; a test asserts the classification
of every symbol in the existing macro tests.

**Why the gate bypass matters and must stay.** `MarketDataGate.observe` is skipped for
observations, so no `SymbolState` is ever created for them, so `isHealthy(symbol)` — which the
live heartbeat calls for **every feed symbol** — returns `true` by its "never observed" rule.
A daily hub stream that were allowed into the gate would be judged stale minutes after its one
tick and would suppress new orders for the whole session. The bypass is therefore load-bearing
and gets its own test (Section 10).

### 5.5 Backtest assembly (`Backtest.kt`)

- Route: extend the existing composite construction at `Backtest.kt:323-330` with
  `SymbolPattern.prefix("HUB:") to HubMarketSource(...)`, **only when
  `request.symbols.any { it.startsWith("HUB:") }`** — the same guard the macro route already
  uses, so non-hub runs construct exactly the object graph they construct today.
- Feed order: hub feeds are appended **after** all market feeds in `perSymbolFeeds`. The
  merge's tie-break is feed index, so a hub tick and a market tick with the same millisecond
  timestamp process market first. This is deliberate: a fact stamped at the same instant as a
  price becomes visible on the *next* evaluation, never on the bar the price itself closes,
  which is the conservative reading of "knowable at T".
- `replayFeed`: hub symbols always take the `ticks()` branch. Under `--bars`, `forceBars` must
  not apply to observation streams (they have no OHLC bars to synthesise); the guard is
  `kind == OBSERVATION → ticks()`, mirroring how `MACRO:` is excluded from bar provisioning in
  `BacktestContext` today. `--tick-fills` is unaffected: `BarResolvedFeed` resolves fills on
  market ticks only.
- Financing: `ReplayEngine.ingest` calls `swapBook.accrueBetween(prev, tick.timestamp)` for
  every tick. Hub ticks add intermediate boundaries but accrual is per UTC rollover crossing,
  so totals are unchanged. A test runs the same market tape with and without a hub feed and
  asserts identical financing totals.

### 5.6 Live assembly (`MarketSourceFactory`, `LiveSession`)

- `MarketSourceFactory.composite` adds `SymbolPattern.prefix("HUB:") to HubMarketSource(...)`
  **only when the config has a `hub:` block**. Without it, `HUB:` symbols fall to the fallback
  and fail at deploy with "no hub configured", exactly as an unknown broker prefix does.
- `LiveSession` passes `feedSymbols` (now including hidden hub symbols) to `liveTicks` as
  today; `FanInTickFeed` combines the hub vendor with MT5. No other line changes.
- Daemon redeploys recompute `feedSymbols` and restart the composite feed, as today; the tail
  source's cursors are process-local, so a restart re-reads from the start of the current day's
  file and de-duplicates by `seq`. Records already reflected in hub slots are re-published as
  event candles with identical values; `CandleHub` de-duplicates a re-seeded identical candle
  by `(startTime)` in the ring (verify in plan; if not, the tailer skips `seq <= lastSeq` from
  a persisted cursor under `state/`).

### 5.7 CLI provisioning (`BacktestContext`)

- Observation streams are excluded from tick/bar provisioning (extend the `broker != "MACRO"`
  filters to `kind != OBSERVATION`).
- Coverage: before the run, the context checks the manifest covers `[from, to)` for every
  bound dataset at the compiled schema hash; short coverage is an `IncompleteDataException`
  downgraded to a WARNING only under `--allow-incomplete`, mirroring bar coverage. The check
  reads only the manifest, never the network.
- `--no-fetch` has no meaning for hub streams (there is nothing to fetch) and is ignored for
  them with no warning.

---

## 6. Configuration

### 6.1 `qkt.config.yaml`

```yaml
hub:
  root: /var/lib/qkt-hub          # read-only mount of the hub_root
  policy:
    min_lag_ms: 0                 # added to every record's known_at before visibility
    refuse_derived: true          # availability=derived records are invisible
    stale_after_ms: 900000        # heartbeat age that flips hub.health / onDisconnect
    skew_tolerance_ms: 5000       # known_at may not exceed clock.now() by more than this
  tail_poll_ms: 250
  datasets: auto                  # or an explicit allowlist; anything else is a deploy error
```

- **The `hub` section must be added to strict unknown-key validation.** `Config.kt` rejects
  unknown keys only under `risk`; every other section silently drops them, so a typo such as
  `refuse_derived: ture` would today parse as absent and fail open. `HUB_KEYS` and
  `HUB_POLICY_KEYS` join `RISK_KEYS` in `validate*Keys`, and the same is done for
  `datasets`.
- Nothing in this section describes fields, parsing, units or derivations. That is the hub's
  schema, and the compiler reads it from the manifest. A `hub` config that tried to declare
  fields would be an unknown key and rejected.
- `qkt preflight --production` gains checks: hub root mounted read-only, manifest parses,
  heartbeat fresh, every bound dataset present at the compiled schema hash.

### 6.2 Defaults

Absent `hub:` block → no hub source is routed anywhere → Section 2 invariant by construction.

### 6.3 Per-strategy

None. Policy is per process, because it is about the trustworthiness of the data source, not
about a strategy's appetite. A strategy that needs a stricter lag encodes it in its own rule
(`NOW.epoch_ms - cpi.known_at > 60000`).

### 6.4 Hub liveness for strategies

Hub liveness is not a new accessor. The hub emits `hub.health` as a dataset; a strategy binds it
and gates on `hub.last_heartbeat_at` with ordinary arithmetic (Section 1 example). Reasons: no
new namespace (a `HUB.*` accessor would touch lexer, parser, AST, compiler, warmup walker,
snapshot planner and the LSP vocabulary), identical semantics in both modes, and the choice of
how to react to a stale hub stays with the strategy author.

---

## 7. Determinism and parity — declared effects

### 7.1 Runs that bind no hub stream

Unchanged, byte for byte. Pinned by re-running the entire existing parity and golden suites
with the change in place (they construct no hub streams) and by
`HubAbsentByteIdentityTest` (Section 10).

### 7.2 Runs that bind a hub stream — exactly two behavioural effects

Both are the same effect that binding any additional market symbol already has; they are
listed so nobody discovers them later.

1. **SCHEDULE placement.** Backtest fires a `SCHEDULE` occurrence on the next replayed tick
   after its trigger time (A8). A hub tick arriving between two market ticks can be that next
   tick, so an occurrence can fire earlier than it would without the hub stream — and closer to
   live, where the 1 Hz heartbeat already fires it on time. Declared; pinned by a test that
   shows the earlier fire and asserts the fill still occurs on the first *market* tick after it.
2. **Account-equity series sampling cadence.** `sampleAccountEquitySeries(tick.timestamp)` runs
   per ingested tick, so `SERIES ACCOUNT.EQUITY` gains samples at hub-tick instants. Values are
   identical (equity does not change on a hub tick); only the sampling grid densifies.

Things that are **not** affected, with the reason: candle aggregation for market streams (hub
ticks never reach a market aggregator); engine-held triggers and fills (price tracker entries
for `HUB:` symbols exist but no order ever targets them — compile-time read-only); financing
totals (5.5); ID generation (`ids.next()` only on order creation); risk state (no fills).

### 7.3 New parity catalogue row

> **A18** — Hub observation visibility: backtest makes a hub field readable at exactly
> `known_at` (merge order); live makes it readable when the tail thread reads the appended
> journal line, `known_at + tail latency` (sub-second at 250 ms poll). INHERENT — same class
> as A7; quantified in evidence by recording per-run tail latency percentiles.

### 7.4 What parity now covers that it did not before

Because backtest and live read the same artifact bytes, the DSL parity harness can drive a hub
feed through both `Backtest` and `LiveSession` and assert identical trade lists, which the
`MACRO:` live poller never allowed. `HubBacktestLiveParityTest` does this (Section 10).

---

## 8. Evidence and reproducibility

`EvidenceEnvelope` gains:

```kotlin
data class HubEvidence(
    val manifestSha256: String,
    val datasets: Map<String, HubDatasetEvidence>,   // keyed by dataset name
    val policy: Map<String, String>,
)
data class HubDatasetEvidence(
    val schemaSha256: String,
    val snapshotSha256: List<String>,                // one per window read
    val recordsRead: Int,
    val derivedRefused: Int,
)
```

A backtest that read hub data is reproducible from `(strategyHash, dataset, hub)`; a report
missing `hub` while the strategy binds a `HUB:` alias is rejected by the evidence writer. The
compiled strategy artifact carries the schema hash it compiled against; deploying it against a
hub whose manifest shows a different hash for that dataset is a deploy error unless the
operator passes an explicit override that lands in `warnings`.

---

## 9. Migration of `MACRO:`

Phase 2 of the hub plan. Sequence, each step independently shippable and reversible:

1. Hub ships `rates.us.dfii10` etc. with `published`/`derived` availability; the hub's own
   FRED collector replaces `FredSeriesFetcher`'s job.
2. qkt's `MACRO:` source becomes a thin alias: `MACRO:DFII10` resolves to
   `HUB:rates.us.dfii10/value` when a `hub:` block exists. The `MacroSeriesDslTest` fixtures
   move to hub snapshot fixtures; assertions unchanged.
3. `PolicyRateLiveFeed`, `FredSeriesFetcher`, `PolicyRateSeriesFetcher` and the macro
   provisioning block in `BacktestContext` are deleted. `MacroSeriesStore` remains only as a
   one-off converter (`qkt data macro-to-hub`) and is then deleted too.
4. `StreamKind.OBSERVATION` is then produced by `HUB:` alone; the `MACRO` string disappears
   from `src/main`.

Until step 2 lands, `MACRO:` behaviour is unchanged (5.4 keeps its classification).

---

## 10. Testing

| Test | Asserts |
|---|---|
| `HubAbsentByteIdentityTest` | a fixed multi-symbol strategy without hub streams produces identical trade tape, evidence JSON and report hash with the change applied (golden captured from `dev` before the change) |
| existing `parity/*`, `dsl/*`, golden verifiers | all green, no fixture changes |
| `StreamKindClassificationTest` | every `MACRO:`/`HUB:` symbol is `OBSERVATION`, every market symbol `MARKET`; the two pipeline gates and `CandleHub.feed` branch on kind only |
| `HubObservationBypassesMarketDataGateTest` | a daily hub stream in a live session never appears in `staleSymbols()` and never triggers `onUnhealthy` after N heartbeats |
| `HubSnapshotFeedTest` | records outside `[from,to)` excluded; sorted; hash mismatch → `SetupError`; policy (`min_lag`, `refuse_derived`, skew) applied; every emitted tick has `timestamp <= to` |
| `HubTailSourceTest` | partial trailing line ignored then delivered; day roll; `seq` de-dup after restart; malformed line skipped and counted; heartbeat stale → `onDisconnect` once; never throws |
| `HubPolicyEquivalenceTest` | identical records through `ticks()` and `liveTicks()` yield identical tick lists |
| `HubAliasExpansionTest` | one alias → hidden keys per schema field + envelope streams; unknown field, string comparison on non-enum, order on hub alias → compile errors with expected messages |
| `HubFieldSemanticsTest` | `IS NULL` on missing record; three-valued logic; edge re-arm after `Undefined`; enum literal rewrite |
| `HubWarmupSeedTest` | `bars()` seeds N pre-window records; rule fires on first in-window bar |
| `HubBacktestLiveParityTest` | same market ticks + same hub records → identical trade lists in `Backtest` and `LiveSession` (DslParityHarness variant) |
| `HubScheduleInteractionTest` | SCHEDULE fires on the hub tick when it is the next tick; fill on first market tick |
| `HubFinancingUnchangedTest` | financing totals identical with and without a hub feed |
| `HubEvidenceTest` | evidence carries manifest/schema/snapshot hashes; missing `hub` block with a bound alias is rejected |
| `ConfigHubStrictKeysTest` | unknown `hub.*` keys rejected at load |
| Live attestation | per `docs/contributing/live-parity-attestation.md`, a demo session bound to `hub.health` + `cal.high_impact` with the six hashed artifacts, before the image reaches `testing` |

---

## 11. Hot-path statement

Per tick: one `HashMap<String, StreamKind>` lookup in `TradingPipeline.ingest` replacing a
`startsWith` (cheaper), and the existing `CandleHub.feed` slot lookup. Hub ticks are rare
(order of one per second across all datasets at the heaviest projection) and each costs one
event-candle publish. Per bar: field reads are the existing candle-field path on a hidden key.
No per-tick allocation is added; the tail thread's parsing is off the engine thread. Cold
paths: manifest read at compile/deploy, snapshot hash verification at run start.

---

## 12. Risks and open questions

1. **Dotted names in `parseStream`** (5.3). Decide (a) vs (b) in the plan; default (a).
2. **Re-publish on daemon restart** (5.6). Confirm `CandleHub` ring de-duplication or persist
   the tail cursor; either is small, but one must be chosen and tested.
3. **`reconnectBudgetMs` for the hub vendor.** A very large budget keeps the market feed alive
   through a hub outage; confirm `FanInTickFeed` end-of-feed semantics treat one vendor's end
   independently, or the hub vendor must never end.
4. **Enum literal rewrite** touches `ExprCompiler` comparison compilation; keep it a
   compile-time AST rewrite so runtime comparison code is untouched.
5. **Evidence rejection** (Section 8) is a new hard failure for reports; confirm no existing
   report path can bind a hub alias without going through the evidence writer.
6. **Sequencing with the hub repo.** This spec can be implemented against fixture snapshots
   and journals (golden files in `src/test/resources/hub/`) before the hub ships; the fixture
   format is the hub spec's Section 5, frozen at `v: 1`.

---

## 13. References

- `qkt-data-hub/docs/spec/2026-09-07-qkt-data-hub-design.md` — record format, pipeline,
  snapshot `QKH1`, manifest, reader contract (Section 8 there mirrors this document).
- `docs/superpowers/specs/2026-06-14-macro-series-data-path-design.md` — the point-in-time
  replay this generalises; its "candle-close read-lag" caveat is stale, the code closes event
  candles immediately (`CandleHub.publishMacroEvent`).
- `docs/parity/backtest-vs-live.md` — rows A7, A8, A12 for the divergence class; new row A18.
- `docs/concepts/determinism.md` — clock and event-time contract this relies on.
- Data-plane and DSL audits of 2026-09-06 (conversation record) for the seam inventory in
  Section 3.
