package com.qkt.connector.bybit.linear

import com.qkt.app.LiveSession
import com.qkt.app.LiveSessionHandle
import com.qkt.broker.BrokerFactory
import com.qkt.common.Side
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.connector.bybit.BybitDocPayloads.WS_ORDER_ID
import com.qkt.connector.bybit.BybitDocPayloads.frame
import com.qkt.connector.bybit.BybitDocPayloads.page
import com.qkt.connector.bybit.BybitDocPayloads.wsExecution
import com.qkt.connector.bybit.BybitDocPayloads.wsOrder
import com.qkt.connector.bybit.FakeBybitClient
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.persistence.FileStatePersistor
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * qkt restarts while Bybit is filling a linear order (Bybit's documented market Sell of 0.5, as 0.2 then
 * 0.3): the next session, on the same state and account, owns the order again, so its last execution is
 * booked once to its strategy, whether it arrives on the stream after the restart or only the replay finds
 * it, and the 0.2 booked before the restart is never booked again.
 */
class BybitLinearRestartTest {
    private val symbol = "BYBIT_LINEAR:BTCUSDT"
    private val venue = mutableMapOf<String, String>()
    private val empty = """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""
    private val first = wsExecution("e1", "e-a", "0.2", "0.3", "10.549011")
    private val last = wsExecution("e1", "e-b", "0.3", "0", "15.8235165")

    private class Session(
        val client: FakeBybitClient,
        val ticks: LinkedBlockingQueue<Tick>,
        val handle: LiveSessionHandle,
    ) {
        fun held(): BigDecimal = handle.positionsFor("s1").sumOf { it.quantity }

        fun stop() {
            ticks += Tick("BYBIT_LINEAR:BTCUSDT", BigDecimal("95900"), 0L)
            handle.stop()
            handle.awaitTermination(Duration.ofSeconds(5))
        }
    }

    /** Sells 0.5 once, on its first tick of the first session. */
    private class Seller(
        private val enter: Boolean,
    ) : Strategy {
        private var sent = false

        override fun onTick(
            tick: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {
            if (!enter || sent) return
            sent = true
            emit(
                Signal.Submit(
                    OrderRequest.Market("e1", tick.symbol, Side.SELL, BigDecimal("0.5"), TimeInForce.GTC, 0L, "s1"),
                ),
            )
        }
    }

    private fun position(size: String) =
        """{"retCode":0,"retMsg":"OK","result":{"list":[{"symbol":"BTCUSDT","side":"Sell","size":"$size",""" +
            """"avgPrice":"95900.1"}]}}"""

    private fun start(
        state: Path,
        enter: Boolean,
    ): Session {
        val client = FakeBybitClient()
        client.responses.putAll(venue)
        client.responses["/v5/order/create"] = """{"retCode":0,"retMsg":"OK","result":{"orderId":"$WS_ORDER_ID"}}"""
        val ticks = LinkedBlockingQueue<Tick>()
        val factory: BrokerFactory = { bus, clock, _, positions, _ -> BybitLinearBroker(client, bus, clock, positions) }
        val source =
            object : MarketSource {
                override val name = "queued"
                override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

                override fun supports(symbol: String) = true

                override fun liveTicks(symbols: List<String>): TickFeed =
                    object : TickFeed {
                        override fun next(): Tick? = runCatching { ticks.take() }.getOrNull()
                    }
            }
        val handle =
            LiveSession(
                strategies = listOf("s1" to Seller(enter)),
                source = source,
                symbols = listOf(symbol),
                clock = SystemClock(),
                calendar = TradingCalendar.crypto(),
                brokerFactories = mapOf("bybit_linear" to factory),
                persistor = FileStatePersistor(state),
            ).start()
        ticks += Tick(symbol, BigDecimal("95900"), System.currentTimeMillis())
        return Session(client, ticks, handle)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(20)
        }
    }

    private fun Session.execution(entry: String) = client.emitWsFrame("execution", frame("execution", entry))

    /** The first session sends the order and books its first 0.2, then stops. */
    private fun firstSession(state: Path) {
        listOf("/v5/order/realtime", "/v5/order/history", "/v5/execution/list").forEach { venue[it] = empty }
        venue["/v5/position/list"] = empty
        val before = start(state, enter = true)
        await { before.client.posts.any { it.path == "/v5/order/create" } }
        before.execution(first)
        await { before.held().compareTo(BigDecimal("-0.2")) == 0 }
        before.stop()
        venue["/v5/position/list"] = position("0.2")
    }

    @Test
    fun `an execution arriving after the restart is booked once to the order's strategy`(
        @TempDir state: Path,
    ) {
        firstSession(state)
        venue["/v5/order/realtime"] =
            """{"retCode":0,"retMsg":"OK","result":{"list":[${wsOrder("e1", "PartiallyFilled", "0.2")}]}}"""
        venue["/v5/execution/list"] = page(first)

        val after = start(state, enter = false)
        try {
            after.execution(last)
            await { after.held().compareTo(BigDecimal("-0.5")) == 0 }
            Thread.sleep(300)
            assertThat(after.held()).isEqualByComparingTo("-0.5")
            assertThat(after.client.posts.none { it.path == "/v5/order/create" }).isTrue
        } finally {
            after.stop()
        }
    }

    @Test
    fun `an execution made while qkt was down is booked once from the replay, never the one booked before`(
        @TempDir state: Path,
    ) {
        firstSession(state)
        venue["/v5/order/history"] =
            """{"retCode":0,"retMsg":"OK","result":{"list":[${wsOrder("e1", "Filled", "0.5")}]}}"""
        venue["/v5/execution/list"] = page(last, first)
        venue["/v5/position/list"] = position("0.5")

        val after = start(state, enter = false)
        try {
            await { after.held().compareTo(BigDecimal("-0.5")) == 0 }
            Thread.sleep(300)
            assertThat(after.held()).isEqualByComparingTo("-0.5")
        } finally {
            after.stop()
        }
    }
}
