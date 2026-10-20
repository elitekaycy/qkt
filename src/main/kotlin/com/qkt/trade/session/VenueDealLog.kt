package com.qkt.trade.session

import com.qkt.connectivity.DirectDeal
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** One execution as the venue reports it: on its own clock, the clock its ticks carry. */
data class VenueDeal(
    val symbol: String,
    val side: String,
    val price: BigDecimal,
    val timeMs: Long,
)

/**
 * `venue-deals.jsonl` of a live session: the venue's own record of its executions over the session, read once at
 * finish (read-only; the engine's booking is untouched). A fill is booked when the venue's confirmation arrives,
 * which trails the execution (live 2026-10-20: by up to a second, a newer tick between), so a trace replays each
 * fill at its deal's time.
 */
object VenueDealLog {
    const val FILE = "venue-deals.jsonl"

    fun write(
        path: Path,
        deals: List<DirectDeal>,
    ) {
        path.parent?.let { Files.createDirectories(it) }
        Files.write(
            path,
            deals.map {
                """{"symbol":"${it.symbol}","side":"${it.side}","price":${it.price.toPlainString()},""" +
                    """"timeMs":${it.timeMs},"dealTicket":"${it.dealTicket}"}"""
            },
        )
    }

    fun read(path: Path): List<VenueDeal> {
        if (!Files.exists(path)) return emptyList()
        return Files.readAllLines(path).filter { it.isNotBlank() }.map { line ->
            val o = Json.parseToJsonElement(line).jsonObject

            fun text(k: String) = (o[k] as JsonPrimitive).contentOrNull!!
            VenueDeal(text("symbol"), text("side"), BigDecimal(text("price")), text("timeMs").toLong())
        }
    }

    /**
     * When the venue executed each booked fill: per intent, its booked time ([filledAt]) moved to the time of the
     * deal with the recorded fill's side and price nearest it (within [WINDOW_MS]). Intents without one keep theirs.
     */
    fun executedAt(
        filledAt: Map<String, Long>,
        fills: List<ReplayFill>,
        deals: List<VenueDeal>,
    ): Map<String, Long> =
        filledAt.mapValues { (_, booked) ->
            val fill = fills.firstOrNull { it.timeMs == booked } ?: return@mapValues booked
            deals
                .filter { it.side == fill.side && it.price.compareTo(fill.price) == 0 }
                .filter { kotlin.math.abs(it.timeMs - booked) <= WINDOW_MS }
                .minByOrNull { kotlin.math.abs(it.timeMs - booked) }
                ?.timeMs ?: booked
        }

    private const val WINDOW_MS = 5_000L
}
