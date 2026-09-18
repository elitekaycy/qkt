package com.qkt.dsl.compile

import com.qkt.dsl.ast.SyncGroupDecl
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext

/**
 * Attaches a compiled strategy to a [CandleHub]: replays seeded history through the state
 * updates, then registers the per-stream, sync-group and basket close listeners that evaluate it.
 */
internal class StrategyHubWiring(
    private val streams: Map<String, HubKey>,
    private val syncGroups: List<SyncGroupDecl>,
    private val baskets: List<com.qkt.dsl.ast.BasketDecl>,
    private val sequenceRuntime: SequenceRuntime,
    private val updater: CloseStateUpdater,
    private val evaluator: AliasCloseEvaluator,
) {
    fun wire(
        hub: CandleHub,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        // Aliases that belong to ANY sync group are evaluated via the sync callback
        // instead of the per-stream close. Without this split, both gold and silver
        // would individually fire their rules with cross-stream data from the wrong
        // window. (#45 Phase 35.)
        val syncedAliases: Set<String> = syncGroups.flatMap { it.aliases }.toSet()
        // A basket is a synthetic stream: its candle is computed by its constituent
        // sync-group, not delivered by a per-stream feed. Skip the per-stream close path
        // for basket aliases — they evaluate from the composite write (see basket groups).
        val basketAliases: Set<String> = baskets.map { it.alias }.toSet()

        // Seeding the ring alone is insufficient: indicators, aggregates, rolling
        // snapshots and CROSSES state must see the same historical closes that a
        // continuous backtest saw. Replay without firing rules or position-open
        // transitions, then attach the live listeners.
        //
        // The replay interleaves every stream's history by bar close time, exactly as a
        // continuous run delivers closes. Walking one alias to completion before the next
        // leaves every cross-stream indicator (`lag(o.close, n)` read while replaying `s`)
        // Undefined for the whole of `s`'s replay, so an expression-fed indicator rooted
        // on `s` never receives an input and stays cold for its full period after deploy.
        val replay = ArrayList<Pair<String, Candle>>()
        for ((alias, key) in streams) {
            if (alias in basketAliases) continue
            for (seeded in hub.seededHistory(key)) replay.add(alias to seeded)
        }
        // Stable sort: bars closing at the same instant keep declaration order, matching
        // the live sync-group update pass.
        replay.sortBy { it.second.endTime }
        for ((alias, seeded) in replay) {
            updater.updatePerAlias(alias, seeded, hub, ctx, warmupReplay = true)
        }

        for ((alias, key) in streams) {
            if (alias in basketAliases || alias in syncedAliases) continue
            hub.onClosed(key, ctx.strategyId) { closed ->
                evaluator.evaluate(alias, closed, hub, ctx, emit)
            }
        }

        for (group in syncGroups) {
            val members = group.aliases.associateWith { streams.getValue(it) }
            val groupKey = SyncGroupKey(members = members, timeoutMs = group.timeoutMs)
            hub.registerSyncGroup(groupKey, ctx.strategyId)
            hub.onSyncClosed(groupKey, ctx.strategyId) { bars ->
                // Two-pass: every alias's indicators/snapshots/aggregates update FIRST,
                // then rules fire. This ensures a gold-anchored rule that reads
                // `sma(silver.close, N)` sees silver's same-window value, not the
                // previous window's. Without this split, the alias evaluated first
                // would fire rules against the other alias's stale indicator state.
                // A basket alias in this group is only a timing gate — its own
                // update-then-fire already ran in its implicit constituent group, so it is
                // skipped here to avoid evaluating the basket twice per window.
                for (alias in group.aliases) {
                    if (alias in basketAliases) continue
                    updater.updatePerAlias(alias, bars.getValue(alias), hub, ctx)
                }
                for (alias in group.aliases) {
                    if (alias in basketAliases) continue
                    evaluator.runSequencesForAlias(alias, bars.getValue(alias), hub, ctx)
                }
                var deferSequenceCompletion = false
                for (alias in group.aliases) {
                    if (alias in basketAliases) continue
                    deferSequenceCompletion =
                        evaluator.fireRulesForAlias(alias, bars.getValue(alias), hub, ctx, emit) ||
                        deferSequenceCompletion
                }
                sequenceRuntime.afterRulePass(deferSequenceCompletion)
                for (alias in group.aliases) {
                    if (alias !in basketAliases) {
                        evaluator.evaluationObserver(
                            alias,
                            streams.getValue(alias),
                            bars.getValue(alias),
                            evaluator.rulesByAlias[alias]?.size ?: 0,
                        )
                    }
                }
            }
        }

        bindBaskets(hub, ctx, emit)
    }

    /**
     * Wire each basket's implicit sync group over its constituents. When the constituents'
     * same-window bars assemble, the compositor folds them into the composite index; the
     * resulting candle is published into the hub under the basket key, then the basket runs
     * its own update-then-fire (indicators, then rules) on that synthesized close — the same
     * two-pass the explicit sync path uses. `null` (the first aligned window, baseline only)
     * publishes nothing, so `basket.close` stays Undefined until the basket is warm.
     */
    private fun bindBaskets(
        hub: CandleHub,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        for (basket in baskets) {
            val basketKey = streams.getValue(basket.alias)
            val compositor = BasketCompositor(basketKey.qktSymbol, basket.constituents)
            val members = basket.constituents.associateWith { streams.getValue(it) }
            val groupKey = SyncGroupKey(members = members, timeoutMs = null)
            hub.registerSyncGroup(groupKey, ctx.strategyId)
            hub.onSyncClosed(groupKey, ctx.strategyId) { bars ->
                val composite = compositor.onAligned(bars) ?: return@onSyncClosed
                hub.publish(basketKey, composite)
                updater.updatePerAlias(basket.alias, composite, hub, ctx)
                evaluator.runSequencesForAlias(basket.alias, composite, hub, ctx)
                val deferSequenceCompletion = evaluator.fireRulesForAlias(basket.alias, composite, hub, ctx, emit)
                sequenceRuntime.afterRulePass(deferSequenceCompletion)
                evaluator.evaluationObserver(
                    basket.alias,
                    basketKey,
                    composite,
                    evaluator.rulesByAlias[basket.alias]?.size ?: 0,
                )
            }
        }
    }
}
