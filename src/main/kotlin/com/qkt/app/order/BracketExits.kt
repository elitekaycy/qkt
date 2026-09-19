package com.qkt.app.order

import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.exitLegIntent
import java.math.BigDecimal

/**
 * The exit orders a bracket gets and where they are anchored: an exit OCO (take-profit limit
 * plus protective stop) built from the actual fill, or the engine-managed stop an attached
 * bracket runs on top of the venue's static stop.
 */
internal class BracketExits(
    private val prices: ObservedPrices,
    private val clock: Clock,
) {
    /**
     * Best-effort entry-price estimate for a bracket's exits. Stop/Limit/IfTouched entries carry
     * their intended trigger; a market entry falls back to the last observed price.
     */
    fun entryEstimate(req: OrderRequest.Bracket): BigDecimal =
        entryEstimateOrNull(req)
            ?: error("Cannot estimate entry price for bracket ${req.id}: no last price for ${req.symbol}")

    /** [entryEstimate], or null while a market entry's symbol has no price yet. */
    fun entryEstimateOrNull(req: OrderRequest.Bracket): BigDecimal? =
        when (val entry = req.entry) {
            is OrderRequest.Stop -> entry.stopPrice
            is OrderRequest.Limit -> entry.limitPrice
            is OrderRequest.IfTouched -> entry.triggerPrice
            is OrderRequest.StopLimit -> entry.stopPrice
            else -> prices.priceOf(req.symbol)
        }

    /** Exit OCO for [req] re-anchored on a fill of [fillQuantity] at [fillPrice]. */
    fun exitOco(
        req: OrderRequest.Bracket,
        fillPrice: BigDecimal,
        fillQuantity: BigDecimal,
    ): OrderRequest.StandaloneOCO {
        val resolved = resolveBracketAtFill(req, fillPrice)
        // Exits must never exceed what actually filled — a venue partial booked at its
        // real volume (#615) would otherwise get exits sized to the full request.
        val exitQuantity = resolved.quantity.min(fillQuantity)
        val exitSide = if (resolved.side == Side.BUY) Side.SELL else Side.BUY
        val exit = resolved.exitLegIntent()
        val tp =
            OrderRequest.Limit(
                "${resolved.id}-tp",
                resolved.symbol,
                exitSide,
                exitQuantity,
                resolved.takeProfit,
                resolved.timeInForce,
                clock.now(),
                resolved.strategyId,
                legIntent = exit,
            )
        val sl =
            protectiveStop(
                "${resolved.id}-sl",
                resolved.stopLoss,
                resolved.symbol,
                exitSide,
                exitQuantity,
                fillPrice,
                resolved.timeInForce,
                clock.now(),
                resolved.strategyId,
                exit,
            )
        return OrderRequest.StandaloneOCO(
            "${resolved.id}-oco",
            resolved.symbol,
            exitSide,
            exitQuantity,
            tp,
            sl,
            resolved.timeInForce,
            clock.now(),
            resolved.strategyId,
        )
    }

    /**
     * The engine-managed stop an attached bracket runs on top of the venue's static stop, or
     * null for a fixed stop (the venue's attached SL covers it outright). Anchored on
     * [entryPrice] when the fill is known, else on the entry estimate.
     */
    fun managedStop(
        req: OrderRequest.Bracket,
        now: Long,
        entryPrice: BigDecimal? = null,
    ): OrderRequest? {
        val exitSide = if (req.side == Side.BUY) Side.SELL else Side.BUY
        val exit = req.exitLegIntent()
        val spec = req.stopLoss
        if (spec is StopLossSpec.Fixed) return null
        return protectiveStop(
            "${req.id}-sl",
            spec,
            req.symbol,
            exitSide,
            req.quantity,
            entryPrice ?: entryEstimate(req),
            req.timeInForce,
            now,
            req.strategyId,
            exit,
        )
    }
}
