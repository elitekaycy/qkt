package com.qkt.marketdata.source

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class ContinuousMarketSourceEdgesTest {
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
            "crypto",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            RollPolicy(8, LocalTime.of(8, 0), PriceAdjustment.PANAMA),
        )
    private val roll = RollRecord(ms("2024-09-19T08:00:00Z"), "BTCUSDT_240927", "BTCUSDT_241227", "63000", "63800")
    private val registry =
        ContractCatalogRegistry(
            listOf(root),
            mapOf(
                root.root to
                    ContractCatalog(
                        root.root,
                        listOf(
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
                        listOf(roll),
                    ),
            ),
        )
    private val probe = DelegationProbe("inner")
    private val source = ContinuousMarketSource(probe, ContinuousChains(requireNotNull(registry.futures())), registry)
    private val logger = LoggerFactory.getLogger(ContinuousMarketSource::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attach() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
    }

    @Test
    fun `the source is named after its inner source and a stream has no option marks`() {
        assertThat(source.name).isEqualTo("inner")
        assertThat(source.optionMarksFor("BINANCE_UM:BTCUSDT@front")).isNull()
        assertThat(source.optionMarksFor("EXNESS:XAUUSD")).isNull()
    }

    @Test
    fun `tick-resolved fills are refused for a continuous stream`() {
        assertThatThrownBy { source.tickSlice("BINANCE_UM:BTCUSDT@front", 0L, 1L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not supported for continuous futures streams (BINANCE_UM:BTCUSDT@front)")
    }

    @Test
    fun `a request reaching before the first measured roll warns once that it is clipped`() {
        val early = TimeRange(Instant.parse("2024-09-19T06:00:00Z"), Instant.parse("2024-09-19T09:00:00Z"))

        val empty =
            ContinuousMarketSource(
                InMemoryMarketSource(),
                ContinuousChains(requireNotNull(registry.futures())),
                registry,
            )

        assertThat(empty.bars("BINANCE_UM:BTCUSDT@front", TimeWindow(15 * 60_000L), early).toList()).isEmpty()
        empty.bars("BINANCE_UM:BTCUSDT@front", TimeWindow(15 * 60_000L), early).toList()

        val warnings = appender.list.map { it.formattedMessage }.filter { "first measured roll" in it }
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single()).startsWith("BINANCE_UM:BTCUSDT@front is served from its first measured roll")
    }
}
