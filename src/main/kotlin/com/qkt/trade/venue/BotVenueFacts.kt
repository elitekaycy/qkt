package com.qkt.trade.venue

import com.qkt.connectivity.VenueFacts
import com.qkt.instrument.QuoteCurrencyGuard
import com.qkt.trade.BotQuoteContext

/**
 * The venue's facts as the one-shot compiler reads them. A venue that does not name the quote currency
 * falls back to the symbol's own (`XAUUSD` is quoted in USD), and one that does not name the account
 * currency to the config's. A quote that is not positive is refused: nothing is priced against it.
 */
fun VenueFacts.toQuoteContext(
    qktSymbol: String,
    configAccountCurrency: String,
    calendar: com.qkt.common.TradingCalendar =
        com.qkt.common.TradingCalendar
            .crypto(),
): BotQuoteContext {
    require(bid.signum() > 0 && ask.signum() > 0) { "venue quote for $qktSymbol is not positive: bid=$bid ask=$ask" }
    return BotQuoteContext(
        bid = bid,
        ask = ask,
        equity = equity,
        balance = balance,
        contractSize = contractSize,
        accountCurrency = accountCurrency ?: configAccountCurrency,
        quoteCurrency = quoteCurrency ?: QuoteCurrencyGuard.quoteOf(qktSymbol),
        volumeMin = volumeMin,
        volumeStep = volumeStep,
        volumeMax = volumeMax,
        digits = digits,
        reference = reference,
        calendar = calendar,
    )
}

/** A venue id as JSON: a number when it is one (an MT5 ticket), else the string (a gateway order id). */
fun idJson(id: String): Any = id.toLongOrNull() ?: id
