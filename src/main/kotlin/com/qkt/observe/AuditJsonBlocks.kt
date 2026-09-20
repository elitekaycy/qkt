package com.qkt.observe

import com.qkt.events.BrokerEvent
import com.qkt.events.FillAccountedEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderRequestEvidence
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.positions.Position

internal fun StringBuilder.appendOrder(request: OrderRequest) {
    append(",\"orderSchemaVersion\":").append(OrderRequestEvidence.SCHEMA_VERSION)
    append(",\"order\":").append(OrderRequestEvidence.toJson(request))
}

internal fun StringBuilder.appendAccountedFill(event: FillAccountedEvent) {
    append(",\"accountedFill\":{")
    append("\"fillSliceId\":").append(jsonString(event.fillSliceId))
    append(",\"sourceFillSequenceId\":").append(event.sourceFillSequenceId)
    event.cumulativeFilled?.let {
        append(",\"cumulativeFilled\":").append(jsonString(it.toPlainString()))
    }
    append(",\"modeledCommissionAccount\":")
        .append(jsonString(event.modeledCommissionAccount.toPlainString()))
    append(",\"venueCostsAccount\":").append(jsonString(event.venueCostsAccount.toPlainString()))
    append(",\"totalCostsAccount\":").append(jsonString(event.totalCostsAccount.toPlainString()))
    append(",\"accountNativeRealized\":").append(jsonString(event.accountNativeRealized.toPlainString()))
    append(",\"strategyNativeRealized\":")
        .append(jsonString(event.strategyNativeRealized.toPlainString()))
    append(",\"nativeCurrency\":").append(jsonString(event.nativeCurrency))
    append(",\"grossAccountRealized\":").append(jsonString(event.grossAccountRealized.toPlainString()))
    append(",\"grossStrategyAccountRealized\":")
        .append(jsonString(event.grossStrategyAccountRealized.toPlainString()))
    append(",\"accountCurrency\":").append(jsonString(event.accountCurrency))
    append(",\"netAccountRealized\":").append(jsonString(event.netAccountRealized.toPlainString()))
    append(",\"netStrategyAccountRealized\":")
        .append(jsonString(event.netStrategyAccountRealized.toPlainString()))
    append(",\"kind\":").append(jsonString(event.kind.name))
    append(",\"executedAt\":").append(event.executedAt)
    append(",\"legId\":").append(event.legId?.let { jsonString(it) } ?: "null")
    append(",\"legAction\":").append(event.legAction?.let { jsonString(it.name) } ?: "null")
    event.conversionRate?.let {
        append(",\"conversionRate\":").append(jsonString(it.toPlainString()))
    }
    event.conversionTimestampMs?.let { append(",\"conversionTimestampMs\":").append(it) }
    event.conversionSource?.let { append(",\"conversionSource\":").append(jsonString(it)) }
    event.contractSize?.let { append(",\"contractSize\":").append(jsonString(it.toPlainString())) }
    append(",\"accountPositionBefore\":").appendPosition(event.accountPositionBefore)
    append(",\"accountPositionAfter\":").appendPosition(event.accountPositionAfter)
    append(",\"strategyPositionBefore\":").appendPosition(event.strategyPositionBefore)
    append(",\"strategyPositionAfter\":").appendPosition(event.strategyPositionAfter)
    append(",\"reducedExposure\":").append(event.reducedExposure)
    append(",\"partial\":").append(event.partial)
    append('}')
}

private fun StringBuilder.appendPosition(position: Position?): StringBuilder {
    if (position == null) return append("null")
    append('{')
    append("\"symbol\":").append(jsonString(position.symbol))
    append(",\"quantity\":").append(jsonString(position.quantity.toPlainString()))
    append(",\"avgEntryPrice\":").append(jsonString(position.avgEntryPrice.toPlainString()))
    position.openedAt?.let { append(",\"openedAtMs\":").append(it) }
    return append('}')
}

internal fun StringBuilder.appendTick(tick: Tick) {
    append(",\"tick\":{")
    append("\"timestampMs\":").append(tick.timestamp)
    append(",\"price\":").append(jsonString(tick.price.toPlainString()))
    tick.volume?.let { append(",\"volume\":").append(jsonString(it.toPlainString())) }
    tick.bid?.let { append(",\"bid\":").append(jsonString(it.toPlainString())) }
    tick.ask?.let { append(",\"ask\":").append(jsonString(it.toPlainString())) }
    tick.bidVolume?.let { append(",\"bidVolume\":").append(jsonString(it.toPlainString())) }
    tick.askVolume?.let { append(",\"askVolume\":").append(jsonString(it.toPlainString())) }
    append('}')
}

internal fun StringBuilder.appendCandle(candle: Candle) {
    append(",\"candle\":{")
    append("\"startTimeMs\":").append(candle.startTime)
    append(",\"endTimeMs\":").append(candle.endTime)
    append(",\"open\":").append(jsonString(candle.open.toPlainString()))
    append(",\"high\":").append(jsonString(candle.high.toPlainString()))
    append(",\"low\":").append(jsonString(candle.low.toPlainString()))
    append(",\"close\":").append(jsonString(candle.close.toPlainString()))
    append(",\"volume\":").append(jsonString(candle.volume.toPlainString()))
    candle.bid?.let { append(",\"bid\":").append(jsonString(it.toPlainString())) }
    candle.ask?.let { append(",\"ask\":").append(jsonString(it.toPlainString())) }
    append('}')
}

internal fun StringBuilder.appendFill(event: BrokerEvent.OrderFilled) {
    append(",\"fill\":{")
    append("\"side\":").append(jsonString(event.side.name))
    append(",\"price\":").append(jsonString(event.price.toPlainString()))
    append(",\"quantity\":").append(jsonString(event.quantity.toPlainString()))
    append(",\"brokerOrderId\":").append(jsonString(event.brokerOrderId.orEmpty()))
    append(",\"partial\":false}")
}

internal fun StringBuilder.appendPartialFill(event: BrokerEvent.OrderPartiallyFilled) {
    append(",\"fill\":{")
    append("\"side\":").append(jsonString(event.side.name))
    append(",\"price\":").append(jsonString(event.price.toPlainString()))
    append(",\"quantity\":").append(jsonString(event.quantity.toPlainString()))
    append(",\"cumulativeFilled\":").append(jsonString(event.cumulativeFilled.toPlainString()))
    append(",\"brokerOrderId\":").append(jsonString(event.brokerOrderId.orEmpty()))
    append(",\"partial\":true}")
}

/** JSON string literal for [value], escaping backslash, quote and the line-control characters. */
internal fun jsonString(value: String): String =
    buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }
