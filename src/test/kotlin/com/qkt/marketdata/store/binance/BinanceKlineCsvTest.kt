package com.qkt.marketdata.store.binance

import com.qkt.candles.TimeWindow
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BinanceKlineCsvTest {
    private val header =
        "open_time,open,high,low,close,volume,close_time,quote_volume,count," +
            "taker_buy_volume,taker_buy_quote_volume,ignore\n"
    private val row1 =
        "1725235200000,57502.9,57581.0,57490.6,57572.9,0.307,1725235259999,17660.0307,52,0.271,15589.5247,0\n"
    private val row2 =
        "1725235260000,57530.8,57556.7,57526.2,57526.6,0.116,1725235319999,6675.0772,32,0.090,5179.1004,0\n"

    @Test
    fun `rows become candles with exclusive end times`() {
        val candles = BinanceKlineCsv.parse("BINANCE_UM:BTCUSDT_240927", header + row1 + row2, TimeWindow.ONE_MINUTE)
        assertThat(candles).hasSize(2)
        val c = candles.first()
        assertThat(c.symbol).isEqualTo("BINANCE_UM:BTCUSDT_240927")
        assertThat(c.open).isEqualByComparingTo("57502.9")
        assertThat(c.high).isEqualByComparingTo("57581.0")
        assertThat(c.low).isEqualByComparingTo("57490.6")
        assertThat(c.close).isEqualByComparingTo("57572.9")
        assertThat(c.volume).isEqualByComparingTo("0.307")
        assertThat(c.startTime).isEqualTo(1725235200000L)
        assertThat(c.endTime).isEqualTo(1725235260000L)
    }

    @Test
    fun `a file without a header parses the same`() {
        assertThat(BinanceKlineCsv.parse("S", row1 + row2, TimeWindow.ONE_MINUTE))
            .isEqualTo(BinanceKlineCsv.parse("S", header + row1 + row2, TimeWindow.ONE_MINUTE))
    }

    @Test
    fun `a row whose span disagrees with the window is refused`() {
        assertThatThrownBy {
            BinanceKlineCsv.parse(
                "S",
                row1,
                TimeWindow.FIVE_MINUTES,
            )
        }.hasMessageContaining("1725235200000")
    }

    @Test
    fun `blank trailing lines are ignored`() {
        assertThat(BinanceKlineCsv.parse("S", header + row1 + "\n\n", TimeWindow.ONE_MINUTE)).hasSize(1)
    }
}
