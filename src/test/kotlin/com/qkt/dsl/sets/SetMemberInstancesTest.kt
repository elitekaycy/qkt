package com.qkt.dsl.sets

import com.qkt.common.Side
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.instrument.universe.SetMembership
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.positions.Position
import com.qkt.positions.StrategyPositionView
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SetMemberInstancesTest {
    private val ast: StrategyAst =
        (
            Dsl.parse(
                """
                STRATEGY movers VERSION 1
                SYMBOLS
                    movers = BACKTEST:* WHERE kind = spot TOP 3 BY symbol EVERY 1m
                RULES
                    FOR EACH m IN movers DO
                      WHEN m.close > sma(m.close, 3) THEN BUY m SIZING 1
                    FOR EACH m IN movers DO
                      WHEN m.close < sma(m.close, 3) THEN SELL m SIZING 1
                """.trimIndent(),
            ) as ParseResult.Success
        ).value

    private val t0 = 1_700_000_040_000L
    private val minute = 60_000L
    private val key = HubKey("BACKTEST", "AAA", "1m")

    private val held = mutableMapOf<String, BigDecimal>()
    private val positions =
        object : StrategyPositionView {
            override fun positionFor(symbol: String): Position? =
                held[symbol]?.let { Position(symbol, it, BigDecimal.ONE) }

            override fun allPositions(): Map<String, Position> = emptyMap()
        }

    private val hub = CandleHub()
    private val signals = mutableListOf<Signal>()
    private val warmups = mutableListOf<Pair<HubKey, Int>>()
    private val warmupEnds = mutableListOf<Long>()
    private var warmupEnabled = true
    private val membership = SetMembership()
    private val instances =
        SetMemberInstances(
            SetMemberTemplate(ast, "movers"),
            hub,
            testStrategyContext(strategyId = "movers", positions = positions),
            { signals += it },
        ) { k, n, endMs ->
            warmups += k to n
            warmupEnds += endMs
            if (warmupEnabled) flat(n, k.qktSymbol).filter { it.endTime <= endMs } else emptyList()
        }

    private fun flat(
        n: Int,
        symbol: String = "BACKTEST:AAA",
    ): List<Candle> =
        (n downTo 1).map { i ->
            val start = t0 - i * minute
            val one = BigDecimal.ONE
            Candle(symbol, one, one, one, one, BigDecimal.ZERO, start, start + minute)
        }

    /** One bar of [symbol] at [price] in minute [m] after t0, closed by a tick in the next minute. */
    private fun bar(
        symbol: String,
        m: Int,
        price: String,
    ) {
        hub.feed(Tick(symbol, BigDecimal(price), t0 + m * minute + 1_000L))
        hub.feed(Tick(symbol, BigDecimal(price), t0 + (m + 1) * minute))
    }

    private fun sides(): List<Side?> = signals.map { it.orderSide()?.second }

    private fun step(
        vararg members: String,
        holding: Set<String> = emptySet(),
    ) = instances.apply(membership.step(members.toList(), holding))

    @Test
    fun `a joining member warms up from history and trades only its own stream`() {
        step("AAA")
        assertThat(instances.members).containsExactly("AAA")
        assertThat(warmups.single().first).isEqualTo(key)
        assertThat(warmups.single().second).isGreaterThanOrEqualTo(3)
        bar("BACKTEST:BBB", 0, "9")
        assertThat(signals).isEmpty()
        bar("BACKTEST:AAA", 0, "5")
        assertThat(sides()).containsExactly(Side.BUY)
        assertThat((signals.single() as Signal.Buy).symbol).isEqualTo("BACKTEST:AAA")
    }

    @Test
    fun `a member that leaves is dropped from the hub and a rejoin warms up afresh`() {
        step("AAA")
        step()
        assertThat(instances.members).isEmpty()
        assertThat(hub.keys()).doesNotContain(key)
        bar("BACKTEST:AAA", 0, "5")
        assertThat(signals).isEmpty()
        warmupEnabled = false
        step("AAA")
        assertThat(warmups).hasSize(2)
        assertThat(hub.seededHistory(key)).isEmpty()
    }

    @Test
    fun `a sticky member only reduces until it is back in the ranking`() {
        step("AAA")
        held["BACKTEST:AAA"] = BigDecimal("-1")
        step(holding = setOf("AAA"))
        assertThat(instances.isSticky("AAA")).isTrue()
        bar("BACKTEST:AAA", 0, "5")
        assertThat(sides()).containsExactly(Side.BUY)
        held["BACKTEST:AAA"] = BigDecimal("1")
        bar("BACKTEST:AAA", 1, "9")
        held.clear()
        bar("BACKTEST:AAA", 2, "0.5")
        assertThat(sides()).containsExactly(Side.BUY)
        held["BACKTEST:AAA"] = BigDecimal("1")
        bar("BACKTEST:AAA", 3, "0.1")
        assertThat(sides()).containsExactly(Side.BUY, Side.SELL)
        step("AAA")
        assertThat(instances.isSticky("AAA")).isFalse()
        bar("BACKTEST:AAA", 4, "20")
        assertThat(sides()).containsExactly(Side.BUY, Side.SELL, Side.BUY)
        step(holding = setOf("AAA"))
        step()
        assertThat(instances.members).isEmpty()
        assertThat(instances.isSticky("AAA")).isFalse()
    }

    @Test
    fun `a restart restores ranked members as members and held ones as sticky`() {
        instances.apply(membership.restore(listOf("AAA"), held = setOf("BBB")))
        assertThat(instances.members).containsExactly("AAA", "BBB")
        assertThat(instances.isSticky("AAA")).isFalse()
        assertThat(instances.isSticky("BBB")).isTrue()
    }

    @Test
    fun `a stream the hub already holds history for is not warmed again`() {
        hub.register(key, 50, "someone-else")
        hub.seed(key, flat(40))
        step("AAA")
        assertThat(warmups).isEmpty()
        bar("BACKTEST:AAA", 0, "5")
        assertThat(sides()).containsExactly(Side.BUY)
        step()
        assertThat(hub.keys()).contains(key)
    }

    @Test
    fun `a member the hub holds too little history for reads only the older bars it lacks`() {
        hub.register(key, 50, "someone-else")
        hub.seed(key, flat(1))
        step("AAA")
        assertThat(warmups.single().second).isGreaterThanOrEqualTo(2)
        assertThat(warmupEnds.single()).isEqualTo(t0 - minute)
        assertThat(hub.seededHistory(key).first().endTime).isLessThan(t0 - minute + 1)
        bar("BACKTEST:AAA", 0, "5")
        assertThat(sides()).containsExactly(Side.BUY)
    }

    @Test
    fun `a member with no history reads bars ending before the bar of the close it joined at`() {
        instances.apply(membership.step(listOf("AAA"), emptySet()), t0 + minute)
        assertThat(warmupEnds.single()).isEqualTo(t0)
    }
}
