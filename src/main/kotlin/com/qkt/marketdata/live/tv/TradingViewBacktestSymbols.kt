package com.qkt.marketdata.live.tv

/**
 * Refuses `BACKTEST:` streams on the TradingView live feed before a run or deploy starts.
 *
 * `BACKTEST:` names the local backtest data under `~/.qkt/data`; it is not an exchange anywhere
 * live. TradingView has no `BACKTEST` exchange either, so a paper run on such a stream would sit in
 * warmup until TradingView's request times out. The refusal names the symbol to write instead: the
 * TradingView exchange for FX and metals pairs (OANDA) and the common crypto pairs (COINBASE for USD,
 * BINANCE for USDT), otherwise how to find it.
 */
object TradingViewBacktestSymbols {
    /** Throws [UnservableStreamException] naming the fix when [symbols] holds a `BACKTEST:` stream. */
    fun requireLive(symbols: List<String>) {
        refusal(symbols)?.let { throw UnservableStreamException(it) }
    }

    private const val BACKTEST_PREFIX = "BACKTEST:"
    private val CURRENCY_PAIR = Regex("^[A-Z]{6}$")
    private val CRYPTO_USD = Regex("^(?:BTC|ETH|SOL|XRP|LTC|ADA|DOGE)USD$")

    /**
     * The refusal for the `BACKTEST:` streams in [symbols], or null when there are none. One line,
     * as it is also returned inside a deploy's JSON error.
     */
    fun refusal(symbols: List<String>): String? {
        val backtest = symbols.filter { it.startsWith(BACKTEST_PREFIX) }.distinct()
        if (backtest.isEmpty()) return null
        val fixes =
            backtest.joinToString(", ") { symbol ->
                val bare = symbol.removePrefix(BACKTEST_PREFIX)
                val replacement =
                    liveSymbolFor(bare) ?: "TradingView's own EXCHANGE:$bare (as tradingview.com shows it)"
                "$symbol -> $replacement"
            }
        val example = backtest.first().removePrefix(BACKTEST_PREFIX)
        return "BACKTEST: streams are local backtest data with no live prices, and the paper feed (source: tv) " +
            "is TradingView, which has no BACKTEST exchange. In SYMBOLS, change $fixes. " +
            "To use your broker's prices instead, prefix the symbol with its brokers: name from " +
            "qkt.config.yaml, upper-cased (for example EXNESS:$example)."
    }

    /** The TradingView symbol for [bare] when qkt knows its exchange, e.g. `OANDA:EURUSD`. */
    fun liveSymbolFor(bare: String): String? =
        when {
            bare.endsWith("USDT") -> "BINANCE:$bare"
            CRYPTO_USD.matches(bare) -> "COINBASE:$bare"
            CURRENCY_PAIR.matches(bare) -> "OANDA:$bare"
            else -> null
        }
}

/**
 * A strategy stream the configured live feed cannot serve, refused before the run or deploy starts.
 * The message is the whole explanation for the user, so the CLI prints it without a stack trace.
 */
class UnservableStreamException(
    message: String,
) : IllegalArgumentException(message)
