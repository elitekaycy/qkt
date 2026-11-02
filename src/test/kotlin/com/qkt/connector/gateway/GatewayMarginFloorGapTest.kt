package com.qkt.connector.gateway

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.qkt.app.EntryGuardRules
import com.qkt.app.LiveSession
import com.qkt.app.MarginFloorNotices
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.rules.MarginFloor
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** The floor stays out of an adapter whose figures cannot drive it, says so at startup, and leaves other venues alone. */
class GatewayMarginFloorGapTest {
    private val fake = FakeGateway(listOf("X"))
    private val clock = FixedClock(1_000L)
    private val client = GatewayClient(fake.url, "k", httpTimeoutMs = 500, retryAttempts = 1)
    private val logger = LoggerFactory.getLogger(LiveSession::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private val notices = MarginFloorNotices()
    private val entry = OrderRequest.Market("e", "V:X", Side.BUY, BigDecimal.ONE, TimeInForce.GTC, 1L, "s")

    @BeforeEach
    fun attach() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun stop() {
        logger.detachAppender(appender)
        fake.shutdown()
    }

    private fun usingMargin() {
        fake.account =
            """{"currency":"USDT","balance":"100","equity":"100","margin_used":"90","margin_available":"0"}"""
    }

    private fun warnings() = appender.list.map { it.formattedMessage }.filter { "margin_floor_pct" in it }

    private fun <T> onAdapter(
        adapter: String,
        label: String,
        body: (GatewayBroker) -> T,
    ): T {
        fake.adapter = adapter
        val session =
            GatewaySession(
                client,
                GatewaySymbols("V:"),
                clock,
                GatewayIdentity(adapter, "7", "demo"),
                { setOf("s") },
                streamFactory = { e, r, c -> GatewayStream(fake.url, "k", e, r, c, initialBackoffMs = 20) },
                label = label,
            )
        try {
            return body(GatewayBroker(session, EventBus(clock, MonotonicSequenceGenerator()), clock, Flat, "s"))
        } finally {
            session.close()
        }
    }

    private fun guard() = EntryGuardRules(clock, BigDecimal("200"), 0L, BigDecimal.ONE, notices)

    private fun pollers(label: String) =
        Thread.getAllStackTraces().keys.filter { it.isAlive && it.name == "qkt-gateway-margin-$label" }

    @Test
    fun `a Bybit figure cannot refuse an entry, and nothing polls for it`() {
        usingMargin()
        onAdapter("bybit", "BYBIT_GAP") { broker ->
            val rules = guard().rules(broker, listOf("V:X"))

            assertThat(broker.supportsMarginLevel).isFalse()
            assertThat(broker.marginLevel()).isNull()
            repeat(4) { assertThat(rules.single().evaluate(entry, Flat)).isEqualTo(Decision.Approve) }
            assertThat(pollers("BYBIT_GAP")).isEmpty()
        }
    }

    @Test
    fun `Bybit is named at startup with the reason, once per venue`() {
        onAdapter("bybit", "BYBIT_GAP") { broker ->
            guard().rules(broker, listOf("V:X"))
            guard().rules(broker, listOf("V:X"))
        }

        assertThat(warnings().single()).contains("venue BYBIT_GAP", "pooled margin", "cannot apply", "V:X")
    }

    @Test
    fun `a paper adapter is named at startup, once per venue`() {
        onAdapter("paper", "PAPER_GAP") { broker ->
            guard().rules(broker, listOf("V:X"))
            guard().rules(broker, listOf("V:X"))
            assertThat(broker.supportsMarginLevel).isFalse()
        }

        assertThat(warnings().single()).contains("venue PAPER_GAP", "always states no margin in use", "cannot apply")
    }

    @Test
    fun `a real venue that is flat gets no warning and stays judged`() {
        fake.account = FakeWire.ACCOUNT
        onAdapter("deribit", "DERIBIT_FLAT") { broker ->
            guard().rules(broker, listOf("V:X"))

            assertThat(broker.supportsMarginLevel).isTrue()
            assertThat(pollers("DERIBIT_FLAT")).hasSize(1)
        }

        assertThat(warnings()).isEmpty()
    }

    @Test
    fun `a real venue using margin is still refused below the floor`() {
        usingMargin()
        onAdapter("deribit", "DERIBIT_USED") { broker ->
            val floor = MarginFloor(broker, BigDecimal("200"))
            broker.warmMarginLevel()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (broker.marginLevel() == null && System.nanoTime() < deadline) Thread.sleep(2)

            assertThat(floor.evaluate(entry, Flat)).isInstanceOf(Decision.Reject::class.java)
        }
    }

    private object Flat : PositionProvider {
        override fun positionFor(symbol: String): Position? = null

        override fun allPositions() = emptyMap<String, Position>()
    }
}
