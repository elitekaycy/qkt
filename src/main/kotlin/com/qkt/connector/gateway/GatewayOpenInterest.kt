package com.qkt.connector.gateway

import com.qkt.marketdata.openinterest.OpenInterest
import com.qkt.marketdata.openinterest.OpenInterestSource
import java.math.BigDecimal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The VGP v1 open-interest objects (docs/superpowers/specs/2026-10-01-vgp-v1-wire.md). Figures stay decimal strings.

/** One published figure: [openInterest] contracts outstanding in the contract's order quantity, known from [time]. */
@Serializable
data class WireOpenInterestPoint(
    val time: Long,
    @SerialName("open_interest") val openInterest: String,
)

/** `GET /v1/open-interest`: one page of [openInterest], oldest first; [next] is the next page's `from`, null on the last. */
@Serializable
data class WireOpenInterest(
    @SerialName("open_interest") val openInterest: List<WireOpenInterestPoint>,
    val next: Long? = null,
)

/** The capability a gateway declares when it serves `/v1/open-interest`. */
const val OPEN_INTEREST_CAPABILITY = "open_interest"

/** `GET /v1/open-interest`: every figure of [code] known from [fromMs] to [toMs], oldest first, page by page. */
fun GatewayClient.openInterest(
    code: String,
    fromMs: Long,
    toMs: Long,
): List<WireOpenInterestPoint> {
    val figures = ArrayList<WireOpenInterestPoint>()
    var from: Long? = fromMs
    while (from != null && from <= toMs) {
        val page = read("/v1/open-interest?symbol=$code&from=$from&to=$toMs", WireOpenInterest.serializer())
        figures += page.openInterest
        require(page.next == null || page.next > from) { "gateway open interest of $code does not advance past $from" }
        from = page.next
    }
    return figures
}

/**
 * A gateway's open interest (`/v1/open-interest`), by qkt symbol through the gateway's listing. A gateway that
 * does not declare `open_interest` in `/v1/health` is refused naming [account], before any read.
 */
internal class GatewayOpenInterest(
    private val client: GatewayClient,
    private val symbols: GatewaySymbols,
    private val account: String,
) : OpenInterestSource {
    private val declared by lazy { OPEN_INTEREST_CAPABILITY in client.health().capabilities }

    override fun figures(
        qktSymbol: String,
        fromMs: Long,
        toMs: Long,
    ): List<OpenInterest> {
        check(declared) {
            "$account: its gateway does not declare $OPEN_INTEREST_CAPABILITY, so it serves no open interest of $qktSymbol"
        }
        if (symbols.venue(qktSymbol) == null) symbols.updateListing(client.instruments())
        val code = symbols.venue(qktSymbol) ?: error("$qktSymbol is not in the gateway's listing")
        return client.openInterest(code, fromMs, toMs).map { OpenInterest(it.time, BigDecimal(it.openInterest)) }
    }
}
