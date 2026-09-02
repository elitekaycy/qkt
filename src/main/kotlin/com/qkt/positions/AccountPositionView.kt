package com.qkt.positions

/**
 * The account's positions as a projection of the strategy ledger: one net [Position] per symbol,
 * folded across every strategy's legs. Nothing writes here — the ledger maintains the index on
 * every leg mutation, so reads are O(1) map lookups and never allocate on the tick path.
 */
class AccountPositionView internal constructor(
    private val ledger: StrategyPositionTracker,
) : LegExposureProvider {
    override fun positionFor(symbol: String): Position? = ledger.accountPositionFor(symbol)

    override fun allPositions(): Map<String, Position> = ledger.accountPositions()

    override fun symbols(): Set<String> = ledger.accountSymbols()

    override fun forEachLeg(
        symbol: String,
        action: (PositionLeg) -> Unit,
    ) = ledger.forEachLeg(symbol, action)
}
