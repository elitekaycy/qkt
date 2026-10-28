package com.qkt.pnl

import com.qkt.common.Side
import com.qkt.instrument.EquityCosts
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Order-level costs over fills: a minimum once per order, fees once per sale, other instruments unchanged. */
class EquityCostCommissionTest {
    private fun meta(
        symbol: String,
        contractSize: String,
        costs: EquityCosts?,
        perLot: String = "0",
    ) = InstrumentMeta(
        qktSymbol = symbol,
        contractSize = BigDecimal(contractSize),
        volumeStep = BigDecimal("0.01"),
        volumeMin = BigDecimal("0.01"),
        volumeMax = null,
        pointSize = BigDecimal("0.01"),
        digits = 2,
        tradeStopsLevelPoints = 0,
        commissionPerLot = BigDecimal(perLot),
        equityCosts = costs,
    )

    private val broker =
        EquityCosts(
            commissionPerShare = BigDecimal("0.005"),
            minCommission = BigDecimal("1.00"),
            secFeeRate = BigDecimal("0.0000278"),
            tafPerShare = BigDecimal("0.000166"),
            tafMax = BigDecimal("8.30"),
        )
    private val registry =
        object : InstrumentRegistry {
            private val table =
                listOf(
                    meta("ALPACA:AAPL", "1", broker),
                    meta("EXNESS:AAPL", "100", broker),
                    meta("EXNESS:EURUSD", "100000", null, perLot = "7"),
                ).associateBy { it.qktSymbol }

            override fun lookup(qktSymbol: String) = table[qktSymbol]
        }
    private val model = EquityCostCommission(registry, PerLotCommission(registry))

    private fun fill(
        symbol: String,
        side: Side,
        qty: String,
        price: String,
        order: String,
    ) = model.cost(symbol, side, BigDecimal(qty), BigDecimal(price), order)

    @Test
    fun `partial fills of one order pay the minimum once and then only what the order's cost grew by`() {
        val fills = listOf("100", "100", "300").map { fill("ALPACA:AAPL", Side.BUY, it, "50", "ORD-1") }
        assertThat(fills)
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal("1.50"))
        val whole =
            EquityCostCommission(
                registry,
                NoCommission,
            ).cost("ALPACA:AAPL", Side.BUY, BigDecimal(500), BigDecimal(50), "X")
        assertThat(fills.reduce(BigDecimal::add)).isEqualByComparingTo(whole)
    }

    @Test
    fun `a second order pays its own minimum, and a sale its fees`() {
        assertThat(fill("ALPACA:AAPL", Side.BUY, "100", "50", "ORD-1")).isEqualByComparingTo("1.00")
        assertThat(fill("ALPACA:AAPL", Side.SELL, "100", "50", "ORD-2")).isEqualByComparingTo("1.16")
    }

    @Test
    fun `lots are converted to shares by the contract size`() {
        // 0.01 lot of a 100-share CFD is one share: the $1 minimum.
        assertThat(fill("EXNESS:AAPL", Side.BUY, "0.01", "346", "ORD-1")).isEqualByComparingTo("1.00")
        // 10 lots = 1000 shares: $5.00.
        assertThat(fill("EXNESS:AAPL", Side.BUY, "10", "346", "ORD-2")).isEqualByComparingTo("5.00")
    }

    @Test
    fun `an instrument without equity costs is charged by the fallback exactly as before`() {
        assertThat(fill("EXNESS:EURUSD", Side.SELL, "0.5", "1.1", "ORD-1")).isEqualByComparingTo("3.5")
        assertThat(model.cost("EXNESS:EURUSD", BigDecimal("0.5"))).isEqualByComparingTo("3.5")
        assertThat(fill("UNKNOWN:X", Side.BUY, "1", "1", "ORD-2")).isEqualByComparingTo("0")
    }

    @Test
    fun `a share's costs are never charged without the fill's side and order`() {
        assertThatThrownBy { model.cost("ALPACA:AAPL", BigDecimal(100), BigDecimal(50)) }
            .hasMessageContaining("need the fill's side and order")
    }
}
