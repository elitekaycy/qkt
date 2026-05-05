package com.qkt.strategy

import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrategyContextBridgeTest {
    private val tick = Tick("X", Money.of("100"), 0L)
    private val ctx =
        SessionContext(
            mode = Mode.BACKTEST,
            clock = FixedClock(time = 0L),
            calendar = TradingCalendar.crypto(),
            source =
                object : MarketSource {
                    override val name = "Empty"
                    override val capabilities = emptySet<MarketSourceCapability>()

                    override fun supports(symbol: String): Boolean = false
                },
        )

    @Test
    fun `default onTickWithContext routes to onTick`() {
        var called = 0
        val s =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    emit: (Signal) -> Unit,
                ) {
                    called++
                }
            }
        s.onTickWithContext(tick, ctx) {}
        assertThat(called).isEqualTo(1)
    }

    @Test
    fun `overriding onTickWithContext uses the context`() {
        val seenModes = mutableListOf<Mode>()
        val s =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    emit: (Signal) -> Unit,
                ): Unit = throw IllegalStateException("should not be called when context override is present")

                override fun onTickWithContext(
                    tick: Tick,
                    ctx: SessionContext,
                    emit: (Signal) -> Unit,
                ) {
                    seenModes.add(ctx.mode)
                }
            }
        s.onTickWithContext(tick, ctx) {}
        assertThat(seenModes).containsExactly(Mode.BACKTEST)
    }
}
