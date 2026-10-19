package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.WhenThen
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AdHocActionsTest {
    private val header =
        """
        STRATEGY bot VERSION 1
        SYMBOLS
          s0 = EXNESS:XAUUSD EVERY 1m
        RULES
        """.trimIndent()

    private val strategy =
        AstCompiler().compile(parse("$header\n  WHEN 1 > 2 THEN BUY s0 SIZING 0.01")) as DslCompiledStrategy

    private val adHoc = requireNotNull(strategy.adHoc)

    private fun parse(src: String) = (Dsl.parse(src) as ParseResult.Success).value

    private fun action(text: String): ActionAst =
        (parse("$header\n  WHEN true\n  THEN $text").rules.single() as WhenThen).action

    private fun closeOneBar(): MutableList<Signal> {
        val hub = CandleHub()
        strategy.declaredStreams.values.forEach { hub.register(it, retention = 10, strategyId = "t") }
        val emitted = mutableListOf<Signal>()
        strategy.bindToHub(hub, testStrategyContext()) { emitted += it }
        hub.feed(Tick("EXNESS:XAUUSD", BigDecimal("2650"), 0L))
        hub.feed(Tick("EXNESS:XAUUSD", BigDecimal("2651"), 60_000L))
        return emitted
    }

    private fun fire(text: String): AdHocFire {
        val emitted = mutableListOf<Signal>()
        val fired = adHoc.fire(action(text), testStrategyContext(), 60_001L) { emitted += it }
        if (fired is AdHocFire.Fired) assertThat(fired.signals).isEqualTo(emitted)
        return fired
    }

    @Test
    fun `market buy fires one submit`() {
        closeOneBar()

        val fired = fire("BUY s0 SIZING 0.5") as AdHocFire.Fired

        val request = fired.signals.single() as Signal.Buy
        assertThat(request.symbol).isEqualTo("EXNESS:XAUUSD")
        assertThat(request.size).isEqualByComparingTo("0.5")
    }

    @Test
    fun `a bracket buy carries both exits`() {
        closeOneBar()

        val fired = fire("BUY s0 SIZING 1 BRACKET { STOP LOSS BY 5, TAKE PROFIT BY 10 }") as AdHocFire.Fired

        val bracket = (fired.signals.single() as Signal.Submit).request as OrderRequest.Bracket
        // The closed bar is [0, 60000) at 2650; the tick at 60000 opens the next one.
        assertThat(bracket.takeProfit).isEqualByComparingTo("2660")
    }

    @Test
    fun `bracket with stop only is refused like a rule`() {
        assertThatThrownBy { adHoc.validate(action("BUY s0 SIZING 1 BRACKET { STOP LOSS BY 5 }")) }
            .hasMessageContaining("BRACKET requires both STOP LOSS and TAKE PROFIT")
    }

    @Test
    fun `indicator in an intent is refused`() {
        assertThatThrownBy {
            adHoc.validate(action("BUY s0 SIZING 1 BRACKET { STOP LOSS BY atr(s0.candle, 14), TAKE PROFIT BY 10 }"))
        }.hasMessageContaining("indicators are not available in an intent")
    }

    @Test
    fun `book sizing in an intent is refused`() {
        assertThatThrownBy {
            adHoc.validate(action("BUY s0 SIZING 0.5 PCT RISK OF BOOK BRACKET { STOP LOSS BY 5, TAKE PROFIT BY 10 }"))
        }.hasMessageContaining("OF BOOK")
    }

    @Test
    fun `an undeclared stream is refused`() {
        assertThatThrownBy {
            adHoc.validate((parse("$header\n  WHEN true\n  THEN BUY s1 SIZING 1").rules.single() as WhenThen).action)
        }.hasMessageContaining("s1")
    }

    @Test
    fun `validating an intent leaves the strategy's exit hooks untouched`() {
        adHoc.validate(
            action("BUY s0 SIZING 1 BRACKET { STOP LOSS BY 5, TAKE PROFIT BY 10 } ON_STOP { SELL s0 SIZING 1 }"),
        )

        assertThat(strategy.exitHookReferences()).isEmpty()
    }

    @Test
    fun `close emits a closing cancel first`() {
        closeOneBar()

        val fired = fire("CLOSE s0") as AdHocFire.Fired

        assertThat(fired.signals.first()).isEqualTo(Signal.CancelPendingForSymbol("EXNESS:XAUUSD", closing = true))
    }

    @Test
    fun `fire before any closed bar is skipped with a reason`() {
        val skipped = fire("BUY s0 SIZING 0.5") as AdHocFire.Skipped

        assertThat(skipped.reason).contains("no closed bar")
    }
}
