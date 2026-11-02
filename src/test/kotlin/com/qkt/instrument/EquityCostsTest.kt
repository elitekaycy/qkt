package com.qkt.instrument

import com.qkt.common.Side
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A per-share broker ($0.005, $1 minimum, 1% cap) with the SEC fee and FINRA TAF on sales, rates from the file. */
class EquityCostsTest {
    private val costs =
        EquityCosts(
            commissionPerShare = BigDecimal("0.005"),
            minCommission = BigDecimal("1.00"),
            maxCommissionRate = BigDecimal("0.01"),
            secFeeRate = BigDecimal("0.0000278"),
            tafPerShare = BigDecimal("0.000166"),
            tafMax = BigDecimal("8.30"),
        )

    @Test
    fun `a buy pays commission only, at least the minimum, at most the cap`() {
        assertThat(costs.orderCost(Side.BUY, BigDecimal(100), BigDecimal(5000))).isEqualByComparingTo("1.00")
        assertThat(costs.orderCost(Side.BUY, BigDecimal(1000), BigDecimal(50_000))).isEqualByComparingTo("5.00")
        // Ten $5 shares: the 1% cap ($0.50) is below the $1 minimum, and wins.
        assertThat(costs.orderCost(Side.BUY, BigDecimal(10), BigDecimal(50))).isEqualByComparingTo("0.50")
    }

    @Test
    fun `a sale adds the SEC fee on its value and the capped TAF per share, each rounded up to the cent`() {
        // 1.00 + ceil(0.139) + ceil(0.0166)
        assertThat(costs.orderCost(Side.SELL, BigDecimal(100), BigDecimal(5000))).isEqualByComparingTo("1.16")
        // 500.00 + 27.80 + min(16.60, 8.30)
        assertThat(costs.orderCost(Side.SELL, BigDecimal(100_000), BigDecimal(1_000_000)))
            .isEqualByComparingTo("536.10")
    }

    @Test
    fun `nothing filled costs nothing, and no cost may be negative`() {
        assertThat(costs.orderCost(Side.SELL, BigDecimal.ZERO, BigDecimal.ZERO)).isEqualByComparingTo("0")
        assertThatThrownBy { EquityCosts(secFeeRate = BigDecimal("-0.1")) }.hasMessageContaining(">= 0")
    }

    @Test
    fun `instruments yaml reads equity costs and refuses an unknown field`(
        @TempDir dir: Path,
    ) {
        fun load(costsYaml: String): YamlInstrumentRegistry {
            val f = dir.resolve("instruments.yaml")
            Files.writeString(
                f,
                "instruments:\n  - qktSymbol: ALPACA:AAPL\n    contractSize: 1\n    volumeStep: 1\n    volumeMin: 1\n" +
                    "    pointSize: 0.01\n    digits: 2\n    tradeStopsLevelPoints: 0\n    equityCosts:\n$costsYaml",
            )
            return YamlInstrumentRegistry.load(f)
        }
        val read = load("      secFeeRate: 0.0000278\n      tafPerShare: 0.000166\n      tafMax: 8.30\n")
        assertThat(read.lookup("ALPACA:AAPL")?.equityCosts)
            .usingRecursiveComparison()
            .withComparatorForType(BigDecimal::compareTo, BigDecimal::class.java)
            .isEqualTo(
                EquityCosts(
                    secFeeRate = BigDecimal("0.0000278"),
                    tafPerShare = BigDecimal("0.000166"),
                    tafMax = BigDecimal("8.30"),
                ),
            )
        assertThatThrownBy { load("      secFee: 0.0000278\n") }.hasMessageContaining("unknown fields [secFee]")
    }
}
