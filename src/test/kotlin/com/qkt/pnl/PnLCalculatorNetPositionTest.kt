package com.qkt.pnl

import com.qkt.common.Side
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.PendingOrderExposureProvider
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A [PositionProvider] that exposes only net positions is valued on its net, at the closing side. */
class PnLCalculatorNetPositionTest {
    private val netOnly =
        object : PositionProvider {
            private val held = mapOf("X" to Position("X", BigDecimal("2"), BigDecimal("100")))

            override fun positionFor(symbol: String): Position? = held[symbol]

            override fun allPositions(): Map<String, Position> = held
        }

    @Test
    fun `a net long is valued from its average entry and the totals add realized`() {
        val prices = MarketPriceTracker()
        prices.update("X", BigDecimal("110"))
        val pnl = PnLCalculator(netOnly, prices)
        pnl.recordRealized(BigDecimal("5"))

        assertThat(pnl.unrealizedFor("X")).isEqualByComparingTo("20")
        assertThat(pnl.unrealizedTotal()).isEqualByComparingTo("20")
        assertThat(pnl.totalPnL()).isEqualByComparingTo("25")
    }

    @Test
    fun `a priced symbol the provider does not hold has no open pnl`() {
        val prices = MarketPriceTracker()
        prices.update("Y", BigDecimal("110"))

        assertThat(PnLCalculator(netOnly, prices).unrealizedFor("Y")).isEqualByComparingTo("0")
    }

    @Test
    fun `a net position with a last price but no closing quote has no open pnl`() {
        val noClosingSide =
            object : MarketPriceProvider {
                override fun lastPrice(symbol: String): BigDecimal = BigDecimal("110")

                override fun executionPrice(
                    symbol: String,
                    side: Side,
                ): BigDecimal? = null
            }

        assertThat(PnLCalculator(netOnly, noClosingSide).unrealizedFor("X")).isEqualByComparingTo("0")
    }

    @Test
    fun `position and pending-exposure providers default to no pending entries`() {
        assertThat(netOnly.pendingEntryOrderCount(Side.BUY)).isZero()
        assertThat(netOnly.pendingEntrySymbols()).isEmpty()
        assertThat(netOnly.pendingOrderQuantity("X", Side.BUY)).isEqualByComparingTo("0")
        assertThat(netOnly.symbols()).containsExactly("X")
        assertThat(PendingOrderExposureProvider.NONE.orderCountFor(Side.SELL, "s")).isZero()
        assertThat(PendingOrderExposureProvider.NONE.symbolsFor("s")).isEmpty()
        assertThat(PendingOrderExposureProvider.NONE.quantityFor("X", Side.BUY, null)).isEqualByComparingTo("0")
    }
}
