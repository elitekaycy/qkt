package com.qkt.marketdata.live.bybit

import com.qkt.common.FixedClock
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitPublicWsClientTest {
    @Test
    fun `subscribe sends op subscribe with tickers and publicTrade args`() {
        val ws = FakeBybitWebSocket()
        val client = BybitPublicWsClient(ws, clock = FixedClock(0L))
        client.subscribe(listOf("BTCUSDT"), onTick = {}, onDisconnect = {})

        assertThat(ws.sentTexts).hasSize(1)
        assertThat(ws.sentTexts[0]).contains("\"op\":\"subscribe\"")
        assertThat(ws.sentTexts[0]).contains("\"tickers.BTCUSDT\"")
        assertThat(ws.sentTexts[0]).contains("\"publicTrade.BTCUSDT\"")
    }

    @Test
    fun `onConnected before any disconnect does not re-send subscribe`() {
        val ws = FakeBybitWebSocket()
        val client = BybitPublicWsClient(ws, clock = FixedClock(0L))
        client.subscribe(listOf("BTCUSDT"), onTick = {}, onDisconnect = {})
        val afterSubscribe = ws.sentTexts.size

        ws.simulateConnect()

        assertThat(ws.sentTexts.size).isEqualTo(afterSubscribe)
    }

    @Test
    fun `reconnect resends subscribe`() {
        val ws = FakeBybitWebSocket()
        val client = BybitPublicWsClient(ws, clock = FixedClock(0L))
        client.subscribe(listOf("BTCUSDT"), onTick = {}, onDisconnect = {})
        val afterSubscribe = ws.sentTexts.size

        ws.simulateDisconnect("oops")
        ws.simulateConnect()

        assertThat(ws.sentTexts.size).isGreaterThan(afterSubscribe)
    }

    @Test
    fun `tickers frame emits Tick with bid ask price`() {
        val ws = FakeBybitWebSocket()
        val client = BybitPublicWsClient(ws, clock = FixedClock(100L))
        val captured = mutableListOf<Tick>()
        client.subscribe(listOf("BTCUSDT"), onTick = { captured.add(it) }, onDisconnect = {})

        ws.deliver(
            """{"topic":"tickers.BTCUSDT","type":"snapshot","data":{"symbol":"BTCUSDT","bid1Price":"60000.0","ask1Price":"60000.5","lastPrice":"60000.2"}}""",
        )

        assertThat(captured).hasSize(1)
        assertThat(captured[0].symbol).isEqualTo("BTCUSDT")
        assertThat(captured[0].price.toPlainString()).isEqualTo("60000.20000000")
        assertThat(captured[0].bid!!.toPlainString()).isEqualTo("60000.00000000")
        assertThat(captured[0].ask!!.toPlainString()).isEqualTo("60000.50000000")
        // bid/ask updates use the clock for timestamp (no broker time on tickers)
        assertThat(captured[0].timestamp).isEqualTo(100L)
    }

    @Test
    fun `publicTrade frame emits Tick with broker time`() {
        val ws = FakeBybitWebSocket()
        val client = BybitPublicWsClient(ws, clock = FixedClock(100L))
        val captured = mutableListOf<Tick>()
        client.subscribe(listOf("BTCUSDT"), onTick = { captured.add(it) }, onDisconnect = {})

        ws.deliver(
            """{"topic":"publicTrade.BTCUSDT","type":"snapshot","data":[{"S":"Buy","p":"60000.2","v":"0.5","T":1778662794911}]}""",
        )

        assertThat(captured).hasSize(1)
        assertThat(captured[0].timestamp).isEqualTo(1778662794911L)
        assertThat(captured[0].price.toPlainString()).isEqualTo("60000.20000000")
        assertThat(captured[0].volume!!.toPlainString()).isEqualTo("0.50000000")
    }
}
