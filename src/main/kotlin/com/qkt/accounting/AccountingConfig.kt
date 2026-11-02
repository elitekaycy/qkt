package com.qkt.accounting

private fun normalizeCurrencyPair(raw: String): String = raw.uppercase().filter { it in 'A'..'Z' }

enum class FxMissingPolicy {
    WARN,
    FAIL,
    ;

    companion object {
        fun fromConfig(raw: String?): FxMissingPolicy =
            when (raw?.trim()?.lowercase()) {
                null, "", "fail", "strict", "production" -> FAIL
                "warn" -> WARN
                else -> error("unknown fx_conversion.missing_policy '$raw' (valid: warn, fail)")
            }
    }
}

data class AccountingConfig(
    val accountCurrency: AccountCurrency = AccountCurrency("USD"),
    val missingPolicy: FxMissingPolicy = FxMissingPolicy.FAIL,
    val source: String = "market",
    val symbols: Map<String, String> = emptyMap(),
) {
    val normalizedSymbols: Map<String, String> =
        symbols
            .filterKeys { it.isNotBlank() }
            .mapKeys { (pair, _) -> normalizeCurrencyPair(pair) }
}
