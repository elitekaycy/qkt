package com.qkt.trade.session

import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.compile.AdHocActions
import com.qkt.dsl.compile.AdHocFire
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import com.qkt.strategy.testStrategyContext
import com.qkt.trade.BotIntent
import com.qkt.trade.parseBotStrategy
import com.qkt.trade.renderBotStrategy
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** How one identity turns what its ad-hoc actions answered into an intent's fate. */
class BotIntentStrategyFireTest {
    private val gold = "EXNESS:XAUUSD"
    private val action: ActionAst =
        parseBotStrategy(renderBotStrategy(BotIntent(Side.BUY, gold, lots = BigDecimal.ONE))).action

    /** Ad-hoc actions that answer every fire with [answer]; validation refuses when [refuse] is set. */
    private class Scripted(
        var refuse: String? = null,
        var answer: () -> AdHocFire = { AdHocFire.Fired(emptyList()) },
    ) : AdHocActions {
        override fun validate(action: ActionAst) {
            refuse?.let { error(it) }
        }

        override fun rebuild(action: ActionAst) = Unit

        override fun fire(
            action: ActionAst,
            ctx: StrategyContext,
            nowMs: Long,
            emit: (Signal) -> Unit,
            onCompiled: () -> Unit,
        ): AdHocFire = answer()
    }

    private fun realStrategy(): DslCompiledStrategy {
        val parsed = Dsl.parse(testStreams().source("bot0")) as ParseResult.Success<StrategyAst>
        return AstCompiler().compile(parsed.value) as DslCompiledStrategy
    }

    private fun strategy(actions: Scripted): BotIntentStrategy {
        val real = realStrategy()
        val inner =
            object : DslCompiledStrategy by real {
                override val adHoc: AdHocActions = actions

                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) = Unit
            }
        return BotIntentStrategy("brain", inner)
    }

    private fun BotIntentStrategy.tick(): List<Signal> {
        val out = mutableListOf<Signal>()
        onTick(Tick(gold, BigDecimal("2400"), 7_000L), testStrategyContext(clock = FixedClock(7_000L))) { out.add(it) }
        return out
    }

    @Test
    fun `submit refuses an action validation rejects and queues nothing`() {
        val s = strategy(Scripted(refuse = "indicators are not available in an intent"))

        assertThatThrownBy { s.submit("int-1", action) }.hasMessage("indicators are not available in an intent")
        assertThat(s.ledger.fates()).isEmpty()
    }

    @Test
    fun `submit queues a valid action and the next tick fires it`() {
        val s = strategy(Scripted())

        s.submit("int-1", action)
        assertThat(s.ledger.fates().single().state).isEqualTo("queued")
        s.tick()

        val fate = s.ledger.fates().single()
        assertThat(fate.state).isEqualTo("skipped")
        assertThat(fate.reason).startsWith("the action built no order")
        assertThat(fate.atMs).isEqualTo(7_000L)
        assertThat(fate.tickMs).isEqualTo(7_000L)
    }

    @Test
    fun `an action that throws while firing is skipped with the error`() {
        val s = strategy(Scripted(answer = { throw ArithmeticException("Division by zero") }))

        s.submit("int-1", action)
        val emitted = s.tick()

        assertThat(emitted).isEmpty()
        assertThat(s.ledger.fates().single().state).isEqualTo("skipped")
        assertThat(s.ledger.fates().single().reason).isEqualTo("Division by zero")
    }

    @Test
    fun `a fire that only suppressed is skipped with the first suppression reason`() {
        val suppressed = listOf(Signal.Suppressed(gold, "gold is pending, not open"), Signal.Suppressed(gold, "second"))
        val s = strategy(Scripted(answer = { AdHocFire.Fired(suppressed) }))

        s.submit("int-1", action)
        s.tick()

        assertThat(s.ledger.fates().single().reason).isEqualTo("gold is pending, not open")
        assertThat(s.ledger.fates().single().state).isEqualTo("skipped")
    }

    @Test
    fun `a skipped fire carries the actions' own reason`() {
        val s = strategy(Scripted(answer = { AdHocFire.Skipped("no closed bar yet on s0_1m") }))

        s.submit("int-1", action)
        s.tick()

        assertThat(s.ledger.fates().single().reason).isEqualTo("no closed bar yet on s0_1m")
    }

    @Test
    fun `an identity over a strategy without ad-hoc actions is refused`() {
        val real = realStrategy()
        val bare =
            object : DslCompiledStrategy by real {
                override val adHoc: AdHocActions? = null
            }

        assertThatThrownBy { BotIntentStrategy("brain", bare) }.hasMessage("strategy has no ad-hoc actions")
    }

    @Test
    fun `identities compile without a bar history and refuse a session that cannot parse`() {
        val compiled = BotSessionIdentities.compile(testStreams(), listOf("a", "b"))
        assertThat(compiled.keys).containsExactly("a", "b")
        assertThat(BotSessionIdentities.compile(testStreams(), emptyList())).isEmpty()

        val broken = BotSessionStreams(listOf("EXNESS:XAU USD"), listOf("1m"))
        assertThatThrownBy { BotSessionIdentities.compile(broken, listOf("a")) }
            .hasMessageStartingWith("session streams failed to parse:")
    }
}
