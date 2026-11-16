package com.qkt.instrument.universe

import com.qkt.instrument.EventTerms
import com.qkt.instrument.FutureTerms
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.OptionTerms
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The vocabulary a symbol-set filter reads, one name per fact every venue's instrument metadata already carries
 * ([InstrumentMeta]). A field that an instrument does not have (`expiry_at` on a spot pair) reads as null, and a
 * comparison against null is false.
 *
 * `kind` is one of `spot`, `equity`, `future`, `perpetual`, `option` and `event`; `trade_mode` is the lowercase
 * [com.qkt.instrument.TradeMode] name (`full`, `close_only`); `family` and `underlying` come from a derivative's
 * terms; `expiry_at` is epoch milliseconds and `dte` the days from the catalog's as-of instant to that expiry.
 */
enum class CatalogField(
    val token: String,
) {
    SYMBOL("symbol"),
    KIND("kind"),
    CATEGORY("category"),
    CURRENCY("currency"),
    CONTRACT_SIZE("contract_size"),
    TICK_SIZE("tick_size"),
    TRADE_MODE("trade_mode"),
    FAMILY("family"),
    UNDERLYING("underlying"),
    EXPIRY_AT("expiry_at"),
    DTE("dte"),
    ;

    /** This field of [meta] as of [nowMs], or null when the instrument has no such fact. */
    fun read(
        meta: InstrumentMeta,
        nowMs: Long,
    ): CatalogValue? =
        when (this) {
            SYMBOL -> CatalogValue.Str(meta.qktSymbol.substringAfter(':'))
            KIND -> CatalogValue.Str(kindOf(meta))
            CATEGORY -> meta.category?.let(CatalogValue::Str)
            CURRENCY -> meta.currency?.let(CatalogValue::Str)
            CONTRACT_SIZE -> CatalogValue.Num(meta.contractSize)
            TICK_SIZE -> CatalogValue.Num(meta.priceStep)
            TRADE_MODE -> CatalogValue.Str(meta.tradeMode.name.lowercase())
            FAMILY ->
                meta.derivative
                    ?.root
                    ?.substringAfter(':')
                    ?.let(CatalogValue::Str)
            UNDERLYING -> (meta.derivative as? OptionTerms)?.underlyingIndex?.let(CatalogValue::Str)
            EXPIRY_AT -> expiryMs(meta)?.let { CatalogValue.Num(BigDecimal(it)) }
            DTE ->
                expiryMs(
                    meta,
                )?.let { CatalogValue.Num(BigDecimal(it - nowMs).divide(DAY_MS, 6, RoundingMode.HALF_EVEN)) }
        }

    companion object {
        private val DAY_MS = BigDecimal(86_400_000)

        /** The field spelled [token] (case-insensitive), or null when the vocabulary has no such name. */
        fun parse(token: String): CatalogField? = entries.firstOrNull { it.token.equals(token, ignoreCase = true) }

        /** Every field name, for error messages. */
        val spellings: String get() = entries.joinToString { it.token }

        /** The `kind` word of [meta]: what the instrument is, from its terms and costs. */
        fun kindOf(meta: InstrumentMeta): String =
            when (val terms = meta.derivative) {
                is EventTerms -> "event"
                is OptionTerms -> "option"
                is FutureTerms -> if (terms.perpetual) "perpetual" else "future"
                null -> if (meta.equityCosts != null) "equity" else "spot"
            }

        private fun expiryMs(meta: InstrumentMeta): Long? =
            when (val terms = meta.derivative) {
                is EventTerms -> terms.expiryMs
                is OptionTerms -> terms.expiryMs
                is FutureTerms -> terms.expiryMs
                null -> null
            }
    }
}
