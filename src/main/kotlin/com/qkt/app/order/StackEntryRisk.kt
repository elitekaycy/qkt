package com.qkt.app.order

import com.qkt.common.Money
import com.qkt.execution.OrderRequest
import com.qkt.instrument.InstrumentRegistry
import java.math.BigDecimal

/**
 * The report's risk and protection for a filled STACK entry (the seed or a layer): each layer carries its own copy of
 * the stack's bracket anchored on its own fill (docs/reference/dsl/stack.md), so the levels are that bracket resolved
 * against the layer's fill price, the same prices [StackLayerExits] arms.
 */
internal class StackEntryRisk(
    private val book: OrderBook,
    private val instruments: InstrumentRegistry,
) {
    /** The entry risk of [clientOrderId] when it is a STACK entry whose plan has a bracket, else null. */
    fun forFill(
        clientOrderId: String,
        quantity: BigDecimal,
        fillPrice: BigDecimal,
        symbol: String,
    ): EntryRiskReport? {
        val order = book[clientOrderId] ?: return null
        val parent = order.parentClientOrderId?.let { book[it]?.request } as? OrderRequest.Stack ?: return null
        // A position-modify venue reports a layer's close under the layer's own id, on the other side.
        if (order.request.side != parent.side) return null
        val bracket = parent.plan.outerBracket ?: return null
        val stopSpec = bracket.stopLoss ?: return null
        val targetSpec = bracket.takeProfit ?: return null
        val instrument = instruments.lookup(symbol)
        val tick = instrument?.priceStep
        val stop = computeChildPrice(stopSpec, parent.side, fillPrice, isStopLoss = true, tick = tick)
        val distance = (fillPrice - stop).abs()
        val target =
            computeChildPrice(targetSpec, parent.side, fillPrice, isStopLoss = false, tick = tick, slDistance = distance)
        val risk =
            distance
                .multiply(quantity, Money.CONTEXT)
                .multiply(instrument?.contractSize ?: BigDecimal.ONE, Money.CONTEXT)
        return EntryRiskReport(risk, ProtectionLevels(stop, target))
    }
}
