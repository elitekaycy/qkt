package com.qkt.cli.bot

import com.qkt.marketdata.Candle
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BotEvalCommandTest {
    private fun candle(
        close: String,
        t: Long,
    ) = Candle(
        symbol = "XAUUSD",
        open = BigDecimal(close),
        high = BigDecimal(close),
        low = BigDecimal(close),
        close = BigDecimal(close),
        volume = BigDecimal.ONE,
        startTime = t,
        endTime = t + 60_000,
    )

    @Test
    fun `evaluates sma over closes to a hand computed value`() {
        val bars = listOf(candle("100", 0), candle("102", 60_000), candle("104", 120_000))
        val result = evalIndicator("sma(3)", bars)
        assertThat(result.isReady).isTrue
        assertThat(result.value).isEqualByComparingTo("102")
    }

    @Test
    fun `reports not ready when bars are insufficient`() {
        val result = evalIndicator("sma(5)", listOf(candle("100", 0), candle("101", 60_000)))
        assertThat(result.isReady).isFalse
        assertThat(result.value).isNull()
        assertThat(result.warmupBars).isGreaterThanOrEqualTo(5)
    }

    @Test
    fun `feeds whole candles to candle series indicators`() {
        val bars = (1..20).map { candle((100 + it).toString(), it * 60_000L) }
        val result = evalIndicator("atr(14)", bars)
        assertThat(result.isReady).isTrue
        assertThat(result.value).isNotNull
    }

    @Test
    fun `rejects malformed expressions and unknown indicators`() {
        assertThatThrownBy { evalIndicator("ema", emptyList()) }
            .hasMessageContaining("indicator(args)")
        assertThatThrownBy { evalIndicator("nope(3)", emptyList()) }
            .hasMessageContaining("unknown indicator")
        assertThatThrownBy { evalIndicator("ema(fast)", emptyList()) }
            .hasMessageContaining("numeric")
    }
}
