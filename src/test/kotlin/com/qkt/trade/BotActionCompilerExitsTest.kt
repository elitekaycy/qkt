package com.qkt.trade

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BotActionCompilerExitsTest : BotActionCompilerFixture() {
    @Test
    fun `bracket by distances use sided entry and sign math`() {
        val buy = compile(intent(sl = ExitSpec.By(BigDecimal("30")), tp = ExitSpec.By(BigDecimal("60"))))
        val bracket = buy.request as OrderRequest.Bracket
        assertThat((bracket.stopLoss as StopLossSpec.Fixed).price).isEqualByComparingTo("2620.50")
        assertThat(bracket.takeProfit).isEqualByComparingTo("2710.50")

        val sell =
            compile(
                intent(side = Side.SELL, sl = ExitSpec.By(BigDecimal("30")), tp = ExitSpec.By(BigDecimal("60"))),
            )
        val sellBracket = sell.request as OrderRequest.Bracket
        assertThat((sellBracket.stopLoss as StopLossSpec.Fixed).price).isEqualByComparingTo("2680.00")
        assertThat(sellBracket.takeProfit).isEqualByComparingTo("2590.00")
    }

    @Test
    fun `rr take profit derives from stop distance`() {
        val out = compile(intent(sl = ExitSpec.By(BigDecimal("30")), tp = ExitSpec.Rr(BigDecimal("2"))))
        val bracket = out.request as OrderRequest.Bracket
        assertThat(bracket.takeProfit).isEqualByComparingTo("2710.50")
    }

    @Test
    fun `pct exits scale from entry price`() {
        val out =
            compile(
                intent(
                    side = Side.SELL,
                    lots = "1",
                    sl = ExitSpec.Pct(BigDecimal("1")),
                    tp = ExitSpec.Pct(BigDecimal("2")),
                ),
            )
        val bracket = out.request as OrderRequest.Bracket
        assertThat((bracket.stopLoss as StopLossSpec.Fixed).price).isEqualByComparingTo("2676.50")
        assertThat(bracket.takeProfit).isEqualByComparingTo("2597.00")
    }

    @Test
    fun `pct exits reject invalid percentages consistently`() {
        assertThatThrownBy {
            compile(intent(sl = ExitSpec.Pct(BigDecimal("0"))))
        }.hasMessageContaining("greater than 0")
        assertThatThrownBy {
            compile(intent(sl = ExitSpec.Pct(BigDecimal("0.004"))))
        }.hasMessageContaining("minimum 0.01 percentage points")
            .hasMessageContaining("PCT uses percentage points")
        assertThatThrownBy {
            compile(intent(sl = ExitSpec.Pct(BigDecimal("50"))))
        }.hasMessageContaining("less than 50")
    }

    @Test
    fun `single exit rides alongside a plain request`() {
        val out = compile(intent(tp = ExitSpec.At(BigDecimal("2700"))))
        assertThat(out.request).isInstanceOf(OrderRequest.Market::class.java)
        assertThat(out.takeProfit).isEqualByComparingTo("2700")
        assertThat(out.stopLoss).isNull()
    }

    @Test
    fun `limit entry prices exits from the limit price`() {
        val out =
            compile(
                intent(
                    limit = "2600",
                    sl = ExitSpec.By(BigDecimal("10")),
                    tp = ExitSpec.By(BigDecimal("20")),
                    tif = BotTif.DAY,
                ),
            )
        val bracket = out.request as OrderRequest.Bracket
        assertThat(bracket.entry).isInstanceOf(OrderRequest.Limit::class.java)
        assertThat((bracket.stopLoss as StopLossSpec.Fixed).price).isEqualByComparingTo("2590")
        assertThat(bracket.takeProfit).isEqualByComparingTo("2620")
        assertThat(bracket.timeInForce).isEqualTo(TimeInForce.DAY)
        assertThat(bracket.expiresAt).isEqualTo(86_400_000L)
    }

    @Test
    fun `stop limit entry compiles with gtd expiry`() {
        val out = compile(intent(stop = "2700", stopLimit = "2701", tif = BotTif.GTD, expiresAtMs = 9_999L))
        val req = out.request as OrderRequest.StopLimit
        assertThat(req.stopPrice).isEqualByComparingTo("2700")
        assertThat(req.limitPrice).isEqualByComparingTo("2701")
        assertThat(req.expiresAt).isEqualTo(9_999L)
        assertThat(req.timeInForce).isEqualTo(TimeInForce.GTD)
    }
}
