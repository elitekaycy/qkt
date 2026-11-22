package com.qkt.broker.continuous

import com.qkt.bus.EventBus
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.derivatives.futures.PriceSpace
import com.qkt.events.CostIncurred
import com.qkt.execution.OrderRequest
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Keeps a lane's booked P&L and working orders on the contract legs when its stream's series moves under them
 * (#1683, #1754).
 *
 * The engine holds each strategy's position, and each order's levels, at continuous prices: contract prices plus the
 * contract's shift as the roll history gave it. `qkt fetch --rolls` re-prices every roll from the stored bars, so a
 * rebuilt history can give the same contract another shift. A session reads it at its next start, or, running, at its
 * next live roll measurement. Mapped by the new shift, every exit would book the move on top of the venue's P&L (a long
 * 0.03 whose contract's shift grew 3 points books 0.09 more than the venue paid), and every working order would be
 * sent the move away from where it rests.
 *
 * So the lane maps its contract by the shift its positions stand at ([space]), saved with the lane ([savedShift]) and
 * restored after a restart ([restore]). Once the session is [active], a chain that shifts that contract differently
 * ([check]) books the move on each open position as a cost, `quantity × multiplier × (new shift − old shift)`, moves
 * each working order on the contract the same amount in the series, so its venue order stays at the contract price it
 * rests at, and maps by the chain's shift from then on. The venue's orders are left alone: they rest where the strategy
 * put them against the market it saw, and their fills now map into the series the engine marks in.
 *
 * The lane is saved at the new shift ([save]) before the engine hears of the costs, as a roll's closes are: a restart
 * that finds the move saved books nothing, so a running session's booking is never booked again by the next start.
 *
 * Only panama streams take orders ([orderRefusal]), so a position or an order only ever stands on an additive shift.
 */
internal class LaneSeriesRebase(
    private val chainOf: () -> ContinuousChain,
    private val bus: EventBus,
    private val positions: Map<String, BigDecimal>,
    private val orders: ContinuousOrderMap,
    private var active: Boolean,
    private val save: () -> Unit,
    private val moved: () -> Unit,
) {
    private val log = LoggerFactory.getLogger(LaneSeriesRebase::class.java)

    /** The contract the lane is on and the shift its positions and orders stand at; null before its first. */
    private var basis: Pair<Int, BigDecimal>? = null

    /** The chain last compared with [basis]: a chain is rebuilt whenever its history changes. */
    private var seen: ContinuousChain? = null

    /** Contract [index]'s mapping: at the shift the lane stands at on its contract, else the chain's. */
    fun space(index: Int): PriceSpace {
        val chain = chainOf()
        val at = basis?.takeIf { it.first == index } ?: return chain.spaceFor(index)
        return PriceSpace(chain.adjust, at.second, chain.root.tickSize)
    }

    /** The shift to save for contract [index]; null before its first contract or past the measured history. */
    fun savedShift(index: Int?): BigDecimal? {
        basis?.takeIf { it.first == index }?.let { return it.second }
        return index?.takeIf(chainOf()::covers)?.let { chainOf().spaceFor(it).shift }
    }

    /** The lane is on contract [index] at the [saved] shift (the chain's when an older qkt saved none). */
    fun restore(
        index: Int?,
        saved: BigDecimal?,
    ) {
        if (saved == null) return track(index)
        basis = index?.let { it to saved }
    }

    /** The lane is now on contract [index], at the chain's shift. */
    fun track(index: Int?) {
        val chain = chainOf()
        seen = chain
        basis = index?.takeIf(chain::covers)?.let { it to chain.spaceFor(it).shift }
    }

    /** The session is restored: the move a restart found is booked now, and later ones as they come. */
    fun activate() {
        active = true
        check()
    }

    /** Books a move of the lane's contract in a chain rebuilt since the last look, when active. */
    fun check() {
        val chain = chainOf()
        if (!active || chain === seen) return
        seen = chain
        val (index, old) = basis ?: return
        if (!chain.covers(index)) return
        val now = chain.spaceFor(index).shift
        val delta = now.subtract(old)
        if (delta.signum() == 0) return
        val contract = chain.contractSymbol(index)
        log.warn(
            "{} roll history moved {} from shift {} to {}; booking it on open positions and moving working orders",
            chain.symbol,
            contract,
            old.toPlainString(),
            now.toPlainString(),
        )
        basis = index to now
        orders.on(index).forEach { orders.add(it.copy(request = it.request.shifted(delta))) }
        moved()
        save()
        val reason = "series rebased $contract ${old.toPlainString()}->${now.toPlainString()}"
        positions
            .filterValues { it.signum() != 0 }
            .forEach { (id, quantity) ->
                val amount = quantity.multiply(chain.root.multiplier).multiply(delta)
                bus.publish(CostIncurred(id, chain.symbol, amount, reason, null))
            }
    }

    /** This order with every level [delta] higher in the series. */
    private fun OrderRequest.shifted(delta: BigDecimal): OrderRequest =
        when (this) {
            is OrderRequest.Limit -> copy(limitPrice = limitPrice + delta)
            is OrderRequest.Stop -> copy(stopPrice = stopPrice + delta)
            is OrderRequest.StopLimit -> copy(stopPrice = stopPrice + delta, limitPrice = limitPrice + delta)
            else -> this
        }
}
