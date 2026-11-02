package com.qkt.connector.gateway

import com.qkt.broker.MarginLevelProvider
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.rules.MarginFloor
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** A gateway account's margin level comes from what VGP v1's `/v1/account` states, and the floor judges entries by it. */
class GatewayMarginLevelTest {
    private val fake = FakeGateway(listOf("X"))
    private var nowMs = 1_000L
    private val clock =
        object : Clock {
            override fun now() = nowMs
        }
    private val client = GatewayClient(fake.url, "k", httpTimeoutMs = 500, retryAttempts = 1)

    // The poller reads once and sleeps past the test: each later reading is the test's own poll().
    private val level = GatewayMarginLevel(client::account, clock, "T", pollMs = 3_600_000L)

    @AfterEach
    fun stop() {
        level.close()
        fake.shutdown()
    }

    private fun account(
        equity: String,
        used: String,
    ) {
        fake.account =
            """{"currency":"USDC","balance":"$equity","equity":"$equity","margin_used":"$used","margin_available":"0"}"""
    }

    private fun warmed(): GatewayMarginLevel =
        level.also {
            it.start()
            awaitTrue { it.read() != null }
        }

    @Test
    fun `reads equity over used margin as a percent`() {
        account("1000", "400")

        assertThat(warmed().read()).isEqualByComparingTo("250")
    }

    @Test
    fun `an account using no margin reads 0, which the floor approves`() {
        account("1000", "0")

        assertThat(warmed().read()).isEqualByComparingTo("0")
    }

    @Test
    fun `each poll replaces the reading`() {
        account("1000", "400")
        warmed()
        account("1000", "800")

        assertThat(level.read()).isEqualByComparingTo("250")
        level.poll()
        assertThat(level.read()).isEqualByComparingTo("125")
    }

    @Test
    fun `a reading stands until it is 15 seconds old, then is missing`() {
        account("1000", "400")
        warmed()

        nowMs += 14_999L
        assertThat(level.read()).isEqualByComparingTo("250")
        nowMs += 1L
        assertThat(level.read()).isNull()
    }

    @Test
    fun `a failed read leaves the last reading to age out`() {
        account("1000", "400")
        warmed()
        fake.account = "not json"

        level.poll()

        assertThat(level.read()).isEqualByComparingTo("250")
        nowMs += 15_000L
        assertThat(level.read()).isNull()
        account("1000", "400")
        level.poll()
        assertThat(level.read()).isEqualByComparingTo("250")
    }

    @Test
    fun `an account that was never readable reads null`() {
        fake.account = "not json"
        val unread = GatewayMarginLevel(client::account, clock, "U", pollMs = 3_600_000L)

        unread.poll()

        assertThat(unread.read()).isNull()
        unread.close()
    }

    @Test
    fun `the poller reads again on its own, and stops when closed`() {
        val calls = AtomicInteger()
        val polled =
            GatewayMarginLevel({ calls.incrementAndGet().let { client.account() } }, clock, "P", pollMs = 5L)

        polled.start()
        awaitTrue { calls.get() >= 3 }
        polled.close()
        Thread.sleep(100)
        val after = calls.get()
        Thread.sleep(100)

        assertThat(calls.get()).isEqualTo(after)
    }

    @Test
    fun `the floor judges an entry on a gateway broker by the account's level, one poller per account`() {
        val session = session("MARGINTEST")
        try {
            val first = GatewayBroker(session, bus(), clock, Flat, "s")
            val second = GatewayBroker(session, bus(), clock, Flat, "t")
            val floor = MarginFloor(first, BigDecimal("200"))
            val entry = OrderRequest.Market("e", "DERIBIT:X", Side.BUY, BigDecimal.ONE, TimeInForce.GTC, 1L, "s")
            assertThat(first).isInstanceOf(MarginLevelProvider::class.java)
            assertThat(first.supportsMarginLevel).isTrue()

            account("1000", "400")
            first.warmMarginLevel()
            second.warmMarginLevel()
            awaitTrue { first.marginLevel() != null }
            assertThat(floor.evaluate(entry, Flat)).isEqualTo(Decision.Approve)
            assertThat(Thread.getAllStackTraces().keys.filter { it.name == "qkt-gateway-margin-MARGINTEST" }).hasSize(1)

            account("1000", "800")
            session.margin.poll()
            assertThat(second.marginLevel()).isEqualByComparingTo("125")
            assertThat(floor.evaluate(entry, Flat)).isInstanceOf(Decision.Reject::class.java)
        } finally {
            session.close()
        }
    }

    private fun bus() = EventBus(clock, MonotonicSequenceGenerator())

    private fun session(label: String) =
        GatewaySession(
            client,
            GatewaySymbols("DERIBIT:"),
            clock,
            GatewayIdentity("fake", "7", "demo"),
            { setOf("s", "t") },
            streamFactory = { e, r, c -> GatewayStream(fake.url, "k", e, r, c, initialBackoffMs = 20) },
            label = label,
        )

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within 5 seconds" }
            Thread.sleep(2)
        }
    }

    private object Flat : PositionProvider {
        override fun positionFor(symbol: String): Position? = null

        override fun allPositions() = emptyMap<String, Position>()
    }
}
