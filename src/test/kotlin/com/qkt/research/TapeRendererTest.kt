package com.qkt.research

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.Trade
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class TapeRendererTest {
    private val footer =
        Footer(
            timestamp = 60_000L,
            barsClosed = 1,
            tradeCount = 1,
            equity = BigDecimal("10015.00"),
            openPositions = emptyMap(),
            exhausted = false,
        )

    @Test
    fun `renders a fill line and a footer`() {
        val fill =
            TapeEvent.Filled(
                timestamp = 60_000L,
                trade = Trade("o1", "X", Money.of("100"), Money.of("1"), Side.BUY, 60_000L),
                realized = Money.of("0"),
                strategyId = "s1",
            )
        val out = TapeRenderer.render(StepResult(tape = listOf(fill), footer = footer))
        assertThat(out).contains("FILL")
        assertThat(out).contains("BUY")
        assertThat(out).contains("equity 10015.00")
    }

    @Test
    fun `renders reload errors`() {
        val r =
            StepResult(
                tape = emptyList(),
                footer = footer,
                reloadErrors = listOf(com.qkt.dsl.parse.ParseError(2, 5, "unexpected token")),
            )
        val out = TapeRenderer.render(r)
        assertThat(out).contains("2:5")
        assertThat(out).contains("unexpected token")
    }
}
