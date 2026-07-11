package com.qkt.marketdata.live.mt5

import com.qkt.broker.mt5.MT5ServerTimeZone
import java.time.Instant
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class Mt5TickClientTest {
    @Test
    fun `broker wall tick epoch uses the same UTC normalization as bars`() {
        val server = MockWebServer().apply { start() }
        try {
            val serverWallMs = Instant.parse("2026-07-15T11:00:00Z").toEpochMilli()
            server.enqueue(
                MockResponse().setBody(
                    """{"bid":1.10,"ask":1.12,"last":0,"flags":6,"time_msc":$serverWallMs}""",
                ),
            )
            val client =
                Mt5TickClient(
                    baseUrl = server.url("/").toString().trimEnd('/'),
                    serverTimeZone = MT5ServerTimeZone.NEW_YORK_CLOSE,
                )

            val tick = client.fetchOnce("EURUSD", capturedAtMs = 0L)

            assertThat(tick.brokerTimeMs).isEqualTo(Instant.parse("2026-07-15T08:00:00Z").toEpochMilli())
        } finally {
            server.shutdown()
        }
    }
}
