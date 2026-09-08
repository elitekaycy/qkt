package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * An order the engine cannot price must say so, not disappear.
 *
 * A rule fires on one stream's bar and may order on another. That order prices itself from its
 * OWN stream's last closed candle, and before that stream has closed one there is no price, so the
 * order cannot be constructed. It used to return nothing at all: no order, no rejection, nothing
 * in the trade record, only a warn line — a hedge quietly missing a leg. It now reports a
 * suppressed signal naming the stream and the fix.
 */
class CrossStreamSuppressionTest {
    private val gold = HubKey("BACKTEST", "GOLD", "1m")
    private val eur = HubKey("BACKTEST", "EURUSD", "1m")

    private fun candle(symbol: String) =
        Candle(
            symbol,
            BigDecimal("100"),
            BigDecimal("100"),
            BigDecimal("100"),
            BigDecimal("100"),
            BigDecimal.ZERO,
            0L,
            60_000L,
        )

    private fun context(currentAlias: String?) =
        EvalContext(
            candle = candle("BACKTEST:GOLD"),
            streams = mapOf("gold" to gold, "eur" to eur),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
            currentAlias = currentAlias,
        )

    private val bracketed =
        ActionOpts(
            sizing = SizeQty(NumLit(BigDecimal("0.01"))),
            bracket = BracketAst(ChildBy(NumLit(BigDecimal("5"))), ChildBy(NumLit(BigDecimal("5")))),
        )

    private fun compile(stream: String) =
        ActionCompiler(ExprCompiler()).compile(
            com.qkt.dsl.ast
                .Buy(stream, bracketed),
        )

    @Test
    fun `an unpriceable order on another stream reports a suppressed signal`() {
        val signals = compile("eur")(context(currentAlias = "gold"))
        val suppressed = signals.filterIsInstance<Signal.Suppressed>()
        assertThat(suppressed).hasSize(1)
        assertThat(suppressed.single().symbol).isEqualTo("BACKTEST:EURUSD")
        assertThat(suppressed.single().reason)
            .contains("'eur'")
            .contains("has not closed a candle yet")
            .contains("WARMUP")
        assertThat(signals.filterIsInstance<Signal.Submit>()).isEmpty()
    }

    @Test
    fun `the same stream during its own warm-up stays quiet`() {
        // Not the invisible case: the strategy's own stream warming up is ordinary and expected,
        // and reporting a suppression every bar of a warm-up would be noise, not signal.
        val ctx =
            EvalContext(
                candle = candle("BACKTEST:GOLD"),
                streams = mapOf("gold" to gold, "eur" to eur),
                lets = emptyMap(),
                strategyContext = testStrategyContext(),
                currentAlias = "gold",
            )
        // gold IS the evaluated stream, and its candle is present, so this one prices fine
        val signals = compile("gold")(ctx)
        assertThat(signals.filterIsInstance<Signal.Suppressed>()).isEmpty()
        assertThat(signals.filterIsInstance<Signal.Submit>()).hasSize(1)
    }

    @Test
    fun `a priceable cross-stream order is unaffected`() {
        // When the target stream HAS closed a candle the order builds normally. Modelled here by
        // evaluating with no currentAlias and the target's own candle in context.
        val ctx =
            EvalContext(
                candle = candle("BACKTEST:EURUSD"),
                streams = mapOf("gold" to gold, "eur" to eur),
                lets = emptyMap(),
                strategyContext = testStrategyContext(),
                currentAlias = null,
            )
        val signals = compile("eur")(ctx)
        assertThat(signals.filterIsInstance<Signal.Suppressed>()).isEmpty()
        assertThat(signals.filterIsInstance<Signal.Submit>()).hasSize(1)
    }

    @Test
    fun `a plain unbracketed order needs no price and is never suppressed`() {
        // The fast path emits Signal.Buy/Sell with a quantity only, so it was always placed --
        // which is why the drop only ever bit bracketed and pending orders.
        val plain = ActionOpts(sizing = SizeQty(NumLit(BigDecimal("0.01"))))
        val signals =
            ActionCompiler(ExprCompiler())
                .compile(
                    com.qkt.dsl.ast
                        .Sell("eur", plain),
                )(context(currentAlias = "gold"))
        assertThat(signals.filterIsInstance<Signal.Suppressed>()).isEmpty()
        assertThat(signals).hasSize(1)
        assertThat(signals.single()).isInstanceOf(Signal.Sell::class.java)
    }
}
