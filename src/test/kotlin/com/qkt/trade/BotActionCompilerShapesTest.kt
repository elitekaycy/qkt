package com.qkt.trade

import com.qkt.common.Side
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.DirRel
import com.qkt.dsl.ast.DirSense
import com.qkt.dsl.ast.ExitRelativeLimit
import com.qkt.dsl.ast.Ioc
import com.qkt.dsl.ast.NumLit
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BotActionCompilerShapesTest : BotActionCompilerFixture() {
    private fun action(intent: BotIntent = intent()): BotAction = parseBotStrategy(renderBotStrategy(intent))

    private fun compileAction(bot: BotAction) = compileBotAction(bot, ctx, "bot-1", 1_000L, "manual")

    @Test
    fun `a stop entry compiles to a stop order at its trigger with exits priced from it`() {
        val stops = intent(side = Side.SELL, stop = "2640", sl = ExitSpec.By(BigDecimal("10")))
        val out = compile(stops.copy(tp = ExitSpec.By(BigDecimal("20"))))

        val bracket = out.request as OrderRequest.Bracket
        val entry = bracket.entry as OrderRequest.Stop
        assertThat(entry.stopPrice).isEqualByComparingTo("2640")
        assertThat(entry.side).isEqualTo(Side.SELL)
        assertThat(entry.strategyId).isEqualTo("manual")
        assertThat(out.stopLoss).isEqualByComparingTo("2650.00")
        assertThat(out.takeProfit).isEqualByComparingTo("2620.00")
    }

    @Test
    fun `a stop entry alone is the request itself`() {
        val out = compile(intent(stop = "2660"))

        assertThat(out.request).isInstanceOf(OrderRequest.Stop::class.java)
        assertThat(out.request.timeInForce).isEqualTo(TimeInForce.GTC)
    }

    @Test
    fun `a reward ratio take profit without a stop loss is refused`() {
        assertThatThrownBy { compile(intent(tp = ExitSpec.Rr(BigDecimal("2")))) }
            .hasMessage("RR take-profit requires a stop loss to derive the distance")
    }

    @Test
    fun `an exit relative entry has no one-shot price`() {
        val bot = action()
        val exitRelative = ExitRelativeLimit(DirRel(DirSense.WITH, NumLit(BigDecimal.ONE)))
        val relative = bot.copy(opts = bot.opts.copy(orderType = exitRelative))

        assertThatThrownBy { compileAction(relative) }.hasMessage("unsupported order type ExitRelativeLimit")
    }

    @Test
    fun `an armed trail take profit is not a one-shot exit`() {
        val bot = action()
        val bracket =
            BracketAst(
                stopLoss = ChildBy(NumLit(BigDecimal("10"))),
                takeProfit = ChildArmedTrail(NumLit(BigDecimal("5")), NumLit(BigDecimal("1"))),
            )

        assertThatThrownBy { compileAction(bot.copy(opts = bot.opts.copy(bracket = bracket))) }
            .hasMessage("exit form ChildArmedTrail is not supported one-shot")
    }

    @Test
    fun `an immediate or cancel time in force is refused one-shot`() {
        val bot = action()

        assertThatThrownBy { compileAction(bot.copy(opts = bot.opts.copy(tif = Ioc))) }
            .hasMessage("TIF Ioc is not supported one-shot; use GTC, DAY, or GTD")
    }

    @Test
    fun `an on fill child needs a persistent engine`() {
        val bot = action()

        assertThatThrownBy { compileAction(bot.copy(opts = bot.opts.copy(onFill = listOf(bot.action)))) }
            .hasMessageStartingWith("ON FILL need a persistent engine to manage")
    }

    @Test
    fun `a timed exit needs a persistent engine`() {
        val bot = action(BotIntent(Side.BUY, "EXNESS:XAUUSD", lots = BigDecimal.ONE, exitAfter = "4h"))

        assertThatThrownBy { compileAction(bot) }.hasMessageStartingWith("EXIT AFTER need a persistent engine")
    }

    @Test
    fun `a rule over an undeclared stream alias is refused`() {
        val source =
            "STRATEGY bot VERSION 1\n\nSYMBOLS\n    x = EXNESS:XAUUSD EVERY 1m\n\nRULES\n    WHEN true\n" +
                "    THEN BUY y SIZING 1\n"

        assertThatThrownBy { parseBotStrategy(source) }
            .hasMessage("bot strategy stream alias 'y' has no SYMBOLS declaration")
    }
}
