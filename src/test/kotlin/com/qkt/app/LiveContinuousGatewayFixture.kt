package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.cli.MarketSourceFactory
import com.qkt.common.Money
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.AccountDirectory
import com.qkt.connectivity.ConnectorContext
import com.qkt.connectivity.ConnectorRegistry
import com.qkt.connector.gateway.FakeGateway
import com.qkt.connector.gateway.WireBar
import com.qkt.connector.gateway.WireInstrument
import com.qkt.connector.gateway.WireQuote
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.LayeredInstrumentRegistry
import com.qkt.instrument.ListedContract
import com.qkt.instrument.PriceAdjustment
import com.qkt.instrument.RollHistory
import com.qkt.instrument.RollHistoryStore
import com.qkt.instrument.RollPolicy
import com.qkt.instrument.RollRecord
import com.qkt.instrument.StandardInstrumentRegistry
import com.qkt.persistence.NoopStatePersistor
import com.qkt.persistence.StatePersistor
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Duration
import java.time.LocalTime

/**
 * A gateway account trading the continuous stream `BINANCE_UM:BTCUSDT@front`, whose next roll falls two
 * to three minutes from now: the front contract quotes 70000 and the next one 70500, both with a closed minute bar
 * before the roll for the stream to measure it from. The roll history lives on disk under [dir], so a
 * session [start]ed later reads what an earlier one measured, as a restarted process would.
 */
internal class LiveContinuousGatewayFixture(
    dir: Path,
) : AutoCloseable {
    private val minute = 60_000L
    private val day = 86_400_000L

    // Two to three minutes ahead: a whole minute candle closes, and the strategy enters on the front
    // contract, before the roll even when the session is slow to start.
    val rollAt = (System.currentTimeMillis() / minute + 3) * minute
    val codes = listOf("BTCUSDT_C0", "BTCUSDT_C1", "BTCUSDT_C2")
    private val expiries = listOf(rollAt - 29 * day, rollAt + day, rollAt + 31 * day)
    private val atUtc = LocalTime.ofSecondOfDay((rollAt / 1_000) % 86_400)
    private val root =
        FuturesRoot(
            "BINANCE_UM:BTCUSDT",
            "USDT",
            BigDecimal.ONE,
            BigDecimal("0.1"),
            BigDecimal("0.001"),
            BigDecimal("0.001"),
            null,
            null,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            RollPolicy(1, atUtc, PriceAdjustment.PANAMA),
            expiryGuardHours = 1,
        )
    private val catalog = ContractCatalog(root.root, codes.zip(expiries).map { (c, e) -> ListedContract(c, e) })
    val store =
        RollHistoryStore(dir).also {
            it.write(
                RollHistory(
                    root.root,
                    "1d@$atUtc",
                    listOf(RollRecord(rollAt - 30 * day, codes[0], codes[1], "60000", "60100")),
                ),
            )
        }
    val fake = FakeGateway(codes.drop(1))
    private val quoting =
        Thread {
            while (!Thread.currentThread().isInterrupted) {
                val now = System.currentTimeMillis()
                val front = if (now < rollAt) codes[1] else codes[2]
                val price = if (now < rollAt) "70000" else "70500"
                fake.quotes.send(WireQuote(front, price, price, "5", "5", price, null, null, now))
                if (now <
                    rollAt
                ) {
                    fake.quotes.send(WireQuote(codes[2], "70500", "70500", "5", "5", "70500", null, null, now))
                }
                runCatching { Thread.sleep(500) }.onFailure { return@Thread }
            }
        }.apply { isDaemon = true }

    init {
        codes.drop(1).zip(expiries.drop(1)).forEach { (code, expiry) ->
            fake.instruments[code] =
                WireInstrument(code, "future", "USDT", "1", "0.1", "0.001", "0.001", expiry = expiry)
        }
        fake.bars[codes[1] to minute] = listOf(WireBar(rollAt - minute, "70000", "70000", "70000", "70000", "1"))
        fake.bars[codes[2] to minute] = listOf(WireBar(rollAt - minute, "70500", "70500", "70500", "70500", "1"))
    }

    /** A live session holding the stream (`BUY 0.01`), on the roll history on disk now, persisting to [persistor]. */
    fun start(persistor: StatePersistor = NoopStatePersistor()): Running {
        val futures =
            ContractCatalogRegistry(
                listOf(root),
                mapOf(root.root to catalog),
                mapOf(
                    root.root to store.read(root.root)!!,
                ),
                store,
            )
        val registry = LayeredInstrumentRegistry(listOf(futures, StandardInstrumentRegistry))
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
                listOf(AccountConfig("binance_um", "gateway", settings)),
                ConnectorRegistry.discover(),
                ConnectorContext(null, mapOf("GW_KEY" to "secret"), SystemClock(), instruments = registry),
            )
        val dsl =
            "STRATEGY hold VERSION 1\nSYMBOLS\n    btc = BINANCE_UM:BTCUSDT@front EVERY 1m\n" +
                "RULES\n    WHEN btc.close > 0\n    THEN BUY btc SIZING 0.01 EXIT AFTER 2d\n"
        val symbols = listOf("BINANCE_UM:BTCUSDT@front")
        val handle =
            LiveSession(
                strategies = listOf("hold" to AstCompiler().compile((Dsl.parse(dsl) as ParseResult.Success).value)),
                source =
                    MarketSourceFactory.composite(
                        accounts.marketDataRoutes(),
                        "local",
                        instruments = registry,
                    )(symbols),
                symbols = symbols,
                candleWindow = TimeWindow.ONE_MINUTE,
                clock = SystemClock(),
                calendar = TradingCalendar.crypto(),
                brokerFactories = accounts.orderEntry(),
                instrumentRegistry = registry,
                initialBalance = Money.of("100000"),
                persistor = persistor,
            ).start()
        return Running(handle, accounts)
    }

    /** Starts quoting both contracts, once the session's quote stream is open. */
    fun quote() {
        await(10) { fake.quotes.open > 0 }
        quoting.start()
    }

    /** Waits up to [seconds] for [condition]. */
    fun await(
        seconds: Long,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + seconds * 1_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(50)
        }
    }

    override fun close() {
        quoting.interrupt()
        fake.shutdown()
    }

    /** A started session and the accounts it trades on. */
    class Running(
        private val handle: LiveSessionHandle,
        private val accounts: AccountDirectory,
    ) {
        /** Stops the session and closes its accounts, as a process ending does. */
        fun stop() {
            handle.stop()
            handle.awaitTermination(Duration.ofSeconds(5))
            accounts.close()
        }
    }
}
