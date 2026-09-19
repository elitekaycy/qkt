package com.qkt.observe.insights

import com.qkt.accounting.ConvertedMoney
import com.qkt.events.FillAccountedEvent
import com.qkt.events.TradeEvent
import com.qkt.positions.Position
import java.math.BigDecimal

/**
 * Insights translation for executions and their accounting: raw trades, post-accounting
 * fill slices with position and PnL evidence, and realized closes. Mixed into
 * [InsightsTranslate]; pure, allocation limited to the payload map each envelope carries.
 */
interface TradeInsights {
    fun fromTrade(e: TradeEvent): InsightsEnvelope =
        busEnvelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "trade",
            mapOf(
                "orderId" to e.trade.orderId,
                "symbol" to e.trade.symbol,
                "side" to e.trade.side.name,
                "price" to e.trade.price,
                "qty" to e.trade.quantity,
                "ts" to e.trade.timestamp,
            ),
        )

    /** Translate one post-accounting fill slice, including position and PnL evidence. */
    fun fromFillAccounted(e: FillAccountedEvent): InsightsEnvelope =
        busEnvelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "fill.accounted",
            mapOf(
                "orderId" to e.orderId,
                "symbol" to e.symbol,
                "fillSliceId" to e.fillSliceId,
                "sourceFillSequenceId" to e.sourceFillSequenceId,
                "cumulativeFilled" to e.cumulativeFilled,
                "modeledCommissionAccount" to e.modeledCommissionAccount,
                "venueCostsAccount" to e.venueCostsAccount,
                "totalCostsAccount" to e.totalCostsAccount,
                "accountNativeRealized" to e.accountNativeRealized,
                "strategyNativeRealized" to e.strategyNativeRealized,
                "nativeCurrency" to e.nativeCurrency,
                "grossAccountRealized" to e.grossAccountRealized,
                "grossStrategyAccountRealized" to e.grossStrategyAccountRealized,
                "accountCurrency" to e.accountCurrency,
                "netAccountRealized" to e.netAccountRealized,
                "netStrategyAccountRealized" to e.netStrategyAccountRealized,
                "kind" to e.kind.name,
                "executedAt" to e.executedAt,
                "legId" to e.legId,
                "legAction" to e.legAction?.name,
                "conversionRate" to e.conversionRate,
                "conversionTimestampMs" to e.conversionTimestampMs,
                "conversionSource" to e.conversionSource,
                "contractSize" to e.contractSize,
                "accountPositionBefore" to positionPayload(e.accountPositionBefore),
                "accountPositionAfter" to positionPayload(e.accountPositionAfter),
                "strategyPositionBefore" to positionPayload(e.strategyPositionBefore),
                "strategyPositionAfter" to positionPayload(e.strategyPositionAfter),
                "reducedExposure" to e.reducedExposure,
                "partial" to e.partial,
            ),
        )

    /**
     * A realized close from the engine's fill accounting (not a bus event).
     *
     * [netAccountRealized] is the canonical closed-trade PnL for dashboards: account-currency PnL
     * after modeled commissions and venue-reported costs. The legacy `realized` payload field is
     * retained as an alias of `netAccountRealized`. When [convertedRealized] is supplied, the
     * payload also carries gross account PnL before those costs plus native-currency and FX
     * conversion evidence, so consumers can reconcile net-vs-gross instead of guessing.
     *
     * Deterministic id, so a re-sent batch dedupes at the collector.
     */
    fun tradeClosed(
        trade: com.qkt.execution.Trade,
        netAccountRealized: BigDecimal,
        strategyId: String,
        convertedRealized: ConvertedMoney? = null,
    ): InsightsEnvelope =
        InsightsEnvelope(
            id = "tc-${trade.orderId}-${trade.timestamp}",
            seq = 0,
            ts = trade.timestamp,
            strategyId = strategyId.takeIf { it.isNotBlank() },
            type = "trade.closed",
            payload =
                mapOf(
                    "orderId" to trade.orderId,
                    "symbol" to trade.symbol,
                    "side" to trade.side.name,
                    "qty" to trade.quantity,
                    "price" to trade.price,
                    "realized" to netAccountRealized,
                    "netAccountRealized" to netAccountRealized,
                    "grossAccountRealized" to convertedRealized?.account?.amount,
                    "accountRealized" to convertedRealized?.account?.amount,
                    "nativeRealized" to convertedRealized?.native?.amount,
                    "nativeCurrency" to convertedRealized?.native?.normalizedCurrency,
                    "accountCurrency" to convertedRealized?.account?.normalizedCurrency,
                    "currency" to convertedRealized?.account?.normalizedCurrency,
                    "fxRate" to convertedRealized?.conversion?.rate,
                    "fxRateTimestamp" to convertedRealized?.conversion?.timestamp,
                    "fxSource" to convertedRealized?.conversion?.source,
                    "costsAccount" to convertedRealized?.account?.amount?.subtract(netAccountRealized),
                    "pnlBasis" to "net_account_after_costs",
                    "realizedAliasOf" to "netAccountRealized",
                    "sideAttribution" to "fill_side",
                    "ts" to trade.timestamp,
                ),
        )
}

private fun positionPayload(position: Position?): Map<String, Any?>? =
    position?.let {
        mapOf(
            "symbol" to it.symbol,
            "quantity" to it.quantity,
            "avgEntryPrice" to it.avgEntryPrice,
            "openedAtMs" to it.openedAt,
        )
    }
