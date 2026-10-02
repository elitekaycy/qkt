package com.qkt.backtest

import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * One BTCUSDT@front position held through three real rolls — 2024-09-19, 2024-12-19 and 2025-03-20
 * (`src/test/resources/futures/btcusdt-3rolls`, see its PROVENANCE.md) — with the 0.05% taker fee on
 * every contract fill. The engine's P&L must equal the chain of contract legs it actually traded.
 */
class ContinuousFuturesThreeRollsTest {
    private val quantity = BigDecimal("0.01")
    private val taker = BigDecimal("0.0005")
    private val contracts = listOf("240927", "241227", "250328", "250627").map { "BINANCE_UM:BTCUSDT_$it" }

    // Each contract's place in the series, from the measured rolls in the fixture's rolls.json.
    private val shifts =
        listOf("65966.9" to "68032.8", "62206.4" to "63343.9", "102050.9" to "105750.5", "85898.3" to "87055.1")
            .runningFold(BigDecimal.ZERO) { shift, (from, to) -> shift.add(BigDecimal(from)).subtract(BigDecimal(to)) }
            .drop(1)

    @Test
    fun `a position carried through three rolls books exactly the P&L of its contract legs`(
        @TempDir dir: Path,
    ) {
        val (result, data) =
            FuturesFixtureRun.run(
                dir,
                "btcusdt-3rolls",
                """
                STRATEGY hold3 VERSION 1
                SYMBOLS
                    btc = BINANCE_UM:BTCUSDT@front EVERY 1h
                RULES
                    WHEN btc.close > 0
                    THEN BUY btc SIZING 0.01 EXIT AFTER 200d
                """,
                from = "2024-09-11",
                to = "2025-04-10",
            )

        val rolls = result.rolls
        assertThat(rolls.map { it.from to it.to }).containsExactly(
            contracts[0] to contracts[1],
            contracts[1] to contracts[2],
            contracts[2] to contracts[3],
        )
        assertThat(rolls.map { it.quantity }).allSatisfy { assertThat(it).isEqualByComparingTo(quantity) }
        rolls.forEach { assertThat(it.fromFill).isEqualByComparingTo(it.fromReference) }
        rolls.forEach { assertThat(it.toFill).isEqualByComparingTo(it.toReference) }

        val (entry, exit) = result.trades.map { it.trade }
        val entryOnFirst = entry.price.subtract(shifts[0])
        val exitOnLast = exit.price.subtract(shifts[3])
        assertThat(
            FuturesFixtureRun.prints(data, contracts[0], "1h", entry.timestamp),
        ).contains(entryOnFirst.stripTrailingZeros())
        assertThat(
            FuturesFixtureRun.prints(data, contracts[3], "1h", exit.timestamp),
        ).contains(exitOnLast.stripTrailingZeros())

        val legPrices = listOf(entryOnFirst) + rolls.flatMap { listOf(it.fromFill, it.toFill) } + exitOnLast
        val legs =
            legPrices
                .chunked(
                    2,
                ).fold(BigDecimal.ZERO) { total, (bought, sold) -> total.add(sold.subtract(bought)) }
        val fees = legPrices.fold(BigDecimal.ZERO, BigDecimal::add).multiply(quantity).multiply(taker)
        val expected = legs.multiply(quantity).subtract(fees)
        assertThat(
            result.perStrategy.values
                .single()
                .realizedTotal,
        ).isEqualByComparingTo(expected)
        assertThat(result.global.totalPnL).isEqualByComparingTo(expected)
    }
}
