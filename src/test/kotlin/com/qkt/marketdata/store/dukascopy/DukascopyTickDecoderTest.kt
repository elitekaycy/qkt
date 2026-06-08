package com.qkt.marketdata.store.dukascopy

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DukascopyTickDecoderTest {
    private fun record(
        msOffset: Int,
        ask: Int,
        bid: Int,
        askVol: Float,
        bidVol: Float,
    ): ByteArray {
        val b = ByteArrayOutputStream()
        DataOutputStream(b).apply {
            writeInt(msOffset) // big-endian by default
            writeInt(ask)
            writeInt(bid)
            writeFloat(askVol)
            writeFloat(bidVol)
        }
        return b.toByteArray()
    }

    @Test
    fun `decodes records to ticks with scaled prices and absolute timestamps`() {
        val hourStartMs = 1_700_000_000_000L
        val bytes = record(0, 2_345_670, 2_345_650, 1.5f, 2.0f) + record(1500, 2_345_680, 2_345_660, 0.5f, 0.25f)

        val ticks = DukascopyTickDecoder.decodeRecords(bytes, hourStartMs, divisor = 1000L, symbol = "XAUUSD")

        assertThat(ticks).hasSize(2)
        assertThat(ticks[0].timestamp).isEqualTo(hourStartMs)
        assertThat(ticks[0].ask).isEqualByComparingTo("2345.67")
        assertThat(ticks[0].bid).isEqualByComparingTo("2345.65")
        assertThat(ticks[1].timestamp).isEqualTo(hourStartMs + 1500L)
        assertThat(ticks[1].bid).isEqualByComparingTo("2345.66")
    }

    @Test
    fun `empty input yields no ticks`() {
        assertThat(DukascopyTickDecoder.decodeRecords(ByteArray(0), 0L, 1000L, "XAUUSD")).isEmpty()
    }
}
