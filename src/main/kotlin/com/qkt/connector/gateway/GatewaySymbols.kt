package com.qkt.connector.gateway

/**
 * The exact map between a gateway's venue codes and qkt symbols, built from the codes its
 * `GET /v1/instruments` lists: a qkt symbol is [prefix] plus the code with `-` written `_` (the DSL
 * cannot hold `-`, as with option symbols). A futures code keeps its own `_`, so the way back is a
 * lookup, never a character swap; two codes that would share a qkt symbol are refused.
 *
 * ```kotlin
 * GatewaySymbols("DERIBIT:", listOf("BTC_USDC-25DEC26-92000-C")).qkt("BTC_USDC-25DEC26-92000-C")
 * // "DERIBIT:BTC_USDC_25DEC26_92000_C"
 * ```
 */
class GatewaySymbols(
    prefix: String,
    codes: Collection<String>,
) {
    private val toQkt = codes.associateWith { prefix + it.replace('-', '_') }
    private val toVenue = toQkt.entries.associate { (code, symbol) -> symbol to code }

    init {
        require(toVenue.size == toQkt.size) {
            "gateway codes collide once '-' is written '_': " +
                toQkt.entries
                    .groupBy({ it.value }, { it.key })
                    .values
                    .first { it.size > 1 }
        }
    }

    /** The qkt symbol of venue [code], or null when the gateway did not list it. */
    fun qkt(code: String): String? = toQkt[code]

    /** The venue code of [qktSymbol], or null when it is not one of the gateway's instruments. */
    fun venue(qktSymbol: String): String? = toVenue[qktSymbol]
}
