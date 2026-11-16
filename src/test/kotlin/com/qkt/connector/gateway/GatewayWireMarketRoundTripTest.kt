package com.qkt.connector.gateway

import org.junit.jupiter.api.Test

class GatewayWireMarketRoundTripTest {
    private val print = """{"id":"i","time":1,"price":"2","size":"3","side":"buy"}"""

    @Test
    fun `quote bar and mark objects round trip every field`() {
        WireRoundTrip(
            WireQuote.serializer(),
            """{"symbol":"S","bid":"1","ask":"2","bid_size":"3","ask_size":"4","mark":"5","mark_iv":"6",
            "underlying":"7","time":8,"index":"9"}""",
            setOf("bid", "ask", "bid_size", "ask_size", "mark", "mark_iv", "underlying", "index"),
        ).verify()
        val bar = """{"start":1,"open":"1","high":"2","low":"0","close":"1","volume":"5"}"""
        WireRoundTrip(WireBar.serializer(), bar).verify()
        WireRoundTrip(WireBars.serializer(), """{"bars":[$bar],"next":9}""", setOf("next")).verify()
        WireRoundTrip(WireMark.serializer(), """{"time":1,"mark":"2","index":"3"}""", setOf("mark", "index")).verify()
        WireRoundTrip(
            WireMarks.serializer(),
            """{"marks":[{"time":1,"mark":"2"}],"next":3}""",
            setOf("next"),
        ).verify()
    }

    @Test
    fun `funding objects round trip every field`() {
        val funding = """{"funding_id":"f","symbol":"S","amount":"1","currency":"USD","position":"p","time":2}"""
        WireRoundTrip(WireFunding.serializer(), funding, setOf("position")).verify()
        WireRoundTrip(WireFundings.serializer(), """{"funding":[$funding]}""").verify()
        val rate = """{"time":1,"rate":"0.1","price":"2"}"""
        WireRoundTrip(WireFundingRate.serializer(), rate, setOf("price")).verify()
        WireRoundTrip(WireFundingRates.serializer(), """{"rates":[$rate],"next":4}""", setOf("next")).verify()
    }

    @Test
    fun `tape depth and open interest objects round trip every field`() {
        WireRoundTrip(WirePrint.serializer(), print).verify()
        WireRoundTrip(WireTrades.serializer(), """{"trades":[$print],"next":5}""", setOf("next")).verify()
        WireRoundTrip(WireLiquidations.serializer(), """{"liquidations":[$print],"next":5}""", setOf("next")).verify()
        val snapshot = """{"time":1,"bids":[["1","2"]],"asks":[["3","4"]]}"""
        WireRoundTrip(WireDepthSnapshot.serializer(), snapshot).verify()
        WireRoundTrip(WireDepth.serializer(), """{"depth":[$snapshot],"next":6}""", setOf("next")).verify()
        val point = """{"time":1,"open_interest":"9"}"""
        WireRoundTrip(WireOpenInterestPoint.serializer(), point).verify()
        WireRoundTrip(WireOpenInterest.serializer(), """{"open_interest":[$point],"next":2}""", setOf("next")).verify()
    }
}
