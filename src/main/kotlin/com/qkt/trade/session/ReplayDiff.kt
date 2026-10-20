package com.qkt.trade.session

import java.math.BigDecimal

/** One execution as a session record and its replay both state it. */
data class ReplayFill(
    val identity: String,
    val symbol: String,
    val side: String,
    val quantity: BigDecimal,
    val price: BigDecimal,
    val timeMs: Long,
)

/** A recorded run's fills beside its replay's, position by position; [identical] when every pair agrees. */
class ReplayDiff private constructor(
    val recorded: List<ReplayFill>,
    val replayed: List<ReplayFill>,
    val mismatches: List<Pair<ReplayFill?, ReplayFill?>>,
) {
    val identical: Boolean get() = mismatches.isEmpty()

    /**
     * The same trades in the same order (identity, symbol, side, quantity), whatever their prices and times. A
     * live record replays with [sameTrades] at best: a venue's fill price and clock are not a tick's.
     */
    val sameTrades: Boolean
        get() =
            recorded.size == replayed.size &&
                recorded.zip(replayed).all { (a, b) ->
                    a.identity == b.identity &&
                        a.symbol == b.symbol &&
                        a.side == b.side &&
                        a.quantity.compareTo(b.quantity) == 0
                }

    fun json(): String {
        fun fill(f: ReplayFill?): String =
            f?.let {
                """{"identity":"${it.identity}","symbol":"${it.symbol}","side":"${it.side}",""" +
                    """"qty":${it.quantity.toPlainString()},"price":${it.price.toPlainString()},"timeMs":${it.timeMs}}"""
            } ?: "null"
        val rows =
            mismatches.joinToString(
                ",",
                "[",
                "]",
            ) { (a, b) -> """{"recorded":${fill(a)},"replayed":${fill(b)}}""" }
        return """{"identical":$identical,"sameTrades":$sameTrades,"recordedFills":${recorded.size},""" +
            """"replayedFills":${replayed.size},"mismatches":$rows}"""
    }

    companion object {
        fun of(
            recorded: List<ReplayFill>,
            replayed: List<ReplayFill>,
        ): ReplayDiff {
            val pairs =
                (0 until maxOf(recorded.size, replayed.size)).map {
                    recorded.getOrNull(it) to
                        replayed.getOrNull(it)
                }
            return ReplayDiff(recorded, replayed, pairs.filterNot { (a, b) -> a != null && b != null && same(a, b) })
        }

        private fun same(
            a: ReplayFill,
            b: ReplayFill,
        ) = a.identity == b.identity &&
            a.symbol == b.symbol &&
            a.side == b.side &&
            a.quantity.compareTo(b.quantity) == 0 &&
            a.price.compareTo(b.price) == 0 &&
            a.timeMs == b.timeMs
    }
}
