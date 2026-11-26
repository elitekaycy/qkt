package com.qkt.cli.bot

import com.qkt.cli.BacktestContext
import com.qkt.dsl.sets.DynamicSetMembership
import com.qkt.dsl.sets.SymbolSetSpec
import com.qkt.instrument.InstrumentMeta

/**
 * `qkt bot universe "<spec>" --at <instant>` for a set whose members move (runtime membership plan, step 8): the
 * members it has at that instant, from the same point-in-time membership a backtest and a live session step (the
 * rows listed then, filtered and ranked as of it). A set reading market fields has members only a running session
 * can rank, and a slot universe names slots rather than listed contracts; both are refused, naming why.
 */
internal object BotUniverseAt {
    fun members(
        spec: SymbolSetSpec.Filtered,
        rows: List<InstrumentMeta>,
        atMs: Long,
    ): List<InstrumentMeta> {
        if (spec.readsMarket) {
            throw BacktestContext.Companion.SetupError(
                "${spec.canonical} ranks on market figures (close, volume, spread) only a running session sees",
            )
        }
        if (spec.slot != null && !spec.ladder) {
            throw BacktestContext.Companion.SetupError(
                "${spec.canonical} names each family's slot; list a family's contracts with " +
                    "${spec.venue}:<FAMILY>@front.* instead",
            )
        }
        val listed = rows.filter { it.qktSymbol.substringBefore(':').equals(spec.venue, ignoreCase = true) }
        val bySymbol = listed.associateBy { it.qktSymbol.substringAfter(':') }
        return DynamicSetMembership(spec, listed, MINUTE_MS).membersAt(atMs).map(bySymbol::getValue)
    }

    private const val MINUTE_MS = 60_000L
}
