package com.qkt.instrument

/**
 * The qkt code of an option contract and its venue name. Venues name options
 * `<UNDERLYING>-<EXPIRY>-<STRIKE>-<C|P>` (Deribit's `BTC_USDC-25DEC26-92000-C`, strike decimals as
 * `d`); a `.qkt` symbol cannot hold `-`, so qkt writes each `-` as `_`
 * (`DERIBIT:BTC_USDC_25DEC26_92000_C`). The mapping is exact both ways: the underlying holds no
 * `-`, and the last three `_`-separated fields are always expiry, strike and right. Venue data
 * (catalogs, chain files) keeps venue names; symbols in strategies and the registry use the code.
 */
object OptionSymbols {
    private const val TAIL_FIELDS = 3
    private val RIGHTS = setOf("C", "P")

    /** The qkt code of the venue option name [venueName]. */
    fun qktCode(venueName: String): String {
        val parts = venueName.split('-')
        require(parts.size == TAIL_FIELDS + 1 && parts.none { it.isEmpty() } && parts.last() in RIGHTS) {
            "not an option name of the form <UNDERLYING>-<EXPIRY>-<STRIKE>-<C|P>: $venueName"
        }
        return parts.joinToString("_")
    }

    /** The venue name of [qktCode] when it is an option of the underlying [rootCode] (`BTC_USDC`), else null. */
    fun venueName(
        qktCode: String,
        rootCode: String,
    ): String? {
        if (!qktCode.startsWith("${rootCode}_")) return null
        val tail = qktCode.removePrefix("${rootCode}_").split('_')
        if (tail.size != TAIL_FIELDS || tail.any { it.isEmpty() } || tail.last() !in RIGHTS) return null
        return (listOf(rootCode) + tail).joinToString("-")
    }
}
