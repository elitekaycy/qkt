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
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Duration
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A continuous futures stream traded live on a gateway account across a roll, end to end: the strategy
 * enters on the front contract, the roll instant passes, the stream measures the roll from the gateway's
 * closed minute bars and appends it to the history on disk, and the stream's lane carries the position
 * with two legs to the gateway: a reduce-only close of the old contract and an open of the new one.
 */
class LiveContinuousGatewayTest {
    private val minute = 60_000L
    private val day = 86_400_000L

    @Test
    fun `a live position is carried across a roll measured from the gateway's own bars`(
        @TempDir dir: Path,
    ) {
        val rollAt = (System.currentTimeMillis() / minute + 2) * minute
        val atUtc = LocalTime.ofSecondOfDay((rollAt / 1_000) % 86_400)
        val codes = listOf("BTCUSDT_C0", "BTCUSDT_C1", "BTCUSDT_C2")
        val expiries = listOf(rollAt - 29 * day, rollAt + day, rollAt + 31 * day)
        val root =
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
        val catalog = ContractCatalog(root.root, codes.zip(expiries).map { (c, e) -> ListedContract(c, e) })
        val store =
            RollHistoryStore(dir).also {
                it.write(
                    RollHistory(
                        root.root,
                        "1d@$atUtc",
                        listOf(
                            RollRecord(
                                rollAt - 30 * day,
                                codes[0],
                                codes[1],
                                "60000",
                                "60100",
                            ),
                        ),
                    ),
                )
            }
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
        val fake = FakeGateway(codes.drop(1))
        codes.drop(1).zip(expiries.drop(1)).forEach { (code, expiry) ->
            fake.instruments[code] =
                WireInstrument(code, "future", "USDT", "1", "0.1", "0.001", "0.001", expiry = expiry)
        }
        fake.bars[codes[1] to minute] = listOf(WireBar(rollAt - minute, "70000", "70000", "70000", "70000", "1"))
        fake.bars[codes[2] to minute] = listOf(WireBar(rollAt - minute, "70500", "70500", "70500", "70500", "1"))
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
        val strategy = AstCompiler().compile((Dsl.parse(dsl) as ParseResult.Success).value)
        val symbols = listOf("BINANCE_UM:BTCUSDT@front")
        val handle =
            LiveSession(
                strategies = listOf("hold" to strategy),
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
            ).start()
        val quoting =
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
        try {
            await(10) { fake.quotes.open > 0 }
            quoting.start()
            await(150) { fake.submits.any { it.symbol == codes[1] } }
            val entry = fake.submits.first { it.symbol == codes[1] }
            fake.act { fill(entry.clientOrderId, "f-entry", entry.quantity, "70000", System.currentTimeMillis()) }

            await(240) { fake.submits.count { it.clientOrderId.startsWith("roll:") } >= 1 }
            val close = fake.submits.first { it.clientOrderId.startsWith("roll:") && it.symbol == codes[1] }
            fake.act { fill(close.clientOrderId, "f-close", close.quantity, "70000", System.currentTimeMillis()) }
            await(30) { fake.submits.any { it.clientOrderId.startsWith("roll:") && it.symbol == codes[2] } }
            val open = fake.submits.first { it.clientOrderId.startsWith("roll:") && it.symbol == codes[2] }

            assertThat(close.symbol to close.side).isEqualTo(codes[1] to "sell")
            assertThat(close.reduceOnly).isTrue()
            assertThat(open.symbol to open.side).isEqualTo(codes[2] to "buy")
            assertThat(open.quantity.toBigDecimal()).isEqualByComparingTo(entry.quantity.toBigDecimal())
            val measured = store.read(root.root)!!.find(rollAt, codes[1], codes[2])
            assertThat(measured?.fromPrice).isEqualTo("70000")
            assertThat(measured?.toPrice).isEqualTo("70500")
        } finally {
            quoting.interrupt()
            handle.stop()
            handle.awaitTermination(Duration.ofSeconds(5))
            accounts.close()
            fake.shutdown()
        }
    }

    private fun await(
        seconds: Long,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + seconds * 1_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(50)
        }
    }
}
