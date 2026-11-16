package com.qkt.marketdata.ingest.check

import com.qkt.marketdata.ingest.record.BarRecord
import com.qkt.marketdata.ingest.record.FundingRecord
import com.qkt.marketdata.ingest.record.TickRecord
import java.math.BigDecimal
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Spike flags price quotes at their mid and bars at their close, and ignore records without a price. */
class SpikeFlagsRecordKindsTest {
    private val day = LocalDate.of(2024, 1, 2)
    private val t0 = 1_704_153_600_000L

    private fun prices(): List<BigDecimal> =
        (0 until 200).map { i ->
            val p = if (i == 100) 61_200.0 else 60_000 + i * 3.0 + (if (i % 2 == 0) 0.0 else 0.5)
            BigDecimal("%.2f".format(p))
        }

    @Test
    fun `a quote mid that jumps away and straight back is flagged`() {
        val half = BigDecimal("0.50")
        val quotes =
            prices().mapIndexed { i, p ->
                TickRecord(t0 + i * 1000, i.toLong(), null, null, p - half, p + half, null, null)
            }
        val tally = CheckTally()

        SpikeFlags.scan(day, quotes, tally)

        assertThat(tally.spikes).hasSize(1)
        assertThat(tally.spikes.single()).startsWith("$day 2024-01-02T00:01:40Z: ")
    }

    @Test
    fun `a bar close that jumps away and straight back is flagged`() {
        val bars =
            prices().mapIndexed { i, p ->
                BarRecord(t0 + i * 60_000, i.toLong(), p, p, p, p, BigDecimal.ONE)
            }
        val tally = CheckTally()

        SpikeFlags.scan(day, bars, tally)

        assertThat(tally.spikes).hasSize(1)
    }

    @Test
    fun `one-sided quotes and funding records carry no price to judge`() {
        val oneSided =
            prices().mapIndexed { i, p -> TickRecord(t0 + i * 1000, i.toLong(), null, null, p, null, null, null) }
        val funding = prices().mapIndexed { i, p -> FundingRecord(t0 + i * 1000, i.toLong(), BigDecimal("0.0001"), p) }
        val tally = CheckTally()

        SpikeFlags.scan(day, oneSided + funding, tally)

        assertThat(tally.spikes).isEmpty()
    }
}
