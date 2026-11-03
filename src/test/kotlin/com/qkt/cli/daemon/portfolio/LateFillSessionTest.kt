package com.qkt.cli.daemon.portfolio

import com.qkt.app.LiveSession
import com.qkt.app.LiveSessionHandle
import com.qkt.broker.Broker
import com.qkt.broker.BrokerFactory
import com.qkt.broker.PaperBroker
import com.qkt.broker.PositionAccountingMode
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.common.TradingCalendar
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.PendingStacks
import com.qkt.events.BrokerEvent
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.positions.LegRole
import com.qkt.risk.FakePnL
import com.qkt.risk.TestClock
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** #1608 through a real session: the entry's fill arrives after the book's close, on a venue answering after submit. */
class LateFillSessionTest : PortfolioRiskAggregatorFixture() {
    private val now = Instant.parse("2024-01-15T15:00:00Z").toEpochMilli()
    private val sent = CopyOnWriteArrayList<OrderRequest>()
    private lateinit var venueBus: EventBus
    private lateinit var handle: LiveSessionHandle

    private val source =
        object : MarketSource {
            override val name = "one-tick"
            override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

            override fun supports(symbol: String) = true

            override fun liveTicks(symbols: List<String>): TickFeed =
                object : TickFeed {
                    private var emitted = false

                    override fun next(): Tick? {
                        if (!emitted) {
                            emitted = true
                            return Tick("EXNESS:X", Money.of("100"), now)
                        }
                        return runCatching { Thread.sleep(Long.MAX_VALUE) }.let { null }
                    }

                    override fun close() = Unit
                }
        }

    private val strategy =
        object : DslCompiledStrategy {
            private var fired = false
            override val declaredStreams = mapOf("x" to HubKey("EXNESS", "X", "1m"))
            override val retentionByKey = emptyMap<HubKey, Int>()
            override val pendingStacks = PendingStacks()

            override fun bindToHub(
                hub: CandleHub,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) = Unit

            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                if (fired) return
                fired = true
                emit(
                    Signal.Submit(
                        OrderRequest.Market(
                            "entry",
                            tick.symbol,
                            Side.BUY,
                            Money.of("1"),
                            TimeInForce.GTC,
                            tick.timestamp,
                            legIntent = LegIntent.Open("entry", LegRole.INDEPENDENT),
                        ),
                    ),
                )
            }
        }

    /** A netting venue that takes every order and fills only when the test says so. */
    private val venue: BrokerFactory =
        { bus, clock, prices, _, _ ->
            venueBus = bus
            val delegate = PaperBroker(bus, clock, prices)
            object : Broker by delegate {
                override fun positionAccountingMode(symbol: String) = PositionAccountingMode.NETTING

                override fun submit(request: OrderRequest): SubmitAck {
                    sent.add(request)
                    return SubmitAck(request.id, request.id, accepted = true)
                }
            }
        }

    private fun fill(request: OrderRequest) =
        venueBus.publish(
            BrokerEvent.OrderFilled(
                request.id,
                request.id,
                request.symbol,
                request.side,
                BigDecimal("100"),
                request.quantity,
                request.strategyId,
                now,
            ),
        )

    private fun await(what: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!what() && System.nanoTime() < deadline) Thread.sleep(10)
        assertThat(what()).isTrue()
    }

    @AfterEach
    fun stop() {
        handle.stop()
        handle.awaitTermination(Duration.ofSeconds(10))
    }

    @Test
    fun `a fill after the halt's close is closed once, however many heartbeats pass while that close works`() {
        handle =
            LiveSession(
                strategies = listOf("test" to strategy),
                source = source,
                symbols = listOf("EXNESS:X"),
                clock = FixedClock(time = now),
                calendar = TradingCalendar.crypto(),
                brokerFactories = mapOf("exness" to venue),
            ).start()
        await { sent.size == 1 }
        val clock = TestClock(0L)
        val child = SessionRiskTarget(handle)
        val book = bookRiskState(FakePnL(BigDecimal.ZERO, BigDecimal.ZERO), clock)
        val aggregator = PortfolioRiskAggregator(listOf(child), book, emptyList(), clock)

        aggregator.failClosed("breach")
        fill(sent.single()) // the entry that was in flight fills only now
        await { child.unclosedLegs() == 1 }

        clock.t += 5_000
        aggregator.evaluate()
        await { sent.size == 2 }
        val close = sent.last()
        assertThat(close.side).isEqualTo(Side.SELL)
        assertThat(close.quantity).isEqualByComparingTo("1")

        repeat(4) {
            clock.t += 60_000
            aggregator.evaluate()
        }
        assertThat(sent).hasSize(2)

        fill(close)
        await { child.unclosedLegs() == 0 && handle.limitReading()?.openLegs == 0 }
        clock.t += 60_000
        aggregator.evaluate()
        assertThat(sent).hasSize(2)
    }
}
