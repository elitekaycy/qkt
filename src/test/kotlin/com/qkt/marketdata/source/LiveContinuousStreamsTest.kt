package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.TimeRange
import com.qkt.derivatives.futures.ContinuousChains
import com.qkt.derivatives.futures.LiveRoll
import com.qkt.derivatives.futures.RollSchedule
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.ContractCatalogStore
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.ListedContract
import com.qkt.instrument.PriceAdjustment
import com.qkt.instrument.RollHistory
import com.qkt.instrument.RollHistoryStore
import com.qkt.instrument.RollPolicy
import com.qkt.instrument.RollRecord
import com.qkt.marketdata.Candle
import com.qkt.marketdata.TickFeed
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `@front` and `@next` of one root measure the same roll live; both land in the history and the chains. */
class LiveContinuousStreamsTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

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
            RollPolicy(8, LocalTime.of(8, 0), PriceAdjustment.PANAMA),
        )
    private val codes = listOf("BTCUSDT_240927", "BTCUSDT_241227", "BTCUSDT_250328", "BTCUSDT_250627")
    private val catalog =
        ContractCatalog(
            root.root,
            codes.zip(listOf("2024-09-27", "2024-12-27", "2025-03-28", "2025-06-27")).map { (c, d) ->
                ListedContract(c, ms("${d}T08:00:00Z"))
            },
        )
    private val september = ms("2024-09-19T08:00:00Z")
    private val december = RollSchedule(catalog.contracts, root.roll!!).transitions[1]
    private val stored =
        RollHistory(
            root.root,
            "8d@08:00",
            listOf(
                RollRecord(september, codes[0], codes[1], "63000", "63800"),
                RollRecord(september, codes[1], codes[2], "63800", "64700"),
            ),
        )
    private val closes = mapOf(codes[1] to "97000", codes[2] to "98500", codes[3] to "99900")

    private val account =
        object : MarketSource {
            override val name = "fake"
            override val capabilities = emptySet<MarketSourceCapability>()

            override fun supports(symbol: String) = true

            override fun liveTicks(symbols: List<String>): TickFeed =
                object : TickFeed {
                    override fun next() = null
                }

            override fun bars(
                symbol: String,
                window: TimeWindow,
                range: TimeRange,
            ): Sequence<Candle> {
                val close = closes[symbol.substringAfter(':')] ?: return emptySequence()
                val start = ms("2024-12-19T07:59:00Z")
                return sequenceOf(
                    Candle(
                        symbol,
                        BigDecimal(close),
                        BigDecimal(close),
                        BigDecimal(close),
                        BigDecimal(close),
                        BigDecimal.ONE,
                        start,
                        start + 60_000,
                    ),
                )
            }
        }

    private fun streams(dir: Path): Pair<LiveContinuousStreams, ContinuousChains> {
        val store = RollHistoryStore(dir).also { it.write(stored) }
        ContractCatalogStore(dir).write(catalog)
        val directory =
            requireNotNull(
                ContractCatalogRegistry(
                    listOf(root),
                    mapOf(root.root to catalog),
                    mapOf(
                        root.root to stored,
                    ),
                ).futures(),
            )
        val chains = ContinuousChains(directory)
        return LiveContinuousStreams(
            chains,
            directory,
            store,
            account,
            object : Clock {
                override fun now() = december.atMs
            },
        ) to
            chains
    }

    @Test
    fun `both streams' records of one roll are appended and both chains extended`(
        @TempDir dir: Path,
    ) {
        val (streams, chains) = streams(dir)

        assertThat(streams.measure("BINANCE_UM:BTCUSDT@front", december)).isInstanceOf(LiveRoll.Measured::class.java)
        assertThat(streams.measure("BINANCE_UM:BTCUSDT@next", december)).isInstanceOf(LiveRoll.Measured::class.java)

        val history = RollHistoryStore(dir).read(root.root)!!
        assertThat(history.find(december.atMs, codes[1], codes[2])?.toPrice).isEqualTo("98500")
        assertThat(history.find(december.atMs, codes[2], codes[3])?.toPrice).isEqualTo("99900")
        assertThat(chains.chainFor("BINANCE_UM:BTCUSDT@front")!!.covers(2)).isTrue()
        assertThat(chains.chainFor("BINANCE_UM:BTCUSDT@next")!!.covers(3)).isTrue()
    }

    @Test
    fun `the two streams measuring at the same moment never lose each other's record`(
        @TempDir dir: Path,
    ) {
        repeat(10) { round ->
            val (streams, _) = streams(dir.resolve("r$round"))
            val threads =
                listOf("@front", "@next").map { s ->
                    Thread { streams.measure("BINANCE_UM:BTCUSDT$s", december) }
                }
            threads.forEach(Thread::start)
            threads.forEach(Thread::join)

            val history = RollHistoryStore(dir.resolve("r$round")).read(root.root)!!
            assertThat(history.rolls.count { it.atMs == december.atMs }).isEqualTo(2)
        }
    }
}
