package com.qkt.backtest

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

/** The rows of the four real book snapshots in `btc-usdc-book-live4`, by snapshot instant. */
internal object StructureBacktestRows {
    /** One quote: the venue [contract] and its sides. */
    class Row(
        val contract: String,
        val bid: BigDecimal?,
        val ask: BigDecimal?,
    )

    val rows: Map<Long, List<Row>> by lazy {
        val dir = requireNotNull(javaClass.getResource("/options/btc-usdc-book-live4/chains/DERIBIT/BTC_USDC/book"))
        GZIPInputStream(Files.newInputStream(Paths.get(dir.toURI()).resolve("2026-10-01.csv.gz")))
            .bufferedReader()
            .readLines()
            .drop(1)
            .map { it.split(',') }
            .groupBy({ it[0].toLong() }, {
                Row(
                    it[1],
                    it[2].takeIf(String::isNotEmpty)?.let(::BigDecimal),
                    it[3].takeIf(String::isNotEmpty)?.let(::BigDecimal),
                )
            })
    }
}
