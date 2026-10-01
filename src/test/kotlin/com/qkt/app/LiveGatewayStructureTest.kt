package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.cli.InstrumentFiles
import com.qkt.cli.MarketSourceFactory
import com.qkt.common.Money
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.AccountDirectory
import com.qkt.connectivity.ConnectorContext
import com.qkt.connectivity.ConnectorRegistry
import com.qkt.connector.gateway.FakeGateway
import com.qkt.connector.gateway.WireQuote
import com.qkt.derivatives.options.chain.ChainSnapshot
import com.qkt.derivatives.options.chain.ChainSnapshotStore
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.execution.Trade
import com.qkt.instrument.QuoteSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A structure strategy deployed live on a gateway account, end to end: the four real Deribit book
 * snapshots of `btc-usdc-book-live4` arrive as gateway quotes (shifted to the last minutes), the
 * account's recorder writes them to the book series, the `CHAIN:` stream ticks from that history, the
 * rule opens a put credit spread chosen from the recorded chain, both legs go to the gateway, and the
 * gateway's fills reach the session. No chain file exists before the run.
 */
class LiveGatewayStructureTest {
    private val fixture = Paths.get(requireNotNull(javaClass.getResource("/options/btc-usdc-book-live4")).toURI())
    private val root = "DERIBIT:BTC_USDC"

    private val dsl =
        "STRATEGY spread VERSION 1\nSYMBOLS\n    chain = OPTIONS:DERIBIT.BTC_USDC EVERY 1m,\n" +
            "    iv = CHAIN:DERIBIT.BTC_USDC.atm_iv.7d EVERY 1m\nRULES\n    WHEN iv.close > 0\n" +
            "    THEN OPEN ps = OPTIONS ON DERIBIT:BTC_USDC " +
            "{ SELL PUT DELTA 0.25 DTE 7 TO 30, BUY PUT DELTA 0.10 SAME EXPIRY } SIZING 0.1\n"

    private fun copyCatalog(dir: Path) {
        Files.copy(fixture.resolve("instruments.yaml"), dir.resolve("instruments.yaml"))
        val catalog = dir.resolve("contracts/DERIBIT")
        Files.createDirectories(catalog)
        Files.copy(fixture.resolve("contracts/DERIBIT/BTC_USDC.options.json"), catalog.resolve("BTC_USDC.options.json"))
    }

    private fun quotesOf(
        snapshot: ChainSnapshot,
        atMs: Long,
    ) = snapshot.quotes.map {
        WireQuote(
            it.contract,
            it.bid?.toPlainString(),
            it.ask?.toPlainString(),
            null,
            null,
            it.mark.toPlainString(),
            it.markIv?.toPlainString(),
            it.underlying.toPlainString(),
            atMs,
        )
    }

    private fun await(
        seconds: Long,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + seconds * 1_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `a put spread opens live from the chain the gateway account recorded, and its fills are booked`(
        @TempDir dir: Path,
    ) {
        copyCatalog(dir)
        val registry = InstrumentFiles.registry(dir, explicit = null)
        val snapshots = ChainSnapshotStore(fixture, QuoteSource.BOOK).readDay(root, LocalDate.parse("2026-10-01"))
        val fake = FakeGateway(snapshots.first().quotes.map { it.contract })
        val settings =
            mapOf(
                "type" to "gateway",
                "gateway_url" to fake.url,
                "api_key" to "env:GW_KEY",
                "expected_adapter" to "fake",
                "expected_account_login" to "7",
                "expected_trade_mode" to "demo",
                "chain_snapshot_seconds" to "60",
            )
        val context = ConnectorContext(null, mapOf("GW_KEY" to "secret"), SystemClock(), instruments = registry)
        val accounts =
            AccountDirectory.open(
                listOf(AccountConfig("deribit", "gateway", settings)),
                ConnectorRegistry.discover(),
                context,
            )
        val symbols = listOf("OPTIONS:DERIBIT.BTC_USDC", "CHAIN:DERIBIT.BTC_USDC.atm_iv.7d")
        val strategy = AstCompiler().compile((Dsl.parse(dsl) as ParseResult.Success).value)
        val trades = CopyOnWriteArrayList<Trade>()
        val handle =
            LiveSession(
                strategies = listOf("spread" to strategy),
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
                initialBalance = Money.of("10000"),
                onTrade = { trade, _, _ -> trades += trade },
            ).start()
        try {
            await(10) { fake.quotes.open > 0 }
            // Snapshot i inside minute i of the last four, then the venue keeps quoting the last book live.
            val base = System.currentTimeMillis() / 60_000 * 60_000 - 4 * 60_000
            snapshots.forEachIndexed {
                i,
                snapshot,
                ->
                quotesOf(snapshot, base + i * 60_000 + 1_000).forEach(fake.quotes::send)
            }
            val live =
                Thread {
                    while (!Thread.currentThread().isInterrupted && fake.submits.size < 2) {
                        quotesOf(snapshots.last(), System.currentTimeMillis()).forEach(fake.quotes::send)
                        runCatching { Thread.sleep(2_000) }.onFailure { return@Thread }
                    }
                }.apply { isDaemon = true }.also { it.start() }
            await(150) { fake.submits.size >= 2 }
            live.interrupt()
            val legs = fake.submits.toList()
            // The backtest of the same chain (StructureBacktestTest) picks the same two puts.
            assertThat(legs.map { it.symbol to it.side })
                .containsExactlyInAnyOrder("BTC_USDC-9OCT26-82000-P" to "sell", "BTC_USDC-9OCT26-79000-P" to "buy")
            assertThat(legs).allMatch { it.quantity.toBigDecimal().compareTo("0.1".toBigDecimal()) == 0 }

            val book = snapshots.last().quotes.associateBy { it.contract }
            fake.act {
                legs.forEachIndexed { i, leg ->
                    val quote = book.getValue(leg.symbol)
                    val price = if (leg.side == "sell") quote.bid else quote.ask
                    fill(
                        leg.clientOrderId,
                        "f$i",
                        leg.quantity,
                        requireNotNull(price).toPlainString(),
                        System.currentTimeMillis(),
                    )
                }
            }
            await(10) { trades.size >= 2 }
            assertThat(trades.map { it.symbol }).containsExactlyInAnyOrderElementsOf(
                legs.map { "DERIBIT:" + it.symbol.replace('-', '_') },
            )
            val recorded = ChainSnapshotStore(dir, QuoteSource.BOOK)
            val days =
                listOf(base, System.currentTimeMillis()).map {
                    java.time.Instant
                        .ofEpochMilli(it)
                        .atZone(java.time.ZoneOffset.UTC)
                        .toLocalDate()
                }
            assertThat(days.distinct().flatMap { recorded.readDay(root, it) }).isNotEmpty()
        } finally {
            handle.stop()
            handle.awaitTermination(Duration.ofSeconds(5))
            accounts.close()
            fake.shutdown()
        }
    }
}
