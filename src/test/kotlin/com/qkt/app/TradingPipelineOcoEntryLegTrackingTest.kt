package com.qkt.app

import com.qkt.app.TradingPipelineOcoEntryFixtures.StraddleStrategy
import com.qkt.app.TradingPipelineOcoEntryFixtures.fill
import com.qkt.app.TradingPipelineOcoEntryFixtures.harness
import com.qkt.app.TradingPipelineOcoEntryFixtures.straddle
import com.qkt.app.TradingPipelineOcoEntryFixtures.symbol
import com.qkt.common.Side
import com.qkt.events.TickEvent
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TradingPipelineOcoEntryLegTrackingTest {
    @Test
    fun `OCO_ENTRY straddle tracks both filled legs as independent positions`() {
        val h = harness(StraddleStrategy(shouldEmit = { _, _ -> true }, makeOco = { straddle("1") }))

        // A tick triggers the strategy to emit the OCO_ENTRY → registers the independent-leg opens.
        h.bus.publish(TickEvent(Tick(symbol, BigDecimal("2000"), 100L)))
        // Both straddle entries fill — price whipsawed through both stops.
        fill(h.bus, "e1-A", Side.BUY, "2010")
        fill(h.bus, "e1-B", Side.SELL, "1990")

        // Two real positions — not netted to zero. This is the truthful view.
        assertThat(h.strategyPositions.openCountFor("alpha", symbol)).isEqualTo(2)
        assertThat(h.strategyPositions.longCountFor("alpha", symbol)).isEqualTo(1)
        assertThat(h.strategyPositions.shortCountFor("alpha", symbol)).isEqualTo(1)
        // The net view still reads 0 — back-compat for everything reading POSITION.quantity.
        assertThat(h.strategyPositions.positionFor("alpha", symbol)?.quantity).isEqualByComparingTo("0")
    }

    @Test
    fun `a straddle leg that fills after its sibling cancelled it still opens its own leg`() {
        // Reproduction of #1098: leg A fills, the OCO cancels leg B, then B fills anyway on a
        // whipsaw. B's fill must open its INDEPENDENT leg, not net into a PRIMARY.
        val h = harness(StraddleStrategy(shouldEmit = { _, _ -> true }, makeOco = { straddle("1") }))
        h.bus.publish(TickEvent(Tick(symbol, BigDecimal("2000"), 100L)))
        fill(h.bus, "e1-A", Side.BUY, "2010")
        fill(h.bus, "e1-B", Side.SELL, "1990")

        val legs = h.strategyPositions.legBookFor("alpha", symbol)!!.all()
        assertThat(legs).hasSize(2)
        assertThat(legs.map { it.role }).containsOnly(com.qkt.positions.LegRole.INDEPENDENT)
        assertThat(legs.map { it.legId }).containsExactlyInAnyOrder("b1-A", "b1-B")
    }
}
