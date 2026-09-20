package com.qkt.trade

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BotActionCompilerTest : BotActionCompilerFixture() {
    @Test
    fun `compiles market buy with literal lots`() {
        val out = compile(intent())
        val req = out.request as OrderRequest.Market
        assertThat(req.side).isEqualTo(Side.BUY)
        assertThat(req.quantity).isEqualByComparingTo("0.5")
        assertThat(req.strategyId).isEqualTo("manual")
        assertThat(req.timeInForce).isEqualTo(TimeInForce.GTC)
        assertThat(out.stopLoss).isNull()
        assertThat(out.takeProfit).isNull()
    }

    @Test
    fun `rejects engine managed shapes fail closed`() {
        val trailing =
            """
            STRATEGY bot VERSION 1

            SYMBOLS
                x = EXNESS:XAUUSD EVERY 1m

            RULES
                WHEN true
                THEN BUY x SIZING 0.5 ORDER_TYPE = TRAILING BY 30
            """.trimIndent()
        assertThatThrownBy {
            compileBotAction(parseBotStrategy(trailing), ctx, "bot-1", 1_000L, "manual")
        }.hasMessageContaining("deploy")

        val armedTrail =
            """
            STRATEGY bot VERSION 1

            SYMBOLS
                x = EXNESS:XAUUSD EVERY 1m

            RULES
                WHEN true
                THEN BUY x SIZING 0.5 BRACKET { STOP LOSS TRAILING 30 AFTER MFE >= 10, TAKE PROFIT BY 60 }
            """.trimIndent()
        assertThatThrownBy {
            compileBotAction(parseBotStrategy(armedTrail), ctx, "bot-1", 1_000L, "manual")
        }.hasMessageContaining("deploy")
    }
}
