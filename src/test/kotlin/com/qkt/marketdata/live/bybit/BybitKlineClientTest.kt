package com.qkt.marketdata.live.bybit

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import java.time.Instant
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitKlineClientTest {
    @Test
    fun `fetchRange parses kline list and sorts ascending`() {
        val server = MockWebServer().apply { start() }
        try {
            // Bybit returns newest-first; we expect ascending after parsing.
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"retCode":0,"retMsg":"OK","result":{
                      "category":"spot","symbol":"BTCUSDT",
                      "list":[
                        ["1778662500000","60005.0","60010.0","60001.0","60008.0","2.0","120000.0"],
                        ["1778662200000","60000.0","60001.0","59999.0","60000.5","1.5","90000.5"]
                      ]
                    }}
                    """.trimIndent(),
                ),
            )
            val client =
                BybitKlineClient(
                    baseUrl = server.url("/").toString().trimEnd('/'),
                    category = "spot",
                )
            val candles =
                client
                    .fetchRange(
                        symbol = "BTCUSDT",
                        window = TimeWindow.parse("5m"),
                        range =
                            TimeRange(
                                from = Instant.ofEpochMilli(1778662200000L),
                                to = Instant.ofEpochMilli(1778662800000L),
                            ),
                    ).toList()
            assertThat(candles).hasSize(2)
            assertThat(candles[0].startTime).isEqualTo(1778662200000L)
            assertThat(candles[0].close.toPlainString()).isEqualTo("60000.5")
            assertThat(candles[1].startTime).isEqualTo(1778662500000L)
            val req = server.takeRequest()
            assertThat(req.path).contains("category=spot")
            assertThat(req.path).contains("symbol=BTCUSDT")
            assertThat(req.path).contains("interval=5")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `empty list returns empty sequence`() {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(
                MockResponse().setBody(
                    """{"retCode":0,"retMsg":"OK","result":{"category":"spot","symbol":"BTCUSDT","list":[]}}""",
                ),
            )
            val client = BybitKlineClient(server.url("/").toString().trimEnd('/'), category = "spot")
            val candles =
                client
                    .fetchRange(
                        symbol = "BTCUSDT",
                        window = TimeWindow.parse("5m"),
                        range = TimeRange(Instant.EPOCH, Instant.ofEpochMilli(60_000_000L)),
                    ).toList()
            assertThat(candles).isEmpty()
        } finally {
            server.shutdown()
        }
    }
}
