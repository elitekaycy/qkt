package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.BrokerFactory
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PositionAccountingMode
import com.qkt.broker.SubmitAck
import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.common.TradingCalendar
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * A live session whose bracket entry the venue filled 0.3 of 0.5, then cancelled by the strategy
 * (`CANCEL`), closed by it (`CLOSE`), or flattened by the operator (`qkt stop --flatten`), #1328. The
 * venue nets, holds no SL/TP (so the bracket is decomposed) and answers a cancel at once.
 */
class LiveSessionBracketCancelTest {
    private val symbol = "VENUE:X"
    private val start = 1_700_000_000_000L
    private val ticks = LinkedBlockingQueue<Tick>()
    private val script = LinkedBlockingQueue<List<Signal>>()
    private val sent = CopyOnWriteArrayList<OrderRequest>()
    private val cancelled = CopyOnWriteArrayList<String>()
    private var entryFill: String? = "0.3"
    private val factory: BrokerFactory = { bus, clock, _, _, _ ->
        val fake =
            FakeBroker(
                bus,
                clock,
                setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT, OrderTypeCapability.STOP),
            )
        object : Broker by fake {
            override fun positionAccountingMode(symbol: String) = PositionAccountingMode.NETTING

            override fun submit(request: OrderRequest): SubmitAck {
                sent += request
                val ack = fake.submit(request)
                val price = BigDecimal("100")
                val q = request.quantity
                val sid = request.strategyId
                val fill = entryFill?.let(::BigDecimal)
                when {
                    request.id == "e1" && fill != null ->
                        bus.publish(
                            BrokerEvent.OrderPartiallyFilled("e1", "v1", symbol, request.side, price, fill, fill, sid),
                        )
                    request.id != "e1" && request is OrderRequest.Market ->
                        bus.publish(
                            BrokerEvent.OrderFilled(request.id, request.id, symbol, request.side, price, q, sid),
                        )
                }
                return ack
            }

            override fun cancel(orderId: String) {
                cancelled += orderId
                fake.cancel(orderId)
            }
        }
    }
    private val strategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                script.poll()?.forEach(emit)
            }
        }
    private val source =
        object : MarketSource {
            override val name = "queued"
            override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

            override fun supports(symbol: String) = true

            override fun liveTicks(symbols: List<String>): TickFeed =
                object : TickFeed {
                    override fun next(): Tick? = runCatching { ticks.take() }.getOrNull()

                    override fun close() = Unit
                }
        }
    private val handle =
        LiveSession(
            strategies = listOf("test" to strategy),
            source = source,
            symbols = listOf(symbol),
            clock = FixedClock(start),
            calendar = TradingCalendar.crypto(),
            brokerFactories = mapOf("venue" to factory),
        ).start()

    @AfterEach
    fun stop() {
        handle.stop()
        handle.awaitTermination(Duration.ofSeconds(2))
    }

    private fun run(vararg signals: Signal) {
        script += signals.toList()
        ticks += Tick(symbol, BigDecimal("100"), start)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    private fun held() = handle.positionsFor("test").sumOf { it.quantity }

    private fun exits() = sent.filter { it is OrderRequest.Stop || it is OrderRequest.Limit }

    private fun enter() {
        val entry = OrderRequest.Market("e1", symbol, Side.BUY, BigDecimal("0.5"), TimeInForce.GTC, start, "test")
        val stop = StopLossSpec.Fixed(BigDecimal("90"))
        run(
            Signal.Submit(
                OrderRequest.Bracket(
                    "b1",
                    symbol,
                    Side.BUY,
                    BigDecimal("0.5"),
                    entry,
                    BigDecimal("110"),
                    stop,
                    TimeInForce.GTC,
                    start,
                    "test",
                ),
            ),
        )
        await { sent.any { it.id == "e1" } }
    }

    @Test
    fun `a strategy CANCEL after a partial entry fill arms the stop and target for the filled part`() {
        enter()
        await { held().compareTo(BigDecimal("0.3")) == 0 }

        run(Signal.CancelPendingForSymbol(symbol))
        await { exits().size == 2 }

        assertThat(cancelled).containsExactly("e1")
        assertThat(exits().map { it.quantity }).allSatisfy { assertThat(it).isEqualByComparingTo("0.3") }
        assertThat(held()).isEqualByComparingTo("0.3")
    }

    @Test
    fun `a strategy CLOSE after a partial entry fill closes the filled part and arms no exits`() {
        enter()
        await { held().compareTo(BigDecimal("0.3")) == 0 }

        run(Signal.CancelPendingForSymbol(symbol, closing = true), Signal.Sell(symbol, BigDecimal("0.3")))
        await { held().signum() == 0 }

        assertThat(cancelled).containsExactly("e1")
        assertThat(exits()).isEmpty()
    }

    @Test
    fun `stop --flatten after a partial entry fill cancels the rest, closes the filled part and arms no exits`() {
        enter()
        await { held().compareTo(BigDecimal("0.3")) == 0 }

        handle.flattenForStop()
        await { held().signum() == 0 }

        assertThat(cancelled).containsExactly("e1")
        assertThat(sent.filter { it.id != "e1" }.map { it.side to it.quantity.toPlainString() })
            .containsExactly(Side.SELL to "0.3")
        assertThat(exits()).isEmpty()
    }

    @Test
    fun `a bracket cancelled before anything filled arms no exits`() {
        entryFill = null
        enter()

        run(Signal.CancelPendingForSymbol(symbol))
        await { cancelled.isNotEmpty() }
        run()
        Thread.sleep(100)

        assertThat(cancelled).containsExactly("e1")
        assertThat(exits()).isEmpty()
        assertThat(held()).isEqualByComparingTo("0")
    }
}
