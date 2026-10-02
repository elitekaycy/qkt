package com.qkt.connector.gateway

import com.qkt.common.Side
import com.qkt.derivatives.futures.PriceSpace
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.PriceAdjustment
import com.qkt.positions.PositionProvider
import java.math.BigDecimal

/** How an engine order maps onto VGP v1: the body to send, or why it cannot be sent. */
internal sealed interface GatewayOrderMapping {
    /** Send [body]. */
    data class Send(
        val body: WireSubmit,
    ) : GatewayOrderMapping

    /** The order has no VGP v1 form, for [reason]. */
    data class Unsupported(
        val reason: String,
    ) : GatewayOrderMapping
}

/**
 * Maps engine orders onto the four VGP v1 order types. `reduce_only` is set only when the order strictly
 * reduces both the strategy's position and the account's (opposite side, no larger than what is held):
 * on a shared account a strategy's close can open the account the other way, so both must agree. The
 * gateway's kill switch then lets a flatten through and never an opening.
 */
internal object GatewayOrders {
    /**
     * The VGP v1 form of [request] on venue [code], judged against [positions] and the account's [accountHeld].
     * Venues refuse a price off the contract's grid, so limit and stop levels are snapped to [tick] the way
     * the backtest exchange does ([PriceSpace]): never filling or triggering before the strategy's level.
     */
    fun map(
        request: OrderRequest,
        code: String,
        positions: PositionProvider,
        accountHeld: BigDecimal,
        tick: BigDecimal?,
    ): GatewayOrderMapping {
        val grid = tick?.let { PriceSpace(PriceAdjustment.NONE, BigDecimal.ZERO, it) }

        fun limit(level: BigDecimal) =
            (grid?.takeIf { level.signum() > 0 }?.limitToContract(level, request.side) ?: level).toPlainString()

        fun stop(level: BigDecimal) =
            (grid?.takeIf { level.signum() > 0 }?.stopToContract(level, request.side) ?: level).toPlainString()

        val tif =
            when (request.timeInForce) {
                TimeInForce.GTC -> "gtc"
                TimeInForce.IOC -> "ioc"
                TimeInForce.FOK -> "fok"
                TimeInForce.DAY -> "day"
                TimeInForce.GTD -> return GatewayOrderMapping.Unsupported("VGP v1 has no good-till-date orders")
            }
        if (request.expiresAt != null) return GatewayOrderMapping.Unsupported("VGP v1 orders cannot carry an expiry")
        val clientOrderId = GatewayClientIds.of(request)
        if (clientOrderId.length > GatewayClientIds.MAX_LENGTH) {
            return GatewayOrderMapping.Unsupported(
                "order id $clientOrderId is longer than ${GatewayClientIds.MAX_LENGTH} characters",
            )
        }
        val side = if (request.side == Side.BUY) "buy" else "sell"
        val quantity = request.quantity.toPlainString()
        val reduceOnly =
            reduces(request, positions.positionFor(request.symbol)?.quantity) && reduces(request, accountHeld)
        val body =
            when (request) {
                is OrderRequest.Market ->
                    WireSubmit(
                        clientOrderId,
                        code,
                        side,
                        "market",
                        quantity,
                        null,
                        null,
                        tif,
                        reduceOnly,
                    )
                is OrderRequest.Limit ->
                    WireSubmit(
                        clientOrderId,
                        code,
                        side,
                        "limit",
                        quantity,
                        limit(request.limitPrice),
                        null,
                        tif,
                        reduceOnly,
                    )
                is OrderRequest.Stop ->
                    WireSubmit(
                        clientOrderId,
                        code,
                        side,
                        "stop",
                        quantity,
                        null,
                        stop(request.stopPrice),
                        tif,
                        reduceOnly,
                    )
                is OrderRequest.StopLimit ->
                    WireSubmit(
                        clientOrderId,
                        code,
                        side,
                        "stop_limit",
                        quantity,
                        limit(request.limitPrice),
                        stop(request.stopPrice),
                        tif,
                        reduceOnly,
                    )
                else -> return GatewayOrderMapping.Unsupported("${request::class.simpleName} has no VGP v1 order type")
            }
        return GatewayOrderMapping.Send(body)
    }

    private fun reduces(
        request: OrderRequest,
        held: BigDecimal?,
    ): Boolean {
        if (held == null) return false
        val opposite =
            (held.signum() > 0 && request.side == Side.SELL) || (held.signum() < 0 && request.side == Side.BUY)
        return opposite && request.quantity <= held.abs()
    }
}
