package com.qkt.marketdata.live.tv

import com.qkt.candles.TimeWindow
import com.qkt.marketdata.source.UnsupportedDataException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TradingViewResolutionTest {
    @Test
    fun `maps ONE_SECOND to 1S`() {
        assertThat(TradingViewResolution.fromTimeWindow(TimeWindow.ONE_SECOND)).isEqualTo("1S")
    }

    @Test
    fun `maps ONE_MINUTE to 1`() {
        assertThat(TradingViewResolution.fromTimeWindow(TimeWindow.ONE_MINUTE)).isEqualTo("1")
    }

    @Test
    fun `maps FIVE_MINUTES to 5`() {
        assertThat(TradingViewResolution.fromTimeWindow(TimeWindow.FIVE_MINUTES)).isEqualTo("5")
    }

    @Test
    fun `maps FIFTEEN_MINUTES to 15`() {
        assertThat(TradingViewResolution.fromTimeWindow(TimeWindow.FIFTEEN_MINUTES)).isEqualTo("15")
    }

    @Test
    fun `maps ONE_HOUR to 60`() {
        assertThat(TradingViewResolution.fromTimeWindow(TimeWindow.ONE_HOUR)).isEqualTo("60")
    }

    @Test
    fun `non-standard window throws UnsupportedDataException with supported list`() {
        val odd = TimeWindow(13_000L)
        assertThatThrownBy { TradingViewResolution.fromTimeWindow(odd) }
            .isInstanceOf(UnsupportedDataException::class.java)
            .hasMessageContaining("TradingViewMarketSource")
            .hasMessageContaining("BARS")
            .hasMessageContaining("13000")
    }
}
