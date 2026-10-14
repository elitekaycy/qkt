package com.qkt.marketdata.ingest.check

import com.qkt.marketdata.ingest.record.TickRecord
import java.math.BigDecimal
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SpikeFlagsTest {
    private val day = LocalDate.of(2024, 1, 2)

    private fun trades(prices: List<String>) =
        prices.mapIndexed {
            i,
            p,
            ->
            TickRecord(1_704_153_600_000L + i * 1000, i.toLong(), BigDecimal(p), null, null, null, null, null)
        }

    private fun trend() = (0 until 200).map { "%.2f".format(60000 + it * 3.0 + (if (it % 2 == 0) 0.0 else 0.5)) }

    @Test
    fun `a print that jumps away and straight back is flagged and kept`() {
        val prices = trend().toMutableList().also { it[100] = "61200.00" }
        val tally = CheckTally()
        SpikeFlags.scan(day, trades(prices), tally)

        assertThat(tally.spikes).hasSize(1)
    }

    @Test
    fun `the bid and ask bounce between trades is not a spike`() {
        val tally = CheckTally()
        SpikeFlags.scan(day, trades(trend()), tally)

        assertThat(tally.spikes).isEmpty()
    }
}
