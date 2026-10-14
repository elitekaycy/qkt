package com.qkt.marketdata.ingest.parse

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EpochUnitTest {
    private fun d(vararg v: String) = v.map(::BigDecimal)

    @Test
    fun `tells seconds, milliseconds, microseconds and nanoseconds apart by magnitude`() {
        assertThat(EpochUnit.detect(d("1595311200", "1595314800"))).isEqualTo(EpochUnit.SECONDS)
        assertThat(EpochUnit.detect(d("1590969600000"))).isEqualTo(EpochUnit.MILLIS)
        assertThat(EpochUnit.detect(d("1748736000000000"))).isEqualTo(EpochUnit.MICROS)
        assertThat(EpochUnit.detect(d("1744792500000000000"))).isEqualTo(EpochUnit.NANOS)
    }

    @Test
    fun `refuses a column that mixes units, as Binance spot did across 2025`() {
        assertThat(EpochUnit.detect(d("1735689540000", "1735689600000000"))).isNull()
    }

    @Test
    fun `an eight-digit date is not an epoch`() {
        assertThat(EpochUnit.detect(d("20240101"))).isNull()
    }

    @Test
    fun `floors sub-millisecond digits and says so`() {
        val bybit = BigDecimal("1791849600.1695")

        assertThat(EpochUnit.SECONDS.toMillis(bybit)).isEqualTo(1_791_849_600_169L)
        assertThat(EpochUnit.SECONDS.hasSubMillis(bybit)).isTrue()
        assertThat(EpochUnit.MICROS.toMillis(BigDecimal("1748736000286958"))).isEqualTo(1_748_736_000_286L)
        assertThat(EpochUnit.MILLIS.hasSubMillis(BigDecimal("1590969600000"))).isFalse()
    }
}
