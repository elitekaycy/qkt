package com.qkt.connector.gateway

import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** One gateway's rate-limit backoff: doubling with jitter, `Retry-After` honoured, shared per gateway (#1663). */
class GatewayThrottleTest {
    private var nowMs = 1_000_000L
    private val throttle = GatewayThrottle(now = { nowMs }, random = Random(7))

    @Test
    fun `each limited answer in a row doubles the pause, jittered to between half and all of it, up to the cap`() {
        val pauses =
            (1..10).map {
                throttle.limited(null)
                throttle.waitMs().also { nowMs += it }
            }

        listOf(250L, 500L, 1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L).forEachIndexed { i, full ->
            assertThat(pauses[i]).isBetween(full / 2, full)
        }
        assertThat(pauses.last()).isLessThanOrEqualTo(15_000L)
    }

    @Test
    fun `an answer without a rate limit ends the streak`() {
        repeat(5) { throttle.limited(null) }
        nowMs += 60_000
        throttle.answered()

        throttle.limited(null)

        assertThat(throttle.waitMs()).isBetween(125L, 250L)
    }

    @Test
    fun `a Retry-After in seconds or as a date is honoured as given`() {
        throttle.limited("3")
        assertThat(throttle.waitMs()).isEqualTo(3_000L)

        nowMs = 1_789_990_400_000L
        throttle.limited("Mon, 21 Sep 2026 11:33:30 GMT")
        assertThat(throttle.waitMs()).isEqualTo(10_000L)
    }

    @Test
    fun `a request facing a pause past a second is not sent`() {
        throttle.limited("5")

        assertThatThrownBy { throttle.pace("/v1/orders/x", null) }
            .isInstanceOf(GatewayUnavailableException::class.java)
            .hasMessageContaining("backing off for 5000 ms")
    }

    @Test
    fun `every client of one gateway shares its throttle`() {
        assertThat(GatewayThrottle.of("http://gw:8443/")).isSameAs(GatewayThrottle.of("http://gw:8443"))
        assertThat(GatewayThrottle.of("http://gw:8443")).isNotSameAs(GatewayThrottle.of("http://other:8443"))
    }
}
