package com.qkt.connector.bybit.spot

import com.qkt.broker.BrokerStateRecovery
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.connector.bybit.BybitBalanceTranslator
import com.qkt.connector.bybit.BybitExecutionReplay
import com.qkt.connector.bybit.BybitHeldEnds
import com.qkt.connector.bybit.BybitTransport
import com.qkt.connector.bybit.requireBybitOk
import com.qkt.events.BrokerEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * State reconciliation for [BybitSpotBroker], on startup and periodically: replays executions, open orders
 * and balances. With [ends], fills are attributed through its orders (ended ones included) and the order
 * ends it holds for their executions are resolved on each reconcile.
 */
class BybitSpotStateRecovery(
    private val transport: BybitTransport,
    private val bus: EventBus,
    private val clock: Clock,
    private val getKnownOrders: () -> Map<String, ManagedOrderView>,
    private val lastFillTimeProvider: () -> Long,
    private val seenExecIds: MutableSet<String>,
    private val ends: BybitHeldEnds? = null,
) : BrokerStateRecovery {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val executions =
        BybitExecutionReplay(
            "spot",
            transport,
            bus,
            clock,
            ends?.orders?.let { it::strategyOf } ?: { getKnownOrders()[it]?.strategyId },
            lastFillTimeProvider,
            seenExecIds,
        ) { exec -> ends?.booked(exec) }

    data class ManagedOrderView(
        val clientOrderId: String,
        val symbol: String,
        val side: Side,
        val strategyId: String = "",
    )

    override fun reconcile() {
        synchronized(lock) {
            val executedOrderIds = executions.reconcile()
            ends?.resolve(executions)
            reconcileOpenOrders(executedOrderIds)
            reconcileBalances()
        }
    }

    private fun reconcileBalances() {
        val response =
            transport.getSigned(
                "/v5/account/wallet-balance",
                mapOf("accountType" to transport.accountType),
            )
        val parsed = BybitBalanceTranslator.parseWalletBalance(response)
        transport.updateBalances(parsed)
        bus.publish(
            BrokerEvent.BalancesUpdated(
                balances = parsed,
                source = "BYBIT_SPOT",
                timestamp = clock.now(),
            ),
        )
    }

    private fun reconcileOpenOrders(executedOrderIds: Set<String>) {
        val response =
            transport.getSigned(
                "/v5/order/realtime",
                mapOf("category" to "spot", "openOnly" to "0", "limit" to "50"),
            )
        val tree = requireBybitOk(response, "open-order reconcile", json)
        val list =
            tree["result"]?.jsonObject?.get("list")?.jsonArray
                ?: throw IllegalStateException("open-order reconcile response omitted result.list")
        val openOrderIds = list.mapNotNull { it.jsonObject["orderLinkId"]?.jsonPrimitive?.content }.toSet()
        val known = getKnownOrders()
        for ((id, view) in known) {
            if (id !in openOrderIds && id !in executedOrderIds) {
                bus.publish(
                    BrokerEvent.OrderCancelled(
                        clientOrderId = id,
                        brokerOrderId = null,
                        reason = "recovered: not in open list",
                        strategyId = view.strategyId,
                        timestamp = clock.now(),
                    ),
                )
            }
        }
    }

    companion object {
        const val MAX_EXECUTIONS_PER_RECONCILE: Int = 200
    }
}
