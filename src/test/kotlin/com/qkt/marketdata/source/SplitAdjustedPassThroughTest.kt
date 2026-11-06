package com.qkt.marketdata.source

import com.qkt.common.TradingCalendar
import com.qkt.instrument.CorporateAction
import com.qkt.instrument.CorporateActionSource
import com.qkt.instrument.SplitAdjustment
import com.qkt.marketdata.flow.FlowKind
import com.qkt.marketdata.flow.SideVolumes
import com.qkt.marketdata.flow.TradeFlow
import java.math.BigDecimal
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SplitAdjustedPassThroughTest {
    private val split = CorporateAction.Split(LocalDate.parse("2026-06-09"), BigDecimal(10))
    private val adjustment =
        SplitAdjustment.of(
            CorporateActionSource { if (it == "EXNESS:NVDA") listOf(split) else emptyList() },
            listOf("EXNESS:NVDA", "EXNESS:AAPL"),
            TradingCalendar.named("nyse[06:00-15:45]") ?: error("windowed nyse"),
            Long.MAX_VALUE,
        )
    private val flow: TradeFlow =
        object : TradeFlow {
            override fun window(
                symbol: String,
                kind: FlowKind,
                windowMs: Long,
                startMs: Long,
            ): SideVolumes? = null
        }
    private val probe = DelegationProbe(flow = flow)
    private val source = SplitAdjustedMarketSource(probe, adjustment)

    @Test
    fun `trade flow of a symbol without a split passes through`() {
        assertThat(source.tradeFlowFor("EXNESS:AAPL")).isSameAs(flow)
    }

    @Test
    fun `trade flow of a split symbol is refused as unadjusted`() {
        assertThatThrownBy { source.tradeFlowFor("EXNESS:NVDA") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("EXNESS:NVDA split")
            .hasMessageContaining("is not split-adjusted")
    }

    @Test
    fun `a split symbol without option marks reads as none`() {
        assertThat(source.optionMarksFor("EXNESS:NVDA")).isNull()
    }

    @Test
    fun `live ticks of symbols without a split come from the inner source`() {
        assertThat(source.liveTicks(listOf("EXNESS:AAPL"))).isSameAs(probe.feed)
        assertThat(probe.calls).containsExactly("liveTicks:[EXNESS:AAPL]")
    }
}
