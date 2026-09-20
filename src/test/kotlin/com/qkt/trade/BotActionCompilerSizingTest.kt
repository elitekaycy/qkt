package com.qkt.trade

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BotActionCompilerSizingTest : BotActionCompilerFixture() {
    @Test
    fun `percent equity sizing converts through contract size`() {
        assertThatThrownBy { compile(intent(lots = null, sizingDsl = "2 % OF EQUITY")) }
            .hasMessageContaining("minimum")
        val bigger =
            compile(intent(lots = null, sizingDsl = "2 % OF EQUITY"), ctx.copy(equity = BigDecimal("10000000")))
        assertThat(bigger.request.quantity).isEqualByComparingTo("0.75")
    }

    @Test
    fun `risk sizing uses stop distance`() {
        val out =
            compile(
                intent(
                    lots = null,
                    sizingDsl = "RISK 0.01",
                    sl = ExitSpec.By(BigDecimal("30")),
                    tp = ExitSpec.By(BigDecimal("60")),
                ),
            )
        assertThat(out.request.quantity).isEqualByComparingTo("0.03")
    }

    @Test
    fun `quantizes volume to step and rejects below minimum`() {
        val out = compile(intent(lots = "0.519"))
        assertThat(out.request.quantity).isEqualByComparingTo("0.51")
        assertThatThrownBy { compile(intent(lots = "0.001")) }
            .hasMessageContaining("minimum")
    }

    @Test
    fun `rejects risk sizing on quote account currency mismatch`() {
        assertThatThrownBy {
            compile(
                intent(lots = null, sizingDsl = "RISK 0.01", sl = ExitSpec.By(BigDecimal("30"))),
                ctx.copy(quoteCurrency = "JPY"),
            )
        }.hasMessageContaining("currency")
    }

    @Test
    fun `rejects risk sizing without stop loss`() {
        assertThatThrownBy { compile(intent(lots = null, sizingDsl = "RISK 0.01")) }
            .hasMessageContaining("stop")
    }
}
