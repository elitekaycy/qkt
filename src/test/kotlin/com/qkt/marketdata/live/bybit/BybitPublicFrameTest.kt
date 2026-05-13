package com.qkt.marketdata.live.bybit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitPublicFrameTest {
    @Test
    fun `parses tickers frame`() {
        val raw =
            """{"topic":"tickers.BTCUSDT","type":"snapshot","data":{"symbol":"BTCUSDT","bid1Price":"60000.0","ask1Price":"60000.5","lastPrice":"60000.2"}}"""
        val frame = BybitPublicFrame.parse(raw) as BybitPublicFrame.Tickers
        assertThat(frame.symbol).isEqualTo("BTCUSDT")
        assertThat(frame.bid.toPlainString()).isEqualTo("60000.0")
        assertThat(frame.ask.toPlainString()).isEqualTo("60000.5")
        assertThat(frame.last.toPlainString()).isEqualTo("60000.2")
    }

    @Test
    fun `parses publicTrade frame`() {
        val raw =
            """{"topic":"publicTrade.BTCUSDT","type":"snapshot","data":[{"S":"Buy","p":"60000.2","v":"0.5","T":1778662794911}]}"""
        val frame = BybitPublicFrame.parse(raw) as BybitPublicFrame.Trade
        assertThat(frame.symbol).isEqualTo("BTCUSDT")
        assertThat(frame.price.toPlainString()).isEqualTo("60000.2")
        assertThat(frame.volume.toPlainString()).isEqualTo("0.5")
        assertThat(frame.brokerTimeMs).isEqualTo(1778662794911L)
    }

    @Test
    fun `parses subscribe ack`() {
        val raw = """{"success":true,"ret_msg":"","op":"subscribe","conn_id":"abc"}"""
        val frame = BybitPublicFrame.parse(raw)
        assertThat(frame).isInstanceOf(BybitPublicFrame.SubscribeAck::class.java)
    }

    @Test
    fun `unknown topic falls through to Unknown`() {
        val raw = """{"topic":"orderbook.BTCUSDT","data":{}}"""
        val frame = BybitPublicFrame.parse(raw)
        assertThat(frame).isInstanceOf(BybitPublicFrame.Unknown::class.java)
    }

    @Test
    fun `garbage text returns Unknown`() {
        assertThat(BybitPublicFrame.parse("not json")).isInstanceOf(BybitPublicFrame.Unknown::class.java)
    }
}
