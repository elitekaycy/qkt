package com.qkt.connector.gateway

import com.qkt.marketdata.marks.MarkHistorySource
import com.qkt.marketdata.marks.MarkSample

/**
 * `GET /v1/marks`: [code]'s mark and index, one sample per window [windowMs] long starting in `[fromMs, toMs)`
 * in which the venue reported them, oldest first, every page read.
 */
fun GatewayClient.marks(
    code: String,
    windowMs: Long,
    fromMs: Long,
    toMs: Long,
): List<WireMark> {
    val marks = ArrayList<WireMark>()
    var from: Long? = fromMs
    while (from != null && from < toMs) {
        val page = read("/v1/marks?symbol=$code&window_ms=$windowMs&from=$from&to=$toMs", WireMarks.serializer())
        marks += page.marks
        require(page.next == null || page.next > from) { "gateway marks of $code do not advance past $from" }
        from = page.next
    }
    return marks
}

/** A gateway's mark history (`/v1/marks`), by qkt symbol through the gateway's listing. */
internal class GatewayMarkHistory(
    private val client: GatewayClient,
    private val symbols: GatewaySymbols,
) : MarkHistorySource {
    override fun marks(
        qktSymbol: String,
        windowMs: Long,
        fromMs: Long,
        toMs: Long,
    ): List<MarkSample> {
        if (symbols.code(qktSymbol) == null) symbols.updateListing(client.instruments())
        val code = symbols.code(qktSymbol) ?: error("$qktSymbol is not in the gateway's listing")
        return client.marks(code, windowMs, fromMs, toMs).map {
            MarkSample(it.time, it.mark?.toBigDecimal(), it.index?.toBigDecimal())
        }
    }
}
