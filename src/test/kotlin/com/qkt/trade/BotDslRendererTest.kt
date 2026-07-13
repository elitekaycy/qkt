package com.qkt.trade

import com.qkt.common.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BotDslRendererTest {
    @Test
    fun `renders market buy with literal lots`() {
        val intent =
            BotIntent(
                side = Side.BUY,
                qktSymbol = "EXNESS:XAUUSD",
                lots = BigDecimal("0.5"),
            )
        assertThat(renderBotStrategy(intent)).isEqualTo(
            """
            STRATEGY bot VERSION 1

            SYMBOLS
                x = EXNESS:XAUUSD EVERY 1m

            RULES
                WHEN true
                THEN BUY x SIZING 0.5
            """.trimIndent() + "\n",
        )
    }

    @Test
    fun `renders bracket with by stop and rr take profit`() {
        val intent =
            BotIntent(
                side = Side.BUY,
                qktSymbol = "EXNESS:XAUUSD",
                lots = BigDecimal("0.5"),
                sl = ExitSpec.By(BigDecimal("30")),
                tp = ExitSpec.Rr(BigDecimal("2")),
            )
        assertThat(renderBotStrategy(intent)).contains(
            "THEN BUY x SIZING 0.5 BRACKET { STOP LOSS BY 30, TAKE PROFIT RR 2 }",
        )
    }

    @Test
    fun `renders limit sell with day tif and at exits`() {
        val intent =
            BotIntent(
                side = Side.SELL,
                qktSymbol = "EXNESS:XAUUSD",
                lots = BigDecimal("0.2"),
                limitPrice = BigDecimal("2680"),
                tif = BotTif.DAY,
                sl = ExitSpec.At(BigDecimal("2710")),
                tp = ExitSpec.Pct(BigDecimal("1.5")),
            )
        assertThat(renderBotStrategy(intent)).contains(
            "THEN SELL x SIZING 0.2 ORDER_TYPE = LIMIT AT 2680 TIF DAY " +
                "BRACKET { STOP LOSS AT 2710, TAKE PROFIT PCT 1.5 }",
        )
    }

    @Test
    fun `renders stop limit entry and gtd expiry as epoch millis`() {
        val intent =
            BotIntent(
                side = Side.BUY,
                qktSymbol = "EXNESS:XAUUSD",
                lots = BigDecimal("0.1"),
                stopPrice = BigDecimal("2700"),
                stopLimitPrice = BigDecimal("2701"),
                tif = BotTif.GTD,
                expiresAtMs = 1752403200000L,
            )
        assertThat(renderBotStrategy(intent)).contains(
            "ORDER_TYPE = STOP AT 2700 LIMIT AT 2701 TIF GTD 1752403200000",
        )
    }

    @Test
    fun `renders dsl sizing text verbatim`() {
        val intent =
            BotIntent(
                side = Side.BUY,
                qktSymbol = "EXNESS:XAUUSD",
                sizingDsl = "2 % OF EQUITY",
            )
        assertThat(renderBotStrategy(intent)).contains("THEN BUY x SIZING 2 % OF EQUITY")
    }

    @Test
    fun `parses exit specs from cli syntax`() {
        assertThat(ExitSpec.parse("2610", allowRr = false)).isEqualTo(ExitSpec.At(BigDecimal("2610")))
        assertThat(ExitSpec.parse("at:2610", allowRr = false)).isEqualTo(ExitSpec.At(BigDecimal("2610")))
        assertThat(ExitSpec.parse("by:30", allowRr = false)).isEqualTo(ExitSpec.By(BigDecimal("30")))
        assertThat(ExitSpec.parse("pct:1.5", allowRr = false)).isEqualTo(ExitSpec.Pct(BigDecimal("1.5")))
        assertThat(ExitSpec.parse("rr:2", allowRr = true)).isEqualTo(ExitSpec.Rr(BigDecimal("2")))
    }

    @Test
    fun `rejects rr for stop loss and malformed specs`() {
        assertThatThrownBy { ExitSpec.parse("rr:2", allowRr = false) }
            .hasMessageContaining("RR")
        assertThatThrownBy { ExitSpec.parse("banana", allowRr = false) }
            .hasMessageContaining("banana")
    }

    @Test
    fun `requires exactly one sizing source`() {
        assertThatThrownBy {
            BotIntent(side = Side.BUY, qktSymbol = "EXNESS:XAUUSD")
        }.hasMessageContaining("sizing")
        assertThatThrownBy {
            BotIntent(
                side = Side.BUY,
                qktSymbol = "EXNESS:XAUUSD",
                lots = BigDecimal.ONE,
                sizingDsl = "1 % OF EQUITY",
            )
        }.hasMessageContaining("sizing")
    }

    @Test
    fun `requires expiry for gtd and rejects stop limit without stop`() {
        assertThatThrownBy {
            BotIntent(
                side = Side.BUY,
                qktSymbol = "EXNESS:XAUUSD",
                lots = BigDecimal.ONE,
                tif = BotTif.GTD,
            )
        }.hasMessageContaining("GTD")
        assertThatThrownBy {
            BotIntent(
                side = Side.BUY,
                qktSymbol = "EXNESS:XAUUSD",
                lots = BigDecimal.ONE,
                stopLimitPrice = BigDecimal("2701"),
            )
        }.hasMessageContaining("stop")
    }
}
