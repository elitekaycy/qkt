package com.qkt.connector.gateway

import com.qkt.broker.Broker
import com.qkt.broker.MarginLevelProvider
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.rules.MarginFloor
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** An entry never waits on the gateway: a level not yet read, or gone stale, is a missing read, decided at once. */
class GatewayMarginLevelHungTest {
    private val server = MockWebServer()
    private var nowMs = 1_000L
    private val clock =
        object : Clock {
            override fun now() = nowMs
        }

    @BeforeEach
    fun hang() {
        // Takes the request and never answers: the client waits out its whole timeout.
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
        server.start()
    }

    @AfterEach
    fun stop() {
        level.close()
        server.shutdown()
    }

    private val hungClient by lazy {
        GatewayClient(server.url("/").toString().trimEnd('/'), "k", httpTimeoutMs = 30_000L, retryAttempts = 3)
    }
    private val level by lazy { GatewayMarginLevel(hungClient::account, clock, "H") }
    private val floor by lazy { MarginFloor(Venue(level), BigDecimal("200")) }
    private val entry = OrderRequest.Market("e", "X", Side.BUY, BigDecimal.ONE, TimeInForce.GTC, 1L, "s")

    @Test
    fun `with a read hanging, an entry is decided without waiting, the first two approve and the third rejects`() {
        val startNs = System.nanoTime()
        val decisions = mutableListOf(floor.evaluate(entry, Flat))

        // The poller is now inside the hung read, which holds it for up to 90 seconds.
        assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull()
        decisions += floor.evaluate(entry, Flat)
        decisions += floor.evaluate(entry, Flat)

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)).isLessThan(5_000L)
        assertThat(decisions.take(2)).containsOnly(Decision.Approve)
        assertThat(decisions[2]).isInstanceOf(Decision.Reject::class.java)
        assertThat((decisions[2] as Decision.Reject).reason).contains("unavailable after 3 reads")
    }

    @Test
    fun `a level gone stale behind a hung read is missing, and a fresh one clears the count`() {
        val answers = java.util.concurrent.ConcurrentLinkedDeque(listOf(account("1000", "400")))
        val polled =
            GatewayMarginLevel(
                { answers.pollFirst() ?: hungClient.account() },
                clock,
                "S",
                pollMs = 3_600_000L,
            )
        val judged = MarginFloor(Venue(polled), BigDecimal("200"))
        polled.poll()
        assertThat(judged.evaluate(entry, Flat)).isEqualTo(Decision.Approve)
        // The read started the poller, which now sits in the hung read.
        assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull()

        nowMs += 15_000L
        assertThat(judged.evaluate(entry, Flat)).isEqualTo(Decision.Approve)
        assertThat(judged.evaluate(entry, Flat)).isEqualTo(Decision.Approve)
        assertThat(judged.evaluate(entry, Flat)).isInstanceOf(Decision.Reject::class.java)

        answers.addLast(account("1000", "400"))
        polled.poll()
        assertThat(judged.evaluate(entry, Flat)).isEqualTo(Decision.Approve)
        nowMs += 15_000L
        assertThat(judged.evaluate(entry, Flat)).isEqualTo(Decision.Approve)
        polled.close()
    }

    private fun account(
        equity: String,
        used: String,
    ) = WireAccount("USDC", equity, equity, used, "0")

    private class Venue(
        private val level: GatewayMarginLevel,
    ) : Broker,
        MarginLevelProvider {
        override val name = "H"
        override val capabilities = emptySet<OrderTypeCapability>()
        override val supportsMarginLevel = true

        override fun marginLevel() = level.read()

        override fun submit(request: OrderRequest) = error("not used")

        override fun cancel(orderId: String) = error("not used")

        override fun modify(
            orderId: String,
            changes: com.qkt.broker.OrderModification,
        ) = error("not used")
    }

    private object Flat : PositionProvider {
        override fun positionFor(symbol: String): Position? = null

        override fun allPositions() = emptyMap<String, Position>()
    }
}
