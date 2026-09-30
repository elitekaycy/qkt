package com.qkt.derivatives.futures

import com.qkt.instrument.ContinuousSelector
import com.qkt.instrument.ListedContract
import com.qkt.instrument.PriceAdjustment
import com.qkt.instrument.RollPolicy
import java.time.Instant
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RollScheduleTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val sep = ListedContract("BTCUSDT_240927", ms("2024-09-27T08:00:00Z"))
    private val dec = ListedContract("BTCUSDT_241227", ms("2024-12-27T08:00:00Z"))
    private val mar = ListedContract("BTCUSDT_250328", ms("2025-03-28T08:00:00Z"))
    private val policy = RollPolicy(8, LocalTime.of(8, 0), PriceAdjustment.PANAMA)
    private val schedule = RollSchedule(listOf(dec, mar, sep), policy)

    @Test
    fun `transitions follow expiry order`() {
        assertThat(schedule.contracts).containsExactly(sep, dec, mar)
        assertThat(schedule.transitions).containsExactly(
            RollTransition(ms("2024-09-19T08:00:00Z"), 0, 1),
            RollTransition(ms("2024-12-19T08:00:00Z"), 1, 2),
        )
    }

    @Test
    fun `the front contract changes exactly at the roll instant`() {
        assertThat(schedule.contractAt(ms("2024-09-19T07:59:59.999Z"), ContinuousSelector.FRONT)).isEqualTo(sep)
        assertThat(schedule.contractAt(ms("2024-09-19T08:00:00Z"), ContinuousSelector.FRONT)).isEqualTo(dec)
        assertThat(schedule.contractAt(ms("2024-01-01T00:00:00Z"), ContinuousSelector.FRONT)).isEqualTo(sep)
    }

    @Test
    fun `next is one contract behind front and runs out first`() {
        assertThat(schedule.contractAt(ms("2024-08-01T00:00:00Z"), ContinuousSelector.NEXT)).isEqualTo(dec)
        assertThat(schedule.contractAt(ms("2025-01-01T00:00:00Z"), ContinuousSelector.NEXT)).isNull()
    }

    @Test
    fun `nothing is front after the last contract expires`() {
        assertThat(schedule.contractAt(ms("2025-03-28T07:59:59Z"), ContinuousSelector.FRONT)).isEqualTo(mar)
        assertThat(schedule.frontIndexAt(ms("2025-03-28T08:00:00Z"))).isNull()
    }

    @Test
    fun `rolls that do not strictly increase are refused`() {
        val a = ListedContract("X_240927", ms("2024-09-27T08:00:00Z"))
        val b = ListedContract("X_240927N", ms("2024-09-27T09:00:00Z"))
        val sameDay = RollPolicy(0, LocalTime.of(7, 0), PriceAdjustment.NONE)
        assertThatThrownBy { RollSchedule(listOf(a, b, dec), sameDay) }
            .hasMessageContaining("X_240927")
            .hasMessageContaining("X_240927N")
    }

    @Test
    fun `an empty chain is refused`() {
        assertThatThrownBy { RollSchedule(emptyList(), policy) }.hasMessageContaining("no contracts")
    }
}
