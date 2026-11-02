package com.qkt.instrument

/**
 * Where an option root's catalog is built from: a trading account's own venue listing (a gateway
 * account's `/v1/instruments`) or a venue's public listing (Deribit's API).
 */
interface OptionCatalogSource {
    /** The catalog of the declared [root]; contracts that could not be catalogued go to [warn]. */
    fun build(
        root: OptionRoot,
        warn: (String) -> Unit = {},
    ): OptionCatalog

    /** How many options of [root] (`PAPER_DERIBIT:BTC_USDC`) the source lists, or null when it cannot tell cheaply. */
    fun listed(root: String): Int? = null
}
