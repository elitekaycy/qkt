package com.qkt.backtest

import com.qkt.common.Side
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Four real Deribit book snapshots a minute apart (`btc-usdc-book-live4`, see its PROVENANCE). A rule
 * opens a put credit spread from the chain. Fills, sizes and decision times are checked against the
 * file itself and an independent Python selector (5% risk on the first snapshot: short 82000 put,
 * long 79000 put, maximum loss 2557.09190265 per contract, so 10000 × 5% / that = 0.19 contracts).
 */
class StructureBacktestTest {
    private val rows = StructureBacktestRows.rows

    private fun quoteAt(
        atMs: Long,
        qktSymbol: String,
    ): StructureBacktestRows.Row =
        rows.getValue(atMs).single { "DERIBIT:${it.contract.replace('-', '_')}" == qktSymbol }

    private val putSpread = "SELL PUT DELTA 0.25 DTE 7 TO 30, BUY PUT DELTA 0.10 SAME EXPIRY"

    private fun run(
        dir: Path,
        flags: List<String> = emptyList(),
        window: String = "1m",
        sizing: String = "0.1",
        legs: String = putSpread,
    ) = FuturesFixtureRun
        .run(
            dir,
            "btc-usdc-book-live4",
            "STRATEGY spread VERSION 1\nSYMBOLS\n    chain = OPTIONS:DERIBIT.BTC_USDC EVERY $window,\n" +
                "    iv = CHAIN:DERIBIT.BTC_USDC.atm_iv.7d EVERY $window\nRULES\n    WHEN iv.close > 0\n" +
                "    THEN OPEN ps = OPTIONS ON DERIBIT:BTC_USDC { $legs } SIZING $sizing\n",
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
        val causality = requireNotNull(result.causality)
        assertThat(causality.decisionOrderLinks.map { it.orderId })
            .containsExactlyInAnyOrderElementsOf(causality.approvedOrders.map { it.request.id })
            .hasSize(2)

        val short = trades.single { it.side == Side.SELL }
        val long = trades.single { it.side == Side.BUY }
        assertThat(listOf(short.timestamp, long.timestamp)).allMatch { it in rows.keys && it > rows.keys.min() }
        assertThat(short.price).isEqualByComparingTo(requireNotNull(quoteAt(short.timestamp, short.symbol).bid))
        assertThat(long.price).isEqualByComparingTo(requireNotNull(quoteAt(long.timestamp, long.symbol).ask))
        assertThat(listOf(short.quantity, long.quantity)).allMatch { it.compareTo(BigDecimal("0.1")) == 0 }
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

    @Test
    fun `a window longer than the snapshot spacing never selects from a snapshot after the decision`(
        @TempDir dir: Path,
    ) {
        val result = run(dir, window = "5m")

        val decided =
            requireNotNull(result.causality)
                .approvedOrders
                .map { it.request.timestamp }
                .distinct()
                .single()
        assertThat(decided).isEqualTo(rows.keys.min())
        assertThat(result.trades.map { it.trade.timestamp }).allMatch { it > decided }
    }

    @Test
    fun `risk sizing divides the risked equity by the spread's loss per contract, and refuses an unbounded one`(
        @TempDir dir: Path,
    ) {
        val sized =
            run(
                dir.resolve("spread").also {
                    Files.createDirectories(it)
                },
                sizing = "5 PCT RISK",
            ).trades.map { it.trade }

        assertThat(
            sized.map {
                it.symbol
            },
        ).containsExactlyInAnyOrder("DERIBIT:BTC_USDC_9OCT26_82000_P", "DERIBIT:BTC_USDC_9OCT26_79000_P")
        assertThat(sized).allMatch { it.quantity.compareTo(BigDecimal("0.19")) == 0 }
        val naked =
            run(
                dir.resolve("naked").also {
                    Files.createDirectories(it)
                },
                sizing = "5 PCT RISK",
                legs = "SELL CALL DELTA 0.25 DTE 7 TO 30",
            )
        assertThat(naked.trades).isEmpty()
    }
}
