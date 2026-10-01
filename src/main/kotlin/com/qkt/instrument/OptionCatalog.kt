package com.qkt.instrument

import java.math.BigDecimal
import kotlinx.serialization.Serializable

/** One catalogued option as stored: strike and prices as decimal strings, right as `call` or `put`. */
@Serializable
data class OptionListing(
    val symbol: String,
    val strike: String,
    val right: String,
    val expiryMs: Long,
) {
    init {
        require(right == "call" || right == "put") { "OptionListing.right must be call or put: $right" }
        require((strike.toBigDecimalOrNull()?.signum() ?: 0) > 0) { "OptionListing.strike must be > 0: $strike" }
    }

    /** This listing as a contract. */
    fun toContract(): OptionContract =
        OptionContract(symbol, BigDecimal(strike), if (right == "call") OptionRight.CALL else OptionRight.PUT, expiryMs)
}

/**
 * Every option contract of [root] the venue has listed, and the settlement index's delivery price
 * per UTC date (`yyyy-MM-dd` to a decimal string), which settles every contract expiring that day.
 */
@Serializable
data class OptionCatalog(
    val root: String,
    val contracts: List<OptionListing>,
    val deliveryPrices: Map<String, String> = emptyMap(),
) {
    init {
        val duplicate = contracts.groupBy { it.symbol }.entries.firstOrNull { it.value.size > 1 }
        require(duplicate == null) { "OptionCatalog $root: duplicate contract '${duplicate?.key}'" }
        val bad = deliveryPrices.entries.firstOrNull { (it.value.toBigDecimalOrNull()?.signum() ?: 0) <= 0 }
        require(bad == null) { "OptionCatalog $root: delivery price on ${bad?.key} must be > 0: ${bad?.value}" }
    }
}

/** Reads and writes option catalogs under `<dataRoot>/contracts/<VENUE>/<ROOT>.options.json`. */
class OptionCatalogStore(
    dataRoot: java.nio.file.Path,
) {
    private val files =
        RootFileStore(dataRoot, ".options.json", OptionCatalog.serializer(), { it.root }, "option catalog")

    /** Where [root]'s option catalog lives. */
    fun path(root: String): java.nio.file.Path = files.path(root)

    /** [root]'s option catalog, or null when none has been written. */
    fun read(root: String): OptionCatalog? = files.read(root)

    /** Writes [catalog], replacing any previous file. */
    fun write(catalog: OptionCatalog) = files.write(catalog)
}
