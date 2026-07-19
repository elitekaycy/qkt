package com.qkt.app

import com.qkt.common.Side
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.ExitContext
import com.qkt.dsl.compile.ExitHookRef
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.PendingStacks
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Tick
import com.qkt.persistence.NoopStatePersistor
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExitHookManagerTest {
    private val ref = ExitHookRef("exit-hook-1", "fingerprint")

    @Test
    fun `stop fill dispatches once with accumulated exit context`() {
        val persistor = NoopStatePersistor()
        val strategy = StubDslStrategy(ref)
        val emitted = mutableListOf<Signal>()
        val manager = ExitHookManager(persistor)
        manager.bind("s", strategy) { emitted.add(it) }
        manager.register("s", bracket(), ref)
        manager.onFill(fill("entry", Side.BUY, "100", "2"), BigDecimal.ZERO, BigDecimal("2"), false)
        manager.onFill(fill("parent-sl", Side.SELL, "95", "2"), BigDecimal("-10"), BigDecimal.ZERO, true)
        manager.onFill(fill("parent-sl", Side.SELL, "95", "2"), BigDecimal("-10"), BigDecimal.ZERO, false)

        assertThat(emitted).containsExactly(Signal.Sell("XAUUSD", BigDecimal.ONE))
        assertThat(strategy.exits).containsExactly(
            ExitContext(
                price = BigDecimal("95"),
                side = Side.SELL,
                quantity = BigDecimal("2"),
                pnl = BigDecimal("-10"),
                reason = ExitReason.STOP,
            ),
        )
        assertThat(persistor.loadExitHooks("s")).isEmpty()
    }

    @Test
    fun `active binding restores and validates its compiled fingerprint`() {
        val persistor = NoopStatePersistor()
        val first = ExitHookManager(persistor)
        first.bind("s", StubDslStrategy(ref)) {}
        first.register("s", bracket(), ref)
        first.onFill(fill("entry", Side.BUY, "100", "2"), BigDecimal.ZERO, BigDecimal("2"), false)
        val saved = persistor.loadExitHooks("s")

        val restoredStrategy = StubDslStrategy(ref)
        val emitted = mutableListOf<Signal>()
        val restored = ExitHookManager(persistor)
        restored.bind("s", restoredStrategy) { emitted.add(it) }
        restored.onFill(fill("parent-tp", Side.SELL, "110", "2"), BigDecimal("20"), BigDecimal.ZERO, true)

        assertThat(restoredStrategy.exits.single().reason).isEqualTo(ExitReason.TAKE_PROFIT)
        assertThat(emitted).hasSize(1)

        persistor.saveExitHooks(
            "s",
            saved.map { it.copy(fingerprint = "stale") },
        )
        assertThatThrownBy {
            ExitHookManager(persistor).bind("s", StubDslStrategy(ref)) {}
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `ticketed close targets only its binding when entries share a symbol`() {
        val strategy = StubDslStrategy(ref)
        val manager = ExitHookManager(NoopStatePersistor())
        manager.bind("s", strategy) {}
        manager.register("s", bracket(parentId = "a", entryId = "entry-a"), ref)
        manager.register("s", bracket(parentId = "b", entryId = "entry-b"), ref)
        manager.onFill(
            fill("entry-a", Side.BUY, "100", "1", brokerOrderId = "ticket-a"),
            BigDecimal.ZERO,
            BigDecimal.ONE,
            false,
        )
        manager.onFill(
            fill("entry-b", Side.BUY, "101", "1", brokerOrderId = "ticket-b"),
            BigDecimal.ZERO,
            BigDecimal("2"),
            false,
        )
        manager.trackCloseRequest("s", closeRequest("close-a", "ticket-a"))
        manager.onFill(
            fill("close-a", Side.SELL, "105", "1", brokerOrderId = "ticket-a"),
            BigDecimal("5"),
            BigDecimal.ONE,
            true,
        )

        val exit = strategy.exits.single()
        assertThat(exit.reason).isEqualTo(ExitReason.CLOSE)
        assertThat(exit.quantity).isEqualByComparingTo("1")
        assertThat(exit.pnl).isEqualByComparingTo("5")
    }

    @Test
    fun `manual close correlation survives restart`() {
        val persistor = NoopStatePersistor()
        val first = ExitHookManager(persistor)
        first.bind("s", StubDslStrategy(ref)) {}
        first.register("s", bracket(), ref)
        first.onFill(
            fill("entry", Side.BUY, "100", "2", brokerOrderId = "ticket-1"),
            BigDecimal.ZERO,
            BigDecimal("2"),
            false,
        )
        first.trackCloseRequest("s", closeRequest("manual-close", "ticket-1"))

        val restoredStrategy = StubDslStrategy(ref)
        val restored = ExitHookManager(persistor)
        restored.bind("s", restoredStrategy) {}
        restored.onFill(
            fill("manual-close", Side.SELL, "103", "2", brokerOrderId = "ticket-1"),
            BigDecimal("6"),
            BigDecimal.ZERO,
            true,
        )

        assertThat(restoredStrategy.exits.single().reason).isEqualTo(ExitReason.CLOSE)
        assertThat(persistor.loadExitHooks("s")).isEmpty()
    }

    @Test
    fun `netting-ticket close allocates quantity and pnl without duplication`() {
        val strategy = StubDslStrategy(ref)
        val manager = ExitHookManager(NoopStatePersistor())
        manager.bind("s", strategy) {}
        manager.register("s", bracket(parentId = "a", entryId = "entry-a"), ref)
        manager.register("s", bracket(parentId = "b", entryId = "entry-b"), ref)
        manager.onFill(
            fill("entry-a", Side.BUY, "100", "1", brokerOrderId = "shared"),
            BigDecimal.ZERO,
            BigDecimal.ONE,
            false,
        )
        manager.onFill(
            fill("entry-b", Side.BUY, "100", "1", brokerOrderId = "shared"),
            BigDecimal.ZERO,
            BigDecimal("2"),
            false,
        )
        manager.trackCloseRequest("s", closeRequest("flatten", "shared"))
        manager.onFill(
            fill("flatten", Side.SELL, "105", "2", brokerOrderId = "shared"),
            BigDecimal("10"),
            BigDecimal.ZERO,
            true,
        )

        assertThat(strategy.exits).hasSize(2)
        assertThat(strategy.exits.map { it.quantity }).allMatch { it.compareTo(BigDecimal.ONE) == 0 }
        assertThat(strategy.exits.map { it.pnl }).allMatch { it.compareTo(BigDecimal("5")) == 0 }
    }

    @Test
    fun `deferred dispatch waits for the later lifecycle subscriber`() {
        val strategy = StubDslStrategy(ref)
        val emitted = mutableListOf<Signal>()
        val manager = ExitHookManager(NoopStatePersistor())
        manager.bind("s", strategy) { emitted.add(it) }
        manager.register("s", bracket(), ref)
        manager.onFill(fill("entry", Side.BUY, "100", "2"), BigDecimal.ZERO, BigDecimal("2"), false)
        val exit = fill("parent-tp", Side.SELL, "110", "2").copy(sequenceId = 42L)

        manager.onFill(
            exit,
            BigDecimal("20"),
            BigDecimal.ZERO,
            reducedExposure = true,
            deferDispatch = true,
        )

        assertThat(emitted).isEmpty()
        manager.dispatchReady(exit)
        assertThat(emitted).hasSize(1)
    }

    private fun bracket(
        parentId: String = "parent",
        entryId: String = "entry",
    ): OrderRequest.Bracket =
        OrderRequest.Bracket(
            id = parentId,
            symbol = "XAUUSD",
            side = Side.BUY,
            quantity = BigDecimal("2"),
            entry =
                OrderRequest.Market(
                    id = entryId,
                    symbol = "XAUUSD",
                    side = Side.BUY,
                    quantity = BigDecimal("2"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 1L,
                    strategyId = "s",
                ),
            takeProfit = BigDecimal("110"),
            stopLoss = StopLossSpec.Fixed(BigDecimal("95")),
            timeInForce = TimeInForce.GTC,
            timestamp = 1L,
            strategyId = "s",
        )

    private fun closeRequest(
        id: String,
        ticket: String,
    ): OrderRequest.Market =
        OrderRequest.Market(
            id = id,
            symbol = "XAUUSD",
            side = Side.SELL,
            quantity = BigDecimal.ONE,
            timeInForce = TimeInForce.GTC,
            timestamp = 2L,
            strategyId = "s",
            closesTicket = ticket,
        )

    private fun fill(
        id: String,
        side: Side,
        price: String,
        quantity: String,
        brokerOrderId: String = id,
    ): BrokerEvent.OrderFilled =
        BrokerEvent.OrderFilled(
            clientOrderId = id,
            brokerOrderId = brokerOrderId,
            symbol = "XAUUSD",
            side = side,
            price = BigDecimal(price),
            quantity = BigDecimal(quantity),
            strategyId = "s",
            timestamp = 2L,
        )

    private class StubDslStrategy(
        private val ref: ExitHookRef,
    ) : DslCompiledStrategy {
        val exits = mutableListOf<ExitContext>()
        override val declaredStreams: Map<String, HubKey> = mapOf("gold" to HubKey("TEST", "XAUUSD", "1m"))
        override val retentionByKey: Map<HubKey, Int> = emptyMap()
        override val pendingStacks: PendingStacks = PendingStacks()

        override fun exitHookReferences(): Map<String, ExitHookRef> = mapOf(ref.definitionId to ref)

        override fun executeExitHook(
            ref: ExitHookRef,
            exit: ExitContext,
            timestampMs: Long,
        ): List<Signal> {
            exits.add(exit)
            return listOf(Signal.Sell("XAUUSD", BigDecimal.ONE))
        }

        override fun bindToHub(
            hub: CandleHub,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) = Unit

        override fun onTick(
            tick: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) = Unit
    }
}
