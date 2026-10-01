package com.qkt.connector.gateway

import com.qkt.accounting.MoneyAmount
import com.qkt.common.Money
import com.qkt.events.BrokerEvent
import com.qkt.events.ContractSettled
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * The brokers attached to one gateway account and where each event goes. An order's events go to the
 * broker of the strategy that sent it, else to a broker serving every strategy (attached with a null
 * strategy); with neither attached they wait, and reach the strategy's broker when it attaches again.
 * A contract settlement goes to every broker with the venue's costs shared once, account-wide, by the
 * size of each broker's holding. Not thread-safe: its owner calls it under one lock.
 */
internal class GatewayRouting {
    /** A broker serving [strategy] (null: every strategy), whose session holds [positions], publishing on [publish]. */
    class Attached(
        val strategy: String?,
        val positions: PositionProvider,
        val publish: (BrokerEvent) -> Unit,
    ) {
        /** What the broker's session holds of [symbol], signed. */
        fun holding(symbol: String): BigDecimal = positions.positionFor(symbol)?.quantity ?: BigDecimal.ZERO
    }

    private val log = LoggerFactory.getLogger(GatewayRouting::class.java)
    private val attached = ArrayList<Attached>()
    private val waiting = LinkedHashMap<String, MutableList<BrokerEvent.OrderEvent>>()

    /** The brokers attached now. */
    val brokers: List<Attached> get() = attached.toList()

    /** Attaches [broker], handing it the events that waited for its strategy; true when it is the first. */
    fun attach(broker: Attached): Boolean {
        attached += broker
        val owed = if (broker.strategy == null) waiting.keys.toList() else listOf(broker.strategy)
        owed.mapNotNull(waiting::remove).flatten().forEach(broker.publish)
        return attached.size == 1
    }

    /** Detaches [broker]; true when none is left. */
    fun detach(broker: Attached): Boolean {
        attached -= broker
        return attached.isEmpty()
    }

    /** Hands an order's [event] to the broker of its strategy, else to one serving every strategy, else keeps it. */
    fun route(event: BrokerEvent.OrderEvent) {
        val targets =
            attached.filter { it.strategy == event.strategyId }.ifEmpty {
                attached.filter {
                    it.strategy ==
                        null
                }
            }
        if (targets.isEmpty()) {
            log.info("gateway event for '{}' waits for its strategy to attach: {}", event.strategyId, event)
            waiting.getOrPut(event.strategyId) { ArrayList() } += event
        }
        targets.forEach { it.publish(event) }
    }

    /** Hands an account-wide [event] to every broker. */
    fun broadcast(event: BrokerEvent) = attached.forEach { it.publish(event) }

    /** Hands [settled] to every broker, each with its share of the venue's costs by its holding. */
    fun settle(settled: ContractSettled) {
        val holdings = attached.map { it.holding(settled.symbol).abs() }
        val total = holdings.fold(BigDecimal.ZERO, BigDecimal::add)
        if (total.signum() == 0 && settled.costs.isNotEmpty()) {
            log.warn("gateway settlement costs of {} reach no holder: {}", settled.symbol, settled.costs)
        }
        val holders = holdings.indices.filter { holdings[it].signum() > 0 }
        for ((index, broker) in attached.withIndex()) {
            val costs =
                if (index !in holders) {
                    emptyList()
                } else {
                    settled.costs.map { it.copy(amount = shareOf(it.amount, holdings, holders, index, total)) }
                }
            broker.publish(settled.copy(costs = costs))
        }
    }

    /** Holder [index]'s share of [amount]; the last holder takes what the others left, so shares sum exactly. */
    private fun shareOf(
        amount: MoneyAmount,
        holdings: List<BigDecimal>,
        holders: List<Int>,
        index: Int,
        total: BigDecimal,
    ): MoneyAmount {
        val part = { i: Int -> amount.amount.multiply(holdings[i]).divide(total, Money.CONTEXT) }
        val value =
            if (index != holders.last()) {
                part(index)
            } else {
                amount.amount.subtract(holders.dropLast(1).fold(BigDecimal.ZERO) { sum, i -> sum.add(part(i)) })
            }
        return MoneyAmount(value, amount.currency)
    }
}
