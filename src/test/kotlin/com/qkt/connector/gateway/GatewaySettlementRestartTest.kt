package com.qkt.connector.gateway

import com.qkt.broker.BrokerDeal
import com.qkt.broker.CloseReason
import com.qkt.broker.continuous.ScriptedLaneFixture
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.ContractSettled
import com.qkt.events.FillAccountedEvent
import com.qkt.execution.ExitReason
import com.qkt.persistence.FileStatePersistor
import com.qkt.persistence.StatePersistor
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A settlement close is a deal no venue fill carries: only what qkt booked can state it. A restart after
 * the booking and before the next deal read, past the 60-second grace, must still report it, once
 * (#1570). Each [Process] is the qkt process: a gateway session and a stream's lane, on the state on disk.
 */
internal class GatewaySettlementRestartTest {
    private val code = "BTCUSDT_240927"
    private val fake = FakeGateway(listOf(code, "BTCUSDT_241227"))
    private val running = mutableListOf<GatewaySession>()

    @TempDir
    lateinit var dir: Path

    private val state: StatePersistor by lazy { FileStatePersistor(dir) }

    @AfterEach
    fun stop() {
        running.forEach { it.close() }
        fake.shutdown()
    }

    private object Flat : PositionProvider {
        override fun positionFor(symbol: String): Position? = null

        override fun allPositions() = emptyMap<String, Position>()
    }

    private inner class Process(
        startMs: Long? = null,
    ) {
        private lateinit var session: GatewaySession

        val lane =
            ScriptedLaneFixture(
                state,
                startMs ?: START,
                laneVenue = { bus, clock, positions ->
                    session =
                        GatewaySession(
                            GatewayClient(fake.url, "k", httpTimeoutMs = 500, retryAttempts = 2),
                            GatewaySymbols("BINANCE_UM:"),
                            clock,
                            GatewayIdentity("fake", "7", "demo"),
                            { setOf("s") },
                            streamFactory = { e, r, c -> GatewayStream(fake.url, "k", e, r, c, initialBackoffMs = 20) },
                            label = "BINANCE_UM",
                        ).also { running += it }
                    GatewayBroker(session, bus, clock, positions, "s")
                },
            )

        private val account = GatewayBroker(session, lane.bus, lane.clock, Flat, "s")

        init {
            // What the session's accounting does with a fill, on the session's bus.
            lane.bus.subscribe<BrokerEvent.OrderFilled> { e ->
                if (e.exitReason == ExitReason.EXPIRY) lane.bus.publish(accounted(e))
            }
        }

        fun deals(): List<BrokerDeal> = account.deals(0, Long.MAX_VALUE)
    }

    private fun Process.holdsAndSettles() {
        lane.last("entry", lane.sep, Side.BUY, "0.006", "63000")
        fake.venue.settlements += WireSettlement(code, "63500", lane.clock.time)
        lane.venueBus.publish(ContractSettled(lane.sep, BigDecimal("63500"), timestamp = lane.clock.time))
    }

    private fun accounted(e: BrokerEvent.OrderFilled) =
        FillAccountedEvent(
            orderId = e.clientOrderId,
            strategyId = e.strategyId,
            symbol = e.symbol,
            fillSliceId = "${e.clientOrderId}:1",
            sourceFillSequenceId = 1,
            cumulativeFilled = null,
            modeledCommissionAccount = BigDecimal.ZERO,
            venueCostsAccount = BigDecimal("0.3"),
            totalCostsAccount = BigDecimal("0.3"),
            accountNativeRealized = REALIZED,
            strategyNativeRealized = REALIZED,
            nativeCurrency = "USDT",
            grossAccountRealized = REALIZED,
            grossStrategyAccountRealized = REALIZED,
            accountCurrency = "USDT",
            netAccountRealized = REALIZED,
            netStrategyAccountRealized = REALIZED,
            conversionRate = null,
            conversionTimestampMs = null,
            conversionSource = null,
            contractSize = BigDecimal.ONE,
            accountPositionBefore = Position(e.symbol, e.quantity, BigDecimal("63000"), openedAt = 1_000),
            accountPositionAfter = null,
            strategyPositionBefore = Position(e.symbol, e.quantity, BigDecimal("63000"), openedAt = 1_000),
            strategyPositionAfter = null,
            reducedExposure = true,
            partial = false,
            executedAt = e.timestamp,
        )

    private fun settlementDeals(deals: List<BrokerDeal>) = deals.filter { it.closeReason == CloseReason.EXPIRY }

    private fun assertTheClose(deals: List<BrokerDeal>) {
        val close = settlementDeals(deals).single()
        assertThat(close.dealTicket).isEqualTo("settle:$code:s")
        assertThat(close.entry).isEqualTo("OUT")
        assertThat(close.side).isEqualTo(Side.SELL)
        assertThat(close.qty).isEqualByComparingTo("0.006")
        assertThat(close.price).isEqualByComparingTo("63500")
        assertThat(close.profit).isEqualByComparingTo(REALIZED)
        assertThat(close.fee).isEqualByComparingTo("-0.3")
        assertThat(close.strategyId).isEqualTo("s")
        assertThat(close.ts).isEqualTo(START)
    }

    @Test
    fun `before any restart the settlement close is a deal`() {
        val before = Process()
        before.holdsAndSettles()

        assertTheClose(before.deals())
    }

    @Test
    fun `a restart between the booking and the next read past the grace still reports the close`() {
        Process().holdsAndSettles()

        val after = Process(START + PAST_GRACE)

        assertTheClose(after.deals())
    }

    @Test
    fun `further restarts and repeated reads report it once and book nothing again`() {
        Process().holdsAndSettles()

        val second = Process(START + PAST_GRACE)
        assertTheClose(second.deals())
        assertTheClose(second.deals())
        second.lane.venueBus.publish(
            ContractSettled(second.lane.sep, BigDecimal("63500"), timestamp = START),
        )
        val third = Process(START + 2 * PAST_GRACE)

        assertTheClose(third.deals())
        assertThat(second.lane.engine.filterIsInstance<BrokerEvent.OrderFilled>()).isEmpty()
    }

    @Test
    fun `each strategy that held the contract keeps its own close across the restart`() {
        val before = Process()
        before.lane.venueBus.publish(
            BrokerEvent.OrderFilled(
                "other",
                "v",
                before.lane.sep,
                Side.SELL,
                BigDecimal("63000"),
                BigDecimal("0.002"),
                "t",
                START,
            ),
        )
        before.holdsAndSettles()

        val closes = settlementDeals(Process(START + PAST_GRACE).deals())

        assertThat(closes.map { it.dealTicket }).containsExactlyInAnyOrder("settle:$code:s", "settle:$code:t")
    }

    @Test
    fun `a close past the deal poll's reach is forgotten`() {
        Process().holdsAndSettles()

        val later = Process(START + 31 * 86_400_000L)

        assertThat(later.deals()).isEmpty()
        assertThat(later.lane.saved().settledCloses).isEmpty()
    }

    private companion object {
        const val START = 1_726_731_900_000L // 2024-09-19T07:45:00Z
        const val PAST_GRACE = 120_000L
        val REALIZED = BigDecimal("3")
    }
}
