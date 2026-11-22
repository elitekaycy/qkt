package com.qkt.derivatives.futures

import com.qkt.instrument.ContinuousSelector
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.ListedContract
import com.qkt.instrument.PriceAdjustment
import com.qkt.instrument.RollHistory
import com.qkt.instrument.RollPolicy
import com.qkt.instrument.RollRecord
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A rebuild over a known history keeps its records and only appends rolls after them (#1754): it neither re-prices
 * a record a session may have booked against nor prepends a roll before the first, which would shift every contract
 * after it. `remeasure` prices every roll the bars can again.
 */
class RollHistoryAppendOnlyTest {
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
    private val codes = listOf("BTCUSDT_240628", "BTCUSDT_240927", "BTCUSDT_241227", "BTCUSDT_250328")
    private val catalog =
        ContractCatalog(
            root.root,
            codes.zip(listOf("2024-06-28", "2024-09-27", "2024-12-27", "2025-03-28")).map { (c, d) ->
                ListedContract(c, ms("${d}T08:00:00Z"))
            },
        )
    private val june = ms("2024-06-20T08:00:00Z")
    private val september = ms("2024-09-19T08:00:00Z")
    private val december = ms("2024-12-19T08:00:00Z")

    /** Every front roll priced by the bars: leaving at 100 x its index, entering 10 above. */
    private val rolls = listOf(june to "2024-06-20", september to "2024-09-19", december to "2024-12-19")

    private fun bars(
        contract: String,
        day: LocalDate,
    ): List<Candle> {
        val at = rolls.indexOfFirst { it.second == day.toString() }.takeIf { it >= 0 } ?: return emptyList()
        val close =
            when (contract) {
                codes[at] -> BigDecimal(100 * (at + 1))
                codes[at + 1] -> BigDecimal(100 * (at + 1) + 10)
                else -> return emptyList()
            }
        val start = ms("${day}T07:59:00Z")
        return listOf(Candle("BINANCE_UM:$contract", close, close, close, close, BigDecimal.ONE, start, start + 60_000))
    }

    /** A live session's September roll, priced 3 above what the bars give. */
    private val known =
        RollHistory(root.root, "8d@08:00", listOf(RollRecord(september, codes[1], codes[2], "200", "213")))

    private fun build(remeasure: Boolean) =
        RollHistoryBuilder(known = known, remeasure = remeasure, bars = ::bars)
            .build(root, catalog, setOf(ContinuousSelector.FRONT))
            .rolls

    @Test
    fun `a rebuild keeps the known record, appends the roll after it and prepends none before it`() {
        assertThat(build(remeasure = false))
            .containsExactly(known.rolls.single(), RollRecord(december, codes[2], codes[3], "300", "310"))
    }

    @Test
    fun `remeasure prices every roll the bars can again`() {
        assertThat(build(remeasure = true)).containsExactly(
            RollRecord(june, codes[0], codes[1], "100", "110"),
            RollRecord(september, codes[1], codes[2], "200", "210"),
            RollRecord(december, codes[2], codes[3], "300", "310"),
        )
    }
}
