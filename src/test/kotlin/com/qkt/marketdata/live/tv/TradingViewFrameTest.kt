package com.qkt.marketdata.live.tv

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TradingViewFrameTest {
    @Test
    fun `parses message frame with method and params`() {
        val json = """{"m":"qsd","p":["session_xyz",{"n":"OANDA:EURUSD","s":"ok","v":{"lp":1.10}}]}"""
        val frame = TradingViewFrame.parse(json)
        assertThat(frame).isInstanceOf(TradingViewFrame.Message::class.java)
        val msg = frame as TradingViewFrame.Message
        assertThat(msg.method).isEqualTo("qsd")
        assertThat(msg.params).hasSize(2)
        assertThat(msg.paramAsString(0)).isEqualTo("session_xyz")
    }

    @Test
    fun `paramAsObject returns the JsonObject at index`() {
        val json = """{"m":"qsd","p":["s",{"n":"OANDA:EURUSD","v":{"lp":1.10}}]}"""
        val msg = TradingViewFrame.parse(json) as TradingViewFrame.Message
        val obj: JsonObject = msg.paramAsObject(1)
        assertThat(obj["n"]?.toString()).isEqualTo("\"OANDA:EURUSD\"")
    }

    @Test
    fun `parses heartbeat frame`() {
        val frame = TradingViewFrame.parse("~h~7~h~")
        assertThat(frame).isInstanceOf(TradingViewFrame.Heartbeat::class.java)
        assertThat((frame as TradingViewFrame.Heartbeat).seq).isEqualTo(7)
    }

    @Test
    fun `serializes message frame back to JSON for sending`() {
        val msg =
            TradingViewFrame.Message(
                method = "quote_create_session",
                params =
                    buildJsonArray {
                        add(JsonPrimitive("session_xyz"))
                    },
            )
        assertThat(msg.toWireJson())
            .isEqualTo("""{"m":"quote_create_session","p":["session_xyz"]}""")
    }

    @Test
    fun `parsing malformed JSON throws TradingViewProtocolException`() {
        assertThatThrownBy { TradingViewFrame.parse("not valid") }
            .isInstanceOf(TradingViewProtocolException::class.java)
            .hasMessageContaining("not valid")
    }
}
