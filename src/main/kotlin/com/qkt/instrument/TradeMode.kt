package com.qkt.instrument

/**
 * What a venue lets an account do with a symbol (each connector translates its venue's own field, e.g. MT5's
 * `trade_mode`). A broker keeps symbols it no
 * longer trades in its list: Exness shows `ATVIm` (Activision, acquired) as [DISABLED] with a 2023 quote, and `FBm`
 * (Facebook's old ticker; Meta trades as `METAm`) as [CLOSE_ONLY]. Opening orders on those are refused before the
 * venue; closing an existing position is still allowed where the venue allows it.
 */
enum class TradeMode {
    DISABLED,
    LONG_ONLY,
    SHORT_ONLY,
    CLOSE_ONLY,
    FULL,
    ;

    /** True when this mode lets an order open or add to a position on [buy]'s side. */
    fun allowsOpening(buy: Boolean): Boolean =
        when (this) {
            FULL -> true
            LONG_ONLY -> buy
            SHORT_ONLY -> !buy
            CLOSE_ONLY, DISABLED -> false
        }

    /** True when this mode lets an order reduce or close an existing position. */
    val allowsClosing: Boolean get() = this != DISABLED

    companion object {
        /** The mode spelled [name] in an instruments file (`full`, `close_only`, ...), case-insensitive. */
        fun parse(name: String): TradeMode =
            runCatching { valueOf(name.trim().uppercase().replace('-', '_')) }
                .getOrElse {
                    error(
                        "unknown tradeMode '$name' (expected ${entries.joinToString { it.name.lowercase() }})",
                    )
                }
    }
}
