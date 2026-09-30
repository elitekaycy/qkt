package com.qkt.accounting

import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.QuoteCurrencyGuard
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

/**
 * Fails when any of [symbols] books P&L in a currency this engine can neither treat as the account
 * currency nor convert — using explicit instrument currencies before suffix inference, so a
 * `currency: EUR` futures root on a USD account is refused at start instead of mid-run.
 */
fun AccountingEngine.requireBookable(symbols: Collection<String>) =
    QuoteCurrencyGuard.assertAccountQuoted(
        symbols,
        accountCurrency = accountCurrency,
        canConvert = { symbol, _ -> canConvertSymbol(symbol) },
        currencyOf = ::quoteCurrencyOf,
    )
