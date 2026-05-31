# Phase 35 — Bar-Level Synchronized Publish for Paired Symbols

## Summary

Strategies can now declare sync groups of streams via `SYNCHRONIZE` clauses inside `SYMBOLS`. The engine evaluates the strategy once per group-bar-window with every member's candle current and in scope. Closes #45 — the proper substrate for pairs trading that #174's "latest known candle" approximation deferred.

## The problem

Pairs and basket strategies read across streams: `gold.close - silver.close`, `btc.close / eth.close`, `sma(silver.close, 20) > sma(gold.close, 20)`. Before this phase, each stream's bar close fired its own evaluation cycle. Gold's 13:00 bar might close at `13:00:00.150`; silver's at `13:00:00.180`. The 30 ms gold-aliased fire reads silver's 12:00 close from the ring — the wrong window. The trade tags the wrong spread.

`pairs_xau_xag.qkt` in prod has been silently running with `silver` dropped entirely (#196 fixed the comma-required parser bug that caused that). Even once silver was reaching the engine, the per-stream race remained.

## What's new

- **DSL keyword `SYNCHRONIZE`** inside the `SYMBOLS` block. Multiple clauses per strategy are allowed; each defines an independent group. Optional `WITHIN <duration>` per clause sets a timeout.
- **AST node `SyncGroupDecl`** and field `StrategyAst.syncGroups: List<SyncGroupDecl>` (default empty).
- **`CandleHub` sync registry** — `registerSyncGroup(group, strategyId)`, `onSyncClosed(group, strategyId, callback)`, `syncGroupKeys()`, and `unregister(strategyId)` cleanup.
- **`SyncGroupKey`** identity for a group, validating ≥2 members, positive timeout when present, and a single shared timeframe across members.
- **Atomic fire on full arrival** — every bar close routes into matching sync slots; when every member has a bar for the same window-end, listeners fire once with `Map<alias, Candle>` and the window clears.
- **Tick-driven timeout sweep** — `CandleHub.feed(tick)` walks pending windows; any window whose `endTime + timeoutMs < tick.timestamp` is dropped without firing. Prevents one dead stream from leaking partial state forever.
- **`AstCompiler.bindToHub`** skips grouped aliases in the per-stream `onClosed` loop; registers each group and subscribes via `onSyncClosed`. Non-grouped aliases keep their pre-#45 wiring.
- **Two-pass evaluate inside the sync callback** — every member's indicators/snapshots/aggregates update first, then rules fire. Cross-stream indicator references (`sma(silver.close, 20)` inside a gold-anchored rule) see the same-window value, not the previous window's.

## Migration

Pure addition. Every existing strategy parses and runs unchanged.

To opt a pairs strategy in, add one line inside `SYMBOLS`:

```diff
 SYMBOLS
     gold   = EXNESS:XAUUSD EVERY 1h,
     silver = EXNESS:XAGUSD EVERY 1h
+    SYNCHRONIZE gold silver
```

## Worked example

```qkt
STRATEGY pairs VERSION 1
SYMBOLS
  gold   = EXNESS:XAUUSD EVERY 1h,
  silver = EXNESS:XAGUSD EVERY 1h
  SYNCHRONIZE gold silver
RULES
  WHEN gold.close - 75 * silver.close > 200 AND POSITION.gold = 0
    THEN BUY gold SIZING 0.1
```

Without `SYNCHRONIZE`, the rule fires at every gold close reading whatever silver bar is currently in the ring — usually the previous window's. With it, the rule fires exactly once per matched bar-pair, evaluating the spread on the same window's prices.

## Timeout semantics

`WITHIN <duration>` is **drop-on-timeout**, not partial-fire. When the first member of a window closes but the rest don't arrive in time, the pending bars are released and no callback fires. Conservative on purpose: a paired-strategy entry on half-data is the kind of trade you regret later. If a partial-fire variant turns out to be useful, file an issue with the case.

Same-broker pairs almost never need a timeout — leave `WITHIN` off and the engine waits forever (in practice, microseconds). Cross-broker pairs are the typical use case (`SYNCHRONIZE btc eth WITHIN 30s`).

## Known limitations

- **Mixed timeframes are rejected.** A sync group of `gold = ... EVERY 1h` and `silver = ... EVERY 1m` would have no shared window boundaries. `SyncGroupKey.init` rejects this at construction rather than letting it silently never fire. If a slowest-anchor variant is wanted, it deserves its own design.
- **Overlapping groups are rejected.** An alias can appear in at most one `SYNCHRONIZE` group per strategy. Two overlapping groups would force the engine to choose which window to anchor on — ambiguous.
- **No partial-fire-on-timeout.** See above; this was an intentional simplification.
- **Indicator ordering within a sync window is alias-order.** Indicators bound to a given alias update in the order aliases are listed in the `SYNCHRONIZE` clause. For most cross-stream indicators this is invisible (each alias's indicators are computed against its own bar), but rules that read indicators bound to a later-listed alias from an earlier-listed alias's evaluation pass see the indicator's prior value — same-window data, prior-window indicator. The two-pass split inside the sync callback (Task 7) makes this a non-issue for the common case. Document the limitation here so the surprise doesn't recur.

## Implementation notes

- The full implementation lands across PRs #195 (spec + plan), #196 (Tasks 1-4: token, AST, parser, hub registration + comma-optional `SYMBOLS` fix), #197 (Tasks 5+7: atomic fire, timeout, `AstCompiler.bindToHub`), #198 (Task 8: end-to-end backtest), and this PR (docs).
- Wire-up is in `CandleHub.routeToSyncSlots` (called from the per-stream close callback) and `CandleHub.sweepSyncTimeouts` (called once per `feed(tick)`). Both walk `syncSlots` linearly — fine for the small number of groups any realistic strategy declares.
- `CompiledStrategy` stores `syncGroups: List<SyncGroupDecl>` and resolves them to `SyncGroupKey` values at `bindToHub` time using the hub-aware `streams` map.

## What's still open

- **Integration with `qkt research`** — an interactive REPL hasn't shipped yet (#81). When it does, sync semantics should compose naturally with whatever tick-replay loop it uses.
- **MT5 paired-symbol latency tuning** — same-broker pairs on MT5 are tight enough not to need `WITHIN`, but the brokers vary. If a venue's bar feed gets noticeably out of order, the diagnostic path is: log group fire timings, check pending-window count on the hub, then decide if a timeout is appropriate.
