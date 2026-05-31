# Phase 35 — Bar-level synchronized publish for paired symbols

**Date:** 2026-05-30
**Issue:** [#45](https://github.com/elitekaycy/qkt/issues/45)

## Goal

Let a strategy declare named **sync groups** of streams in its `SYMBOLS` block. When the engine sees a bar close on any stream in a group, it withholds the strategy's evaluation until *every* member of the group has produced a bar for the same time window — then evaluates once, atomically, with all bars in scope.

Strategies that don't declare any group keep firing per-stream exactly as today.

## Motivation

A pairs-trading strategy watches two streams and acts on their relationship — for example `gold.close - 75 * silver.close`. Today each stream's candle close triggers an independent evaluation, and the rule reads the other side from `CandleHub.history(otherKey, 0)` — the **last known** value. If gold closes a few hundred ms before silver, the gold-triggered evaluation reads a stale silver, then silver closes and triggers its own evaluation with the now-current values. Two firings per bar boundary, each with a stale-by-one-side view.

For same-broker same-timeframe pairs the skew is microseconds and the approximation is practically fine — that's the regime `pairs_xau_xag` is shipped in today. But it isn't a guarantee, and any cross-broker or different-timeframe pair will break.

#174 (expression-fed indicators) noted the "latest known candle" approximation as the substrate for cross-stream expressions. Phase 35 is the proper substrate the approximation deferred.

## Current state (what already exists)

- `CandleHub` (`src/main/kotlin/com/qkt/dsl/compile/CandleHub.kt`) — one `Slot` per `HubKey(broker, symbol, timeframe)`. Each slot owns an aggregator, a ring of closed candles, and a list of listener callbacks. Slots are independent: a tick that closes a bar fires only that slot's listeners.
- `AstCompiler.bindToHub` (`src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt:208`) — loops over the strategy's declared streams and registers one `hub.onClosed(key, …)` callback per alias. Each callback drives `evaluate(alias, candle, hub, ctx, emit)`.
- `evaluate` reads other streams' values from the hub's rings via `hub.history(otherKey, 0)` and `hub.history(otherKey, N)`. The "atomicity gap" is between the moment one slot fires and the moment the other slot has its bar in its ring.

Gaps to fill:
- No concept of "group of streams" in the AST or the hub.
- No mechanism for "fire once all members have closed."
- No DSL syntax to declare a sync group.

## Scope

### In scope

- DSL syntax for declaring multiple independent sync groups per strategy, with an optional per-group timeout (`WITHIN <duration>`).
- AST + parser support for the new clauses.
- `CandleHub` gains a sync-group registration path; per-stream registration path is unchanged.
- `AstCompiler.bindToHub` routes grouped streams through a single group listener, non-grouped streams through the existing per-stream listeners.
- Tick-driven timeout heartbeat (timeouts checked on each `hub.feed(tick)` call).
- Tests at parser, hub, compiler, and end-to-end Backtest levels.
- Reference docs + phase changelog.

### Out of scope (deferred)

- **Cross-timeframe sync** (e.g. 5m gold + 1h silver in the same group). Different timeframes don't have a natural "same bar" concept; would need an explicit alignment rule. Defer until a real strategy needs it.
- **Persisting pending-bars across daemon restart.** Bar closes are at minute-to-hour cadence; one missed group fire after restart is acceptable. The strategy resumes on the next clean group close. MT5StateRecovery still handles open positions normally.
- **Precise scheduled-task timeouts.** A timer thread per group would give millisecond-accurate timeouts independent of tick arrival; tick-driven heartbeat is good enough and dramatically simpler. Document the precision limit; revisit if a strategy needs tighter bounds.
- **Sync across portfolio children.** Each child strategy declares its own groups; cross-child sync is a different problem (portfolio supervisor concern).
- **Non-uniform group composition** — e.g. "gold required, silver optional." Out of scope; every group member is mandatory.

## Approach

### 1. DSL syntax — `SYNCHRONIZE` clauses inside `SYMBOLS`

```qkt
STRATEGY pairs_demo VERSION 1

SYMBOLS
    gold   = EXNESS:XAUUSD EVERY 1h
    silver = EXNESS:XAGUSD EVERY 1h
    btc    = BYBIT_SPOT:BTCUSDT EVERY 1h
    eth    = BYBIT_SPOT:ETHUSDT EVERY 1h
    vix    = TV:VIX EVERY 1d

    SYNCHRONIZE gold silver                ← group 1, no timeout
    SYNCHRONIZE btc eth WITHIN 200ms       ← group 2, with timeout
    -- vix not mentioned → standalone, fires independently

RULES
    WHEN gold.close > 75 * silver.close AND btc.close > eth.close * 60
    THEN BUY gold SIZING 0.1
```

The clauses live at the end of the `SYMBOLS` block, indented as siblings of the stream declarations. Each `SYNCHRONIZE` clause defines one independent group.

### 2. New token

Add `SYNCHRONIZE` to `TokenKind.kt` (between `SYMBOLS` and the next keyword for grouping coherence). The existing `WITHIN` token is reused. The lexer derives keywords from `TokenKind.values()` reflectively (minus a denylist), so this is the only lexer-side change.

### 3. AST shape

```kotlin
data class StrategyAst(
    …,
    val streams: List<StreamDecl>,
    val syncGroups: List<SyncGroupDecl> = emptyList(),   // NEW
    …
)

/** One declared sync group inside a SYMBOLS block. */
data class SyncGroupDecl(
    val aliases: List<String>,
    val timeoutMs: Long? = null,
)
```

A `List<SyncGroupDecl>` rather than a single boolean or single group — adding a future variant (e.g. a conditional sync group) plugs in as a new field on `SyncGroupDecl` or a new sealed-class sibling, not a refactor.

### 4. Parser changes

In `parseSymbols()` (`Parser.kt`), after the stream-declaration loop:

```kotlin
val groups = mutableListOf<SyncGroupDecl>()
while (peek().kind == TokenKind.SYNCHRONIZE) {
    advance()
    val aliases = mutableListOf<String>()
    while (peek().kind == TokenKind.IDENT) {
        aliases.add(expect(TokenKind.IDENT).lexeme)
    }
    var timeoutMs: Long? = null
    if (peek().kind == TokenKind.WITHIN) {
        advance()
        timeoutMs = parseDuration().toMillis()
    }
    groups.add(SyncGroupDecl(aliases, timeoutMs))
}
```

### 5. Validation (parse-time, before returning the AST)

- Each group must list **≥2 aliases**. A single-alias group is a no-op and almost certainly a typo.
- Each alias must exist in the surrounding `streams` block.
- No alias appears in more than one group (no overlapping groups).
- An alias may be NOT in any group — that's a standalone stream, treated exactly as today.
- Timeouts must parse as a positive duration.

### 6. `CandleHub` engine changes

Add a sync-group registry alongside the existing slot map:

```kotlin
private class SyncGroup(
    val keys: List<HubKey>,
    val timeoutMs: Long?,
    val listeners: MutableList<OwnedSyncListener>,
    val owners: MutableSet<String>,
    /** window-end-ms → (HubKey → Candle) accumulated until full. */
    val pending: MutableMap<Long, MutableMap<HubKey, Candle>> = mutableMapOf(),
    var firstArrivalMs: Long? = null,
)

private val syncGroups: MutableMap<SyncGroupId, SyncGroup> = ConcurrentHashMap()
// Reverse index for the per-slot fast path:
private val syncGroupsByKey: MutableMap<HubKey, List<SyncGroupId>> = ConcurrentHashMap()
```

The existing `Slot` close-callback gets extended:

```kotlin
on slot close for key K with candle C (window-end T):
    push C into K's ring (existing behaviour)
    for groupId in syncGroupsByKey[K] ?: emptyList():
        group = syncGroups[groupId]
        group.pending.getOrPut(T) { mutableMapOf() }[K] = C
        if group.firstArrivalMs == null:
            group.firstArrivalMs = clock.now()
        if group.pending[T].keys == group.keys.toSet():
            // ALL keys in the group have closed bar T → atomic fire
            for listener in group.listeners:
                listener.callback(group.pending[T].toMap())
            group.pending.remove(T)
            if group.pending.isEmpty(): group.firstArrivalMs = null
    if K is not in any group:
        for listener in slot.listeners: listener.callback(C)   // existing path
```

**Note the key invariant:** a stream that's part of a group does NOT also fire its slot's per-stream listeners. The grouping is exclusive — sync-aware strategies see one combined fire; the per-stream firing would re-trigger evaluation with a partial view and reintroduce the bug.

### 7. Timeout via tick-driven heartbeat

Inside `feed(tick)`:

```kotlin
fun feed(tick: Tick) {
    for ((key, slot) in slots) {
        if (key.qktSymbol == tick.symbol) slot.aggregator.onTick(tick)
    }
    checkSyncTimeouts(clock.now())
}

private fun checkSyncTimeouts(nowMs: Long) {
    for (group in syncGroups.values) {
        val firstArrival = group.firstArrivalMs ?: continue
        val timeout = group.timeoutMs ?: continue   // null timeout = wait forever
        if (nowMs - firstArrival <= timeout) continue
        for ((window, partial) in group.pending.toMap()) {
            for (listener in group.listeners) {
                listener.callback(partial.toMap())
            }
            group.pending.remove(window)
            log.warn(
                "sync group fired with ${partial.size}/${group.keys.size} slots " +
                    "after ${nowMs - firstArrival}ms timeout (window=$window)"
            )
        }
        group.firstArrivalMs = null
    }
}
```

**Known precision limit:** timeouts only fire on tick arrivals. If a strategy's streams all stop ticking, no timeout fires. In practice both members of a pair never stop simultaneously; document the limit and revisit when a strategy hits it.

### 8. New `CandleHub` public API

```kotlin
fun registerSyncGroup(
    id: SyncGroupId,
    keys: List<HubKey>,
    timeoutMs: Long?,
    strategyId: String,
)

fun onSyncClosed(
    id: SyncGroupId,
    strategyId: String,
    callback: (Map<HubKey, Candle>) -> Unit,
)
```

The existing `register`, `onClosed`, `seed`, `historySize`, and `history` stay unchanged. Non-sync strategies keep using them as before.

`unregister(strategyId)` also tears down any sync groups owned solely by that strategy — symmetry with the existing slot-cleanup behaviour.

### 9. `AstCompiler.bindToHub` wiring

```kotlin
val groupedAliases = ast.syncGroups.flatMap { it.aliases }.toSet()

for ((alias, key) in streams) {
    if (alias in groupedAliases) continue        // handled below
    // existing per-stream registration loop
    hub.onClosed(key, ctx.strategyId) { closed ->
        evaluate(alias, closed, hub, ctx, emit)
    }
}

for ((groupIdx, group) in ast.syncGroups.withIndex()) {
    val keys = group.aliases.map { streams.getValue(it) }
    val groupId = SyncGroupId("${ctx.strategyId}#$groupIdx")
    hub.registerSyncGroup(groupId, keys, group.timeoutMs, ctx.strategyId)
    hub.onSyncClosed(groupId, ctx.strategyId) { closedMap ->
        val driverAlias = group.aliases.first()
        val driverCandle = closedMap.getValue(streams.getValue(driverAlias))
        evaluate(driverAlias, driverCandle, hub, ctx, emit)
    }
}
```

**Why "driver alias"?** The existing `evaluate(alias, candle, …)` signature takes one alias. For a synced group all bars are already in the hub's rings at this point (because the group's close-callback put them there atomically right before invoking listeners). Rules that read `silver.close` go through `hub.history(silverKey, 0)` — which now reads the just-arrived silver bar. So `evaluate()` itself does not change; just one alternate listener-registration path.

### 10. Backtest path

`Backtest` replays ticks from historical data. `CandleHub` aggregates the same way; sync logic runs identically. **No `Backtest` code change required.** If historical data has a gap on one stream, that group's pending-bars window for the affected period stays incomplete and the group doesn't fire for that period — correct behaviour.

### 11. Warmup interaction

`hub.seed(key, candles)` prepends historical bars to a slot's ring without invoking listeners. With sync groups added, seeded warmup is **not** routed through `SyncGroup.pending` either — warmup is a ring-loading concern, not an evaluation concern. Live ticks (the first ones the engine sees after warmup completes) are the first events that can populate `SyncGroup.pending`.

### 12. Strategy opt-in (separate PR in qkt-prod)

After Phase 35 ships, `pairs_xau_xag.qkt` opts in with a one-line diff:

```diff
 SYMBOLS
     gold   = EXNESS:XAUUSD EVERY 1h
     silver = EXNESS:XAGUSD EVERY 1h
+    SYNCHRONIZE gold silver
```

No engine deploy required between Phase 35's image build and the strategy update — same image runs both shapes; the runtime behaviour change comes from the strategy file. This makes the cut-over reversible: revert the strategy file to go back to the approximation.

## Validation rules (summary)

- `SYNCHRONIZE` lists ≥2 aliases.
- Every listed alias exists in `streams`.
- No alias appears in more than one group.
- `WITHIN` is optional; when present, parses as a positive `DURATION` literal.
- Aliases not in any group keep firing as today (standalone).
- An empty `SYNCHRONIZE` (zero aliases) is a parse error.
- A group with one alias is a parse error (defensive — silently allowing it would mask typos).
- A non-existent alias in a group is a parse error with a pointed message.

## Testing strategy

### Parser unit tests — `ParserSyncSymbolsTest`

- single group parses to a single `SyncGroupDecl` with two aliases.
- two groups parse to two independent decls.
- `WITHIN 200ms` parses to `timeoutMs = 200`.
- single-alias group is rejected with a pointed message.
- unknown alias in group is rejected.
- duplicate alias across groups is rejected.

### Hub unit tests — `CandleHubSyncTest`

- two slots both close a bar for the same window → one combined fire with both candles in the map.
- one slot closes, the other doesn't → no fire; pending state holds the first bar.
- second slot closes for the same window → fire happens; pending clears.
- second slot closes for a DIFFERENT window → no fire for either; pending holds two distinct entries.
- non-grouped slot continues to fire its own per-slot listeners independently.

### Hub timeout tests — `CandleHubSyncTimeoutTest`

- group with `timeoutMs = 100`: first arrival at t=0, next tick at t=200 → partial fire happens with WARN, pending clears.
- group with `timeoutMs = null`: first arrival at t=0, next tick at t=200 → no fire; pending unchanged.
- after timeout fires, next bar close on either slot starts a new pending entry cleanly.

### Compiler tests — `AstCompilerSyncTest`

- DSL with `SYNCHRONIZE gold silver` → bindToHub registers exactly one sync group and zero per-stream listeners on the grouped aliases.
- DSL with one group + one standalone alias → one sync-group registration + one per-stream registration.
- Two groups → two sync-group registrations.

### End-to-end Backtest — `SyncPairsEndToEndTest`

- A two-stream strategy with `SYNCHRONIZE gold silver`, tick sequence with gold-bar-then-silver-bar within timeout, asserts the rule body fires exactly once with both bars current.
- Same strategy without `SYNCHRONIZE` — same tick sequence fires the rule twice. Pins the "no-regression for non-grouped strategies" guarantee.

## Docs targets

- `docs/reference/dsl/streams.md` — add a "Synchronizing streams" subsection with the multi-group example and the timeout semantic.
- `docs/phases/phase-35-bar-sync.md` — phase changelog (summary, what's new, migration, cookbook with three examples — single sync pair, multi-group, mixed sync + standalone — testing patterns, known limitations).
- `docs/reference/dsl/index.md` — link the new subsection from the streams entry.

## Backwards compatibility

- `synchronized` defaults to `emptyList()` on `StrategyAst`; every existing strategy parses unchanged.
- `AstCompiler.bindToHub` falls back to the existing per-stream path when `ast.syncGroups` is empty — zero hub or compiler behaviour change for non-sync strategies.
- `pairs_xau_xag.qkt` stays on the "latest known candle" approximation in prod until its file is explicitly updated to opt in. The engine code can ship and run for days without affecting the live strategy.

## Risk assessment

- **Low: parse-time validation gaps.** Mitigated by the dedicated `ParserSyncSymbolsTest` — every rejection path is covered.
- **Medium: hub re-entrancy / listener ordering.** A sync-group fire invokes user callbacks while the hub holds a reference to the pending map. Callbacks that re-enter the hub (e.g. via `historySize`) must see consistent state. Mitigation: snapshot `group.pending[T]` into a local immutable map before invoking listeners (already in the pseudocode); listeners receive an immutable view.
- **Medium: timeout precision when one stream stalls entirely.** Documented as a known limitation. A monitoring rule can alert on "sync group with non-empty pending for > N seconds" once observability for the hub is wired.
- **Low: backtest determinism.** Backtest replay is tick-driven; the sync-group state is fully deterministic given the same tick sequence. Verified by the e2e tests.
- **Low: warmup interaction.** Warmup never invokes listeners and never populates `SyncGroup.pending`, by design. The first tick post-warmup is the first event that can drive a group fire.

## References

- Issue: [#45](https://github.com/elitekaycy/qkt/issues/45)
- Precursor: [#174](https://github.com/elitekaycy/qkt/issues/174) — expression-fed indicators; introduced the "latest known candle" approximation Phase 35 properly replaces for opted-in strategies.
- Live consumer: `qkt-prod/strategies/pairs_xau_xag.qkt` (currently on the approximation).
- Related infra: `CandleHub`, `CandleAggregator`, `AstCompiler.bindToHub`, `StrategyAst.streams`.
