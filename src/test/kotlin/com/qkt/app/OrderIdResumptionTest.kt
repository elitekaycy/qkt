package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.events.OrderEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.persistence.FileStatePersistor
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OrderIdResumptionTest {
    private val clock = FixedClock(0L)
    private val source =
        """
        STRATEGY ids_dsl VERSION 1

        SYMBOLS
            btc = DERIBIT:BTC_USDC_PERPETUAL EVERY 1m

        RULES
            WHEN btc.close > 0 THEN BUY btc SIZING 0.001
        """.trimIndent()

    private fun compile(): DslCompiledStrategy =
        when (val parsed = Dsl.parse(source)) {
            is ParseResult.Success -> AstCompiler().compile(parsed.value) as DslCompiledStrategy
            is ParseResult.Failure -> error(parsed.errors.joinToString { it.message })
        }

    /** One session's restore over [state]: its strategy, session ids and the bus it records marks from. */
    private fun restore(
        state: Path,
        strategy: DslCompiledStrategy,
        sessionIds: SequentialIdGenerator,
    ): EventBus {
        val persistor = FileStatePersistor(state)
        val engineBus = EventBus(clock, MonotonicSequenceGenerator())
        val orders =
            OrderManager(FakeBroker(engineBus, clock, emptySet()), engineBus, MarketPriceTracker(), clock, persistor)
        val marks = EventBus(clock, MonotonicSequenceGenerator())
        val strategies = listOf("ids_dsl" to strategy)
        OrderIdResumption.resume(strategies, orders, StrategyPositionTracker(persistor), sessionIds, persistor, marks)
        return marks
    }

    private fun submitted(id: String) =
        OrderEvent(
            OrderRequest.Market(
                id,
                "DERIBIT:BTC_USDC_PERPETUAL",
                Side.BUY,
                BigDecimal("0.001"),
                TimeInForce.GTC,
                0L,
                "ids_dsl",
            ),
        )

    @Test
    fun `a strategy restarted with nothing restored continues both its sequences past what it sent`(
        @TempDir state: Path,
    ) {
        val before = compile()
        val beforeSession = SequentialIdGenerator.forSession(listOf("ids_dsl"))
        val bus = restore(state, before, beforeSession)
        val dslIds = (1..3).map { before.orderIds!!.next() }
        val sessionId = beforeSession.next()
        bus.publish(submitted(dslIds.last()))

        val after = compile()
        val afterSession = SequentialIdGenerator.forSession(listOf("ids_dsl"))
        restore(state, after, afterSession)

        assertThat(dslIds).containsExactly("dsl-ids_dsl--0", "dsl-ids_dsl--1", "dsl-ids_dsl--2")
        assertThat(after.orderIds!!.next()).isEqualTo("dsl-ids_dsl--3")
        assertThat(sessionId).isEqualTo("ORD-ids_dsl-0")
        assertThat(afterSession.next()).isEqualTo("ORD-ids_dsl-1")
    }

    @Test
    fun `a fresh state starts both sequences at zero`(
        @TempDir state: Path,
    ) {
        val strategy = compile()
        val session = SequentialIdGenerator.forSession(listOf("ids_dsl"))
        restore(state, strategy, session)

        assertThat(strategy.orderIds!!.next()).isEqualTo("dsl-ids_dsl--0")
        assertThat(session.next()).isEqualTo("ORD-ids_dsl-0")
    }
}
