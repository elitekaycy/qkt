package com.qkt.marketdata.live.tv

import java.io.IOException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class TradingViewChartSessionBarsTest {
    private val bar = "[1700000000,1.1,1.2,1.0,1.15,42]"

    private fun answering(vararg rows: String) =
        ScriptedTradingViewWebSocket { params -> push(timescaleUpdate(params[0], params[1], *rows)) }

    private fun session(ws: TradingViewWebSocketLike) = TradingViewChartSession(ws, { "cs_t" }, { "sds_t" }, 1_000L)

    @ParameterizedTest
    @CsvSource(
        "1S,1000",
        "5S,5000",
        "15S,15000",
        "30S,30000",
        "1,60000",
        "5,300000",
        "15,900000",
        "30,1800000",
        "60,3600000",
        "240,14400000",
        "1D,86400000",
        "1W,604800000",
    )
    fun `each resolution spans its own bar width`(
        resolution: String,
        widthMs: Long,
    ) {
        val candle = session(answering(bar)).getBars("FX:EURUSD", resolution, 1, 0L).single()

        assertThat(candle.startTime).isEqualTo(1_700_000_000_000L)
        assertThat(candle.endTime - candle.startTime).isEqualTo(widthMs)
    }

    @Test
    fun `an unknown resolution fails the request naming it`() {
        assertThatThrownBy { session(answering(bar)).getBars("FX:EURUSD", "7", 1, 0L) }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("Unknown resolution: 7")
    }

    @Test
    fun `a row without volume or with unreadable volume has zero volume`() {
        val ws = answering("[1700000000,1,1,1,1]", "[1700000060,2,2,2,2,\"n/a\"]")

        val candles = session(ws).getBars("FX:EURUSD", "1", 2, 0L)

        assertThat(candles.map { it.volume.signum() }).containsExactly(0, 0)
        assertThat(candles.map { it.close.toInt() }).containsExactly(1, 2)
    }

    @Test
    fun `a row without values fails the request`() {
        val noValues = """{"m":"timescale_update","p":["cs_t",{"sds_t":{"s":[{"i":0}]}}]}"""
        val ws = ScriptedTradingViewWebSocket { push(noValues) }

        assertThatThrownBy { session(ws).getBars("FX:EURUSD", "1", 1, 0L) }
            .hasMessageContaining("missing v")
    }

    @Test
    fun `frames for other series heartbeats and reconnect notices are ignored until the bars arrive`() {
        val ws =
            ScriptedTradingViewWebSocket { params ->
                push("~h~1")
                push("""{"m":"quote_completed","p":["qs_x","FX:EURUSD"]}""")
                push(timescaleUpdate(params[0], "other", bar))
                push("""{"m":"timescale_update","p":["cs_t",{"sds_t":{}}]}""")
                connected()
                push(timescaleUpdate(params[0], params[1], bar))
            }

        assertThat(session(ws).getBars("FX:EURUSD", "1", 1, 0L)).hasSize(1)
    }

    @Test
    fun `a disconnect during the request fails it with the reason`() {
        val ws = ScriptedTradingViewWebSocket { disconnected("closed:1006") }

        assertThatThrownBy { session(ws).getBars("FX:EURUSD", "1", 1, 0L) }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("TradingView disconnected: closed:1006")
        assertThat(ws.listeners).isEmpty()
    }

    @Test
    fun `a cleanup send that fails still returns the bars and drops the listener`() {
        val ws =
            ScriptedTradingViewWebSocket(failOn = "remove_series") { params ->
                push(timescaleUpdate(params[0], params[1], bar))
            }

        assertThat(session(ws).getBars("FX:EURUSD", "1", 1, 0L)).hasSize(1)
        assertThat(ws.sent.map { it.first }).doesNotContain("chart_delete_session")
        assertThat(ws.listeners).isEmpty()
    }

    @Test
    fun `default ids are random session and series names`() {
        val ws = answering(bar)

        TradingViewChartSession(ws).getBars("FX:EURUSD", "1", 1, 0L)

        val (sessionId, seriesId) = ws.sent.first { it.first == "create_series" }.second
        assertThat(sessionId as String).matches("cs_[a-z0-9]{8}")
        assertThat(seriesId as String).matches("sds_[a-z0-9]{6}")
    }

    @Test
    fun `a non-positive count is refused before anything is sent`() {
        val ws = answering(bar)

        assertThatThrownBy { session(ws).getBars("FX:EURUSD", "1", 0, 0L) }.hasMessageContaining("count must be > 0")
        assertThat(ws.sent).isEmpty()
    }
}
