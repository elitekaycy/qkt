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

class TradingPipelineOcoEntryRearmTest {
    @Test
    fun `count-gated straddle does not re-arm after both legs fill`() {
        // The fix for the prod accumulation: gate on the TRUTHFUL count, not the net. Emit a
        // straddle once per session tick, only while flat by count. The old POSITION = 0 gate
        // re-armed every session because the two filled legs netted to 0; POSITION.count = 2
        // does not.
        val sessionTimes = setOf(100L, 200L)
        var n = 0
        val strategy =
            StraddleStrategy(
                shouldEmit = { tick, ctx ->
                    tick.timestamp in sessionTimes && ctx.positions.openCountFor(symbol) == 0
                },
                makeOco = {
                    n += 1
                    straddle("s$n")
                },
            )
        val h = harness(strategy)

        // Session 1: flat → arm one straddle, then both legs whipsaw-fill.
        h.bus.publish(TickEvent(Tick(symbol, BigDecimal("2000"), 100L)))
        fill(h.bus, "es1-A", Side.BUY, "2010")
        fill(h.bus, "es1-B", Side.SELL, "1990")
        assertThat(h.strategyPositions.openCountFor("alpha", symbol)).isEqualTo(2)

        // Session 2: count is 2, not 0 → the gate holds, no new straddle is armed.
        h.bus.publish(TickEvent(Tick(symbol, BigDecimal("2000"), 200L)))

        assertThat(h.ocoEmits).hasSize(1)
        assertThat(h.strategyPositions.openCountFor("alpha", symbol)).isEqualTo(2)
    }
}
