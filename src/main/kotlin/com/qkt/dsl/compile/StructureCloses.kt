package com.qkt.dsl.compile

import com.qkt.common.IdGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.strategy.Signal
import com.qkt.strategy.StructurePosition
import com.qkt.strategy.StructureState
import com.qkt.strategy.StructureView
import java.math.BigDecimal

/**
 * The signals that close a strategy's option structures, shared by `CLOSE <structure>`, `CLOSE_ALL`
 * and a portfolio gate's flatten. A structure closes as one group, so the margin rule judges its closes
 * together (closing a wing alone could leave a short uncovered) and the gate never drops it.
 */
internal object StructureCloses {
    /**
     * One closing group for [structure]: a market order per held, unexpired leg, shorts first, naming
     * the structure it closes; null when no leg is left to close (expired legs settle at expiry).
     */
    fun closeGroup(
        structure: StructurePosition,
        strategyId: String,
        now: Long,
        ids: IdGenerator,
    ): Signal.SubmitGroup? {
        val requests =
            structure.legs
                .filter { it.heldQuantity.signum() != 0 && it.expiryMs > now }
                .sortedBy { it.heldQuantity.signum() }
                .map { leg ->
                    val side = if (leg.heldQuantity.signum() < 0) Side.BUY else Side.SELL
                    OrderRequest.Market(
                        ids.next(),
                        leg.symbol,
                        side,
                        leg.heldQuantity.abs(),
                        TimeInForce.GTC,
                        now,
                        strategyId,
                    )
                }
        if (requests.isEmpty()) return null
        return Signal.SubmitGroup(
            "${structure.id}-close-${ids.next()}",
            structure.alias,
            requests,
            closes = structure.id,
        )
    }

    /**
     * What ends every live structure in [view]: an OPEN one closes as a group; a PENDING one has its
     * working legs cancelled, and its coordinator then unwinds the legs that filled; an UNWINDING or
     * CLOSING one is already being closed. Every signal only removes risk, so none is gated.
     */
    fun endAll(
        view: StructureView,
        strategyId: String,
        now: Long,
        ids: IdGenerator,
    ): List<Signal> =
        view.all().flatMap { structure ->
            when (structure.state) {
                StructureState.OPEN -> listOfNotNull(closeGroup(structure, strategyId, now, ids))
                StructureState.PENDING ->
                    structure.legs
                        .filter { it.entryPrice == null }
                        .map { Signal.CancelPendingForSymbol(it.symbol, force = true) }
                StructureState.UNWINDING, StructureState.CLOSING -> emptyList()
            }
        }

    /** The signed quantity every live structure in [view] holds, per contract. */
    fun heldBySymbol(view: StructureView): Map<String, BigDecimal> =
        view
            .all()
            .flatMap { it.legs }
            .groupBy { it.symbol }
            .mapValues { (_, legs) -> legs.fold(BigDecimal.ZERO) { sum, leg -> sum.add(leg.heldQuantity) } }
}
