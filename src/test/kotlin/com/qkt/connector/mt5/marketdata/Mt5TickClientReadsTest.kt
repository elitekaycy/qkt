package com.qkt.connector.mt5.marketdata

import com.qkt.connector.mt5.Mt5RoutedGateway
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class Mt5TickClientReadsTest {
    private val gateway = Mt5RoutedGateway()
    private val client = Mt5TickClient(gateway.url, apiKey = "k")

    @AfterEach
    fun stop() = gateway.close()

    private class Delivered : RuntimeException()

    @Test
    fun `one quote reads bid ask last and flags, timed in seconds when the venue gives no millis`() {
        gateway.on(
            "/symbol_info_tick/XAUUSDm",
            """{"bid":2400.10,"ask":2400.30,"last":2400.2,"flags":6,"time":1800000000}""",
        )

        val tick = client.fetchOnce("XAUUSDm", capturedAtMs = 42L)

        assertThat(tick.capturedAtMs).isEqualTo(42L)
        assertThat(tick.brokerTimeMs).isEqualTo(1_800_000_000_000L)
        assertThat(tick.last).isEqualByComparingTo("2400.2")
        assertThat(tick.flags).isEqualTo(6)
        assertThat(tick.mid).isEqualByComparingTo("2400.20")
    }

    @Test
    fun `one quote fails on an HTTP error or a body that is not an object`() {
        gateway.fail("/symbol_info_tick/XAUUSDm", 502)
        assertThatThrownBy { client.fetchOnce("XAUUSDm", 0L) }.hasMessageContaining("HTTP 502")

        gateway.on("/symbol_info_tick/XAUUSDm", "[]")
        assertThatThrownBy { client.fetchOnce("XAUUSDm", 0L) }.hasMessageContaining("non-object")
    }

    @Test
    fun `a tick range defaults a missing last and flags, and falls back to whole seconds`() {
        gateway.on(
            "/copy_ticks_range",
            """{"data":[{"bid":1.1,"ask":1.2,"time":1800000001},""" +
                """{"bid":1.0,"ask":1.3,"last":1.15,"flags":2,"time_msc":1800000000500},""" +
                """{"bid":1.0,"ask":1.3}]}""",
        )

        val ticks = client.fetchRange("EURUSDm", 1_800_000_000_000L, 1_800_000_002_000L, capturedAtMs = 7L)

        assertThat(ticks.map { it.brokerTimeMs }).containsExactly(0L, 1_800_000_000_500L, 1_800_000_001_000L)
        assertThat(ticks[2].last).isEqualByComparingTo("0")
        assertThat(ticks[2].flags).isZero()
        assertThat(ticks[1].last).isEqualByComparingTo("1.15")
        assertThat(ticks[1].flags).isEqualTo(2)
        assertThat(ticks.map { it.capturedAtMs }).containsOnly(7L)
    }

    @Test
    fun `a tick range fails on an HTTP error or a body that is not a list`() {
        gateway.fail("/copy_ticks_range", 500)
        assertThatThrownBy { client.fetchRange("EURUSDm", 0L, 5_000L, 0L) }.hasMessageContaining("HTTP 500")

        gateway.on("/copy_ticks_range", """{"ticks":[]}""")
        assertThatThrownBy { client.fetchRange("EURUSDm", 0L, 5_000L, 0L) }.hasMessageContaining("non-array")
    }

    @Test
    fun `polling skips a failed read and a stale quote and hands on the first newer one`() {
        val seen = mutableListOf<Mt5TickClient.Mt5Tick>()
        gateway.on(
            "/symbol_info_tick/XAUUSDm",
            "{}",
            """{"bid":1,"ask":1,"last":1,"flags":0,"time_msc":0}""",
            """{"bid":2,"ask":2,"last":2,"flags":0,"time_msc":5000}""",
        )

        assertThatThrownBy {
            client.pollUntil("XAUUSDm", deadlineMs = Long.MAX_VALUE, intervalMs = 0L) { tick ->
                seen.add(tick)
                throw Delivered()
            }
        }.isInstanceOf(Delivered::class.java)

        assertThat(seen.single().bid).isEqualByComparingTo("2")
        assertThat(gateway.calls("/symbol_info_tick")).hasSize(3)
    }

    @Test
    fun `polling past its deadline reads nothing`() {
        client.pollUntil("XAUUSDm", deadlineMs = 0L, intervalMs = 0L) { error("no tick expected") }

        assertThat(gateway.requests).isEmpty()
    }
}
