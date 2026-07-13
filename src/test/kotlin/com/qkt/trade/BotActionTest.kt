package com.qkt.trade

import com.qkt.common.Side
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeQty
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BotActionTest {
    @Test
    fun `parses rendered market buy into bot action`() {
        val intent =
            BotIntent(side = Side.BUY, qktSymbol = "EXNESS:XAUUSD", lots = BigDecimal("0.5"))
        val bot = parseBotStrategy(renderBotStrategy(intent))
        assertThat(bot.qktSymbol).isEqualTo("EXNESS:XAUUSD")
        assertThat(bot.action).isInstanceOf(Buy::class.java)
        assertThat((bot.opts.sizing as SizeQty)).isNotNull
        assertThat(bot.sha256).hasSize(64)
        assertThat(bot.source).contains("THEN BUY x SIZING 0.5")
    }

    @Test
    fun `parses bracket clauses into child price asts`() {
        val intent =
            BotIntent(
                side = Side.SELL,
                qktSymbol = "EXNESS:XAUUSD",
                lots = BigDecimal("0.2"),
                sl = ExitSpec.By(BigDecimal("30")),
                tp = ExitSpec.Rr(BigDecimal("2")),
            )
        val bot = parseBotStrategy(renderBotStrategy(intent))
        assertThat(bot.action).isInstanceOf(Sell::class.java)
        assertThat(bot.opts.bracket?.stopLoss).isInstanceOf(ChildBy::class.java)
        assertThat(bot.opts.bracket?.takeProfit).isInstanceOf(ChildRr::class.java)
    }

    @Test
    fun `rejects malformed source with parse errors`() {
        assertThatThrownBy { parseBotStrategy("STRATEGY broken VERSION") }
            .hasMessageContaining("parse")
    }

    @Test
    fun `rejects multi rule strategies`() {
        val source =
            """
            STRATEGY bot VERSION 1

            SYMBOLS
                x = EXNESS:XAUUSD EVERY 1m

            RULES
                WHEN true
                THEN BUY x SIZING 0.5
                WHEN true
                THEN SELL x SIZING 0.5
            """.trimIndent()
        assertThatThrownBy { parseBotStrategy(source) }
            .hasMessageContaining("exactly one")
    }

    @Test
    fun `rejects non entry actions`() {
        val source =
            """
            STRATEGY bot VERSION 1

            SYMBOLS
                x = EXNESS:XAUUSD EVERY 1m

            RULES
                WHEN true
                THEN CLOSE x
            """.trimIndent()
        assertThatThrownBy { parseBotStrategy(source) }
            .hasMessageContaining("BUY or SELL")
    }
}
