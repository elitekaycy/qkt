package com.qkt.connector.gateway

import com.qkt.common.Side
import com.qkt.marketdata.flow.FlowKind
import com.qkt.marketdata.flow.Print
import com.qkt.marketdata.flow.PrintHistorySource
import java.math.BigDecimal
import kotlinx.serialization.Serializable

/** One print of `/v1/trades` or `/v1/liquidations`: [size] at [price] at [time]; [side] `buy` or `sell`. */
@Serializable
data class WirePrint(
    val id: String,
    val time: Long,
    val price: String,
    val size: String,
    val side: String,
)

/** `GET /v1/trades`: one page of [trades], oldest first; [next] is the `from` of the next page, null on the last. */
@Serializable
data class WireTrades(
    val trades: List<WirePrint>,
    val next: Long? = null,
)

/** `GET /v1/liquidations`: one page of [liquidations], oldest first; [next] is the next page's `from`, null on the last. */
@Serializable
data class WireLiquidations(
    val liquidations: List<WirePrint>,
    val next: Long? = null,
)

/**
 * `GET /v1/trades` or `/v1/liquidations` ([kind]): [code]'s prints with time in `[fromMs, toMs)`, oldest first, every
 * page read. A page never repeats a print of the one before (the gateway cuts pages between milliseconds).
 */
fun GatewayClient.prints(
    code: String,
    kind: FlowKind,
    fromMs: Long,
    toMs: Long,
): List<Print> {
    val prints = ArrayList<Print>()
    var from: Long? = fromMs
    while (from != null && from < toMs) {
        val path = "/v1/${kind.capability}?symbol=$code&from=$from&to=$toMs"
        val (page, next) =
            when (kind) {
                FlowKind.TRADES -> read(path, WireTrades.serializer()).let { it.trades to it.next }
                FlowKind.LIQUIDATIONS -> read(path, WireLiquidations.serializer()).let { it.liquidations to it.next }
            }
        page.mapTo(prints) { it.print() }
        require(next == null || next > from) { "gateway ${kind.capability} of $code do not advance past $from" }
        from = next
    }
    return prints
}

private fun WirePrint.print() =
    Print(
        id,
        time,
        BigDecimal(price),
        BigDecimal(size),
        when (side) {
            "buy" -> Side.BUY
            "sell" -> Side.SELL
            else -> error("gateway print $id has side '$side', not buy or sell")
        },
    )

/** A gateway's tape and liquidations (`/v1/trades`, `/v1/liquidations`), by qkt symbol through its listing. */
internal class GatewayPrintHistory(
    private val client: GatewayClient,
    private val symbols: GatewaySymbols,
) : PrintHistorySource {
    override fun prints(
        qktSymbol: String,
        kind: FlowKind,
        fromMs: Long,
        toMs: Long,
    ): List<Print> {
        if (symbols.code(qktSymbol) == null) symbols.updateListing(client.instruments())
        val code = symbols.code(qktSymbol) ?: error("$qktSymbol is not in the gateway's listing")
        return client.prints(code, kind, fromMs, toMs)
    }
}
