package com.qkt.app

import com.qkt.common.Side
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.ExitContext
import com.qkt.dsl.compile.ExitHookRef
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.PendingStacks
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal

object ExitHookManagerFixtures {
    val ref = ExitHookRef("exit-hook-1", "fingerprint")

    fun bracket(
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

    fun closeRequest(
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

    fun fill(
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

    class StubDslStrategy(
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
