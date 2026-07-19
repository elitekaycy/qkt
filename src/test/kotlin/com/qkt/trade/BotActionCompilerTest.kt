package com.qkt.trade

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BotActionCompilerTest {
    private val ctx =
        BotQuoteContext(
            bid = BigDecimal("2650.00"),
            ask = BigDecimal("2650.50"),
            equity = BigDecimal("10000"),
            balance = BigDecimal("9800"),
            contractSize = BigDecimal("100"),
            accountCurrency = "USD",
            quoteCurrency = "USD",
            volumeMin = BigDecimal("0.01"),
            volumeStep = BigDecimal("0.01"),
            volumeMax = BigDecimal("50"),
            digits = 2,
        )

    private fun compile(
        intent: BotIntent,
        context: BotQuoteContext = ctx,
    ): CompiledBotOrder =
        compileBotAction(
            bot = parseBotStrategy(renderBotStrategy(intent)),
            ctx = context,
            id = "bot-1",
            timestamp = 1_000L,
            strategyId = "manual",
        )

    private fun intent(
        side: Side = Side.BUY,
        lots: String? = "0.5",
        sizingDsl: String? = null,
        limit: String? = null,
        stop: String? = null,
        stopLimit: String? = null,
        sl: ExitSpec? = null,
        tp: ExitSpec? = null,
        tif: BotTif = BotTif.GTC,
        expiresAtMs: Long? = null,
    ) = BotIntent(
        side = side,
        qktSymbol = "EXNESS:XAUUSD",
        lots = lots?.let { BigDecimal(it) },
        sizingDsl = sizingDsl,
        limitPrice = limit?.let { BigDecimal(it) },
        stopPrice = stop?.let { BigDecimal(it) },
        stopLimitPrice = stopLimit?.let { BigDecimal(it) },
        sl = sl,
        tp = tp,
        tif = tif,
        expiresAtMs = expiresAtMs,
    )

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
