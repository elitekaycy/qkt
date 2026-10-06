package com.qkt.broker.continuous

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RollLedgerTest {
    private fun entry(
        quantity: String,
        fromFill: String,
        toFill: String,
        fees: String = "0",
        multiplier: String = "1",
    ) = RollEntry(
        atMs = 1L,
        stream = "BINANCE_UM:BTCUSDT@front",
        strategyId = "s",
        from = "BINANCE_UM:BTCUSDT_240927",
        to = "BINANCE_UM:BTCUSDT_241227",
        quantity = BigDecimal(quantity),
        multiplier = BigDecimal(multiplier),
        fromFill = BigDecimal(fromFill),
        toFill = BigDecimal(toFill),
        fromReference = BigDecimal("63000"),
        toReference = BigDecimal("63800"),
        fees = BigDecimal(fees),
    )

    @Test
    fun `a long roll that sells below and buys above the references costs the difference plus fees`() {
        // Sold the old leg 0.2 under 63000, bought the new one 0.2 over 63800: 0.4 x 0.01 = 0.004.
        assertThat(entry("0.01", "62999.8", "63800.2", fees = "0.5").cost).isEqualByComparingTo("0.504")
    }

    @Test
    fun `a short roll costs the same for the mirrored fills`() {
        assertThat(entry("-0.01", "63000.2", "63799.8").cost).isEqualByComparingTo("0.004")
    }

    @Test
    fun `fills better than the references are a credit`() {
        assertThat(entry("0.01", "63000.3", "63799.9").cost).isEqualByComparingTo("-0.004")
    }

    @Test
    fun `the multiplier scales the price difference but not the fees`() {
        assertThat(entry("2", "62999.75", "63800.25", fees = "4.2", multiplier = "50").cost)
            .isEqualByComparingTo("54.2")
    }

    @Test
    fun `the ledger keeps entries in the order they were recorded`() {
        val ledger = RollLedger()
        val first = entry("0.01", "62999.8", "63800.2")
        val second = entry("-0.01", "63000.2", "63799.8")

        ledger.record(first)
        ledger.record(second)

        assertThat(ledger.entries).containsExactly(first, second)
    }
}
