package com.qkt.cli.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class GoldenMarketRecordParsersTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun ohlc(
        high: String = "12",
        low: String = "9",
        start: Int = 0,
        end: Int = 60_000,
    ) = """"open":"10","high":"$high","low":"$low","close":"11","volume":"1","startTimeMs":$start,"endTimeMs":$end"""

    private fun candle(body: String = ohlc()) = """"candle":{$body}"""

    @Test
    fun `a warmup tick keeps its source timeframe`() {
        val tick =
            readTick(
                json(
                    """{"symbol":"EXNESS:XAUUSD","sourceTimeframeMs":60000,"tick":{"price":"2000","timestampMs":5}}""",
                ),
                1,
                true,
                "j",
                1,
            )

        assertThat(tick.sourceTimeframeMs).isEqualTo(60_000L)
        assertThat(tick.receivedMs).isNull()
    }

    @Test
    fun `ticks without a body crossed quotes or a bad warmup timeframe are refused`() {
        assertThatThrownBy { readTick(json("""{"symbol":"EXNESS:XAUUSD"}"""), 1, false, "j", 7) }
            .hasMessage("missing structured tick at j:7")
        assertThatThrownBy {
            readTick(
                json(
                    """{"symbol":"EXNESS:XAUUSD","tick":{"price":"2000","timestampMs":5,"bid":"2001","ask":"2000"}}""",
                ),
                1,
                false,
                "j",
                8,
            )
        }.hasMessage("crossed tick quote at j:8")
        assertThatThrownBy {
            readTick(
                json("""{"symbol":"EXNESS:XAUUSD","sourceTimeframeMs":0,"tick":{"price":"2000","timestampMs":5}}"""),
                1,
                true,
                "j",
                9,
            )
        }.hasMessage("invalid warmup sourceTimeframeMs at j:9")
    }

    @Test
    fun `candles without a body an empty window or impossible prices are refused`() {
        assertThatThrownBy { readCandle(json("""{"symbol":"EXNESS:XAUUSD"}"""), 1, "j", 2) }
            .hasMessage("missing structured candle at j:2")
        val emptyWindow = ohlc(start = 5, end = 5)
        assertThatThrownBy { readCandle(json("""{"symbol":"EXNESS:XAUUSD",${candle(emptyWindow)}}"""), 1, "j", 3) }
            .hasMessage("invalid candle window at j:3")
        val inverted = ohlc(high = "9", low = "8")
        assertThatThrownBy { readCandle(json("""{"symbol":"EXNESS:XAUUSD",${candle(inverted)}}"""), 1, "j", 4) }
            .hasMessage("invalid candle OHLC at j:4")
    }

    @Test
    fun `a stream candle must span its declared timeframe`() {
        val record = json("""{"symbol":"EXNESS:XAUUSD","broker":"EXNESS","timeframe":"5m",${candle()}}""")

        assertThatThrownBy { readStreamCandle(record, 1, "j", 5) }.hasMessage("stream candle timeframe mismatch at j:5")
        assertThat(
            readStreamCandle(
                json("""{"symbol":"EXNESS:XAUUSD","broker":"EXNESS","timeframe":"1m",${candle()}}"""),
                1,
                "j",
                6,
            ).candle.close,
        ).isEqualByComparingTo("11")
    }
}
