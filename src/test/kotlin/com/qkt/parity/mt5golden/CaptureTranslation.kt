package com.qkt.parity.mt5golden

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.InstrumentMeta
import com.qkt.marketdata.Tick
import java.math.BigDecimal

/** Turns captured MT5 session records into the engine's instrument, tick and order types. */
internal object CaptureTranslation {
    fun CapturedInstrument.toMeta(): InstrumentMeta =
        InstrumentMeta(
            qktSymbol = qktSymbol,
            contractSize = decimal(contractSize),
            volumeStep = decimal(volumeStep),
            volumeMin = decimal(volumeMin),
            volumeMax = volumeMax?.let(::decimal),
            pointSize = decimal(pointSize),
            digits = digits,
            tradeStopsLevelPoints = tradeStopsLevelPoints,
        )

    fun CapturedTick.toTick(): Tick {
        val bidValue = decimal(bid)
        val askValue = decimal(ask)
        return Tick(
            symbol = symbol,
            price = bidValue.add(askValue).divide(BigDecimal(2)),
            timestamp = timestampMs,
            bid = bidValue,
            ask = askValue,
        )
    }

    fun CapturedClientOrder.toOrderRequest(): OrderRequest {
        val parsedSide = Side.valueOf(side)
        val parsedTif = TimeInForce.valueOf(timeInForce)
        val parsedQuantity = decimal(quantity)
        return when (kind) {
            CapturedOrderKind.MARKET ->
                OrderRequest.Market(id, symbol, parsedSide, parsedQuantity, parsedTif, timestampMs, strategyId)
            CapturedOrderKind.LIMIT ->
                OrderRequest.Limit(
                    id,
                    symbol,
                    parsedSide,
                    parsedQuantity,
                    decimal(requireNotNull(limitPrice)),
                    parsedTif,
                    timestampMs,
                    strategyId,
                    expiresAtMs,
                )
            CapturedOrderKind.STOP ->
                OrderRequest.Stop(
                    id,
                    symbol,
                    parsedSide,
                    parsedQuantity,
                    decimal(requireNotNull(stopPrice)),
                    parsedTif,
                    timestampMs,
                    strategyId,
                    expiresAtMs,
                )
            CapturedOrderKind.STOP_LIMIT ->
                OrderRequest.StopLimit(
                    id,
                    symbol,
                    parsedSide,
                    parsedQuantity,
                    decimal(requireNotNull(stopPrice)),
                    decimal(requireNotNull(limitPrice)),
                    parsedTif,
                    timestampMs,
                    strategyId,
                    expiresAtMs,
                )
        }
    }
}
