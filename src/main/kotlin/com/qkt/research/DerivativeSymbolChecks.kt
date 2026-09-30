package com.qkt.research

import com.qkt.accounting.AccountingEngine
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.QuoteCurrencyGuard

/**
 * Fails a replay up front for derivative symbols qkt cannot book correctly: a continuous stream
 * (`VENUE:ROOT@selector`) whose root is not declared, or an exchange fee in a currency the account
 * cannot book 1:1. CFD and spot symbols are not examined.
 */
internal fun requireDerivativeSymbolsResolvable(
    symbols: List<String>,
    accounting: AccountingEngine,
    instruments: InstrumentRegistry,
) {
    for (symbol in symbols.distinct()) {
        val meta = instruments.lookup(symbol)
        if (meta == null) {
            require('@' !in symbol) {
                "$symbol is a continuous futures stream but its root is not declared under 'futures:' in instruments.yaml"
            }
            continue
        }
        val terms = meta.derivative ?: continue
        val charges = terms.exchangeFeePerContract.signum() != 0 || terms.takerFeeRate.signum() != 0
        val currency = accounting.pnlCurrencyFor(symbol)
        require(!charges || QuoteCurrencyGuard.sameCurrency(currency, accounting.accountCurrency)) {
            "$symbol charges fees in $currency but the account books in ${accounting.accountCurrency}; " +
                "cross-currency futures fees are not supported"
        }
    }
}
