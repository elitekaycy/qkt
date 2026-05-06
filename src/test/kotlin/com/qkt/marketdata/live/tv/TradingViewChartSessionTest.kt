package com.qkt.marketdata.live.tv

import com.qkt.common.Money
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TradingViewChartSessionTest {
    private fun loadFrames(resource: String): Sequence<TradingViewFrame> =
        File("src/test/resources/$resource")
            .readText()
            .lines()
            .filter { it.isNotBlank() }
            .map { TradingViewFrame.parse(it) }
            .asSequence()

    @Test
    fun `getBars returns candles from a timescale_update frame`() {
        val ws = FakeTradingViewWebSocket()
        val session = TradingViewChartSession(ws, sessionIdGenerator = { "cs_test" }, seriesIdGenerator = { "sds_1" })

        val thread =
            Thread {
                Thread.sleep(50)
                ws.replay(loadFrames("tv-fixtures/chart-session-eurusd-m5.jsonl"))
            }.apply {
                isDaemon = true
                start()
            }

        val bars =
            session.getBars(
                symbol = "OANDA:EURUSD",
                resolution = "5",
                count = 3,
                toTimestampSeconds = 1_700_000_900L,
            )
        thread.join()

        assertThat(bars).hasSize(3)
        assertThat(bars[0].symbol).isEqualTo("OANDA:EURUSD")
        assertThat(bars[0].open).isEqualByComparingTo(Money.of("1.10005"))
        assertThat(bars[0].high).isEqualByComparingTo(Money.of("1.10025"))
        assertThat(bars[0].low).isEqualByComparingTo(Money.of("1.09995"))
        assertThat(bars[0].close).isEqualByComparingTo(Money.of("1.10010"))
        assertThat(bars[0].volume).isEqualByComparingTo(Money.of("123"))
        assertThat(bars[0].startTime).isEqualTo(1_700_000_000_000L)
        assertThat(bars[0].endTime).isEqualTo(1_700_000_300_000L)
    }

    @Test
    fun `getBars sends create_session resolve_symbol create_series in order`() {
        val ws = FakeTradingViewWebSocket()
        val session = TradingViewChartSession(ws, sessionIdGenerator = { "cs_test" }, seriesIdGenerator = { "sds_1" })

        val thread =
            Thread {
                Thread.sleep(50)
                ws.replay(loadFrames("tv-fixtures/chart-session-eurusd-m5.jsonl"))
            }.apply {
                isDaemon = true
                start()
            }

        session.getBars("OANDA:EURUSD", "5", 3, 1_700_000_900L)
        thread.join()

        val methods = ws.commandsSent.map { it.first }
        val firstThree = methods.take(3)
        assertThat(firstThree).containsExactly(
            "chart_create_session",
            "resolve_symbol",
            "create_series",
        )
    }

    @Test
    fun `getBars throws IOException on timeout`() {
        val ws = FakeTradingViewWebSocket()
        val session =
            TradingViewChartSession(
                ws,
                sessionIdGenerator = { "cs_test" },
                seriesIdGenerator = { "sds_1" },
                timeoutMs = 200,
            )

        assertThatThrownBy { session.getBars("OANDA:EURUSD", "5", 3, 1_700_000_900L) }
            .isInstanceOf(java.io.IOException::class.java)
    }

    @Test
    fun `getBars cleans up the chart session after success`() {
        val ws = FakeTradingViewWebSocket()
        val session = TradingViewChartSession(ws, sessionIdGenerator = { "cs_test" }, seriesIdGenerator = { "sds_1" })

        val thread =
            Thread {
                Thread.sleep(50)
                ws.replay(loadFrames("tv-fixtures/chart-session-eurusd-m5.jsonl"))
            }.apply {
                isDaemon = true
                start()
            }

        session.getBars("OANDA:EURUSD", "5", 3, 1_700_000_900L)
        thread.join()

        assertThat(ws.commandsSent.map { it.first }).contains("chart_delete_session")
    }
}
