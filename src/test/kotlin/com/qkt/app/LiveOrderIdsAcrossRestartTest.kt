package com.qkt.app

import com.qkt.cli.MarketSourceFactory
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.AccountDirectory
import com.qkt.connectivity.ConnectorContext
import com.qkt.connectivity.ConnectorRegistry
import com.qkt.connector.gateway.FakeGateway
import com.qkt.connector.gateway.WireInstrument
import com.qkt.connector.gateway.WireQuote
import com.qkt.marketdata.Tick
import com.qkt.persistence.FileStatePersistor
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A session that traded and ended flat, then restarted on the same state, never sends an order id it
 * already sent (#1338): with nothing restored to resume past, the sequence used to start at 0 again.
 */
class LiveOrderIdsAcrossRestartTest {
    private val code = "BTC_USDC-PERPETUAL"
    private val symbol = "DERIBIT:BTC_USDC_PERPETUAL"
    private val fake = FakeGateway(listOf(code))
    private val quoting =
        Thread {
            while (!Thread.currentThread().isInterrupted) {
                fake.quotes.send(
                    WireQuote(code, "70000", "70000", "5", "5", "70000", null, null, System.currentTimeMillis()),
                )
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

    /** Sends the market order the test asks for on the next tick. */
    private class OnDemand : Strategy {
        val next = AtomicReference<Signal?>(null)

        override fun onTick(
            tick: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {
            next.getAndSet(null)?.let(emit)
        }
    }

    private class Running(
        val handle: LiveSessionHandle,
        val accounts: AccountDirectory,
        val strategy: OnDemand,
    ) {
        fun stop() {
            handle.stop()
            handle.awaitTermination(Duration.ofSeconds(5))
            accounts.close()
        }

        fun held(): BigDecimal = handle.positionsFor("ids").sumOf { it.quantity }
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
        val strategy = OnDemand()
        val handle =
            LiveSession(
                strategies = listOf("ids" to strategy),
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

    /** Sends [signal] through [running] and fills it at the venue; returns its engine order id. */
    private fun trade(
        running: Running,
        signal: Signal,
        held: String,
    ): String {
        val sent = fake.submits.size
        running.strategy.next.set(signal)
        await { fake.submits.size > sent }
        val id = fake.submits.last().clientOrderId
        fake.act { fill(id, "f$sent", "0.001", "70000", System.currentTimeMillis()) }
        await { running.held().compareTo(BigDecimal(held)) == 0 }
        return id.substringBeforeLast('.')
    }

    @Test
    fun `a session restarted flat continues its order ids instead of sending them again`(
        @TempDir state: Path,
    ) {
        val before = start(state)
        val first =
            try {
                listOf(
                    trade(before, Signal.Buy(symbol, BigDecimal("0.001")), "0.001"),
                    trade(before, Signal.Sell(symbol, BigDecimal("0.001")), "0"),
                )
            } finally {
                before.stop()
            }

        val after = start(state)
        val again =
            try {
                trade(after, Signal.Buy(symbol, BigDecimal("0.001")), "0.001")
            } finally {
                after.stop()
            }

        assertThat(first).doesNotHaveDuplicates()
        assertThat(first).doesNotContain(again)
    }
}
