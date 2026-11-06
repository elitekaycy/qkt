package com.qkt.observe.insights

import com.qkt.common.Side
import com.qkt.execution.Trade
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InsightsTranslateDefaultsTest {
    @Test
    fun `a strategy start without metadata carries only its id and time`() {
        val env = InsightsTranslate.strategyStarted("alpha", 5L)

        assertThat(env.type).isEqualTo("strategy.started")
        assertThat(env.payload).containsExactlyEntriesOf(mapOf("strategyId" to "alpha", "ts" to 5L))
    }

    @Test
    fun `a closed trade without a conversion leaves the currency fields empty`() {
        val trade = Trade("o-1", "XAUUSD", BigDecimal("4700"), BigDecimal("0.1"), Side.SELL, 9L)

        val env = InsightsTranslate.tradeClosed(trade, BigDecimal("12.5"), "alpha")

        assertThat(env.id).isEqualTo("tc-o-1-9")
        assertThat(env.payload)
            .containsEntry("realized", BigDecimal("12.5"))
            .containsEntry("accountCurrency", null)
            .containsEntry("costsAccount", null)
    }
}
