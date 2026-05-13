package com.qkt.dsl.compile

import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.SinceTPast
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprCompilerAggregateTest {
    private fun candle(price: String) =
        Candle(
            "BACKTEST:BTCUSDT",
            BigDecimal(price),
            BigDecimal(price),
            BigDecimal(price),
            BigDecimal(price),
            BigDecimal.ZERO,
            0L,
            60_000L,
        )

    private fun ctx(c: Candle) =
        EvalContext(
            candle = c,
            streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )

    @Test
    fun `MAX SINCE OPEN starts Undefined and tracks max after update`() {
        val aggBag = AggregateBinding.Bag()
        val ec = ExprCompiler(aggregates = aggBag)
        val expr = Aggregate(AggFn.MAX, StreamFieldRef("btc", "close"), SinceOpen)
        val compiled = ec.compile(expr, ruleAlias = "BACKTEST:BTCUSDT")
        val c1 = ctx(candle("100"))
        assertThat(compiled.evaluate(c1)).isEqualTo(Value.Undefined)
        aggBag.all().forEach { it.update(c1) }
        val c2 = ctx(candle("130"))
        aggBag.all().forEach { it.update(c2) }
        val c3 = ctx(candle("110"))
        aggBag.all().forEach { it.update(c3) }
        assertThat((compiled.evaluate(c3) as Value.Num).v).isEqualByComparingTo("130")
        aggBag.all().forEach { it.resetIfSinceOpen() }
        assertThat(compiled.evaluate(c3)).isEqualTo(Value.Undefined)
    }

    @Test
    fun `MEAN SINCE T-3 returns Undefined until 3 samples`() {
        val aggBag = AggregateBinding.Bag()
        val ec = ExprCompiler(aggregates = aggBag)
        val expr = Aggregate(AggFn.MEAN, StreamFieldRef("btc", "close"), SinceTPast(3))
        val compiled = ec.compile(expr, ruleAlias = "BACKTEST:BTCUSDT")
        for (price in listOf("100", "110")) {
            val c = ctx(candle(price))
            aggBag.all().forEach { it.update(c) }
        }
        val c = ctx(candle("120"))
        assertThat(compiled.evaluate(c)).isEqualTo(Value.Undefined)
        aggBag.all().forEach { it.update(c) }
        val v = compiled.evaluate(c) as Value.Num
        assertThat(v.v).isEqualByComparingTo("110")
    }
}
