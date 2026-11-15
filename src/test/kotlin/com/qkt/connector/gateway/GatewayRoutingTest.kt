package com.qkt.connector.gateway

import com.qkt.accounting.CostKind
import com.qkt.accounting.MoneyAmount
import com.qkt.accounting.VenueCost
import com.qkt.events.BrokerEvent
import com.qkt.events.ContractSettled
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Which attached broker hears an order event, and how a settlement's costs are shared by holding. */
class GatewayRoutingTest {
    private val routing = GatewayRouting()
    private val heard = mutableMapOf<String, MutableList<BrokerEvent>>()

    private fun holding(lots: String?) =
        object : PositionProvider {
            override fun positionFor(symbol: String): Position? =
                lots?.let { Position(symbol, BigDecimal(it), BigDecimal.ONE) }

            override fun allPositions(): Map<String, Position> = emptyMap()
        }

    private fun broker(
        label: String,
        strategy: String?,
        lots: String? = null,
    ) = GatewayRouting.Attached(strategy, holding(lots)) { heard.getOrPut(label) { mutableListOf() } += it }

    private fun accepted(strategy: String) = BrokerEvent.OrderAccepted("o-$strategy", "v1", strategy)

    @Test
    fun `an event goes to its sender while attached, else to its strategy's brokers`() {
        val sender = broker("sender", "a")
        val sibling = broker("sibling", "a")
        routing.attach(sender)
        routing.attach(sibling)

        routing.route(accepted("a"), sender)
        routing.detach(sender)
        routing.route(accepted("a"), sender)

        assertThat(heard.getValue("sender")).hasSize(1)
        assertThat(heard.getValue("sibling")).hasSize(1)
    }

    @Test
    fun `an event with no broker of its strategy goes to one serving every strategy`() {
        routing.attach(broker("mine", "a"))
        routing.attach(broker("all", null))

        routing.route(accepted("b"))

        assertThat(heard.keys).containsExactly("all")
    }

    @Test
    fun `an event nobody can take waits until its strategy attaches`() {
        assertThat(routing.attach(broker("other", "x"))).isTrue()

        routing.route(accepted("a"))
        routing.route(accepted("b"))
        assertThat(heard).isEmpty()

        assertThat(routing.attach(broker("a", "a"))).isFalse()
        routing.attach(broker("all", null))

        assertThat(heard.getValue("a").map { it.strategyId }).containsExactly("a")
        assertThat(heard.getValue("all").map { it.strategyId }).containsExactly("b")
    }

    @Test
    fun `settlement costs are shared by holding and the last holder takes the remainder`() {
        routing.attach(broker("big", "a", lots = "-2"))
        routing.attach(broker("flat", "b"))
        routing.attach(broker("small", "c", lots = "1"))
        val cost = VenueCost(CostKind.EXCHANGE_FEE, MoneyAmount(BigDecimal("10"), "USDC"), 9L)

        routing.settle(ContractSettled("S", BigDecimal("100"), listOf(cost)))

        fun shares(label: String) =
            heard.getValue(label).filterIsInstance<ContractSettled>().single().costs.map {
                it.amount.amount
            }
        assertThat(shares("flat")).isEmpty()
        val big = shares("big").single()
        val small = shares("small").single()
        assertThat(big.add(small)).isEqualByComparingTo("10")
        assertThat(big.toDouble()).isEqualTo(
            20.0 / 3,
            org.assertj.core.data.Offset
                .offset(1e-9),
        )
    }

    @Test
    fun `settlement costs nobody holds reach every broker as no cost`() {
        routing.attach(broker("flat", "a"))
        val cost = VenueCost(CostKind.EXCHANGE_FEE, MoneyAmount(BigDecimal("10"), "USDC"), 9L)

        routing.settle(ContractSettled("S", BigDecimal("100"), listOf(cost)))
        routing.settle(ContractSettled("S", BigDecimal("100")))

        val settled = heard.getValue("flat").filterIsInstance<ContractSettled>()
        assertThat(settled.map { it.costs }).containsExactly(emptyList(), emptyList())
        assertThat(settled.map { it.price }).containsOnly(BigDecimal("100"))
    }
}
