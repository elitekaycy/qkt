package com.qkt.instrument

/** Where a futures root's contract catalog is built from: a venue's own listing or a public archive. */
interface ContractCatalogSource {
    /** The catalog of [root] (`DERIBIT:BTC_USDC`); what could not be read without failing goes to [warn]. */
    fun build(
        root: String,
        warn: (String) -> Unit = {},
    ): ContractCatalog
}
