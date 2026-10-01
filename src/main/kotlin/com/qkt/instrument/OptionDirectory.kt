package com.qkt.instrument

import java.math.BigDecimal

/** What the option venue and its market data need about catalogued option contracts beyond their metadata. */
interface OptionDirectory {
    /** The declared root of the catalogued option [qktSymbol], or null when it is not one. */
    fun optionRoot(qktSymbol: String): OptionRoot?

    /** [qktSymbol]'s contract name in venue data (catalog, chain files), or null when it is not an option. */
    fun venueName(qktSymbol: String): String?

    /** The settlement index's delivery price on [qktSymbol]'s expiry date, when its catalog records one. */
    fun deliveryPrice(qktSymbol: String): BigDecimal?
}
