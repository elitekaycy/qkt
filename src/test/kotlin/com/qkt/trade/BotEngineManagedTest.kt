package com.qkt.trade

import com.qkt.common.Side
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.ExitHooksAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.OcoAst
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackAtClause
import com.qkt.dsl.ast.TrailingPct
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Each shape a one-shot command cannot manage is refused by its own name. */
class BotEngineManagedTest {
    private val bot = parseBotStrategy(renderBotStrategy(BotIntent(Side.BUY, "EXNESS:XAUUSD", lots = BigDecimal.ONE)))
    private val one = NumLit(BigDecimal.ONE)

    private fun refusal(opts: ActionOpts) = assertThatThrownBy { rejectEngineManaged(bot.copy(opts = opts)) }

    @Test
    fun `a plain entry needs no engine`() {
        assertThatCode { rejectEngineManaged(bot) }.doesNotThrowAnyException()
    }

    @Test
    fun `a percent trailing entry is refused`() {
        refusal(bot.opts.copy(orderType = TrailingPct(one))).hasMessageStartingWith("TRAILING entries need")
    }

    @Test
    fun `a stack is refused`() {
        val source = renderBotAction(mapOf("x" to "EXNESS:XAUUSD"), "BUY x SIZING 0.1 STACK 3 SPACING 100")
        val stacked = parseBotStrategy(source)

        assertThatThrownBy { rejectEngineManaged(stacked) }.hasMessageStartingWith("STACK need")
    }

    @Test
    fun `a stack at tier is refused`() {
        val tier = StackAtClause(one, DurationAst(60_000L), SizeQty(one), BracketAst(stopLoss = ChildBy(one)))

        refusal(bot.opts.copy(stackAts = listOf(tier))).hasMessageStartingWith("STACK_AT need")
    }

    @Test
    fun `an oco is refused`() {
        refusal(bot.opts.copy(oco = OcoAst(ChildAt(one), ChildAt(one)))).hasMessageStartingWith("OCO need")
    }

    @Test
    fun `exit hooks are refused`() {
        refusal(bot.opts.copy(exitHooks = ExitHooksAst(onClose = listOf(bot.action))))
            .hasMessageStartingWith("exit hooks need")
    }
}
