package com.qkt.backtest

import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.time.Instant
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BacktestInputGuardsTest {
    private val idle =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) = Unit
        }

    @Test
    fun `candle-close sampling without a candle window is refused`() {
        assertThatThrownBy {
            Backtest(
                strategies = listOf("s" to idle),
                ticks = listOf(Tick("X", Money.of("1"), 1L)),
                cadence = SampleCadence.CANDLE_CLOSE,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("SampleCadence.CANDLE_CLOSE requires candleWindow")
    }

    @Test
    fun `a source with neither ticks nor bars cannot drive a backtest`() {
        val liveOnly =
            object : MarketSource {
                override val name = "live-only"
                override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

                override fun supports(symbol: String) = true
            }
        val request = MarketRequest(listOf("X"), Instant.EPOCH, Instant.ofEpochMilli(60_000))

        assertThatThrownBy {
            ReplayInputs.of(liveOnly, NoopInstrumentRegistry, request, TradingCalendar.crypto())
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Backtest requires a MarketSource with TICKS or BARS; live-only has [LIVE_TICKS]")
    }
}
