package com.qkt.positions

/**
 * The account's positions as a projection of the strategy ledger: one net [Position] per symbol,
 * folded across every strategy's legs. Nothing writes here — the ledger maintains the index on
 * every leg mutation, so reads are O(1) map lookups and never allocate on the tick path.
 */
class AccountPositionView internal constructor(
    private val index: AccountNetIndex,
) : LegExposureProvider {
    override fun positionFor(symbol: String): Position? = index.positionFor(symbol)

    override fun allPositions(): Map<String, Position> = index.positions()

    override fun symbols(): Set<String> = index.symbols()

    override fun forEachLeg(
        symbol: String,
        action: (PositionLeg) -> Unit,
    ) = index.forEachLeg(symbol, action)
}
