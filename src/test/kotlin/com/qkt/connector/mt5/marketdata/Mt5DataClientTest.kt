package com.qkt.connector.mt5.marketdata

import com.qkt.connector.mt5.MT5ServerTimeZone
import com.qkt.connector.mt5.Mt5RoutedGateway
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class Mt5DataClientTest {
    private val gateway = Mt5RoutedGateway()
    private val client = Mt5DataClient(gateway.url, apiKey = "k")
    private val eight = Instant.parse("2026-05-13T08:00:00Z").toEpochMilli()

    @AfterEach
    fun stop() = gateway.close()

    @Test
    fun `bars by position ask for the newest bars and close each one a timeframe later`() {
        gateway.on(
            "/fetch_data_pos",
            """[{"open":1,"high":2,"low":0.5,"close":1.5,"tick_volume":7,"time":${eight / 1000}}]""",
        )

        val bar = client.fetchBarsByPos("XAUUSDm", "H1", 3).single()

        assertThat(gateway.requests.single().first)
            .isEqualTo("GET /fetch_data_pos?symbol=XAUUSDm&timeframe=H1&num_bars=3")
        assertThat(bar.open.toPlainString()).isEqualTo("1.00000000")
        assertThat(bar.high).isEqualByComparingTo("2")
        assertThat(bar.low).isEqualByComparingTo("0.5")
        assertThat(bar.close).isEqualByComparingTo("1.5")
        assertThat(bar.volume).isEqualByComparingTo("7")
        assertThat(bar.startTime).isEqualTo(eight)
        assertThat(bar.endTime).isEqualTo(eight + 3_600_000L)
    }

    @Test
    fun `bars by range encode the window and read wrapped rows in every gateway shape`() {
        val row = """{"open":1,"high":1,"low":1,"close":1,"time":"2026-05-13 08:00:00"}"""
        gateway.on("/fetch_data_range", """{"bars":[$row]}""", """{"data":{"data":[$row]}}""", """{"data":[$row]}""")

        repeat(3) {
            val bar = client.fetchBarsByRange("XAUUSDm", "M1", "2026-05-13T08:00:00", "2026-05-13T09:00:00").single()
            assertThat(bar.startTime).isEqualTo(eight)
            assertThat(bar.volume).isEqualByComparingTo("0")
        }
        assertThat(gateway.requests.first().first)
            .endsWith("&start=2026-05-13T08%3A00%3A00&end=2026-05-13T09%3A00%3A00")
    }

    @Test
    fun `bid bars are lifted to mid by half the recorded spread`() {
        gateway.on(
            "/fetch_data_range",
            """[{"open":100,"high":101,"low":99,"close":100.5,"spread":20,"timestamp":"$eight"}]""",
        )

        val bar = client.fetchBarsByRange("XAUUSDm", "M5", "a", "b", midPoint = "0.01".toBigDecimal()).single()

        assertThat(bar.open).isEqualByComparingTo("100.10")
        assertThat(bar.close).isEqualByComparingTo("100.60")
        assertThat(bar.startTime).isEqualTo(eight)
        assertThat(bar.endTime - bar.startTime).isEqualTo(300_000L)
    }

    @Test
    fun `mid normalization refuses a row with no spread`() {
        gateway.on("/fetch_data_range", """[{"open":1,"high":1,"low":1,"close":1,"time":1}]""")

        assertThatThrownBy { client.fetchBarsByRange("XAUUSDm", "M15", "a", "b", midPoint = "0.01".toBigDecimal()) }
            .hasMessageContaining("missing spread")
    }

    @Test
    fun `volume falls back from tick volume to volume to real volume`() {
        gateway.on(
            "/fetch_data_range",
            """[{"open":1,"high":1,"low":1,"close":1,"volume":5,"time":1},""" +
                """{"open":1,"high":1,"low":1,"close":1,"real_volume":9,"time":1}]""",
        )

        val bars = client.fetchBarsByRange("XAUUSDm", "M30", "a", "b")

        assertThat(bars.map { it.volume.toInt() }).containsExactly(5, 9)
        assertThat(bars.map { it.endTime - it.startTime }).containsOnly(1_800_000L)
    }

    @Test
    fun `broker wall time and ISO instants are both read as broker local`() {
        val nyClose = Mt5DataClient(gateway.url, serverTimeZone = MT5ServerTimeZone.NEW_YORK_CLOSE)
        gateway.on(
            "/fetch_data_range",
            """[{"open":1,"high":1,"low":1,"close":1,"time":"2026-05-13T11:00:00Z"},""" +
                """{"open":1,"high":1,"low":1,"close":1,"time":"2026-05-13T11:00:00.000"}]""",
        )

        val starts = nyClose.fetchBarsByRange("XAUUSDm", "H4", "a", "b").map { it.startTime }

        // New York close is UTC+3 in May: 11:00 broker wall time is 08:00 UTC.
        assertThat(starts).containsExactly(eight, eight)
    }

    @Test
    fun `seconds and milliseconds epochs are both understood`() {
        gateway.on(
            "/fetch_data_range",
            """[{"open":1,"high":1,"low":1,"close":1,"time":${eight / 1000}},""" +
                """{"open":1,"high":1,"low":1,"close":1,"time":$eight}]""",
        )

        val bars = client.fetchBarsByRange("XAUUSDm", "D1", "a", "b")

        assertThat(bars.map { it.startTime }).containsExactly(eight, eight)
        assertThat(bars.first().endTime - eight).isEqualTo(86_400_000L)
    }

    @Test
    fun `a row with no time, an unknown timeframe, an odd shape and an HTTP failure are errors`() {
        gateway.on("/fetch_data_range", """[{"open":1,"high":1,"low":1,"close":1}]""")
        assertThatThrownBy { client.fetchBarsByRange("X", "M1", "a", "b") }.hasMessageContaining("missing time")

        gateway.on("/fetch_data_range", "[]")
        assertThatThrownBy { client.fetchBarsByRange("X", "W1", "a", "b") }
            .hasMessageContaining("Unknown MT5 timeframe")

        gateway.on("/fetch_data_range", """{"rows":[]}""")
        assertThatThrownBy { client.fetchBarsByRange("X", "M1", "a", "b") }
            .hasMessageContaining("Unexpected MT5 response")

        gateway.fail("/fetch_data_range", 503)
        assertThatThrownBy { client.fetchBarsByRange("X", "M1", "a", "b") }.hasMessageContaining("HTTP 503")
    }

    @Test
    fun `symbol point is read from symbol info and is null when the venue cannot answer`() {
        gateway.on("/symbol_info/XAUUSDm", """{"point":0.01}""")
        gateway.on("/symbol_info/EURUSDm", """{"digits":5}""")
        gateway.fail("/symbol_info/BTCUSDm", 404)

        assertThat(client.fetchSymbolPoint("XAUUSDm")).isEqualByComparingTo("0.01")
        assertThat(client.fetchSymbolPoint("EURUSDm")).isNull()
        assertThat(client.fetchSymbolPoint("BTCUSDm")).isNull()
    }
}
