package com.qkt.marketdata.store.macro

/** Stable `MACRO:` identifiers backed by official central-bank policy-rate publications. */
enum class PolicyRateSeries(
    val id: String,
) {
    RBA_CASH_RATE("RBA_CASH_RATE"),
    RBNZ_OCR("RBNZ_OCR"),
    RBA_RBNZ_DIFFERENTIAL("RBA_RBNZ_RATE_DIFF"),
    ;

    companion object {
        /** Resolve a stored series identifier, returning `null` for ordinary FRED identifiers. */
        fun fromId(id: String): PolicyRateSeries? = entries.firstOrNull { it.id == id }
    }
}
