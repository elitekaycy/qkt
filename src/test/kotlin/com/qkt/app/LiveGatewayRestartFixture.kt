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
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Tick
import com.qkt.persistence.FileStatePersistor
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Duration

/**
 * A Deribit perpetual on a [FakeGateway] account, quoted at [price] every 100 ms, and live sessions of
 * one strategy (`guard`) [start]ed on a state directory: a later session on the same directory is a
 * restarted process. The strategy enters [entry] once, when flat with no entry open.
 */
internal class LiveGatewayRestartFixture(
    private val entry: (symbol: String) -> OrderRequest,
) : AutoCloseable {
    val code = "BTC_USDC-PERPETUAL"
    val symbol = "DERIBIT:BTC_USDC_PERPETUAL"
    val fake = FakeGateway(listOf(code))

    @Volatile var price = "70000"
    private val quoting =
        Thread {
            while (!Thread.currentThread().isInterrupted) {
                val now = System.currentTimeMillis()
                fake.quotes.send(WireQuote(code, price, price, "5", "5", price, null, null, now))
                runCatching { Thread.sleep(100) }.onFailure { return@Thread }
            }
        }.apply { isDaemon = true }

    init {
        fake.instruments[code] = WireInstrument(code, "perpetual", "USDC", "1", "0.5", "0.001", "0.001")
        fake.capabilities = listOf("funding")
    }

    /** Enters once when flat with no entry open, as `WHEN POSITION = 0 AND OPEN_ORDERS = 0` would. */
    inner class Guard : Strategy {
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
            emit(Signal.Submit(entry(tick.symbol)))
        }
    }

    /** A started session, its accounts and its strategy. */
    class Running(
        val handle: LiveSessionHandle,
        private val accounts: AccountDirectory,
        val strategy: LiveGatewayRestartFixture.Guard,
    ) {
        /** Stops the session and closes its accounts, as a process ending does. */
        fun stop() {
            handle.stop()
            handle.awaitTermination(Duration.ofSeconds(5))
            accounts.close()
        }

        /** What the strategy holds, from its position ledger. */
        fun held(): BigDecimal = handle.positionsFor("guard").sumOf { it.quantity }
    }

    /** A live session persisting to [state]. */
    fun start(state: Path): Running {
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

    /** The gateway id of the entry the strategy sent as engine order [engineId]. */
    fun sent(engineId: String) = fake.submits.single { it.clientOrderId.startsWith("$engineId.") }.clientOrderId

    /** Waits up to 15 s for [condition]. */
    fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(20)
        }
    }

    override fun close() {
        quoting.interrupt()
        fake.shutdown()
    }
}
