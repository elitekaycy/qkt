package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.derivatives.futures.ContinuousChains
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.ListedContract
import com.qkt.instrument.PriceAdjustment
import com.qkt.instrument.RollHistory
import com.qkt.instrument.RollPolicy
import com.qkt.instrument.RollRecord
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ContinuousMarketSourceTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val quarter = TimeWindow(15 * 60_000L)
    private val root =
        FuturesRoot(
            "BINANCE_UM:BTCUSDT",
            "USDT",
            BigDecimal.ONE,
            BigDecimal("0.1"),
            BigDecimal("0.001"),
            BigDecimal("0.001"),
            null,
            "crypto",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            RollPolicy(8, LocalTime.of(8, 0), PriceAdjustment.PANAMA),
        )
    private val registry =
        ContractCatalogRegistry(
            listOf(root),
            mapOf(
                root.root to
                    ContractCatalog(
                        root.root,
                        listOf(
                            ListedContract("BTCUSDT_240628", ms("2024-06-28T08:00:00Z")),
                            ListedContract("BTCUSDT_240927", ms("2024-09-27T08:00:00Z")),
                            ListedContract("BTCUSDT_241227", ms("2024-12-27T08:00:00Z")),
                        ),
                    ),
            ),
            mapOf(
                root.root to
                    RollHistory(
                        root.root,
                        "8d@08:00",
                        listOf(
                            RollRecord(
                                ms("2024-06-20T08:00:00Z"),
                                "BTCUSDT_240628",
                                "BTCUSDT_240927",
                                "65000",
                                "65000",
                            ),
                            RollRecord(
                                ms("2024-09-19T08:00:00Z"),
                                "BTCUSDT_240927",
                                "BTCUSDT_241227",
                                "63000",
                                "63800",
                            ),
                        ),
                    ),
            ),
        )

    private fun bars(
        contract: String,
        fromIso: String,
        count: Int,
        firstClose: Int,
    ): List<Candle> =
        (0 until count).map { i ->
            val px = BigDecimal(firstClose + i)
            val start = ms(fromIso) + i * quarter.durationMs
            Candle(
                "BINANCE_UM:$contract",
                px,
                px.add(BigDecimal.ONE),
                px.subtract(BigDecimal.ONE),
                px,
                BigDecimal.ONE,
                start,
                start + quarter.durationMs,
            )
        }

    private val inner =
        FakeBars(
            mapOf(
                "BINANCE_UM:BTCUSDT_240927" to bars("BTCUSDT_240927", "2024-09-19T07:00:00Z", 8, 62996),
                "BINANCE_UM:BTCUSDT_241227" to bars("BTCUSDT_241227", "2024-09-19T07:00:00Z", 8, 63796),
                "EXNESS:XAUUSD" to bars("XAUUSD", "2024-09-19T07:00:00Z", 2, 2500),
            ),
        )
    private val source = ContinuousMarketSource(inner, ContinuousChains(requireNotNull(registry.futures())), registry)
    private val window = TimeRange(Instant.parse("2024-09-19T07:00:00Z"), Instant.parse("2024-09-19T09:00:00Z"))

    @Test
    fun `bars switch contracts at the roll and shift the new contract onto the series`() {
        val served = source.bars("BINANCE_UM:BTCUSDT@front", quarter, window).toList()
        assertThat(served).hasSize(8)
        assertThat(served.map { it.symbol }.toSet()).containsExactly("BINANCE_UM:BTCUSDT@front")
        assertThat(served[3].close).isEqualByComparingTo("62999")
        assertThat(served[4].startTime).isEqualTo(ms("2024-09-19T08:00:00Z"))
        assertThat(served[4].close).isEqualByComparingTo("63000")
        assertThat(served[4].high).isEqualByComparingTo("63001")
    }

    @Test
    fun `a bar that would span the roll is refused`() {
        assertThatThrownBy { source.bars("BINANCE_UM:BTCUSDT@front", TimeWindow(86_400_000L), window).toList() }
            .hasMessageContaining("2024-09-19T08:00:00Z")
            .hasMessageContaining("86400000")
    }

    @Test
    fun `an explicit contract is not served at or after its expiry`() {
        val late =
            FakeBars(mapOf("BINANCE_UM:BTCUSDT_240927" to bars("BTCUSDT_240927", "2024-09-27T07:30:00Z", 4, 65000)))
        val cut = ContinuousMarketSource(late, ContinuousChains(requireNotNull(registry.futures())), registry)
        val range = TimeRange(Instant.parse("2024-09-27T07:00:00Z"), Instant.parse("2024-09-27T09:00:00Z"))
        assertThat(cut.bars("BINANCE_UM:BTCUSDT_240927", quarter, range).map { it.startTime }.toList())
            .containsExactly(ms("2024-09-27T07:30:00Z"), ms("2024-09-27T07:45:00Z"))
    }

    @Test
    fun `other symbols pass through untouched`() {
        assertThat(
            source.bars("EXNESS:XAUUSD", quarter, window).toList(),
        ).isEqualTo(inner.bars("EXNESS:XAUUSD", quarter, window).toList())
    }

    @Test
    fun `next without a roll history for it fails at request time`() {
        assertThatThrownBy { source.bars("BINANCE_UM:BTCUSDT@next", quarter, window) }.hasMessageContaining("@next")
    }

    @Test
    fun `plain symbols keep the inner source's per-symbol capabilities and tick path`() {
        val tagged = TaggedSource()
        val wrapped = ContinuousMarketSource(tagged, ContinuousChains(requireNotNull(registry.futures())), registry)
        assertThat(wrapped.capabilitiesFor("EXNESS:XAUUSD")).containsExactly(MarketSourceCapability.VOLUME)
        assertThat(wrapped.ticks("EXNESS:XAUUSD", window).single().price).isEqualByComparingTo("1")
    }

    @Test
    fun `supports never builds a chain`() {
        val broken = ContractCatalogRegistry(listOf(root), emptyMap(), emptyMap())
        val wrapped = ContinuousMarketSource(inner, ContinuousChains(requireNotNull(broken.futures())), broken)
        assertThat(wrapped.supports("BINANCE_UM:BTCUSDT@front")).isTrue()
    }

    /** Answers `ticks` with price 1 and `tickSlice` with price 2, so the path taken is visible. */
    private class TaggedSource : MarketSource {
        override val name = "tagged"
        override val capabilities = setOf(MarketSourceCapability.TICKS, MarketSourceCapability.VOLUME)

        override fun supports(symbol: String) = true

        override fun capabilitiesFor(symbol: String) = setOf(MarketSourceCapability.VOLUME)

        override fun ticks(
            symbol: String,
            range: TimeRange,
        ) = sequenceOf(com.qkt.marketdata.Tick(symbol, BigDecimal.ONE, range.from.toEpochMilli()))

        override fun tickSlice(
            symbol: String,
            fromMs: Long,
            toMs: Long,
        ) = sequenceOf(com.qkt.marketdata.Tick(symbol, BigDecimal("2"), fromMs))
    }

    private class FakeBars(
        private val bySymbol: Map<String, List<Candle>>,
    ) : MarketSource {
        override val name = "fake"
        override val capabilities = setOf(MarketSourceCapability.BARS)

        override fun supports(symbol: String) = true

        override fun bars(
            symbol: String,
            window: TimeWindow,
            range: TimeRange,
        ): Sequence<Candle> =
            bySymbol[symbol].orEmpty().asSequence().filter {
                it.startTime >= range.from.toEpochMilli() && it.startTime < range.to.toEpochMilli()
            }
    }
}
