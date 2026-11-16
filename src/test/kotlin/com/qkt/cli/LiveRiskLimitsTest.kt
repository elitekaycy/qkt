package com.qkt.cli

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.risk.SetCaps
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LiveRiskLimitsTest {
    private val streams =
        (
            Dsl.parse(
                """
                STRATEGY rotation VERSION 1
                SYMBOLS
                    stocks = ALPACA:{AAPL, MSFT} EVERY 1h
                    spy = ALPACA:SPY EVERY 1h
                RULES
                    FOR EACH m IN stocks DO WHEN m.close > spy.close THEN BUY m SIZING 1
                """.trimIndent(),
            ) as ParseResult.Success
        ).value.streams

    @Test
    fun `a strategy with no risk entry has no limits`() {
        assertThat(liveRiskLimits("rotation", null, streams)).isNull()
    }

    @Test
    fun `a live session's set caps are bound to the members the strategy declares, beside its other limits`() {
        val risk = PerStrategyRisk(maxOpenPositions = 4, sets = mapOf("stocks" to SetCaps(maxOpenPositions = 1)))

        val limits = liveRiskLimits("rotation", risk, streams)!!

        assertThat(limits.maxOpenPositions).isEqualTo(4)
        val stocks = limits.sets.getValue("stocks")
        assertThat(stocks.members).containsExactlyInAnyOrder("ALPACA:AAPL", "ALPACA:MSFT")
        assertThat(stocks.maxOpenPositions).isEqualTo(1)
    }

    @Test
    fun `a cap on a set the strategy does not declare refuses the start by name`() {
        val risk = PerStrategyRisk(sets = mapOf("fx" to SetCaps(maxOpenPositions = 1)))

        assertThatThrownBy { liveRiskLimits("rotation", risk, streams) }
            .hasMessage("risk.per_strategy.rotation.sets.fx: the strategy declares no symbol set 'fx'")
    }

    @Test
    fun `a set cap on a strategy that declares no sets, such as a bot identity, is refused, not ignored`() {
        val risk = PerStrategyRisk(maxTradesPerDay = 3, sets = mapOf("stocks" to SetCaps(maxOpenPositions = 1)))

        assertThat(liveRiskLimits("manual", PerStrategyRisk(maxTradesPerDay = 3), emptyList())!!.maxTradesPerDay)
            .isEqualTo(3)
        assertThatThrownBy { liveRiskLimits("manual", risk, emptyList()) }
            .hasMessage("risk.per_strategy.manual.sets.stocks: the strategy declares no symbol set 'stocks'")
    }
}
