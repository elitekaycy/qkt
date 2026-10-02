package com.qkt.backtest

import com.qkt.common.Side
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.GZIPInputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A put credit spread on real Deribit trade chains (`btc-usdc-trade-25sep26`, see its PROVENANCE):
 * buy the 83500 put on its first fresh quote and sell the 84500 put on its first fresh quote, each
 * decided an hour before (a 1h strategy decides on the quote before), then hold both into the
 * 2026-09-26T08:00Z expiry (delivery 84042.83). The root charges no fees. Every price is recomputed
 * here from the fixture's raw marks and the 5% spread on the 5 USDC grid.
 */
class ShortOptionBacktestTest {
    private val delivery = BigDecimal("84042.83")
    private val hour = 3_600_000L

    /** [contract]'s stored rows of 2026-09-25: instant to (mark, mark age in ms). */
    private fun rows(contract: String): Map<Long, Pair<BigDecimal, Long>> {
        val dir = requireNotNull(javaClass.getResource("/options/btc-usdc-trade-25sep26/chains/DERIBIT/BTC_USDC/trade"))
        val lines =
            GZIPInputStream(
                Files.newInputStream(Paths.get(dir.toURI()).resolve("2026-09-25.csv.gz")),
            ).bufferedReader().readLines()
        return lines
            .drop(1)
            .map { it.split(',') }
            .filter { it[1] == contract }
            .associate { it[0].toLong() to (BigDecimal(it[4]) to it[8].toLong()) }
    }

    /** The first quote at most an hour old with quotes one and two hours before it. */
    private fun firstFresh(quotes: Map<Long, Pair<BigDecimal, Long>>): Long =
        quotes.keys.sorted().first { at ->
            quotes.getValue(at).second <= hour &&
                (at - hour) in quotes &&
                (at - 2 * hour) in quotes
        }

    private fun half(mark: BigDecimal) = BigDecimal("5").max(mark.multiply(BigDecimal("0.05")))

    private fun grid(
        value: BigDecimal,
        mode: RoundingMode,
    ) = value.divide(BigDecimal("5"), 0, mode).multiply(BigDecimal("5"))

    @Test
    fun `a put credit spread opens on real quotes, settles at delivery and its P&L reconciles`(
        @TempDir dir: Path,
    ) {
        val longQuotes = rows("BTC_USDC-26SEP26-83500-P")
        val shortQuotes = rows("BTC_USDC-26SEP26-84500-P")
        val longAt = firstFresh(longQuotes)
        val shortAt = firstFresh(shortQuotes)
        val (result, _) =
            FuturesFixtureRun.run(
                dir,
                "btc-usdc-trade-25sep26",
                """
                STRATEGY spread VERSION 1
                SYMBOLS
                    lw = DERIBIT:BTC_USDC_26SEP26_83500_P EVERY 1h
                    sw = DERIBIT:BTC_USDC_26SEP26_84500_P EVERY 1h
                RULES
                    WHEN NOW.epoch_ms >= ${longAt - hour}
                    THEN BUY lw SIZING 0.1
                    WHEN NOW.epoch_ms >= ${shortAt - hour}
                    THEN SELL sw SIZING 0.1
                """,
                from = "2026-09-25",
                to = "2026-09-27",
                resources = "options",
            )

        val trades = result.trades.map { it.trade }
        val bought = trades.single { it.symbol.endsWith("83500_P") && it.side == Side.BUY }
        val sold = trades.single { it.symbol.endsWith("84500_P") && it.side == Side.SELL }
        val longMark = longQuotes.getValue(longAt).first
        val shortMark = shortQuotes.getValue(shortAt).first
        assertThat(listOf(bought.timestamp, sold.timestamp)).containsExactly(longAt, shortAt)
        assertThat(bought.price).isEqualByComparingTo(grid(longMark.add(half(longMark)), RoundingMode.CEILING))
        assertThat(sold.price).isEqualByComparingTo(grid(shortMark.subtract(half(shortMark)), RoundingMode.FLOOR))

        val shortIntrinsic = BigDecimal("84500").subtract(delivery)
        assertThat(
            trades
                .single {
                    it.symbol.endsWith("84500_P") && it.side == Side.BUY
                }.price,
        ).isEqualByComparingTo(shortIntrinsic)
        assertThat(
            trades.single { it.symbol.endsWith("83500_P") && it.side == Side.SELL }.price,
        ).isEqualByComparingTo("0")

        val expected =
            sold.price
                .subtract(bought.price)
                .subtract(shortIntrinsic)
                .multiply(BigDecimal("0.1"))
        val report = result.perStrategy.values.single()
        assertThat(report.realizedTotal).isEqualByComparingTo(expected)
        assertThat(report.unrealizedTotal).isEqualByComparingTo("0")
    }

    @Test
    fun `a naked short call is refused by the margin check before it reaches the venue`(
        @TempDir dir: Path,
    ) {
        val (result, _) =
            FuturesFixtureRun.run(
                dir,
                "btc-usdc-trade-25sep26",
                """
                STRATEGY naked VERSION 1
                SYMBOLS
                    c = DERIBIT:BTC_USDC_26SEP26_84500_C EVERY 1h
                RULES
                    WHEN c.close > 0
                    THEN SELL c SIZING 0.1
                """,
                from = "2026-09-25",
                to = "2026-09-27",
                resources = "options",
            )

        assertThat(result.trades.filter { it.trade.side == Side.SELL }).isEmpty()
        assertThat(result.rejections.map { it.reason }).anyMatch { it.contains("unbounded") }
    }
}
