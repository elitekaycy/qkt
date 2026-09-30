package com.qkt.accounting

import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceProvider

/**
 * The accounting engine a session books with: FX from [prices], and each symbol's explicit
 * currency from [instruments] when its metadata declares one.
 */
fun accountingEngine(
    config: AccountingConfig,
    prices: MarketPriceProvider,
    instruments: InstrumentRegistry?,
): AccountingEngine = AccountingEngine(config, prices, currencyOf = { instruments?.lookup(it)?.currency })
