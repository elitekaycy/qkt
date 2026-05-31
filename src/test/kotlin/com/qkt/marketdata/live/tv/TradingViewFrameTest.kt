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

    // ───────── #189 — heartbeat shape variations and session metadata ─────────

    @Test
    fun `parses bare heartbeat without trailing marker`() {
        // Live TV emits `~h~N` (no trailing marker). The previous regex required
        // the trailing marker and these heartbeats were thrown as exceptions on every
        // server ping.
        val frame = TradingViewFrame.parse("~h~7")
        assertThat(frame).isInstanceOf(TradingViewFrame.Heartbeat::class.java)
        assertThat((frame as TradingViewFrame.Heartbeat).seq).isEqualTo(7)
    }

    @Test
    fun `parses session metadata frame as SessionMeta variant`() {
        // The first frame after the WS upgrade is a JSON object describing the
        // session (no `m` method field). Before #189, this triggered
        // TradingViewProtocolException("Frame missing 'm' field").
        val payload =
            """{"session_id":"0.95746652.0_fra2","timestamp":1780095536,"timestampMs":1780095536219,""" +
                """"release":"release_209-10","studies_metadata_hash":"68cca7","auth_scheme_vsn":2,""" +
                """"protocol":"json","via":"84.16.251.42:443","javastudies":["3.66"]}"""
        val frame = TradingViewFrame.parse(payload)
        assertThat(frame).isInstanceOf(TradingViewFrame.SessionMeta::class.java)
        val meta = frame as TradingViewFrame.SessionMeta
        assertThat(meta.obj["session_id"]?.toString()).contains("0.95746652")
        assertThat(meta.obj["release"]?.toString()).contains("release_209-10")
    }

    @Test
    fun `JSON object with no m and no session-meta keys still throws`() {
        // Defensive: a non-message JSON object that doesn't look like session meta
        // should still surface as a protocol error so we notice unknown frame shapes.
        assertThatThrownBy { TradingViewFrame.parse("""{"x":1,"y":"foo"}""") }
            .isInstanceOf(TradingViewProtocolException::class.java)
            .hasMessageContaining("Frame missing 'm' field")
    }
}
