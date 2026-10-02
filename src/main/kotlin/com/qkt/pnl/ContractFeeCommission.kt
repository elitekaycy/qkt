package com.qkt.pnl

import com.qkt.instrument.DerivativeTerms
import com.qkt.instrument.InstrumentRegistry
import java.math.BigDecimal

/**
 * Exchange fees for derivatives: `|qty| × exchangeFeePerContract` plus
 * `|qty| × price × contractSize × takerFeeRate`, in the instrument's currency (the replay refuses
 * a fee currency the account cannot book 1:1). Symbols without derivative terms use [fallback], so
 * CFD commissions are exactly what [fallback] charged before.
 */
class ContractFeeCommission(
    private val instruments: InstrumentRegistry,
    private val fallback: CommissionModel,
) : CommissionModel {
    override fun cost(
        symbol: String,
        quantity: BigDecimal,
    ): BigDecimal {
        val terms = instruments.lookup(symbol)?.derivative ?: return fallback.cost(symbol, quantity)
        require(terms.takerFeeRate.signum() == 0) { "notional fee for $symbol needs the fill price" }
        return perContract(terms, quantity)
    }

    override fun cost(
        symbol: String,
        quantity: BigDecimal,
        price: BigDecimal,
    ): BigDecimal {
        val meta = instruments.lookup(symbol)
        val terms = meta?.derivative ?: return fallback.cost(symbol, quantity, price)
        val notional =
            quantity
                .abs()
                .multiply(price)
                .multiply(meta.contractSize)
                .multiply(terms.takerFeeRate)
        return perContract(terms, quantity).add(notional)
    }

    private fun perContract(
        terms: DerivativeTerms,
        quantity: BigDecimal,
    ): BigDecimal = quantity.abs().multiply(terms.exchangeFeePerContract)
}
