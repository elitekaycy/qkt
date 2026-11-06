package com.qkt.research

import com.qkt.common.Money
import com.qkt.positions.Position
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TapeRendererEventsTest {
    private fun footer(
        positions: Map<String, Position>,
        exhausted: Boolean,
    ) = Footer(0L, 2, 0, BigDecimal("100"), positions, exhausted)

    @Test
    fun `renders rejections, signals, notices, open positions and the end marker`() {
        val result =
            StepResult(
                tape =
                    listOf(
                        TapeEvent.SignalEmitted(0L, Signal.Buy("X", Money.of("1"))),
                        TapeEvent.Rejected(60_000L, "X", "insufficient margin"),
                    ),
                footer = footer(mapOf("X" to Position("X", BigDecimal("2"), BigDecimal("10"))), exhausted = true),
                notice = "replay reset to start",
            )

        val lines = TapeRenderer.render(result).lines()

        assertThat(lines[0]).isEqualTo("  1970-01-01T00:00:00Z  SIGNAL Buy")
        assertThat(lines[1]).isEqualTo("  1970-01-01T00:01:00Z  REJECT X (insufficient margin)")
        assertThat(lines[2]).isEqualTo("replay reset to start")
        assertThat(lines[3]).contains("pos X 2").endsWith("equity 100 [end]")
    }

    @Test
    fun `a flat unfinished replay prints flat without the end marker`() {
        val out = TapeRenderer.render(StepResult(tape = emptyList(), footer = footer(emptyMap(), exhausted = false)))

        assertThat(out).contains("pos flat").doesNotContain("[end]")
    }
}
