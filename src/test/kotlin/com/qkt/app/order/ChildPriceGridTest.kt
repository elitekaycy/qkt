package com.qkt.app.order

import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.NumLit
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ChildPriceGridTest {
    private val point = BigDecimal("0.00001")

    @Test
    fun `a level between two ticks goes to the nearest one`() {
        assertThat(snapToTick(BigDecimal("1.160053"), point, BigDecimal("1.161"))).isEqualByComparingTo("1.16005")
        assertThat(snapToTick(BigDecimal("1.160057"), point, BigDecimal("1.161"))).isEqualByComparingTo("1.16006")
    }

    @Test
    fun `an exact half goes toward the entry, so a stop never risks more and a target never promises more`() {
        val entry = BigDecimal("1.161055")
        // A long's stop below the entry rounds up, its target above rounds down.
        assertThat(snapToTick(BigDecimal("1.160055"), point, entry)).isEqualByComparingTo("1.16006")
        assertThat(snapToTick(BigDecimal("1.163055"), point, entry)).isEqualByComparingTo("1.16305")
    }

    @Test
    fun `a sub-tick distance keeps the exit one tick on its own side of the entry`() {
        val entry = BigDecimal("1.16105")
        assertThat(snapToTick(BigDecimal("1.161052"), point, entry)).isEqualByComparingTo("1.16106")
        assertThat(snapToTick(BigDecimal("1.161048"), point, entry)).isEqualByComparingTo("1.16104")
    }

    @Test
    fun `a level already on the grid, or a symbol with no known tick, is left alone`() {
        assertThat(snapToTick(BigDecimal("1.16005"), point, BigDecimal("1.16105"))).isEqualByComparingTo("1.16005")
        assertThat(snapToTick(BigDecimal("1.160055"), null, BigDecimal("1.161055"))).isEqualByComparingTo("1.160055")
    }

    @Test
    fun `a half-point paper fill gives venue-valid bracket prices`() {
        // An MT5 quote's last is the bid/ask mid: 1.16105 / 1.16106 fills a paper market BUY at 1.161055.
        val fill = BigDecimal("1.161055")
        val by = ChildBy(NumLit(BigDecimal("0.001")))
        val stop = computeChildPrice(by, Side.BUY, fill, isStopLoss = true, tick = point)
        val target = computeChildPrice(ChildBy(NumLit(BigDecimal("0.002"))), Side.BUY, fill, false, tick = point)
        assertThat(stop).isEqualByComparingTo("1.16006")
        assertThat(target).isEqualByComparingTo("1.16305")
        // The same entry on the grid is unchanged by the rounding.
        assertThat(computeChildPrice(by, Side.BUY, BigDecimal("1.16105"), true, tick = point))
            .isEqualByComparingTo("1.16005")
    }

    @Test
    fun `a short's percentage stop and target land on the grid too`() {
        val fill = BigDecimal("1.16105")
        val stop = computeChildPrice(ChildPct(NumLit(BigDecimal("0.1"))), Side.SELL, fill, true, tick = point)
        // 1.16105 x 1.001 = 1.16221105: nearest tick is 1.16221.
        assertThat(stop).isEqualByComparingTo("1.16221")
    }

    @Test
    fun `an exit OCO built on a fill carries prices on the symbol's grid`() {
        val exits = BracketExits(ObservedPrices(MarketPriceTracker()), FixedClock(0L), { point })
        val entry =
            OrderRequest.Market("e1", "MT5:EURUSD", Side.BUY, BigDecimal("0.1"), TimeInForce.GTC, 0L)
        val bracket =
            OrderRequest.Bracket(
                id = "b1",
                symbol = "MT5:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                entry = entry,
                takeProfit = BigDecimal("1.163"),
                stopLoss = StopLossSpec.Fixed(BigDecimal("1.16")),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                takeProfitAst = ChildBy(NumLit(BigDecimal("0.002"))),
                stopLossAst = ChildBy(NumLit(BigDecimal("0.001"))),
            )

        val oco = exits.exitOco(bracket, BigDecimal("1.161055"), BigDecimal("0.1"))

        assertThat((oco.leg1 as OrderRequest.Limit).limitPrice).isEqualByComparingTo("1.16305")
        assertThat((oco.leg2 as OrderRequest.Stop).stopPrice).isEqualByComparingTo("1.16006")
    }
}
