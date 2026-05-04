package com.qkt.indicators.catalog

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BollingerBandsTest {
    @Test
    fun `not ready before warmup`() {
        val bb = BollingerBands(period = 2, stddevK = 1.0)
        bb.update(Money.of("10"))
        assertThat(bb.isReady).isFalse()
        assertThat(bb.value()).isNull()
        assertThat(bb.bands()).isNull()
        assertThat(bb.warmupBars).isEqualTo(2)
    }

    @Test
    fun `produces middle upper lower for known series`() {
        val bb = BollingerBands(period = 2, stddevK = 1.0)
        bb.update(Money.of("10"))
        bb.update(Money.of("20"))
        assertThat(bb.isReady).isTrue()
        assertThat(bb.value()).isEqualByComparingTo(Money.of("15"))
        val bands = bb.bands()!!
        assertThat(bands.middle).isEqualByComparingTo(Money.of("15"))
        assertThat(bands.upper).isEqualByComparingTo(Money.of("20"))
        assertThat(bands.lower).isEqualByComparingTo(Money.of("10"))
    }

    @Test
    fun `width is zero on a constant series`() {
        val bb = BollingerBands(period = 3, stddevK = 2.0)
        repeat(3) { bb.update(Money.of("7")) }
        val bands = bb.bands()!!
        assertThat(bands.upper).isEqualByComparingTo(Money.of("7"))
        assertThat(bands.middle).isEqualByComparingTo(Money.of("7"))
        assertThat(bands.lower).isEqualByComparingTo(Money.of("7"))
    }

    @Test
    fun `upper is at least middle is at least lower`() {
        val bb = BollingerBands(period = 4, stddevK = 2.0)
        listOf("1", "5", "2", "8").forEach { bb.update(Money.of(it)) }
        val bands = bb.bands()!!
        assertThat(bands.upper).isGreaterThanOrEqualTo(bands.middle)
        assertThat(bands.middle).isGreaterThanOrEqualTo(bands.lower)
    }

    @Test
    fun `rejects non-positive period`() {
        assertThatThrownBy { BollingerBands(period = 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { BollingerBands(period = -1) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
