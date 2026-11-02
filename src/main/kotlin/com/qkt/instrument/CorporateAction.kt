package com.qkt.instrument

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Something a company does to its shares that changes what a holding is worth or holds, effective at the start of
 * [exDate] (the exchange's local date): from then the share trades without the dividend, or at the split price.
 */
sealed interface CorporateAction {
    val exDate: LocalDate

    /**
     * A cash dividend of [amount] per share in [currency]. A holder of the share before [exDate] is credited it (a
     * CFD broker books it on the ex-date, a cash share is paid on [payDate]); a short pays it.
     */
    data class Dividend(
        override val exDate: LocalDate,
        val amount: BigDecimal,
        val currency: String,
        val payDate: LocalDate? = null,
    ) : CorporateAction {
        init {
            require(amount.signum() > 0) { "dividend on $exDate: amount must be positive, got $amount" }
            require(
                payDate == null || !payDate.isBefore(exDate),
            ) { "dividend on $exDate: pay date $payDate is before it" }
        }
    }

    /**
     * A split giving [ratio] new shares for each old one: 10 for NVDA's 10-for-1, 0.125 for a 1-for-8 reverse split.
     * A position's quantity is multiplied by [ratio] and its price divided by it; nothing is gained or lost.
     */
    data class Split(
        override val exDate: LocalDate,
        val ratio: BigDecimal,
    ) : CorporateAction {
        init {
            require(ratio.signum() > 0) { "split on $exDate: ratio must be positive, got $ratio" }
        }
    }
}

/** The corporate actions of the instruments a run trades, oldest first by ex-date. */
fun interface CorporateActionSource {
    /** [qktSymbol]'s actions, oldest first; empty when it has none. */
    fun actions(qktSymbol: String): List<CorporateAction>
}
