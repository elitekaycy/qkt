package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.ListedContract
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DatedContractDataTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val quarter = TimeWindow(15 * 60_000L)
    private val sep = "BINANCE_UM:BTCUSDT_240927"
    private val dec = "BINANCE_UM:BTCUSDT_241227"
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
        )
    private val registry =
        ContractCatalogRegistry(
            listOf(root),
            mapOf(
                root.root to
                    ContractCatalog(
                        root.root,
                        listOf(
                            ListedContract("BTCUSDT_240927", ms("2024-09-27T08:00:00Z"), "63000.5"),
                            ListedContract("BTCUSDT_241227", ms("2024-12-27T08:00:00Z")),
                        ),
                    ),
            ),
        )
    private val dated = DatedContractData(registry)

    private fun bar(
        symbol: String,
        startIso: String,
        close: String,
    ): Candle {
        val start = ms(startIso)
        val px = BigDecimal(close)
        return Candle(symbol, px, px, px, px, BigDecimal.ONE, start, start + quarter.durationMs)
    }

    private fun range(
        from: String,
        to: String,
    ) = TimeRange(Instant.parse(from), Instant.parse(to))

    @Test
    fun `a range covering expiry ends with a settlement bar at the delivery price`() {
        val data = sequenceOf(bar(sep, "2024-09-27T07:45:00Z", "63010"), bar(sep, "2024-09-27T08:00:00Z", "63001"))

        val served = dated.bars(sep, quarter, range("2024-09-27T07:00:00Z", "2024-09-28T00:00:00Z"), data).toList()

        assertThat(served.map { it.startTime }).containsExactly(ms("2024-09-27T07:45:00Z"), ms("2024-09-27T08:00:00Z"))
        val settlement = served.last()
        assertThat(listOf(settlement.open, settlement.high, settlement.low, settlement.close))
            .allSatisfy { assertThat(it).isEqualByComparingTo("63000.5") }
        assertThat(settlement.volume).isEqualByComparingTo("0")
        assertThat(settlement.endTime).isEqualTo(ms("2024-09-27T08:15:00Z"))
    }

    @Test
    fun `without a delivery price the settlement bar repeats the last close`() {
        val data = sequenceOf(bar(dec, "2024-12-27T07:45:00Z", "64123.4"))

        val served = dated.bars(dec, quarter, range("2024-12-27T07:00:00Z", "2024-12-28T00:00:00Z"), data).toList()

        assertThat(served.last().startTime).isEqualTo(ms("2024-12-27T08:00:00Z"))
        assertThat(served.last().close).isEqualByComparingTo("64123.4")
    }

    @Test
    fun `the settlement bar is clipped to the end of the run`() {
        val served =
            dated.bars(sep, quarter, range("2024-09-27T07:00:00Z", "2024-09-27T08:05:00Z"), emptySequence()).toList()

        assertThat(served.single().endTime).isEqualTo(ms("2024-09-27T08:05:00Z"))
    }

    @Test
    fun `ranges that end by expiry or start after it get no settlement print`() {
        val before = dated.bars(sep, quarter, range("2024-09-27T07:00:00Z", "2024-09-27T08:00:00Z"), emptySequence())
        val after = dated.bars(sep, quarter, range("2024-09-27T08:00:01Z", "2024-09-28T00:00:00Z"), emptySequence())

        assertThat(before.toList()).isEmpty()
        assertThat(after.toList()).isEmpty()
    }

    @Test
    fun `tick ranges get one settlement tick at expiry`() {
        val data = sequenceOf(Tick(sep, BigDecimal("63010"), ms("2024-09-27T07:59:59Z")))

        val served = dated.ticks(sep, range("2024-09-27T07:00:00Z", "2024-09-28T00:00:00Z"), data).toList()

        assertThat(served.last()).isEqualTo(Tick(sep, BigDecimal("63000.5"), ms("2024-09-27T08:00:00Z")))
    }

    @Test
    fun `symbols that are not dated contracts pass through unchanged`() {
        val data = sequenceOf(bar("EXNESS:XAUUSD", "2024-09-27T07:45:00Z", "2600"))

        assertThat(
            dated.bars("EXNESS:XAUUSD", quarter, range("2024-09-27T07:00:00Z", "2024-09-28T00:00:00Z"), data).toList(),
        ).containsExactlyElementsOf(data.toList())
    }
}
