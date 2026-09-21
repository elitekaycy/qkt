package com.qkt.connector.mt5

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.MarketPriceProvider
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Resolves an MT5-native stop whose trigger is already through the current executable
 * market into the same active order shape used by the engine-held trigger path.
 */
internal fun convertAlreadyCrossedStop(
    request: OrderRequest,
    currentExecPrice: BigDecimal,
): OrderRequest =
    when (request) {
        is OrderRequest.Stop ->
            if (stopIsCrossed(request.side, request.stopPrice, currentExecPrice)) {
                OrderRequest.Market(
                    id = request.id,
                    symbol = request.symbol,
                    side = request.side,
                    quantity = request.quantity,
                    timeInForce = request.timeInForce,
                    timestamp = request.timestamp,
                    strategyId = request.strategyId,
                    legIntent = request.legIntent,
                )
            } else {
                request
            }
        is OrderRequest.StopLimit ->
            if (stopIsCrossed(request.side, request.stopPrice, currentExecPrice)) {
                OrderRequest.Limit(
                    id = request.id,
                    symbol = request.symbol,
                    side = request.side,
                    quantity = request.quantity,
                    limitPrice = request.limitPrice,
                    timeInForce = request.timeInForce,
                    timestamp = request.timestamp,
                    strategyId = request.strategyId,
                    expiresAt = request.expiresAt,
                    legIntent = request.legIntent,
                )
            } else {
                request
            }
        is OrderRequest.Bracket -> {
            val entry = request.entry as? OrderRequest.Stop
            if (entry != null && stopIsCrossed(entry.side, entry.stopPrice, currentExecPrice)) {
                request.copy(
                    entry =
                        OrderRequest.Market(
                            id = entry.id,
                            symbol = entry.symbol,
                            side = entry.side,
                            quantity = entry.quantity,
                            timeInForce = entry.timeInForce,
                            timestamp = entry.timestamp,
                            strategyId = entry.strategyId,
                            legIntent = entry.legIntent,
                        ),
                )
            } else {
                request
            }
        }
        else -> request
    }

private fun stopIsCrossed(
    side: Side,
    stopPrice: BigDecimal,
    currentExecPrice: BigDecimal,
): Boolean = if (side == Side.BUY) stopPrice <= currentExecPrice else stopPrice >= currentExecPrice

private data class NativeStopTrigger(
    val side: Side,
    val stopPrice: BigDecimal,
)

private fun nativeStopTrigger(request: OrderRequest): NativeStopTrigger? =
    when (request) {
        is OrderRequest.Stop -> NativeStopTrigger(request.side, request.stopPrice)
        is OrderRequest.StopLimit -> NativeStopTrigger(request.side, request.stopPrice)
        is OrderRequest.Bracket ->
            (request.entry as? OrderRequest.Stop)?.let { NativeStopTrigger(it.side, it.stopPrice) }
        else -> null
    }

/**
 * Checks an outgoing native stop against the live executable price and sends it as the order it
 * would become if the venue had triggered it, e.g. a BUY stop at 2400.00 submitted while the ask
 * is already 2400.35 goes out as a market buy (a stop-limit goes out as its limit).
 */
internal class MT5CrossedStopConversion(
    private val profile: MT5BrokerProfile,
    private val priceTracker: MarketPriceProvider?,
) {
    private val log = LoggerFactory.getLogger(MT5Broker::class.java)

    fun convertAlreadyCrossedStopAtMarket(request: OrderRequest): OrderRequest {
        val trigger = nativeStopTrigger(request) ?: return request
        val currentExecPrice = priceTracker?.executionPrice(request.symbol, trigger.side) ?: return request
        val converted = convertAlreadyCrossedStop(request, currentExecPrice)
        if (converted == request) return request
        val target = if (request is OrderRequest.StopLimit) "LIMIT" else "MARKET"
        log.info(
            "MT5Broker {} converted already-crossed stop order_id={} side={} stop_price={} " +
                "market_price={} converted_to={}",
            profile.name,
            request.id,
            trigger.side,
            trigger.stopPrice.toPlainString(),
            currentExecPrice.toPlainString(),
            target,
        )
        return converted
    }
}
