package com.qkt.risk.rules

import com.qkt.accounting.AccountingEngine
import com.qkt.accounting.MissingFxRateException
import com.qkt.execution.OrderRequest
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule
import java.math.BigDecimal

/**
 * Per-order notional (currency value) cap — qty x reference price x contractSize must
 * not exceed [maxNotional]. The reference is the order's own price field when it has
 * one, else the last seen market price. An order whose notional CANNOT be computed
 * (no price reference at all) is rejected: a cap that silently passes unpriceable
 * orders is not a cap (FIA §1.1, SEC 15c3-5).
 */
class MaxOrderNotional(
    private val maxNotional: BigDecimal,
    private val prices: MarketPriceProvider,
    private val instruments: InstrumentRegistry = NoopInstrumentRegistry,
    private val accounting: AccountingEngine = AccountingEngine(),
) : RiskRule {
    init {
        require(maxNotional.signum() > 0) { "maxNotional must be > 0: $maxNotional" }
    }

    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision {
        val reference =
            explicitPrice(request) ?: prices.lastPrice(request.symbol)
                ?: return Decision.Reject(
                    "cannot compute notional for ${request.symbol}: no price reference",
                )
        val cs = instruments.lookup(request.symbol)?.contractSize ?: BigDecimal.ONE
        val nativeNotional = request.quantity.multiply(reference).multiply(cs)
        val converted =
            try {
                accounting.convertNotional(
                    symbol = request.symbol,
                    nativeNotional = nativeNotional,
                    timestamp = request.timestamp,
                    referencePrice = reference,
                )
            } catch (e: MissingFxRateException) {
                return Decision.Reject("cannot compute account-currency notional for ${request.symbol}: ${e.message}")
            }
        if (converted.conversion == null &&
            converted.native.normalizedCurrency != converted.account.normalizedCurrency
        ) {
            return Decision.Reject(
                "cannot compute account-currency notional for ${request.symbol}: missing FX conversion " +
                    "${converted.native.normalizedCurrency}->${converted.account.normalizedCurrency}",
            )
        }
        val notional = converted.account.amount
        return if (notional > maxNotional) {
            Decision.Reject(
                "order notional ${notional.toPlainString()} exceeds cap ${maxNotional.toPlainString()} " +
                    "(qty=${request.quantity.toPlainString()} ref=${reference.toPlainString()} contractSize=$cs " +
                    "currency=${converted.account.normalizedCurrency})",
            )
        } else {
            Decision.Approve
        }
    }

    private fun explicitPrice(request: OrderRequest): BigDecimal? =
        when (request) {
            is OrderRequest.Limit -> request.limitPrice
            is OrderRequest.Stop -> request.stopPrice
            is OrderRequest.StopLimit -> request.stopPrice
            is OrderRequest.IfTouched -> request.triggerPrice
            is OrderRequest.Bracket -> explicitPrice(request.entry)
            else -> null
        }
}
