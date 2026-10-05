package com.qkt.app

import com.qkt.cli.MarketSourceFactory
import com.qkt.common.Side
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.AccountDirectory
import com.qkt.connectivity.ConnectorContext
import com.qkt.connectivity.ConnectorRegistry
import com.qkt.connector.gateway.FakeGateway
import com.qkt.connector.gateway.WireInstrument
import com.qkt.connector.gateway.WireQuote
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Tick
import com.qkt.persistence.FileStatePersistor
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * qkt restarts inside the window between a bracket entry's partial fill and the venue cancelling its
 * remainder (#1329): the next session, on the same state and the gateway's account, books the order
 * as cancelled with its filled part, holds exactly the fills, arms the stop and target for that part,
 * counts no open entry, and sends nothing again.
 */
class LiveGatewayPartFillRestartTest {
    private val code = "BTC_USDC-PERPETUAL"
    private val symbol = "DERIBIT:BTC_USDC_PERPETUAL"
    private val fake = FakeGateway(listOf(code))
    private val quoting =
        Thread {
            while (!Thread.currentThread().isInterrupted) {
                val now = System.currentTimeMillis()
                fake.quotes.send(WireQuote(code, "70000", "70000", "5", "5", "70000", null, null, now))
                runCatching { Thread.sleep(100) }.onFailure { return@Thread }
            }
        }.apply { isDaemon = true }

    init {
        fake.instruments[code] = WireInstrument(code, "perpetual", "USDC", "1", "0.5", "0.001", "0.001")
        fake.capabilities = listOf("funding")
    }

    @AfterEach
    fun close() {
        quoting.interrupt()
        fake.shutdown()
    }

    /** Enters one bracket when flat with no entry open, as `WHEN POSITION = 0 AND OPEN_ORDERS = 0` would. */
    private class Guard : Strategy {
        @Volatile var openOrders = -1

        @Volatile var entered = false

        override fun onTick(
            tick: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {
            openOrders = ctx.openOrders.entryCountFor(tick.symbol)
            val held = ctx.positions.positionFor(tick.symbol)?.quantity ?: BigDecimal.ZERO
            if (entered || openOrders != 0 || held.signum() != 0) return
            entered = true
            val quantity = BigDecimal("0.5")
            val entry = OrderRequest.Market("e1", tick.symbol, Side.BUY, quantity, TimeInForce.GTC, 5L, "guard")
            val stop = StopLossSpec.Fixed(BigDecimal("69000"))
            val bracket =
                OrderRequest.Bracket(
                    "b1",
                    tick.symbol,
                    Side.BUY,
                    quantity,
                    entry,
                    BigDecimal("71000"),
                    stop,
                    TimeInForce.GTC,
                    5L,
                    "guard",
                )
            emit(Signal.Submit(bracket))
        }
    }

    private class Running(
        val handle: LiveSessionHandle,
        val accounts: AccountDirectory,
        val strategy: Guard,
    ) {
        fun stop() {
            handle.stop()
            handle.awaitTermination(Duration.ofSeconds(5))
            accounts.close()
        }

        fun held(): BigDecimal = handle.positionsFor("guard").sumOf { it.quantity }
    }

    private fun start(state: Path): Running {
        val settings =
            mapOf(
                "type" to "gateway",
                "gateway_url" to fake.url,
                "api_key" to "env:GW_KEY",
                "expected_adapter" to "fake",
                "expected_account_login" to "7",
                "expected_trade_mode" to "demo",
            )
        val accounts =
            AccountDirectory.open(
                listOf(AccountConfig("deribit", "gateway", settings)),
                ConnectorRegistry.discover(),
                ConnectorContext(null, mapOf("GW_KEY" to "secret"), SystemClock()),
            )
        val strategy = Guard()
        val handle =
            LiveSession(
                strategies = listOf("guard" to strategy),
                source = MarketSourceFactory.composite(accounts.marketDataRoutes(), "local")(listOf(symbol)),
                symbols = listOf(symbol),
                clock = SystemClock(),
                calendar = TradingCalendar.crypto(),
                brokerFactories = accounts.orderEntry(),
                persistor = FileStatePersistor(state),
            ).start()
        if (!quoting.isAlive) {
            await { fake.quotes.open > 0 }
            quoting.start()
        }
        return Running(handle, accounts, strategy)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(20)
        }
    }

    private fun entryId() = fake.submits.single { it.clientOrderId.startsWith("e1.") }.clientOrderId

    private fun exits() = fake.submits.filter { it.side == "sell" }

    private fun assertRecovered(
        after: Running,
        state: Path,
    ) {
        await { exits().size == 2 }
        val seenAt = System.currentTimeMillis()
        await { after.strategy.openOrders == 0 && System.currentTimeMillis() - seenAt > 300 }
        assertThat(fake.submits.count { it.clientOrderId.startsWith("e1.") }).isEqualTo(1)
        assertThat(after.held()).isEqualByComparingTo("0.3")
        assertThat(
            exits().map {
                it.type to
                    BigDecimal(it.stopPrice ?: it.limitPrice).stripTrailingZeros().toPlainString()
            },
        ).containsExactlyInAnyOrder("stop" to "69000", "limit" to "71000")
        assertThat(exits().map { BigDecimal(it.quantity) }).allSatisfy { assertThat(it).isEqualByComparingTo("0.3") }
        assertThat(after.strategy.entered).isFalse()
        assertThat(FileStatePersistor(state).loadPendingOrders("guard").keys).doesNotContain("e1")
    }

    @Test
    fun `a remainder cancelled while qkt was down, after a fill it booked, ends with the filled part protected`(
        @TempDir state: Path,
    ) {
        val before = start(state)
        await { fake.submits.isNotEmpty() }
        fake.act { fill(entryId(), "f1", "0.3", "70000", System.currentTimeMillis()) }
        await { before.held().compareTo(BigDecimal("0.3")) == 0 }
        before.stop()
        fake.act { cancel(entryId()) }

        val after = start(state)
        try {
            assertRecovered(after, state)
        } finally {
            after.stop()
        }
    }

    @Test
    fun `a fill and the remainder's cancel both made while qkt was down end with the filled part protected`(
        @TempDir state: Path,
    ) {
        val before = start(state)
        await { fake.submits.isNotEmpty() }
        before.stop()
        fake.act { cancelAfterFilling(entryId(), "f1", "0.3", "70000") }

        val after = start(state)
        try {
            assertRecovered(after, state)
        } finally {
            after.stop()
        }
    }
}
