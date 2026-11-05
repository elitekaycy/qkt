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

/**
 * Fails when a coin traded as one of [symbols] has no rate to the account currency (#1641). A spot venue can take
 * a buy's fee in the coin bought; the fee is priced from the pair itself when the pair is quoted in the account
 * currency (an ETH fee on `ETHUSDT`, USDT counting as USD), else from a configured `fx_conversion.symbols` pair.
 * With neither, the session is refused at start with the config line to add instead of meeting it on a fill.
 */
fun AccountingEngine.requireCoinFeesBookable(symbols: Collection<String>) {
    val missing =
        symbols.distinct().mapNotNull { symbol ->
            val coin = CurrencyPair.of(symbol)?.base?.takeIf(QuoteCurrencyGuard::isCoin) ?: return@mapNotNull null
            if (canConvertCoin(coin, symbol)) null else symbol to coin
        }
    require(missing.isEmpty()) {
        missing.joinToString("; ") { (symbol, coin) ->
            "$symbol can charge its fees in $coin and no $coin->$accountCurrency rate is known: add " +
                "fx_conversion.symbols.$coin$accountCurrency: <a qkt symbol pricing $coin in $accountCurrency>"
        } + "; refusing to trade"
    }
}
