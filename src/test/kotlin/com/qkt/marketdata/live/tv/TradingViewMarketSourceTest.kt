package com.qkt.marketdata.live.tv

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.marketdata.source.UnsupportedDataException
import java.io.File
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TradingViewMarketSourceTest {
    private fun loadFrames(resource: String): Sequence<TradingViewFrame> =
        File("src/test/resources/$resource")
            .readText()
            .lines()
            .filter { it.isNotBlank() }
            .map { TradingViewFrame.parse(it) }
            .asSequence()

    @Test
    fun `name and capabilities`() {
        val src =
            TradingViewMarketSource(
                webSocket = FakeTradingViewWebSocket(),
                clock = FixedClock(time = 0L),
            )
        assertThat(src.name).isEqualTo("TradingView")
        assertThat(src.capabilities).containsExactlyInAnyOrder(
            MarketSourceCapability.LIVE_TICKS,
            MarketSourceCapability.BARS,
        )
    }

    @Test
    fun `supports validates EXCHANGE_COLON_SYMBOL form`() {
        val src =
            TradingViewMarketSource(
                webSocket = FakeTradingViewWebSocket(),
                clock = FixedClock(time = 0L),
            )
        assertThat(src.supports("OANDA:EURUSD")).isTrue()
        assertThat(src.supports("BINANCE:BTCUSDT")).isTrue()
        assertThat(src.supports("EURUSD")).isFalse()
        assertThat(src.supports("oanda:eurusd")).isFalse()
        assertThat(src.supports("OANDA:")).isFalse()
    }

    @Test
    fun `ticks throws UnsupportedDataException because TV does not expose tick history`() {
        val src =
            TradingViewMarketSource(
                webSocket = FakeTradingViewWebSocket(),
                clock = FixedClock(time = 0L),
            )
        val range =
            TimeRange(
                Instant.parse("2024-01-15T00:00:00Z"),
                Instant.parse("2024-01-16T00:00:00Z"),
            )
        assertThatThrownBy { src.ticks("OANDA:EURUSD", range).toList() }
            .isInstanceOf(UnsupportedDataException::class.java)
            .hasMessageContaining("TICKS")
    }

    @Test
    fun `bars maps window to resolution and clips by range`() {
        val ws = FakeTradingViewWebSocket()
        val src =
            TradingViewMarketSource(
                webSocket = ws,
                clock = FixedClock(time = 1_700_001_000_000L),
                chartSessionFactory = { _ ->
                    TradingViewChartSession(
                        ws,
                        sessionIdGenerator = { "cs_test" },
                        seriesIdGenerator = { "sds_1" },
                    )
                },
            )

        val thread =
            Thread {
                Thread.sleep(50)
                ws.replay(loadFrames("tv-fixtures/chart-session-eurusd-m5.jsonl"))
            }.apply {
                isDaemon = true
                start()
            }

        val range =
            TimeRange(
                Instant.ofEpochSecond(1_700_000_000L),
                Instant.ofEpochSecond(1_700_000_900L),
            )
        val bars = src.bars("OANDA:EURUSD", TimeWindow.FIVE_MINUTES, range).toList()
        thread.join()

        assertThat(bars).hasSize(3)
        assertThat(bars[0].close).isEqualByComparingTo(Money.of("1.10010"))
        assertThat(bars.last().close).isEqualByComparingTo(Money.of("1.10035"))
    }

    @Test
    fun `bars on unsupported window throws UnsupportedDataException`() {
        val src =
            TradingViewMarketSource(
                webSocket = FakeTradingViewWebSocket(),
                clock = FixedClock(time = 0L),
            )
        val odd = TimeWindow(13_000L)
        val range =
            TimeRange(
                Instant.parse("2024-01-15T00:00:00Z"),
                Instant.parse("2024-01-15T00:01:00Z"),
            )
        assertThatThrownBy { src.bars("OANDA:EURUSD", odd, range).toList() }
            .isInstanceOf(UnsupportedDataException::class.java)
            .hasMessageContaining("13000")
    }

    @Test
    fun `liveTicks returns a LiveTickFeed backed by a quote session`() {
        val ws = FakeTradingViewWebSocket()
        val src =
            TradingViewMarketSource(
                webSocket = ws,
                clock = FixedClock(time = 1_700_000_000_000L),
            )
        val feed = src.liveTicks(listOf("OANDA:EURUSD"))

        ws.replay(loadFrames("tv-fixtures/quote-session-eurusd.jsonl"))

        val ticks = generateSequence { feed.next() }.take(3).toList()
        assertThat(ticks.map { it.symbol }).allMatch { it == "OANDA:EURUSD" }

        feed.close()
    }
}
