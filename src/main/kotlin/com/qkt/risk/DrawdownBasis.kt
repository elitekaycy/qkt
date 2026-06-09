package com.qkt.risk

/** How total drawdown is measured: from the session high-water mark, or from the initial balance. */
enum class DrawdownBasis {
    TRAILING,
    STATIC,
    ;

    companion object {
        fun fromConfig(value: String?): DrawdownBasis =
            when (value?.lowercase()) {
                null, "static" -> STATIC
                "trailing" -> TRAILING
                else -> throw IllegalArgumentException("unknown total_dd_basis '$value' (valid: trailing, static)")
            }
    }
}
