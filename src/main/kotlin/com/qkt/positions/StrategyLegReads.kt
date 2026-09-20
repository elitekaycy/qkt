package com.qkt.positions

import java.math.BigDecimal

/**
 * Read-only queries over the per-strategy leg ledger: net [Position] views, individual legs,
 * venue tickets and leg counts, keyed by strategy and symbol. [StrategyPositionTracker]
 * answers these by delegating to the leg books it owns; nothing here mutates the ledger.
 */
interface StrategyLegReads {
    /** Net view of [strategyId]'s legs on [symbol], or null when it holds none. */
    fun positionFor(
        strategyId: String,
        symbol: String,
    ): Position?

    /** Net view per symbol for every symbol [strategyId] holds. */
    fun positionsFor(strategyId: String): Map<String, Position>

    /** Net views for every strategy, keyed by strategy then symbol. */
    fun allByStrategy(): Map<String, Map<String, Position>>

    /** Immutable snapshot of every open leg owned by [strategyId], across symbols. */
    fun allLegsFor(strategyId: String): List<PositionLeg>

    /** New Phase 27 accessor: the full leg book for direct inspection. */
    fun legBookFor(
        strategyId: String,
        symbol: String,
    ): LegBook?

    /** Find an open leg by id across every symbol the strategy holds. */
    fun legById(
        strategyId: String,
        legId: String,
    ): PositionLeg?

    /**
     * Venue ticket of the leg with [legId] for [strategyId], searching across that strategy's
     * symbols, or null if no such leg (or it has no ticket). Lets an engine-fired exit close the
     * exact venue position by ticket — e.g. a trailing stop closing its independent straddle leg.
     */
    fun ticketForLeg(
        strategyId: String,
        legId: String,
    ): String?

    /** Venue ticket for the strategy's PRIMARY position on [symbol], when unambiguous. */
    fun ticketForPrimary(
        strategyId: String,
        symbol: String,
    ): String?

    /** Open position count on [symbol] for [strategyId] — the real number of legs, not the net. */
    fun openCountFor(
        strategyId: String,
        symbol: String,
    ): Int

    /** Open long-side legs on [symbol] for [strategyId]. */
    fun longCountFor(
        strategyId: String,
        symbol: String,
    ): Int

    /** Open short-side legs on [symbol] for [strategyId]. */
    fun shortCountFor(
        strategyId: String,
        symbol: String,
    ): Int

    /** Gross exposure (sum of leg sizes, side-blind) on [symbol] for [strategyId]. */
    fun grossFor(
        strategyId: String,
        symbol: String,
    ): BigDecimal

    /** Strategy ledger net on [symbol] minus [brokerView]'s net, at money scale. */
    fun driftFor(
        symbol: String,
        brokerView: PositionProvider,
    ): BigDecimal
}
