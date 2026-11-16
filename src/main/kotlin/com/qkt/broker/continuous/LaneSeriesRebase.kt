package com.qkt.broker.continuous

import com.qkt.bus.EventBus
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.events.CostIncurred
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Keeps a live lane's booked P&L on the contract legs when its stream's series moved under an open position (#1683).
 *
 * The engine holds each strategy's position at continuous prices, mapped through the contract's shift as the roll
 * history gave it when the position was booked. A roll history rebuilt while the daemon was down (`qkt fetch --rolls`
 * re-prices every roll from the stored bars) can give the same contract another shift, and every exit after the
 * restart is then mapped by the new one: a long 0.03 whose contract's shift grew 3 points books 0.09 more than the
 * venue paid. So each save records the shift of the contract the lane is on ([savedShift]); a restart that finds the
 * chain shifting that contract by a different amount ([restore]) books what the move adds to each open position as
 * a cost, `quantity × multiplier × (new shift − saved shift)`, once the session is restored ([publish]). Until then
 * the saved shift stays the one saved, so a crash in between books it on the next start instead.
 *
 * Only panama streams take orders ([orderRefusal]), so a position, and so a cost, only ever exists on an additive
 * shift.
 */
internal class LaneSeriesRebase(
    private val chainOf: () -> ContinuousChain,
) {
    private val log = LoggerFactory.getLogger(LaneSeriesRebase::class.java)

    /** The shift restored and not yet booked over, on the contract index it was saved for; null when none waits. */
    private var held: Pair<Int, BigDecimal>? = null
    private var costs: List<CostIncurred> = emptyList()

    /**
     * The shift the lane's positions stand at on contract [index], to save; null before its first contract or past the
     * measured history.
     */
    fun savedShift(index: Int?): BigDecimal? {
        held?.takeIf { it.first == index }?.let { return it.second }
        return index?.takeIf(chainOf()::covers)?.let { chainOf().spaceFor(it).shift }
    }

    /**
     * Compares the [saved] shift of contract [index] with the chain's: when they differ, each of [positions] (signed,
     * by strategy) is owed what the move adds to its continuous P&L, held as a cost until [publish].
     */
    fun restore(
        index: Int?,
        saved: BigDecimal?,
        positions: Map<String, BigDecimal>,
    ) {
        val chain = chainOf()
        if (index == null || saved == null || !chain.covers(index)) return
        val now = chain.spaceFor(index).shift
        val delta = now.subtract(saved)
        if (delta.signum() == 0) return
        val contract = chain.contractSymbol(index)
        log.warn(
            "{} roll history moved {} from shift {} to {} since the lane saved it; booking it on its open positions",
            chain.symbol,
            contract,
            saved.toPlainString(),
            now.toPlainString(),
        )
        held = index to saved
        val reason = "series rebased $contract ${saved.toPlainString()}->${now.toPlainString()}"
        costs =
            positions
                .filterValues { it.signum() != 0 }
                .map { (id, quantity) ->
                    val amount = quantity.multiply(chain.root.multiplier).multiply(delta)
                    CostIncurred(id, chain.symbol, amount, reason, null)
                }
    }

    /** Books the costs [restore] held on [bus], and saves the chain's shift from now on. */
    fun publish(bus: EventBus) {
        costs.forEach(bus::publish)
        costs = emptyList()
        held = null
    }
}
