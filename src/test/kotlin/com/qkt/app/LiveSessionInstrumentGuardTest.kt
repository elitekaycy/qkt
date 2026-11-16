package com.qkt.app

import com.qkt.common.FixedClock
import com.qkt.common.TradingCalendar
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LiveSessionInstrumentGuardTest {
    private val blindRegistry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta? = null
        }
    private val source =
        object : MarketSource {
            override val name = "none"
            override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

            override fun supports(symbol: String) = true
        }
    private val strategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }

    @Test
    fun `a metals symbol the registry cannot size is refused at deploy rather than booked at contract size one`() {
        val session =
            LiveSession(
                strategies = listOf("alpha" to strategy),
                source = source,
                symbols = listOf("EXNESS:XAUUSD"),
                clock = FixedClock(0L),
                calendar = TradingCalendar.crypto(),
                instrumentRegistry = blindRegistry,
            )

        assertThatThrownBy { session.start() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("InstrumentMeta unresolvable for EXNESS:XAUUSD at deploy")
    }
}
