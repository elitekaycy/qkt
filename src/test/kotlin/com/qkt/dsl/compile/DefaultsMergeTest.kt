package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.DefaultsBlock
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.Gtc
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.StackAtClause
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackSpacing
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DefaultsMergeTest {
    @Test
    fun `null defaults leaves action unchanged`() {
        val action = Buy("btc", ActionOpts(sizing = SizeQty(NumLit(BigDecimal.ONE))))
        assertThat(mergeDefaults(action, null)).isEqualTo(action)
    }

    @Test
    fun `defaults fill missing sizing`() {
        val action = Buy("btc", ActionOpts())
        val defaults = DefaultsBlock(sizing = SizeRiskFrac(NumLit(BigDecimal("0.01"))))
        val merged = mergeDefaults(action, defaults) as Buy
        assertThat(merged.opts.sizing).isInstanceOf(SizeRiskFrac::class.java)
    }

    @Test
    fun `action sizing overrides defaults sizing`() {
        val action = Buy("btc", ActionOpts(sizing = SizeQty(NumLit(BigDecimal("3")))))
        val defaults = DefaultsBlock(sizing = SizeRiskFrac(NumLit(BigDecimal("0.01"))))
        val merged = mergeDefaults(action, defaults) as Buy
        assertThat(merged.opts.sizing).isInstanceOf(SizeQty::class.java)
    }

    @Test
    fun `defaults stopLoss and takeProfit build implicit BRACKET when action has none`() {
        val action = Buy("btc", ActionOpts(sizing = SizeQty(NumLit(BigDecimal.ONE))))
        val defaults =
            DefaultsBlock(
                stopLoss = ChildBy(NumLit(BigDecimal("5"))),
                takeProfit = ChildRr(NumLit(BigDecimal("3"))),
            )
        val merged = mergeDefaults(action, defaults) as Buy
        assertThat(merged.opts.bracket).isNotNull
        assertThat(merged.opts.bracket!!.stopLoss).isInstanceOf(ChildBy::class.java)
        assertThat(merged.opts.bracket!!.takeProfit).isInstanceOf(ChildRr::class.java)
    }

    @Test
    fun `defaults fill missing bracket child`() {
        val action =
            Buy(
                "btc",
                ActionOpts(
                    sizing = SizeQty(NumLit(BigDecimal.ONE)),
                    bracket = BracketAst(stopLoss = ChildBy(NumLit(BigDecimal("10"))), takeProfit = null),
                ),
            )
        val defaults = DefaultsBlock(takeProfit = ChildRr(NumLit(BigDecimal("3"))))
        val merged = mergeDefaults(action, defaults) as Buy
        assertThat(merged.opts.bracket!!.takeProfit).isInstanceOf(ChildRr::class.java)
        assertThat(merged.opts.bracket!!.stopLoss).isInstanceOf(ChildBy::class.java)
    }

    @Test
    fun `defaults TIF and orderType fill if missing`() {
        val action = Buy("btc", ActionOpts(sizing = SizeQty(NumLit(BigDecimal.ONE))))
        val defaults =
            DefaultsBlock(
                tif = Gtc,
                orderType = Limit(NumLit(BigDecimal("99"))),
            )
        val merged = mergeDefaults(action, defaults) as Buy
        assertThat(merged.opts.tif).isEqualTo(Gtc)
        assertThat(merged.opts.orderType).isInstanceOf(Limit::class.java)
    }

    @Test
    fun `merging defaults preserves the action's STACK`() {
        val stack =
            StackSpacing(count = 2, spacing = NumLit(BigDecimal.ONE), direction = StackDirection.TRADE_DIRECTION)
        val action = Buy("btc", ActionOpts(sizing = SizeQty(NumLit(BigDecimal.ONE)), stack = stack))
        val merged = mergeDefaults(action, DefaultsBlock(tif = Gtc)) as Buy
        assertThat(merged.opts.stack).isEqualTo(stack)
    }

    @Test
    fun `merging defaults preserves the action's STACK_AT clauses`() {
        val stackAt =
            StackAtClause(
                mfeThreshold = NumLit(BigDecimal.TEN),
                withinDuration = DurationAst(60_000L),
                sizing = SizeQty(NumLit(BigDecimal.ONE)),
                bracket = BracketAst(),
            )
        val action = Buy("btc", ActionOpts(sizing = SizeQty(NumLit(BigDecimal.ONE)), stackAts = listOf(stackAt)))
        val merged = mergeDefaults(action, DefaultsBlock(tif = Gtc)) as Buy
        assertThat(merged.opts.stackAts).containsExactly(stackAt)
    }
}
