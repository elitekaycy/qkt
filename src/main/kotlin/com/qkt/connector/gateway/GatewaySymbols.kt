package com.qkt.connector.gateway

import com.qkt.broker.VenueInstrument
import com.qkt.instrument.OptionSymbols
import java.math.BigDecimal

/**
 * The map between a gateway's venue codes and qkt symbols. A qkt symbol is [prefix] plus the code with
 * `-` written `_` (the DSL cannot hold `-`, as with option symbols), so the way from a code is a pure
 * rule and holds for any code, listed or long expired. A futures code keeps its own `_`, so the way back
 * is a lookup in the codes the gateway lists, refreshed by [update]; two codes that would share a qkt
 * symbol are refused.
 *
 * ```kotlin
 * GatewaySymbols("DERIBIT:").apply { update(listOf("BTC_USDC-25DEC26-92000-C")) }
 *     .venue("DERIBIT:BTC_USDC_25DEC26_92000_C") // "BTC_USDC-25DEC26-92000-C"
 * ```
 */
class GatewaySymbols(
    private val prefix: String,
) {
    @Volatile private var toVenue: Map<String, String> = emptyMap()

    @Volatile private var ticks: Map<String, BigDecimal> = emptyMap()

    @Volatile private var listed: Map<String, WireInstrument> = emptyMap()

    /** Takes [codes] as the gateway's listing now. */
    fun update(codes: Collection<String>) {
        val listed = codes.groupBy(::qkt)
        val collision = listed.values.firstOrNull { it.size > 1 }
        require(collision == null) { "gateway codes collide once '-' is written '_': $collision" }
        toVenue = listed.mapValues { (_, same) -> same.single() }
    }

    /** Takes [listing] as the gateway's listing now: its codes, and the price tick of each. */
    fun updateListing(listing: List<WireInstrument>) {
        update(listing.map { it.code })
        listed = listing.associateBy { qkt(it.code) }
        ticks =
            listing
                .mapNotNull { i -> positive(i.tickSize)?.let { qkt(i.code) to it } }
                .toMap()
    }

    /** What the latest listing states about [qktSymbol]: its code, kind, contract size, tick and currency. */
    fun instrument(qktSymbol: String): VenueInstrument? =
        listed[qktSymbol]?.let {
            VenueInstrument(
                it.code,
                it.kind,
                positive(it.contractSize),
                positive(it.tickSize),
                it.currency,
                baseAsset(qktSymbol),
            )
        }

    /**
     * The coin a spot pair [qktSymbol] buys (`BTC` of `BTCUSD`, `BTCUSDT` or `BTC_USDC`), or null for anything else:
     * a fee charged in it is paid with the coin itself ([GatewayInKindFees]).
     */
    fun baseAsset(qktSymbol: String): String? =
        listed[qktSymbol]
            ?.takeIf { it.kind == "spot" && it.code.length > it.currency.length && it.code.endsWith(it.currency) }
            ?.let { it.code.removeSuffix(it.currency).trimEnd('_', '-', '/') }
            ?.takeIf { it.isNotEmpty() }

    /** The price tick of [qktSymbol] in the latest listing, or null when the listing gave none. */
    fun tick(qktSymbol: String): BigDecimal? = ticks[qktSymbol]

    /** The qkt symbol of venue [code]. */
    fun qkt(code: String): String = prefix + code.replace('-', '_')

    /** The venue code of [qktSymbol], or null when the gateway's listing does not hold it. */
    fun venue(qktSymbol: String): String? = toVenue[qktSymbol]

    /** Whether [qktSymbol] belongs to this account, listed or not. */
    fun owns(qktSymbol: String): Boolean = qktSymbol.startsWith(prefix)

    /**
     * The venue code of [qktSymbol] even once the listing dropped it (an expired contract): an option's
     * code follows from its symbol by rule ([OptionSymbols]); any other unlisted symbol has none.
     */
    fun code(qktSymbol: String): String? {
        venue(qktSymbol)?.let { return it }
        if (!owns(qktSymbol)) return null
        val name = qktSymbol.removePrefix(prefix)
        val fields = name.split('_')
        if (fields.size < OPTION_FIELDS) return null
        return OptionSymbols.venueName(name, fields.dropLast(OPTION_FIELDS - 1).joinToString("_"))
    }

    private fun positive(text: String): BigDecimal? = text.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }

    private companion object {
        /** Underlying, expiry, strike and right. */
        const val OPTION_FIELDS = 4
    }
}
