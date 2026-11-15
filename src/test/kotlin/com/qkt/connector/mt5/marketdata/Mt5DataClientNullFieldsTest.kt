package com.qkt.connector.mt5.marketdata

import com.qkt.connector.mt5.Mt5RoutedGateway
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Bar rows and symbol info whose optional fields are null or unreadable. */
class Mt5DataClientNullFieldsTest {
    private val gateway = Mt5RoutedGateway()
    private val client = Mt5DataClient(gateway.url)

    @AfterEach
    fun stop() = gateway.close()

    @Test
    fun `a null or unreadable volume is no volume`() {
        gateway.on(
            "/fetch_data_pos",
            """[{"open":1,"high":1,"low":1,"close":1,"tick_volume":null,"time":60},""" +
                """{"open":1,"high":1,"low":1,"close":1,"volume":"lots","time":120}]""",
        )

        val bars = client.fetchBarsByPos("XAUUSDm", "m1", 2)

        assertThat(bars.map { it.volume.signum() }).containsOnly(0)
        assertThat(bars.map { it.startTime }).containsExactly(60_000L, 120_000L)
    }

    @Test
    fun `a null time falls back to the timestamp field and a row with neither is refused`() {
        gateway.on("/fetch_data_pos", """[{"open":1,"high":1,"low":1,"close":1,"time":null,"timestamp":180}]""")
        assertThat(client.fetchBarsByPos("XAUUSDm", "H1", 1).single().startTime).isEqualTo(180_000L)

        gateway.on("/fetch_data_pos", """[{"open":1,"high":1,"low":1,"close":1,"time":null,"timestamp":null}]""")
        assertThatThrownBy { client.fetchBarsByPos("XAUUSDm", "H1", 1) }.hasMessageContaining("missing time/timestamp")
    }

    @Test
    fun `mid normalization refuses a null or unreadable spread`() {
        gateway.on(
            "/fetch_data_range",
            """[{"open":1,"high":1,"low":1,"close":1,"spread":null,"time":1}]""",
            """[{"open":1,"high":1,"low":1,"close":1,"spread":"wide","time":1}]""",
        )

        repeat(2) {
            assertThatThrownBy { client.fetchBarsByRange("XAUUSDm", "M1", "a", "b", midPoint = "0.01".toBigDecimal()) }
                .hasMessageContaining("missing spread required for bid-to-mid normalization")
        }
    }

    @Test
    fun `a symbol point that is null or unreadable is no point`() {
        gateway.on("/symbol_info/XAUUSDm", """{"point":null}""", """{"point":"tiny"}""")

        assertThat(client.fetchSymbolPoint("XAUUSDm")).isNull()
        assertThat(client.fetchSymbolPoint("XAUUSDm")).isNull()
    }

    @Test
    fun `bars or data that are not a list are an unexpected shape`() {
        gateway.on("/fetch_data_pos", """{"bars":{"open":1},"data":7}""")

        assertThatThrownBy {
            client.fetchBarsByPos(
                "XAUUSDm",
                "H1",
                1,
            )
        }.hasMessageContaining("Unexpected MT5 response shape")
    }
}
