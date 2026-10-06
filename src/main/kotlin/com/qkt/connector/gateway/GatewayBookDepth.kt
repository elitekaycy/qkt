package com.qkt.connector.gateway

import com.qkt.marketdata.depth.BookDepth
import com.qkt.marketdata.depth.BookDepthSource
import com.qkt.marketdata.depth.BookLevel
import java.math.BigDecimal
import kotlinx.serialization.Serializable

// The VGP v1 depth objects (docs/superpowers/specs/2026-10-01-vgp-v1-wire.md). Prices and amounts stay decimal strings.

/** One book snapshot known from [time]: [bids] from the highest price down, [asks] from the lowest up, each `[price, amount]`. */
@Serializable
data class WireDepthSnapshot(
    val time: Long,
    val bids: List<List<String>>,
    val asks: List<List<String>>,
)

/** `GET /v1/depth`: one page of [depth], oldest first; [next] is the next page's `from`, null on the last. */
@Serializable
data class WireDepth(
    val depth: List<WireDepthSnapshot>,
    val next: Long? = null,
)

/** The capability a gateway declares when it serves `/v1/depth`. */
const val DEPTH_CAPABILITY = "depth"

/** `GET /v1/depth`: every snapshot of [code] known from [fromMs] to [toMs], oldest first, page by page. */
fun GatewayClient.depth(
    code: String,
    fromMs: Long,
    toMs: Long,
): List<WireDepthSnapshot> {
    val snapshots = ArrayList<WireDepthSnapshot>()
    var from: Long? = fromMs
    while (from != null && from <= toMs) {
        val page = read("/v1/depth?symbol=$code&from=$from&to=$toMs", WireDepth.serializer())
        snapshots += page.depth
        require(page.next == null || page.next > from) { "gateway depth of $code does not advance past $from" }
        from = page.next
    }
    return snapshots
}

/**
 * A gateway's order-book depth (`/v1/depth`), by qkt symbol through the gateway's listing. A gateway that does
 * not declare `depth` in `/v1/health` is refused naming [account], before any read.
 */
internal class GatewayBookDepth(
    private val client: GatewayClient,
    private val symbols: GatewaySymbols,
    private val account: String,
) : BookDepthSource {
    private val declared by lazy { DEPTH_CAPABILITY in client.health().capabilities }

    override fun snapshots(
        qktSymbol: String,
        fromMs: Long,
        toMs: Long,
    ): List<BookDepth> {
        check(
            declared,
        ) { "$account: its gateway does not declare $DEPTH_CAPABILITY, so it serves no depth of $qktSymbol" }
        if (symbols.venue(qktSymbol) == null) symbols.updateListing(client.instruments())
        val code = symbols.venue(qktSymbol) ?: error("$qktSymbol is not in the gateway's listing")
        return client.depth(code, fromMs, toMs).map { BookDepth(it.time, levels(it.bids), levels(it.asks)) }
    }

    private fun levels(side: List<List<String>>) =
        side.map { level ->
            require(level.size == 2) { "a gateway depth level is [price, amount], not $level" }
            BookLevel(BigDecimal(level[0]), BigDecimal(level[1]))
        }
}
