package com.qkt.verification

import java.math.BigDecimal
import java.nio.file.Path
import kotlin.io.path.readLines

/**
 * Reads a run's `trades.csv` into [ActualFill]s. The exit reason is what the artifact states about the fill:
 * a bracket or OCO stop leg's id ends in `-sl`, a target's in `-tp`; a `CLOSE_*`/`REDUCE_*` position effect
 * from any other order is a rule-driven close; everything else opened or added to a position (an entry).
 */
object TradesCsvTape {
    fun read(file: Path): List<ActualFill> {
        val lines = file.readLines().filter { it.isNotBlank() }
        val header = lines.first().split(',')

        fun col(name: String) = header.indexOf(name).also { check(it >= 0) { "trades.csv lacks column $name" } }
        val ts = col("timestamp")
        val side = col("side")
        val effect = col("positionEffect")
        val orderType = col("orderType")
        val qty = col("quantity")
        val price = col("price")
        val realized = col("realized")
        val orderId = col("brokerOrderId")
        val stop = col("stopLossPrice")
        val target = col("takeProfitPrice")
        return lines.drop(1).map { line ->
            val c = line.split(',')
            ActualFill(
                ts = c[ts].toLong(),
                side = c[side],
                qty = BigDecimal(c[qty]),
                price = BigDecimal(c[price]),
                realized = BigDecimal(c[realized]),
                reason = reason(c[orderId], c[effect]),
                orderType = c[orderType],
                stopLoss = c[stop].takeIf { it.isNotEmpty() }?.let(::BigDecimal),
                takeProfit = c[target].takeIf { it.isNotEmpty() }?.let(::BigDecimal),
            )
        }
    }

    private fun reason(
        orderId: String,
        effect: String,
    ): String =
        when {
            orderId.endsWith("-sl") -> "stop"
            orderId.endsWith("-tp") -> "target"
            effect.startsWith("CLOSE") || effect.startsWith("REDUCE") -> "close"
            else -> "entry"
        }
}
