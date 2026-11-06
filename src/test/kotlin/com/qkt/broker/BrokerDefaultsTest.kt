package com.qkt.broker

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.positions.LegBook
import com.qkt.positions.LegRole
import com.qkt.positions.Position
import com.qkt.positions.PositionLeg
import com.qkt.positions.PositionProvider
import com.qkt.positions.StrategyLegReads
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BrokerDefaultsTest {
    private val minimal =
        object : Broker {
            override val name = "Minimal"
            override val capabilities: Set<OrderTypeCapability> = emptySet()

            override fun submit(request: OrderRequest): SubmitAck = SubmitAck(request.id, request.id, accepted = true)

            override fun cancel(orderId: String) = Unit
        }

    private fun market(id: String) =
        OrderRequest.Market(id, "A:X", Side.BUY, Money.of("1"), timeInForce = TimeInForce.GTC, timestamp = 0L)

    @Test
    fun `a broker without modify support refuses order modification`() {
        assertThatThrownBy { minimal.modify("o1", OrderModification(newLimitPrice = Money.of("1"))) }
            .isInstanceOf(UnsupportedOperationException::class.java)
            .hasMessage("Minimal does not support modify")
    }

    @Test
    fun `position modification defaults to an accepted no-op reported synchronously and asynchronously`() {
        assertThat(minimal.modifyPosition("t1")).isEqualTo(SubmitAck("t1", "t1", accepted = true))
        val results = mutableListOf<SubmitAck>()

        minimal.modifyPositionAsync("t2", sl = Money.of("1"), tp = null) { results += it }

        assertThat(results).containsExactly(SubmitAck("t2", "t2", accepted = true))
        minimal.modifyPositionAsync("t3") { results += it }
        assertThat(results.last()).isEqualTo(SubmitAck("t3", "t3", accepted = true))
    }

    @Test
    fun `a broker without venue truth never reads the booked legs and keeps no settled closes`() {
        var reads = 0
        minimal.watchBookedLegs {
            reads++
            emptyList()
        }
        minimal.restoreSettledCloses(emptyList())

        assertThat(reads).isZero()
        assertThat(minimal.deals(0L, Long.MAX_VALUE)).isEmpty()
    }

    @Test
    fun `recovery without venue truth vouches for every order`() {
        val orders =
            listOf("a", "b").map {
                ManagedOrder(it, market(it), OrderState.WORKING, createdAt = 0L, lastUpdatedAt = 0L)
            }

        assertThat(minimal.recoverPendingOrders(orders)).containsExactly("a", "b")
    }

    @Test
    fun `venue reads and descriptions default to nothing known`() {
        val described =
            object : VenueDescribed {
                override fun venueIdentity(): VenueIdentity? = null
            }
        val margin =
            object : MarginLevelProvider {
                override val supportsMarginLevel = false
            }

        assertThat(minimal.marginLevel()).isNull()
        assertThat(minimal.pendingOrders()).isEmpty()
        assertThat(minimal.deals(0L, 1L)).isEmpty()
        assertThat(described.fillPositionId("b1")).isNull()
        assertThat(described.instrument("A:X")).isNull()
        assertThat(margin.marginFloorGap).isNull()
    }

    @Test
    fun `booked legs list only the legs that carry a venue ticket, per strategy`() {
        val ticketed =
            PositionLeg("l1", "A:X", Side.SELL, Money.of("2"), Money.of("10"), 5L, LegRole.PRIMARY, brokerTicket = "T1")
        val unticketed = PositionLeg("l2", "A:X", Side.BUY, Money.of("1"), Money.of("11"), 6L, LegRole.PRIMARY)
        val reads = legReads(mapOf("s1" to listOf(ticketed, unticketed), "s2" to emptyList()))

        assertThat(reads.bookedLegs(listOf("s1", "s2")))
            .containsExactly(
                BookedLeg(
                    strategyId = "s1",
                    legId = "l1",
                    ticket = "T1",
                    symbol = "A:X",
                    side = Side.SELL,
                    quantity = Money.of("2"),
                    entryPrice = Money.of("10"),
                    openedAt = 5L,
                ),
            )
    }

    private fun legReads(legs: Map<String, List<PositionLeg>>): StrategyLegReads =
        object : StrategyLegReads {
            override fun positionFor(
                strategyId: String,
                symbol: String,
            ): Position? = null

            override fun positionsFor(strategyId: String): Map<String, Position> = emptyMap()

            override fun allByStrategy(): Map<String, Map<String, Position>> = emptyMap()

            override fun allLegsFor(strategyId: String): List<PositionLeg> = legs[strategyId].orEmpty()

            override fun legBookFor(
                strategyId: String,
                symbol: String,
            ): LegBook? = null

            override fun legById(
                strategyId: String,
                legId: String,
            ): PositionLeg? = null

            override fun ticketForLeg(
                strategyId: String,
                legId: String,
            ): String? = null

            override fun ticketForPrimary(
                strategyId: String,
                symbol: String,
            ): String? = null

            override fun openCountFor(
                strategyId: String,
                symbol: String,
            ): Int = 0

            override fun longCountFor(
                strategyId: String,
                symbol: String,
            ): Int = 0

            override fun shortCountFor(
                strategyId: String,
                symbol: String,
            ): Int = 0

            override fun grossFor(
                strategyId: String,
                symbol: String,
            ): BigDecimal = BigDecimal.ZERO

            override fun driftFor(
                symbol: String,
                brokerView: PositionProvider,
            ): BigDecimal = BigDecimal.ZERO
        }
}
