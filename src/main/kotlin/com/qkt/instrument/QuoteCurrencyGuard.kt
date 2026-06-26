package com.qkt.instrument

/**
 * Deploy-time guard against silently misbooked PnL: qkt has no quote-currency
 * conversion, so an instrument quoted in anything but the account currency books
 * its PnL at the wrong magnitude (a USDJPY trade's JPY PnL recorded as USD is
 * ~150x overstated). Until conversion exists, such symbols are rejected at bind —
 * fail loud at deploy, never compute silently wrong.
 *
 * The quote currency is inferred from the symbol's trailing currency code.
 * Dollar-family quotes (USD, USDT, USDC) are treated as equivalent — the stablecoin
 * basis differs from USD by far less than any strategy's edge. Symbols with no
 * recognizable currency tail (indices, stocks) pass — their quote cannot be inferred,
 * and rejecting them would block instruments this guard knows nothing about.
 */
object QuoteCurrencyGuard {
    private val DOLLAR_FAMILY = setOf("USD", "USDT", "USDC")

    /** Currency tails that identify a non-dollar quote. Longest first so USDT wins over USD-like overlap. */
    private val KNOWN_QUOTES =
        listOf("USDT", "USDC", "USD", "JPY", "CHF", "CAD", "GBP", "EUR", "AUD", "NZD")

    /**
     * Throws when any of [qktSymbols] is quoted in a known currency outside the
     * account's dollar family. e.g. `assert(listOf("EXNESS:USDJPY"))` fails with a
     * message naming the symbol and quote; `EXNESS:XAUUSD` and `BYBIT_SPOT:BTCUSDT` pass.
     */
    fun assertAccountQuoted(
        qktSymbols: Collection<String>,
        accountCurrency: String = "USD",
        canConvert: (qktSymbol: String, quoteCurrency: String) -> Boolean = { _, _ -> false },
    ) {
        val offending =
            qktSymbols.mapNotNull { qktSymbol ->
                val quote = quoteOf(qktSymbol) ?: return@mapNotNull null
                val compatible =
                    quote.equals(accountCurrency, ignoreCase = true) ||
                        (accountCurrency.uppercase() in DOLLAR_FAMILY && quote in DOLLAR_FAMILY)
                if (compatible || canConvert(qktSymbol, quote)) null else qktSymbol to quote
            }
        require(offending.isEmpty()) {
            val detail = offending.joinToString(", ") { (sym, quote) -> "$sym (quoted in $quote)" }
            "PnL conversion for non-$accountCurrency quotes is not configured; refusing to trade: $detail. " +
                "These symbols would book quote-currency PnL as $accountCurrency at the wrong magnitude."
        }
    }

    /** The inferred quote currency of [qktSymbol], or null when no known currency tail matches. */
    fun quoteOf(qktSymbol: String): String? {
        val bare = qktSymbol.substringAfter(':').uppercase()
        return KNOWN_QUOTES.firstOrNull { bare.endsWith(it) }
    }

    /** Base assets that trade as unit contracts whatever they're quoted in. */
    private val CRYPTO_BASES =
        setOf("BTC", "ETH", "SOL", "XRP", "BNB", "LTC", "DOGE", "ADA", "DOT", "AVAX")

    /**
     * True when [qktSymbol] is a fiat-quoted FX/metal shape, where a missing
     * `contractSize` is a 100-100,000x PnL error. Crypto — stablecoin-quoted
     * (BTCUSDT) or fiat-quoted (BTCUSD) — and unrecognized shapes (stocks, indices)
     * are unit contracts by convention; a registry that cannot resolve them is not
     * lying about their size.
     */
    fun requiresContractSizeMeta(qktSymbol: String): Boolean {
        val quote = quoteOf(qktSymbol) ?: return false
        if (quote in setOf("USDT", "USDC")) return false
        val bare = qktSymbol.substringAfter(':').uppercase()
        if (CRYPTO_BASES.any { bare.startsWith(it) }) return false
        return true
    }
}
