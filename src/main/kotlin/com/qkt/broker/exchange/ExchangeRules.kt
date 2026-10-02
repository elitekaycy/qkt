package com.qkt.broker.exchange

import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.instrument.FutureTerms
import com.qkt.instrument.InstrumentMeta
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * The order-acceptance rules of [ExchangeSimulator] (named [venue] in reasons): no orders at or after
 * expiry, only exits within the contract's expiry guard, only the order shapes it matches, and no
 * quantity above `volumeMax`. Exposure is judged against the exchange's own positions in [settlement].
 */
internal class ExchangeRules(
    private val venue: String,
    private val clock: Clock,
    private val settlement: ExpirySettlement,
) {
    /**
     * Why the exchange refuses [request] on the dated or perpetual contract described by [meta] and
     * [terms], or null. [pending] is the signed quantity of the strategy's other working orders on it.
     */
    fun refusal(
        request: OrderRequest,
        meta: InstrumentMeta,
        terms: FutureTerms,
        pending: BigDecimal,
    ): String? {
        val expiryMs = terms.expiryMs
        if (expiryMs != null && clock.now() >= expiryMs) {
            return "${request.symbol} expired at ${Instant.ofEpochMilli(expiryMs)}"
        }
        guardRefusal(request, terms, pending)?.let { return it }
        if (request !is OrderRequest.Market &&
            request !is OrderRequest.Limit &&
            request !is OrderRequest.Stop &&
            request !is OrderRequest.StopLimit
        ) {
            return "$venue does not accept ${request::class.simpleName} orders"
        }
        val max = meta.volumeMax ?: return null
        val floored = request.quantity.divide(meta.volumeStep, 0, RoundingMode.DOWN).multiply(meta.volumeStep)
        if (floored > max) {
            return "quantity ${request.quantity.toPlainString()} is above venue volumeMax ${max.toPlainString()} " +
                "for ${request.symbol}"
        }
        return null
    }

    /**
     * Why [request] may not stand inside [terms]' expiry guard window, or null: it would grow the
     * strategy's position or turn it to the other side once it and the strategy's other working
     * orders ([pending], signed) have filled. A perpetual has no window.
     */
    fun guardRefusal(
        request: OrderRequest,
        terms: FutureTerms,
        pending: BigDecimal,
    ): String? {
        val expiryMs = terms.expiryMs ?: return null
        val guardMs = terms.expiryGuardHours * HOUR_MS
        if (guardMs == 0L || clock.now() < expiryMs - guardMs) return null
        val held = settlement.netOf(request.symbol, request.strategyId)
        val signed = if (request.side == Side.BUY) request.quantity else request.quantity.negate()
        val after = held.add(pending).add(signed)
        val opens = after.abs() > held.abs() || (held.signum() != 0 && after.signum() == -held.signum())
        if (!opens) return null
        return "${request.symbol} takes only exits within ${terms.expiryGuardHours}h of its expiry at " +
            "${Instant.ofEpochMilli(expiryMs)}"
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
    }
}
