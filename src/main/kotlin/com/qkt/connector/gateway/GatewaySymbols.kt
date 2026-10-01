package com.qkt.connector.gateway

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

    /** Takes [codes] as the gateway's listing now. */
    fun update(codes: Collection<String>) {
        val listed = codes.groupBy(::qkt)
        val collision = listed.values.firstOrNull { it.size > 1 }
        require(collision == null) { "gateway codes collide once '-' is written '_': $collision" }
        toVenue = listed.mapValues { (_, same) -> same.single() }
    }

    /** The qkt symbol of venue [code]. */
    fun qkt(code: String): String = prefix + code.replace('-', '_')

    /** The venue code of [qktSymbol], or null when the gateway's listing does not hold it. */
    fun venue(qktSymbol: String): String? = toVenue[qktSymbol]
}
