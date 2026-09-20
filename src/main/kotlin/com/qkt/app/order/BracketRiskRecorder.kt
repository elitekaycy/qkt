package com.qkt.app.order

import com.qkt.common.Money
import com.qkt.execution.OrderRequest
import com.qkt.instrument.InstrumentRegistry
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/** Exact protective stop and target prices resolved for an entry fill. */
data class ProtectionLevels(
    val stopLoss: BigDecimal,
    val takeProfit: BigDecimal,
)

/** Dollar risk and protective prices resolved against one exact entry fill. */
data class EntryRiskReport(
    val riskUsd: BigDecimal,
    val protection: ProtectionLevels,
)

/**
 * Per-bracket risk (`|entry - stop| x qty x contractSize`) and protection prices for the
 * backtest report. Only the backtest consumes them, so a live session constructs this disabled —
 * otherwise the maps would grow for the life of a 24/7 session. Each entry is consumed once.
 */
internal class BracketRiskRecorder(
    private val enabled: Boolean,
    private val instruments: InstrumentRegistry,
) {
    private val riskByClientOrderId: MutableMap<String, BigDecimal> = ConcurrentHashMap()
    private val protectionByClientOrderId: MutableMap<String, ProtectionLevels> = ConcurrentHashMap()
    private val reportBracketByClientOrderId: MutableMap<String, OrderRequest.Bracket> = mutableMapOf()

    /** Returns and removes the recorded risk for [clientOrderId]. */
    fun riskUsdFor(clientOrderId: String): BigDecimal? = riskByClientOrderId.remove(clientOrderId)

    /** Returns and removes the protection prices recorded for [clientOrderId]. */
    fun protectionFor(clientOrderId: String): ProtectionLevels? = protectionByClientOrderId.remove(clientOrderId)

    /**
     * Records the pre-fill risk of [request] against [entryEstimate] (zero when unquoted, which
     * skips the risk figures) and retains the bracket so its fill can be re-anchored.
     */
    fun recordAtSubmit(
        request: OrderRequest.Bracket,
        entryEstimate: BigDecimal,
    ) {
        if (!enabled) return
        val ids = listOf(request.id, request.entry.id)
        if (entryEstimate.signum() != 0) {
            // Pre-arm stop level for a trailing stop: the worst-case loss the bracket can take.
            val riskStop = stopPriceAtEntry(request, entryEstimate)
            val risk = calculateRisk(request.quantity, entryEstimate, riskStop, request.symbol)
            for (id in ids) riskByClientOrderId[id] = risk
            val protection = ProtectionLevels(riskStop, request.takeProfit)
            for (id in ids) protectionByClientOrderId[id] = protection
        }
        reportBracketByClientOrderId[request.id] = request
        reportBracketByClientOrderId[request.entry.id] = request
    }

    /**
     * Resolve and consume an entry bracket using the broker's actual [fillPrice] and [quantity].
     * The accounting subscriber runs before the ordinary order-state subscriber, so report
     * generation must not depend on the fill handler having re-anchored relative children first.
     */
    fun entryRiskForFill(
        clientOrderId: String,
        quantity: BigDecimal,
        fillPrice: BigDecimal,
        symbol: String,
    ): EntryRiskReport? {
        if (!enabled) return null
        val bracket = reportBracketByClientOrderId[clientOrderId] ?: return null
        val ids = listOf(bracket.id, bracket.entry.id, clientOrderId).distinct()
        for (id in ids) {
            reportBracketByClientOrderId.remove(id)
            riskByClientOrderId.remove(id)
            protectionByClientOrderId.remove(id)
        }
        val resolved = resolveBracketAtFill(bracket, fillPrice)
        val stopPrice = stopPriceAtEntry(resolved, fillPrice)
        return EntryRiskReport(
            riskUsd = calculateRisk(quantity, fillPrice, stopPrice, symbol),
            protection = ProtectionLevels(stopPrice, resolved.takeProfit),
        )
    }

    /** Drops everything recorded for the bracket [clientOrderId] belongs to; it will never fill. */
    fun forgetRejected(clientOrderId: String) {
        val bracket = reportBracketByClientOrderId.remove(clientOrderId) ?: return
        reportBracketByClientOrderId.remove(bracket.id)
        reportBracketByClientOrderId.remove(bracket.entry.id)
        riskByClientOrderId.remove(bracket.id)
        riskByClientOrderId.remove(bracket.entry.id)
        protectionByClientOrderId.remove(bracket.id)
        protectionByClientOrderId.remove(bracket.entry.id)
    }

    private fun calculateRisk(
        quantity: BigDecimal,
        entry: BigDecimal,
        stop: BigDecimal,
        symbol: String,
    ): BigDecimal {
        val contractSize = instruments.lookup(symbol)?.contractSize ?: BigDecimal.ONE
        return entry
            .subtract(stop)
            .abs()
            .multiply(quantity, Money.CONTEXT)
            .multiply(contractSize, Money.CONTEXT)
    }
}
