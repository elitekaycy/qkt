package com.qkt.accounting

import com.qkt.common.Money
import com.qkt.instrument.QuoteCurrencyGuard
import com.qkt.marketdata.MarketPriceProvider
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

private fun normalizeCurrencyPair(raw: String): String = raw.uppercase().filter { it in 'A'..'Z' }

@JvmInline
value class AccountCurrency(
    val code: String,
) {
    init {
        require(code.trim().matches(Regex("[A-Za-z]{3,5}"))) {
            "account currency must be a 3-5 letter code: $code"
        }
    }

    val normalized: String get() = code.trim().uppercase()

    override fun toString(): String = normalized
}

data class MoneyAmount(
    val amount: BigDecimal,
    val currency: String,
) {
    init {
        require(currency.trim().matches(Regex("[A-Za-z]{3,5}"))) {
            "money currency must be a 3-5 letter code: $currency"
        }
    }

    val normalizedCurrency: String get() = currency.trim().uppercase()
}

data class FxConversion(
    val from: String,
    val to: String,
    val rate: BigDecimal,
    val timestamp: Long,
    val source: String,
)

data class ConvertedMoney(
    val native: MoneyAmount,
    val account: MoneyAmount,
    val conversion: FxConversion?,
)

enum class CostKind {
    COMMISSION,
    SWAP,
    FUNDING,
    BORROW,
    EXCHANGE_FEE,
    SPREAD_COST,
    TAX,
}

data class VenueCost(
    val kind: CostKind,
    val amount: MoneyAmount,
    val timestamp: Long,
)

enum class FxMissingPolicy {
    WARN,
    FAIL,
    ;

    companion object {
        fun fromConfig(raw: String?): FxMissingPolicy =
            when (raw?.trim()?.lowercase()) {
                null, "", "warn" -> WARN
                "fail", "strict", "production" -> FAIL
                else -> error("unknown fx_conversion.missing_policy '$raw' (valid: warn, fail)")
            }
    }
}

data class AccountingConfig(
    val accountCurrency: AccountCurrency = AccountCurrency("USD"),
    val missingPolicy: FxMissingPolicy = FxMissingPolicy.WARN,
    val source: String = "market",
    val symbols: Map<String, String> = emptyMap(),
) {
    val normalizedSymbols: Map<String, String> =
        symbols
            .filterKeys { it.isNotBlank() }
            .mapKeys { (pair, _) -> normalizeCurrencyPair(pair) }
}

data class AccountingSnapshot(
    val accountCurrency: String,
    val missingPolicy: String,
    val source: String,
    val configuredSymbols: Map<String, String>,
    val conversions: List<FxConversion>,
    val warnings: List<String>,
    val supportedCostKinds: List<String> = CostKind.entries.map { it.name },
)

class MissingFxRateException(
    message: String,
) : IllegalStateException(message)

fun interface FxRateProvider {
    fun rate(
        qktSymbol: String,
        timestamp: Long,
    ): BigDecimal?
}

private class MarketPriceFxRateProvider(
    private val prices: MarketPriceProvider,
) : FxRateProvider {
    override fun rate(
        qktSymbol: String,
        timestamp: Long,
    ): BigDecimal? = prices.lastPrice(qktSymbol)
}

/**
 * Converts native quote-currency PnL/costs into the configured account currency.
 *
 * The hot path stays cheap for the common case: account-quoted symbols return identity without
 * touching the market-price provider. Non-account FX pairs can use the traded symbol itself
 * (e.g. USDJPY converts JPY PnL to USD as 1 / USDJPY) or configured conversion symbols.
 */
class AccountingEngine(
    private val config: AccountingConfig = AccountingConfig(),
    prices: MarketPriceProvider? = null,
    private val fxRates: FxRateProvider =
        prices?.let(::MarketPriceFxRateProvider)
            ?: FxRateProvider { _, _ -> null },
) {
    private val conversions: MutableMap<String, FxConversion> = ConcurrentHashMap()
    private val warnings: MutableMap<String, String> = ConcurrentHashMap()

    val accountCurrency: String get() = config.accountCurrency.normalized

    fun canConvertSymbol(symbol: String): Boolean {
        val quote = pnlCurrencyFor(symbol)
        if (compatible(quote, accountCurrency)) return true
        return contextPair(symbol, from = quote, to = accountCurrency) != null ||
            configuredPair(from = quote, to = accountCurrency) != null
    }

    fun pnlCurrencyFor(symbol: String): String = QuoteCurrencyGuard.quoteOf(symbol)?.uppercase() ?: accountCurrency

    fun convertPnl(
        symbol: String,
        nativeAmount: BigDecimal,
        timestamp: Long,
        referencePrice: BigDecimal?,
    ): ConvertedMoney {
        val nativeCurrency = pnlCurrencyFor(symbol)
        return convert(
            native = MoneyAmount(nativeAmount, nativeCurrency),
            timestamp = timestamp,
            contextSymbol = symbol,
            referencePrice = referencePrice,
        )
    }

    fun convertNotional(
        symbol: String,
        nativeNotional: BigDecimal,
        timestamp: Long,
        referencePrice: BigDecimal?,
    ): ConvertedMoney {
        val nativeCurrency = pnlCurrencyFor(symbol)
        return convert(
            native = MoneyAmount(nativeNotional, nativeCurrency),
            timestamp = timestamp,
            contextSymbol = symbol,
            referencePrice = referencePrice,
        )
    }

    fun convertCost(
        cost: VenueCost,
        contextSymbol: String? = null,
        referencePrice: BigDecimal? = null,
    ): ConvertedMoney =
        convert(
            native = cost.amount,
            timestamp = cost.timestamp,
            contextSymbol = contextSymbol,
            referencePrice = referencePrice,
        )

    fun snapshot(): AccountingSnapshot =
        AccountingSnapshot(
            accountCurrency = accountCurrency,
            missingPolicy = config.missingPolicy.name.lowercase(),
            source = config.source,
            configuredSymbols = config.normalizedSymbols,
            conversions = conversions.values.sortedWith(compareBy({ it.from }, { it.to }, { it.source })),
            warnings = warnings.values.sorted(),
        )

    private fun convert(
        native: MoneyAmount,
        timestamp: Long,
        contextSymbol: String?,
        referencePrice: BigDecimal?,
    ): ConvertedMoney {
        val from = native.normalizedCurrency
        val to = accountCurrency
        val scaledNative = native.amount.setScale(Money.SCALE, Money.ROUNDING)
        if (scaledNative.signum() == 0 || compatible(from, to)) {
            return ConvertedMoney(
                native = MoneyAmount(scaledNative, from),
                account = MoneyAmount(scaledNative, to),
                conversion =
                    if (compatible(from, to)) {
                        FxConversion(
                            from = from,
                            to = to,
                            rate = BigDecimal.ONE,
                            timestamp = timestamp,
                            source = "identity",
                        )
                    } else {
                        null
                    },
            )
        }
        val conversion =
            contextSymbol
                ?.let { symbol ->
                    contextPair(symbol, from, to)?.let { direction ->
                        referencePrice?.takeIf { it.signum() > 0 }?.let { price ->
                            conversionFromDirection(from, to, direction, price, timestamp, "context:$symbol")
                        }
                    }
                }
                ?: configuredPair(from, to)?.let { (direction, symbol) ->
                    fxRates
                        .rate(symbol, timestamp)
                        ?.takeIf { it.signum() > 0 }
                        ?.let { price ->
                            conversionFromDirection(from, to, direction, price, timestamp, "market:$symbol")
                        }
                }
        if (conversion == null) {
            val msg =
                "missing FX conversion $from->$to at $timestamp" +
                    (contextSymbol?.let { " for $it" } ?: "") +
                    "; configure fx_conversion.symbols.${from}$to or ${to}$from"
            if (config.missingPolicy == FxMissingPolicy.FAIL) throw MissingFxRateException(msg)
            warnings.putIfAbsent("$from->$to", msg)
            return ConvertedMoney(
                native = MoneyAmount(scaledNative, from),
                account = MoneyAmount(scaledNative, to),
                conversion = null,
            )
        }
        conversions["${conversion.from}->${conversion.to}:${conversion.source}"] = conversion
        return ConvertedMoney(
            native = MoneyAmount(scaledNative, from),
            account =
                MoneyAmount(
                    scaledNative
                        .multiply(conversion.rate, Money.CONTEXT)
                        .setScale(Money.SCALE, Money.ROUNDING),
                    to,
                ),
            conversion = conversion,
        )
    }

    private fun contextPair(
        symbol: String,
        from: String,
        to: String,
    ): Direction? {
        val pair = currencyPair(symbol) ?: return null
        return when {
            pair.base == from && pair.quote == to -> Direction.DIRECT
            pair.base == to && pair.quote == from -> Direction.INVERSE
            else -> null
        }
    }

    private fun configuredPair(
        from: String,
        to: String,
    ): Pair<Direction, String>? {
        config.normalizedSymbols["$from$to"]?.let { return Direction.DIRECT to it }
        config.normalizedSymbols["$to$from"]?.let { return Direction.INVERSE to it }
        return null
    }

    private fun conversionFromDirection(
        from: String,
        to: String,
        direction: Direction,
        price: BigDecimal,
        timestamp: Long,
        source: String,
    ): FxConversion {
        val rate =
            when (direction) {
                Direction.DIRECT -> price
                Direction.INVERSE -> BigDecimal.ONE.divide(price, Money.CONTEXT)
            }
        return FxConversion(
            from = from,
            to = to,
            rate = rate,
            timestamp = timestamp,
            source = source,
        )
    }

    private enum class Direction {
        DIRECT,
        INVERSE,
    }

    private data class CurrencyPair(
        val base: String,
        val quote: String,
    )

    private companion object {
        private val DOLLAR_FAMILY = setOf("USD", "USDT", "USDC")

        fun compatible(
            from: String,
            to: String,
        ): Boolean =
            from.equals(to, ignoreCase = true) ||
                (from.uppercase() in DOLLAR_FAMILY && to.uppercase() in DOLLAR_FAMILY)

        fun currencyPair(symbol: String): CurrencyPair? {
            val bare = symbol.substringAfter(':').uppercase()
            val quote = QuoteCurrencyGuard.quoteOf(bare) ?: return null
            val base = bare.removeSuffix(quote)
            if (base.isBlank()) return null
            return CurrencyPair(base = base, quote = quote)
        }
    }
}
