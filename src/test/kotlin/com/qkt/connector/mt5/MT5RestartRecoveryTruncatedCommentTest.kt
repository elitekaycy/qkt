package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Restart recovery after a `STACK_AT` burst (live run scale-burst-003): the seed is the only open
 * position, its venue comment truncated to `dsl-gold_scale_burst_fixe`, while ten leg entries
 * that were rejected or filled-and-closed on their own tickets come back from persisted state.
 */
class MT5RestartRecoveryTruncatedCommentTest {
    private lateinit var server: MockWebServer
    private lateinit var broker: MT5Broker
    private val captured = CopyOnWriteArrayList<BrokerEvent>()
    private val clock = FixedClock(time = SEED_OPENED_MS + 50_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        repeat(3) { server.enqueue(MockResponse().setBody("[]")) }
        bus.subscribe<BrokerEvent.OrderAccepted> { captured.add(it) }
        bus.subscribe<BrokerEvent.OrderFilled> { captured.add(it) }
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { captured.add(it) }
        bus.subscribe<BrokerEvent.OrderCancelled> { captured.add(it) }
        broker =
            MT5Broker(
                MT5DefaultProfiles.exness.copy(
                    gatewayUrl = server.url("/").toString().trimEnd('/'),
                    httpTimeoutMs = 2000,
                    retryAttempts = 0,
                    pollIntervalMs = 100_000,
                    instrumentOverrides = mapOf("EXNESS:EURUSD" to EURUSD_SPEC),
                ),
                bus,
                clock,
                recoveryReadAttempts = 3,
                recoveryReadBackoffMs = 1L,
            )
    }

    @AfterEach
    fun teardown() {
        broker.shutdown()
        server.shutdown()
    }

    @Test
    fun `stale leg entries never adopt the seed's position when the ledger has not booked it`() {
        venueHoldsOnlyTheSeed()

        val accounted = broker.recoverPendingOrders(staleLegEntries(legQty = "0.05"), bookedTickets = emptySet())

        assertThat(captured).isEmpty()
        assertThat(accounted).isEmpty()
        assertThat(broker.ticketAttributions()).doesNotContainKey(SEED_TICKET.toString())
    }

    @Test
    fun `stale leg entries do not re-attribute the seed's booked ticket to themselves`() {
        venueHoldsOnlyTheSeed()

        val accounted =
            broker.recoverPendingOrders(staleLegEntries(legQty = "0.05"), bookedTickets = setOf(SEED_TICKET.toString()))

        assertThat(captured).isEmpty()
        assertThat(accounted).isEmpty()
    }

    @Test
    fun `a single stale entry larger than the seed never adopts it as a partial fill`() {
        venueHoldsOnlyTheSeed()

        val accounted =
            broker.recoverPendingOrders(staleLegEntries(legQty = "0.05").take(1), bookedTickets = emptySet())

        assertThat(captured).isEmpty()
        assertThat(accounted).isEmpty()
    }

    @Test
    fun `an entry that truly filled during downtime still recovers from its truncated comment`() {
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(
            MockResponse().setBody(
                positionJson(ticket = 3272336628L, volume = "0.05", openedMs = LEGS_SENT_MS + 400L),
            ),
        )
        val leg = staleLegEntries(legQty = "0.05").take(1)

        val accounted = broker.recoverPendingOrders(leg, bookedTickets = emptySet())

        assertThat(accounted).containsExactly(leg.single().id)
        assertThat(captured.filterIsInstance<BrokerEvent.OrderFilled>().single().brokerOrderId)
            .isEqualTo("3272336628")
    }

    private fun venueHoldsOnlyTheSeed() {
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody(positionJson(SEED_TICKET, volume = "0.01", openedMs = SEED_OPENED_MS)))
    }

    private fun staleLegEntries(legQty: String): List<ManagedOrder> =
        (0..9).map { tier ->
            val id = "dsl-gold_scale_burst_fixed--0-stack-tier$tier-entry"
            ManagedOrder(
                id = id,
                request =
                    OrderRequest.Market(
                        id = id,
                        symbol = "EXNESS:EURUSD",
                        side = Side.BUY,
                        quantity = BigDecimal(legQty),
                        timeInForce = TimeInForce.GTC,
                        timestamp = LEGS_SENT_MS,
                        strategyId = "gold_scale_burst_fixed",
                    ),
                state = OrderState.WORKING,
                createdAt = clock.now(),
                lastUpdatedAt = clock.now(),
            )
        }

    private fun positionJson(
        ticket: Long,
        volume: String,
        openedMs: Long,
    ): String =
        """[{"ticket":"$ticket","symbol":"EURUSDm","type":"0","volume":"$volume","price_open":"1.10000",""" +
            """"sl":"0","tp":"0","profit":"0","magic":"10001","time_msc":"$openedMs",""" +
            """"comment":"dsl-gold_scale_burst_fixe"}]"""

    companion object {
        private const val SEED_TICKET = 3272336587L
        private const val SEED_OPENED_MS = 1_790_177_100_350L
        private const val LEGS_SENT_MS = 1_790_177_100_676L

        private val EURUSD_SPEC =
            InstrumentSpec(
                minVolume = BigDecimal("0.01"),
                volumeStep = BigDecimal("0.01"),
                pointSize = BigDecimal("0.00001"),
                digits = 5,
                tradeStopsLevelPoints = 0,
                maxVolume = BigDecimal("100"),
            )
    }
}
