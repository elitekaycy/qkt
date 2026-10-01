package com.qkt.backtest

import com.qkt.common.Side
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.GZIPInputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Four real Deribit book snapshots a minute apart (`btc-usdc-book-live4`, see its PROVENANCE). A rule
 * opens a put credit spread from the chain; both legs must fill on a later stored snapshot at that
 * snapshot's own ask (the long wing) and bid (the short), read here straight from the file.
 */
class StructureBacktestTest {
    private class Row(
        val contract: String,
        val bid: BigDecimal?,
        val ask: BigDecimal?,
    )

    private val rows: Map<Long, List<Row>> by lazy {
        val dir = requireNotNull(javaClass.getResource("/options/btc-usdc-book-live4/chains/DERIBIT/BTC_USDC/book"))
        GZIPInputStream(Files.newInputStream(Paths.get(dir.toURI()).resolve("2026-10-01.csv.gz")))
            .bufferedReader()
            .readLines()
            .drop(1)
            .map { it.split(',') }
            .groupBy({
                it[0].toLong()
            }, {
                Row(
                    it[1],
                    it[2].takeIf(String::isNotEmpty)?.let(::BigDecimal),
                    it[3].takeIf(String::isNotEmpty)?.let(::BigDecimal),
                )
            })
    }

    private fun quoteAt(
        atMs: Long,
        qktSymbol: String,
    ): Row = rows.getValue(atMs).single { "DERIBIT:${it.contract.replace('-', '_')}" == qktSymbol }

    private fun run(
        dir: Path,
        flags: List<String> = emptyList(),
    ) = FuturesFixtureRun
        .run(
            dir,
            "btc-usdc-book-live4",
            """
                STRATEGY spread VERSION 1
                SYMBOLS
                    chain = OPTIONS:DERIBIT.BTC_USDC EVERY 1m,
                    iv = CHAIN:DERIBIT.BTC_USDC.atm_iv.7d EVERY 1m
                RULES
                    WHEN iv.close > 0
                    THEN OPEN ps = OPTIONS ON DERIBIT:BTC_USDC {
                        SELL PUT DELTA 0.25 DTE 7 TO 30,
                        BUY PUT DELTA 0.10 SAME EXPIRY
                    } SIZING 0.1
                """,
            from = "2026-10-01",
            to = "2026-10-02",
            flags = flags,
            resources = "options",
        ).first

    @Test
    fun `a put credit spread opened by one rule fills both legs at the next snapshot's real quotes`(
        @TempDir dir: Path,
    ) {
        val result = run(dir)

        val trades = result.trades.map { it.trade }
        val short = trades.single { it.side == Side.SELL }
        val long = trades.single { it.side == Side.BUY }
        val first = rows.keys.min()
        assertThat(listOf(short.timestamp, long.timestamp)).allMatch { it in rows.keys && it > first }
        assertThat(
            short.symbol.substringBeforeLast('_').substringBeforeLast('_'),
        ).isEqualTo(long.symbol.substringBeforeLast('_').substringBeforeLast('_'))
        assertThat(short.price).isEqualByComparingTo(requireNotNull(quoteAt(short.timestamp, short.symbol).bid))
        assertThat(long.price).isEqualByComparingTo(requireNotNull(quoteAt(long.timestamp, long.symbol).ask))
        assertThat(listOf(short.quantity, long.quantity)).allMatch { it.compareTo(BigDecimal("0.1")) == 0 }
        assertThat(result.rejections).isEmpty()
    }

    @Test
    fun `equity that covers the spread but not its short leg alone still opens the structure`(
        @TempDir dir: Path,
    ) {
        // Width less credit is a few hundred for 0.1 contracts; the short put alone would need about 800.
        val result = run(dir, listOf("--starting-balance", "500"))

        assertThat(result.trades.map { it.trade.side }).containsExactlyInAnyOrder(Side.SELL, Side.BUY)
        assertThat(result.rejections).isEmpty()
    }
}
