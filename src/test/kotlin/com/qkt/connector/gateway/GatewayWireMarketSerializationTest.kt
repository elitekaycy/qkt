package com.qkt.connector.gateway

import com.qkt.common.WireAsserts.decodes
import com.qkt.common.WireAsserts.refusesWithout
import com.qkt.common.WireAsserts.refusesWrongType
import com.qkt.common.WireAsserts.roundTrips
import org.junit.jupiter.api.Test

class GatewayWireMarketSerializationTest {
    private val bar = WireBar(1L, "1", "2", "0.5", "1.5", "10")

    @Test
    fun `quote round trips with every optional set and none`() {
        roundTrips(WireQuote("BTC", "1", "2", "3", "4", "5", "6", "7", 8L, "9"))
        roundTrips(WireQuote("BTC", time = 8L))
        val body = """{"symbol":"BTC","time":8}"""
        decodes(body, WireQuote("BTC", null, null, null, null, null, null, null, 8L, null))
        refusesWithout<WireQuote>(body, "symbol", "time")
        refusesWrongType<WireQuote>(
            body,
            "bid",
            "ask",
            "bid_size",
            "ask_size",
            "mark",
            "mark_iv",
            "underlying",
            "index",
        )
    }

    @Test
    fun `bar and bars round trip with and without a next page`() {
        roundTrips(bar)
        roundTrips(WireBars(listOf(bar), 9L))
        roundTrips(WireBars(emptyList()))
        decodes("""{"bars":[]}""", WireBars(emptyList(), null))
        refusesWithout<WireBar>(
            """{"start":1,"open":"1","high":"2","low":"0","close":"1","volume":"3"}""",
            "start",
            "open",
            "high",
            "low",
            "close",
            "volume",
        )
        refusesWithout<WireBars>("""{"bars":[]}""", "bars")
        refusesWrongType<WireBars>("""{"bars":[]}""", "next", "bars")
    }

    @Test
    fun `mark and marks round trip with every optional set and none`() {
        roundTrips(WireMark(1L, "2", "3"))
        roundTrips(WireMark(1L))
        roundTrips(WireMarks(listOf(WireMark(1L, "2"), WireMark(2L, index = "3")), 5L))
        roundTrips(WireMarks(emptyList()))
        decodes("""{"time":1}""", WireMark(1L, null, null))
        decodes("""{"marks":[]}""", WireMarks(emptyList(), null))
        refusesWithout<WireMark>("""{"time":1}""", "time")
        refusesWithout<WireMarks>("""{"marks":[]}""", "marks")
        refusesWrongType<WireMark>("""{"time":1}""", "mark", "index")
    }
}
