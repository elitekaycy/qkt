package com.qkt.indicators.catalog

import com.qkt.marketdata.Candle
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class AnchoredReturnTest {
    private val minMs = 60_000L

    private fun candle(
        startTime: Long,
        open: String,
        close: String,
    ) = Candle(
        symbol = "X",
        open = BigDecimal(open),
        high = BigDecimal(close),
        low = BigDecimal(open),
        close = BigDecimal(close),
        volume = BigDecimal.ZERO,
        startTime = startTime,
        endTime = startTime + minMs,
    )

    @Test
    fun `return since the open of the current bucket`() {
        val ar = AnchoredReturn(bucketMinutes = 30)
        // First bar of bucket 0: anchor = its open (100), so its own return is close/open - 1.
        ar.update(candle(0, "100", "100"))
        assertThat(ar.isReady).isTrue()
        assertThat(ar.value()).isEqualByComparingTo("0")
        // Second bar in bucket 0: measured from the bucket open (100).
        ar.update(candle(1 * minMs, "100", "103"))
        assertThat(ar.value()!!.toDouble()).isCloseTo(0.03, within(1e-9))
    }

    @Test
    fun `anchor resets at each bucket boundary`() {
        val ar = AnchoredReturn(bucketMinutes = 30)
        ar.update(candle(0, "100", "105")) // bucket 0
        assertThat(ar.value()!!.toDouble()).isCloseTo(0.05, within(1e-9))
        // 30m later -> new bucket, anchor becomes this bar's open (103).
        ar.update(candle(30 * minMs, "103", "106"))
        assertThat(ar.value()!!.toDouble()).isCloseTo(106.0 / 103.0 - 1.0, within(1e-7))
    }

    @Test
    fun `rejects non-positive bucket`() {
        assertThatThrownBy { AnchoredReturn(bucketMinutes = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
