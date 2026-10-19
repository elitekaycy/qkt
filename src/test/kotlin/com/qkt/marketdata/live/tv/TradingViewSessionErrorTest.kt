package com.qkt.marketdata.live.tv

import com.qkt.common.FixedClock
import com.qkt.marketdata.Tick
import java.io.IOException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** TradingView's error frames end a request with its reason instead of a timeout. */
class TradingViewSessionErrorTest {
    private fun replayLater(
        ws: FakeTradingViewWebSocket,
        vararg frames: String,
    ) = Thread {
        Thread.sleep(50)
        ws.replay(frames.map { TradingViewFrame.parse(it) }.asSequence())
    }.apply {
        isDaemon = true
        start()
    }

    private fun chartSession(
        ws: FakeTradingViewWebSocket,
        timeoutMs: Long = 30_000L,
    ) = TradingViewChartSession(ws, { "cs_test" }, { "sds_1" }, timeoutMs)

    @Test
    fun `an unknown symbol fails at once with TradingView's reason`() {
        val ws = FakeTradingViewWebSocket()
        replayLater(ws, """{"m":"symbol_error","p":["cs_test","symbol_1","invalid symbol"]}""")
        val started = System.nanoTime()

        assertThatThrownBy { chartSession(ws).getBars("BACKTEST:EURUSD", "5", 3, 0L) }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("BACKTEST:EURUSD@5")
            .hasMessageContaining("symbol_error invalid symbol")
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(5_000)
        assertThat(ws.commandsSent.map { it.first }).contains("chart_delete_session")
    }

    @Test
    fun `a series TradingView cannot build fails with its reason`() {
        val ws = FakeTradingViewWebSocket()
        replayLater(ws, """{"m":"series_error","p":["cs_test","sds_1","s1","resolution not supported"]}""")

        assertThatThrownBy { chartSession(ws).getBars("OANDA:EURUSD", "1S", 3, 0L) }
            .hasMessageContaining("series_error resolution not supported")
    }

    @Test
    fun `a protocol error fails the request`() {
        val ws = FakeTradingViewWebSocket()
        replayLater(ws, """{"m":"protocol_error","p":["wrong data"]}""")

        assertThatThrownBy { chartSession(ws).getBars("OANDA:EURUSD", "5", 3, 0L) }
            .hasMessageContaining("protocol_error wrong data")
    }

    @Test
    fun `another chart session's error does not end this request`() {
        val ws = FakeTradingViewWebSocket()
        replayLater(ws, """{"m":"symbol_error","p":["cs_other","symbol_1","invalid symbol"]}""")

        assertThatThrownBy { chartSession(ws, timeoutMs = 300).getBars("OANDA:EURUSD", "5", 3, 0L) }
            .hasMessageContaining("timed out")
    }

    @Test
    fun `a quote error status is reported and yields no tick`() {
        val ws = FakeTradingViewWebSocket()
        val session = TradingViewQuoteSession(ws, FixedClock(0L), sessionIdGenerator = { "qs_test" })
        val ticks = mutableListOf<Tick>()
        val errors = mutableListOf<Throwable>()
        session.subscribe(listOf("BACKTEST:EURUSD"), { ticks.add(it) }, { errors.add(it) }, {})

        ws.replay(
            sequenceOf(
                TradingViewFrame.parse(
                    """{"m":"qsd","p":["qs_test",{"n":"BACKTEST:EURUSD","s":"error","errmsg":"invalid symbol","v":{}}]}""",
                ),
            ),
        )

        assertThat(ticks).isEmpty()
        assertThat(errors.single()).hasMessage("TradingView refused quotes for BACKTEST:EURUSD: invalid symbol")
    }
}
