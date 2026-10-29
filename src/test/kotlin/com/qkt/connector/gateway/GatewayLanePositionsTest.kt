package com.qkt.connector.gateway

import com.qkt.bus.EventBus
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SystemClock
import com.qkt.events.FillAccountedEvent
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * A strategy trading a continuous stream (`DERIBIT:BTC_USDC@front`) holds the dated contract the stream
 * follows through the stream's lane, a second broker on the same account: the session's book names only the
 * stream. The account's venue position on that contract is still the strategy's, and is the stream's position
 * under the id its deals carry, across rolls, wherever positions are reported from (live, 2026-10-28: the
 * soak's `soak_o14_roll` position showed `attribution: none`, id `#null`).
 */
internal class GatewayLanePositionsTest {
    private val front = "BTC_USDC-30OCT26"
    private val next = "BTC_USDC-27NOV26"
    private val fake = FakeGateway(listOf(front, next))
    private val stream = "DERIBIT:BTC_USDC@front"
    private val session =
        GatewaySession(
            GatewayClient(fake.url, "k", httpTimeoutMs = 500, retryAttempts = 2),
            GatewaySymbols("DERIBIT:"),
            SystemClock(),
            GatewayIdentity("fake", "7", "demo"),
            { setOf("roll") },
            streamFactory = { e, r, c -> GatewayStream(fake.url, "k", e, r, c, initialBackoffMs = 20) },
            submitDeadlineMs = 300,
            retryMs = 50,
            resyncRetryMs = 50,
            label = "DERIBIT",
        )

    /** What one broker's session holds, by qkt symbol. */
    private class Book : PositionProvider {
        val held = ConcurrentHashMap<String, Position>()

        override fun positionFor(symbol: String) = held[symbol]

        override fun allPositions(): Map<String, Position> = held
    }

    private val strategyBook =
        Book().apply {
            held[stream] =
                Position(stream, BigDecimal("-0.1"), BigDecimal("70000"), 5L)
        }
    private val laneHeld = Book()
    private val laneBook =
        com.qkt.broker.continuous
            .StreamContractPositions(stream, laneHeld)
    private val bus = EventBus(SystemClock(), MonotonicSequenceGenerator())
    private val account = GatewayBroker(session, bus, SystemClock(), strategyBook, "roll")
    private val lane =
        GatewayBroker(session, EventBus(SystemClock(), MonotonicSequenceGenerator()), SystemClock(), laneBook, "roll")

    /** qkt's accounting booked the stream's entry: the account's `@front` position opened at [openedAt]. */
    private fun bookedStream(openedAt: Long) =
        bus.publish(
            FillAccountedEvent(
                orderId = "ORD-roll-0",
                strategyId = "roll",
                symbol = stream,
                fillSliceId = "ORD-roll-0:1",
                sourceFillSequenceId = 1,
                cumulativeFilled = null,
                modeledCommissionAccount = BigDecimal.ZERO,
                venueCostsAccount = BigDecimal.ZERO,
                totalCostsAccount = BigDecimal.ZERO,
                accountNativeRealized = BigDecimal.ZERO,
                strategyNativeRealized = BigDecimal.ZERO,
                nativeCurrency = "USDC",
                grossAccountRealized = BigDecimal.ZERO,
                grossStrategyAccountRealized = BigDecimal.ZERO,
                accountCurrency = "USDC",
                netAccountRealized = BigDecimal.ZERO,
                netStrategyAccountRealized = BigDecimal.ZERO,
                conversionRate = null,
                conversionTimestampMs = null,
                conversionSource = null,
                contractSize = BigDecimal.ONE,
                accountPositionBefore = null,
                accountPositionAfter = Position(stream, BigDecimal("-0.1"), BigDecimal("70000"), openedAt),
                strategyPositionBefore = null,
                strategyPositionAfter = Position(stream, BigDecimal("-0.1"), BigDecimal("70000"), openedAt),
                reducedExposure = false,
                partial = false,
                executedAt = openedAt,
            ),
        )

    @AfterEach
    fun stop() {
        session.close()
        fake.shutdown()
    }

    private fun sell(
        code: String,
        id: String,
    ) = fake.act {
        place(WireSubmit(id, code, "sell", "market", "0.1", null, null, "gtc", false))
        fill(id, "f-$id", "0.1", "70000", FakeGateway.TIME)
    }

    @Test
    fun `the contract a stream's lane holds is the stream's position, under its strategy and its deals' id`() {
        sell(front, "roll:1")
        bookedStream(5L)
        laneHeld.held["DERIBIT:BTC_USDC_30OCT26"] =
            Position("DERIBIT:BTC_USDC_30OCT26", BigDecimal("-0.1"), BigDecimal("70000"), 1_000L)

        val position = account.positionTickets().single()

        assertThat(position.symbol).isEqualTo("DERIBIT:BTC_USDC_30OCT26")
        assertThat(position.strategyId).isEqualTo("roll")
        assertThat(position.ticket).isEqualTo("$stream#5")
        assertThat(
            lane.positionTickets().single().strategyId,
        ).`as`("every broker states the same owner").isEqualTo("roll")
    }

    @Test
    fun `after a roll the next contract is the same stream position, the strategy's`() {
        sell(front, "roll:1")
        bookedStream(5L)
        fake.act {
            place(WireSubmit("roll:2", front, "buy", "market", "0.1", null, null, "gtc", true))
            fill("roll:2", "f-roll:2", "0.1", "70000", FakeGateway.TIME)
        }
        sell(next, "roll:3")
        laneHeld.held["DERIBIT:BTC_USDC_27NOV26"] =
            Position("DERIBIT:BTC_USDC_27NOV26", BigDecimal("-0.1"), BigDecimal("70500"), 2_000L)

        val position = account.positionTickets().single()

        assertThat(position.symbol).isEqualTo("DERIBIT:BTC_USDC_27NOV26")
        assertThat(position.strategyId).isEqualTo("roll")
        assertThat(position.ticket).isEqualTo("$stream#5")
    }

    @Test
    fun `a lane holding a contract its stream never booked here still names the lane's strategy and position`() {
        sell(front, "roll:1")
        laneHeld.held["DERIBIT:BTC_USDC_30OCT26"] =
            Position("DERIBIT:BTC_USDC_30OCT26", BigDecimal("-0.1"), BigDecimal("70000"), 1_000L)

        val position = account.positionTickets().single()

        assertThat(position.strategyId).isEqualTo("roll")
        assertThat(position.ticket).isEqualTo("DERIBIT:BTC_USDC_30OCT26#1000")
    }

    @Test
    fun `a contract no broker of the account holds stays unattributed`() {
        sell(front, "x-1")

        val position = account.positionTickets().single()

        assertThat(position.strategyId).isNull()
    }
}
