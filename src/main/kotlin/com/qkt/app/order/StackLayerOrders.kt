package com.qkt.app.order

import com.qkt.broker.PositionAccountingMode
import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.execution.At
import com.qkt.execution.LayerSpec
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.positions.LegRole
import java.math.BigDecimal

/**
 * Turns one layer of a [OrderRequest.Stack] into the venue order that fires it: its quantity,
 * its trigger price relative to the seed fill, whether it rests as a stop or a limit, and the
 * leg it opens on a hedging account.
 */
internal class StackLayerOrders(
    private val clock: Clock,
    private val positionMode: (symbol: String) -> PositionAccountingMode,
) {
    fun resolveTriggerPrice(
        trigger: com.qkt.execution.LayerTrigger,
        anchor: BigDecimal,
    ): BigDecimal {
        val at = (trigger as? At) ?: error("non-Immediate triggers must be At")
        return evaluateAt(at.price, anchor)
    }

    fun resolveLayerQuantity(layer: LayerSpec): BigDecimal {
        layer.resolvedQuantity?.let { return it }
        // Fallback: supports test code that builds LayerSpec by hand without going through
        // ActionCompiler. Only literal-qty sizing is supported in this path.
        val sizing = layer.sizing
        if (sizing is SizeQty) {
            val n =
                sizing.expr as? NumLit
                    ?: error("STACK layer qty must be a literal in tests that bypass ActionCompiler")
            return n.value
        }
        error(
            "STACK non-qty sizing (RISK/NOTIONAL/EQUITY%/BALANCE%) requires resolution by ActionCompiler. " +
                "If building LayerSpec manually for testing, use SizeQty(NumLit). " +
                "If reaching this in production, ActionCompiler did not populate LayerSpec.resolvedQuantity.",
        )
    }

    /**
     * Turn one layer into the venue order that fires it. A layer written as a plain touch
     * (`AT price`, market on touch) becomes a stop when its trigger sits beyond the seed in the
     * trade direction — price has to move through it — and a limit when the trigger sits behind
     * the seed, where price has to come back to it. A buy stop below the market would be
     * triggered the moment it was placed, which is not what "buy more when down 200" means.
     * The compact `STACK n SPACING d BELOW` form already resolves this at compile time; this is
     * the same rule applied to the layer-list form, whose triggers are only known once the seed
     * fills. [anchor] is the seed fill (null for the seed layer itself).
     */
    fun buildLayerOrder(
        layerId: String,
        parent: OrderRequest.Stack,
        layer: LayerSpec,
        qty: BigDecimal,
        triggerPrice: BigDecimal?,
        anchor: BigDecimal? = null,
    ): OrderRequest {
        val intent = layerEntryIntent(layerId, parent.symbol)
        val restsBehindAnchor =
            triggerPrice != null &&
                anchor != null &&
                (
                    (parent.side == Side.BUY && triggerPrice < anchor) ||
                        (parent.side == Side.SELL && triggerPrice > anchor)
                )
        return when {
            triggerPrice == null ->
                OrderRequest.Market(
                    id = layerId,
                    symbol = parent.symbol,
                    side = parent.side,
                    quantity = qty,
                    timeInForce = parent.timeInForce,
                    timestamp = clock.now(),
                    strategyId = parent.strategyId,
                    legIntent = intent,
                )
            layer.orderType is com.qkt.dsl.ast.Limit || restsBehindAnchor ->
                OrderRequest.Limit(
                    id = layerId,
                    symbol = parent.symbol,
                    side = parent.side,
                    quantity = qty,
                    limitPrice = triggerPrice,
                    timeInForce = parent.timeInForce,
                    timestamp = clock.now(),
                    strategyId = parent.strategyId,
                    legIntent = intent,
                )
            else ->
                OrderRequest.Stop(
                    id = layerId,
                    symbol = parent.symbol,
                    side = parent.side,
                    quantity = qty,
                    stopPrice = triggerPrice,
                    timeInForce = parent.timeInForce,
                    timestamp = clock.now(),
                    strategyId = parent.strategyId,
                    legIntent = intent,
                )
        }
    }

    /**
     * A pyramiding layer is its own ticket on a hedging venue and nets into the book elsewhere —
     * the same rule the planner applies to a strategy-emitted entry.
     */
    private fun layerEntryIntent(
        layerId: String,
        symbol: String,
    ): LegIntent =
        if (positionMode(symbol) == PositionAccountingMode.HEDGING) {
            LegIntent.Open(layerId, LegRole.INDEPENDENT)
        } else {
            LegIntent.Net
        }
}
