package com.qkt.backtest

import com.qkt.common.Side
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.zip.GZIPInputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Real Deribit chains (`btc-usdc-26sep26`, see its PROVENANCE): a strategy buys a call and holds it
 * into the 2026-09-26T08:00Z expiry. Every expectation is recomputed here from the fixture's raw
 * rows and the published rules (5% mark spread on the 5 USDC grid; fee min(0.03% of the index,
 * 12.5% of the premium); delivery fee min(0.015% of 84042.83, 12.5% of the intrinsic)), not from
 * qkt's own pricing code.
 */
class OptionBacktestTest {
    private val delivery = BigDecimal("84042.83")
    private val expiryMs = Instant.parse("2026-09-26T08:00:00Z").toEpochMilli()

    private class Row(
        val mark: BigDecimal,
        val index: BigDecimal,
        val ageMs: Long,
    )

    private fun rows(contract: String): Map<Long, Row> {
        val base =
            Paths.get(
                requireNotNull(
                    javaClass.getResource("/options/btc-usdc-26sep26/chains/DERIBIT/BTC_USDC/trade"),
                ).toURI(),
            )
        return listOf("2026-09-25", "2026-09-26")
            .flatMap { day ->
                GZIPInputStream(Files.newInputStream(base.resolve("$day.csv.gz"))).bufferedReader().readLines().drop(1)
            }.map { it.split(',') }
            .filter { it[1] == contract }
            .associate { it[0].toLong() to Row(BigDecimal(it[4]), BigDecimal(it[6]), it[8].toLong()) }
    }

    private fun ask(mark: BigDecimal): BigDecimal {
        val raw = mark.add(BigDecimal("5").max(mark.multiply(BigDecimal("0.05"))))
        return raw.divide(BigDecimal("5"), 0, RoundingMode.CEILING).multiply(BigDecimal("5"))
    }

    /**
     * When to decide so the next quote is tradeable: a 1h strategy decides at a bar close, on the
     * quote after the bar, so pick the first fresh quote with two hourly quotes before it and decide
     * at the one just before it.
     */
    private fun decisionMs(quotes: Map<Long, Row>): Long =
        quotes.keys.sorted().first { at ->
            quotes.getValue(at).ageMs <= 3_600_000 && (at - 3_600_000) in quotes && (at - 7_200_000) in quotes
        } - 3_600_000

    private fun backtest(
        dir: Path,
        code: String,
        decideAtMs: Long,
    ) = FuturesFixtureRun
        .run(
            dir,
            "btc-usdc-26sep26",
            """
            STRATEGY hold VERSION 1
            SYMBOLS
                opt = DERIBIT:$code EVERY 1h
            RULES
                WHEN NOW.epoch_ms >= $decideAtMs
                THEN BUY opt SIZING 0.1
            """,
            from = "2026-09-25",
            to = "2026-09-27",
            resources = "options",
        ).first

    private fun assertHeldToExpiry(
        dir: Path,
        code: String,
        venueName: String,
        intrinsic: BigDecimal,
    ) {
        val quotes = rows(venueName)
        val result = backtest(dir, code, decisionMs(quotes))
        val buys = result.trades.filter { it.trade.side == Side.BUY }.map { it.trade }
        val sells = result.trades.filter { it.trade.side == Side.SELL }.map { it.trade }

        assertThat(buys).isNotEmpty()
        var expected = BigDecimal.ZERO
        for (buy in buys) {
            val row =
                requireNotNull(
                    quotes[buy.timestamp],
                ) { "a buy at ${Instant.ofEpochMilli(buy.timestamp)} is not on a stored quote" }
            assertThat(row.ageMs).isLessThanOrEqualTo(3_600_000)
            assertThat(buy.price).isEqualByComparingTo(ask(row.mark))
            val fee =
                BigDecimal(
                    "0.0003",
                ).multiply(row.index).min(BigDecimal("0.125").multiply(buy.price)).multiply(buy.quantity)
            expected = expected.subtract(buy.price.multiply(buy.quantity)).subtract(fee)
        }
        val held = buys.fold(BigDecimal.ZERO) { q, b -> q.add(b.quantity) }
        val settled = sells.single()
        assertThat(settled.timestamp).isGreaterThanOrEqualTo(expiryMs)
        assertThat(settled.price).isEqualByComparingTo(intrinsic)
        assertThat(settled.quantity).isEqualByComparingTo(held)
        val deliveryFee =
            if (intrinsic.signum() == 0) {
                BigDecimal.ZERO
            } else {
                BigDecimal("0.00015").multiply(delivery).min(BigDecimal("0.125").multiply(intrinsic)).multiply(held)
            }
        expected = expected.add(intrinsic.multiply(held)).subtract(deliveryFee)

        val report = result.perStrategy.values.single()
        assertThat(report.unrealizedTotal).isEqualByComparingTo("0")
        assertThat(
            report.realizedTotal.setScale(6, RoundingMode.HALF_EVEN),
        ).isEqualByComparingTo(expected.setScale(6, RoundingMode.HALF_EVEN))
        assertThat(result.finalPositions.values.filter { it.quantity.signum() != 0 }).isEmpty()
    }

    @Test
    fun `an in-the-money call bought on real quotes settles at its intrinsic value and P&L reconciles`(
        @TempDir dir: Path,
    ) = assertHeldToExpiry(dir, "BTC_USDC_26SEP26_84000_C", "BTC_USDC-26SEP26-84000-C", BigDecimal("42.83"))

    @Test
    fun `an out-of-the-money call expires worthless with no delivery fee and P&L reconciles`(
        @TempDir dir: Path,
    ) = assertHeldToExpiry(dir, "BTC_USDC_26SEP26_85000_C", "BTC_USDC-26SEP26-85000-C", BigDecimal.ZERO)
}
