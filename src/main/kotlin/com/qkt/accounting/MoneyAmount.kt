package com.qkt.accounting

import java.math.BigDecimal

// A 3-5 letter currency code — equivalent to the regex [A-Za-z]{3,5}, but as a char check so the
// per-tick MoneyAmount construction compiles no pattern and allocates no Matcher (that regex
// dominated the backtest hot path). e.g. "USD" -> true, "us" -> false, "DOLLAR" -> false.
private fun isCurrencyCode(code: String): Boolean {
    val trimmed = code.trim()
    return trimmed.length in 3..5 && trimmed.all { it in 'A'..'Z' || it in 'a'..'z' }
}

/** The currency an account books in, a 3-5 letter code. */
@JvmInline
value class AccountCurrency(
    val code: String,
) {
    init {
        require(isCurrencyCode(code)) {
            "account currency must be a 3-5 letter code: $code"
        }
    }

    val normalized: String get() = code.trim().uppercase()

    override fun toString(): String = normalized
}

/** An amount of money in one currency. */
data class MoneyAmount(
    val amount: BigDecimal,
    val currency: String,
) {
    init {
        require(isCurrencyCode(currency)) {
            "money currency must be a 3-5 letter code: $currency"
        }
    }

    val normalizedCurrency: String get() = currency.trim().uppercase()
}

/** One currency conversion: [rate] from [from] to [to], observed at [timestamp] from [source]. */
data class FxConversion(
    val from: String,
    val to: String,
    val rate: BigDecimal,
    val timestamp: Long,
    val source: String,
)

/** An amount in its native currency and in account currency, with the conversion used (null when none). */
data class ConvertedMoney(
    val native: MoneyAmount,
    val account: MoneyAmount,
    val conversion: FxConversion?,
)
