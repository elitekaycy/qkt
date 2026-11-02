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
    private val level = GatewayMarginLevel(client, clock)

    @AfterEach
    fun stop() = fake.shutdown()

    private fun account(
        equity: String,
        used: String,
    ) {
        fake.account =
            """{"currency":"USDC","balance":"$equity","equity":"$equity","margin_used":"$used","margin_available":"0"}"""
    }

    @Test
    fun `reads equity over used margin as a percent`() {
        account("1000", "400")

        assertThat(level.read()).isEqualByComparingTo("250")
    }

    @Test
    fun `an account using no margin reads 0, which the floor approves`() {
        account("1000", "0")

        assertThat(level.read()).isEqualByComparingTo("0")
    }

    @Test
    fun `a reading stands for its time to live, then is read afresh`() {
        account("1000", "400")
        level.read()
        account("1000", "800")

        assertThat(level.read()).isEqualByComparingTo("250")
        nowMs += 5_000L
        assertThat(level.read()).isEqualByComparingTo("125")
    }

    @Test
    fun `an account the gateway cannot state reads null, which the floor counts as missing`() {
        fake.account = "not json"

        assertThat(level.read()).isNull()
    }

    @Test
    fun `the floor judges an entry on a gateway broker by the account's level`() {
        val symbol = "DERIBIT:X"
        val session =
            GatewaySession(
                client,
                GatewaySymbols("DERIBIT:"),
                clock,
                GatewayIdentity("fake", "7", "demo"),
                { setOf("s") },
                streamFactory = { e, r, c -> GatewayStream(fake.url, "k", e, r, c, initialBackoffMs = 20) },
                label = "DERIBIT",
            )
        try {
            val bus = EventBus(clock, MonotonicSequenceGenerator())
            val broker = GatewayBroker(session, bus, clock, Flat, "s")
            val floor = MarginFloor(broker, BigDecimal("200"))
            val entry = OrderRequest.Market("e", symbol, Side.BUY, BigDecimal.ONE, TimeInForce.GTC, 1L, "s")
            assertThat(broker).isInstanceOf(MarginLevelProvider::class.java)
            assertThat(broker.supportsMarginLevel).isTrue()

            account("1000", "400")
            assertThat(floor.evaluate(entry, Flat)).isEqualTo(Decision.Approve)

            account("1000", "800")
            nowMs += 5_000L
            assertThat(floor.evaluate(entry, Flat)).isInstanceOf(Decision.Reject::class.java)
        } finally {
            session.close()
        }
    }

    private object Flat : PositionProvider {
        override fun positionFor(symbol: String): Position? = null

        override fun allPositions() = emptyMap<String, Position>()
    }
}
