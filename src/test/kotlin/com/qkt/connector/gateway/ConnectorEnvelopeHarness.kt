package com.qkt.connector.gateway

import com.qkt.broker.Broker
import com.qkt.broker.VenueDescribed
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.connector.mt5.MT5Broker
import com.qkt.connector.mt5.MT5DefaultProfiles
import com.qkt.events.FillAccountedEvent
import com.qkt.observe.insights.InsightsEnvelope
import com.qkt.observe.insights.InsightsTranslate
import com.qkt.observe.insights.InsightsVenues
import com.qkt.observe.insights.SharedDealFetch
import com.qkt.observe.insights.TicketAttribution
import com.qkt.observe.insights.VenueStateCycle
import com.qkt.observe.insights.venuePayload
import com.qkt.pnl.PositionMarks
import com.qkt.positions.Position
import java.math.BigDecimal
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach

/**
 * Both connectors on the same account fixtures: an MT5 broker over a mock MT5 gateway and a VGP broker
 * over the fake gateway, each polled once by [VenueStateCycle] plus a `broker.connected`.
 */
internal abstract class ConnectorEnvelopeHarness : GatewayHarness() {
    private val mt5Server = MockWebServer().also { it.start() }
    private lateinit var mt5: MT5Broker

    @AfterEach
    fun stopMt5() {
        if (::mt5.isInitialized) mt5.shutdown()
        mt5Server.shutdown()
    }

    protected fun envelopes(
        broker: Broker,
        deployed: Set<String>,
        marks: PositionMarks? = null,
    ): Map<String, InsightsEnvelope> =
        // The closing deal carries the most facts: its leg, its close reason and the P&L it realized.
        emitted(broker, deployed, marks).groupBy { it.type }.mapValues { (_, all) -> all.last() }

    protected fun emitted(
        broker: Broker,
        deployed: Set<String>,
        marks: PositionMarks? = null,
    ): List<InsightsEnvelope> {
        val out = mutableListOf<InsightsEnvelope>()
        val venues = InsightsVenues({ listOf(broker) })
        VenueStateCycle(
            Any(),
            out::add,
            TicketAttribution(),
            { deployed },
            venues,
            null,
            true,
            30,
            60_000,
            SharedDealFetch(),
            { NOW },
            marks,
        ).poll(broker, mutableSetOf())
        out +=
            InsightsTranslate.brokerConnected(
                broker.name,
                NOW,
                venue = venuePayload((broker as VenueDescribed).venueIdentity()),
            )
        return out
    }

    protected fun mt5Broker(): MT5Broker {
        mt5Server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/get_positions") -> MockResponse().setBody(Mt5Wire.MT5_POSITIONS)
                        path.startsWith("/orders") -> MockResponse().setBody(Mt5Wire.MT5_ORDERS)
                        path.startsWith("/account") -> MockResponse().setBody(Mt5Wire.MT5_ACCOUNT)
                        path.startsWith("/history_deals_get") -> MockResponse().setBody(Mt5Wire.MT5_DEALS)
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val clock = FixedClock(time = NOW)
        val profile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = mt5Server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100_000,
            )
        return MT5Broker(profile, EventBus(clock, MonotonicSequenceGenerator()), clock).also { mt5 = it }
    }

    protected fun vgpBroker(): GatewayBroker {
        val strategy = Strategy()
        val session = session(setOf("a"))
        val broker = broker(session, strategy, "a")
        session.legs.booked("dsl-a-1", "f-1")
        session.legs.booked("dsl-a-2-tp", "f-2")
        val open = Position(symbol, BigDecimal("0.2"), BigDecimal("640"), NOW - 2_000)
        val after = Position(symbol, BigDecimal("0.1"), BigDecimal("640"), NOW - 2_000)
        fake.venue.deals += WireFill("dsl-a-1.k", "v-1", "f-1", code, "buy", "0.2", "640", NOW - 2_000)
        strategy.bus.publish(accounted("dsl-a-1", NOW - 2_000, null, open, BigDecimal.ZERO))
        val cost = listOf(WireCost("commission", "0.03", "USDC"))
        fake.venue.deals += WireFill("dsl-a-2-tp.k", "v-2", "f-2", code, "sell", "0.1", "660", NOW - 1_000, cost)
        strategy.bus.publish(accounted("dsl-a-2-tp", NOW - 1_000, open, after, BigDecimal("2")))
        fake.venue.place(WireSubmit("dsl-a-3.k", code, "buy", "limit", "0.1", limitPrice = "600", timeInForce = "gtc"))
        return broker
    }

    private fun accounted(
        orderId: String,
        time: Long,
        before: Position?,
        after: Position?,
        realized: BigDecimal,
    ) = FillAccountedEvent(
        orderId,
        "a",
        symbol,
        "$orderId:1",
        1,
        null,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        realized,
        realized,
        "USDC",
        realized,
        realized,
        "USDC",
        realized,
        realized,
        null,
        null,
        null,
        BigDecimal.ONE,
        before,
        after,
        before,
        after,
        reducedExposure = before != null,
        partial = false,
        executedAt = time,
    )

    companion object {
        const val NOW = 1_700_060_000_000L
        const val MT5_LOGIN = "435898347"
        const val MT5_SERVER = "Exness-MT5Trial9"
        val TYPES = listOf("state.account", "state.positions", "state.orders", "broker.deal", "broker.connected")
    }
}
