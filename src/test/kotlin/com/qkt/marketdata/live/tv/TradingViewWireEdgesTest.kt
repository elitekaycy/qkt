package com.qkt.marketdata.live.tv

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TradingViewWireEdgesTest {
    @Test
    fun `command parameters become JSON numbers booleans lists and raw JSON`() {
        val raw = buildJsonArray { add(JsonPrimitive("x")) }

        val params = tradingViewParams(listOf("s", 2, 1.5, true, raw, listOf("a", 3)))

        assertThat(params.toString()).isEqualTo("""["s",2,1.5,true,["x"],["a",3]]""")
        assertThat(params[5].jsonArray).hasSize(2)
    }

    @Test
    fun `an unsupported command parameter is refused naming its type`() {
        assertThatThrownBy { tradingViewParams(listOf(Any())) }
            .hasMessageContaining("Unsupported parameter type: Object")
    }

    @Test
    fun `a null inside a list parameter is refused`() {
        assertThatThrownBy { tradingViewParams(listOf(listOf("a", null))) }.hasMessageContaining("null param element")
    }

    @Test
    fun `a frame reads a raw parameter and a heartbeat writes its wire form`() {
        val frame = TradingViewFrame.parse("""{"m":"x","p":["a",7]}""") as TradingViewFrame.Message

        assertThat(frame.paramAt(1)).isEqualTo(JsonPrimitive(7))
        assertThat(TradingViewFrame.Heartbeat(12).toWireString()).isEqualTo("~h~12~h~")
    }

    @Test
    fun `a JSON frame that is not an object is a protocol error`() {
        assertThatThrownBy { TradingViewFrame.parse("[1,2]") }
            .isInstanceOf(TradingViewProtocolException::class.java)
            .hasMessageContaining("not a JSON object")
    }

    @Test
    fun `supported windows are the bar widths TradingView serves`() {
        val windows = TradingViewResolution.supportedWindows()

        assertThat(windows).contains(TimeWindow(1_000L), TimeWindow(60_000L), TimeWindow(604_800_000L))
        assertThat(windows.map { TradingViewResolution.fromTimeWindow(it) }).contains("1S", "1", "1W")
    }

    @Test
    fun `a quote frame that cannot be translated is reported as an error`() {
        val ws = FakeTradingViewWebSocket()
        val session = TradingViewQuoteSession(ws, clock = FixedClock(0L), sessionIdGenerator = { "qs_t" })
        val errors = mutableListOf<Throwable>()
        val ticks = mutableListOf<Any>()
        session.subscribe(listOf("FX:EURUSD"), { ticks += it }, { errors += it }, {})

        ws.replay(sequenceOf(TradingViewFrame.parse("""{"m":"qsd","p":["qs_t","not an object"]}""")))

        assertThat(ticks).isEmpty()
        assertThat(errors).hasSize(1)
    }

    @Test
    fun `a market source refuses live and bar requests for symbols outside EXCHANGE colon SYMBOL`() {
        val source = TradingViewMarketSource(FakeTradingViewWebSocket())

        assertThatThrownBy { source.liveTicks(listOf("FX:EURUSD", "eurusd")) }
            .hasMessageContaining("EXCHANGE:SYMBOL")
        assertThatThrownBy {
            source.bars(
                "eurusd",
                TimeWindow(60_000L),
                com.qkt.marketdata.source
                    .probeRange(),
            )
        }.hasMessageContaining("EXCHANGE:SYMBOL")
    }

    @Test
    fun `a market source reads bars through a default chart session and closes its socket`() {
        val ws =
            ScriptedTradingViewWebSocket { params ->
                push(timescaleUpdate(params[0], params[1], "[0,1,1,1,1,0]", "[60,2,2,2,2,0]"))
            }
        val source = TradingViewMarketSource(ws)

        val bars =
            source.bars(
                "FX:EURUSD",
                TimeWindow(60_000L),
                com.qkt.marketdata.source
                    .probeRange(0L, 60_000L),
            )
        source.close()

        assertThat(bars.map { it.startTime }.toList()).containsExactly(0L)
        assertThat(ws.closed).isTrue()
    }
}
