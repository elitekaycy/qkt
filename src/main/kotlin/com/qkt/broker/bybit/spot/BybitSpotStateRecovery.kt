package com.qkt.broker.bybit.spot

import com.qkt.broker.BrokerStateRecovery
import com.qkt.broker.bybit.BybitBalanceTranslator
import com.qkt.broker.bybit.BybitOrderTranslator
import com.qkt.broker.bybit.BybitSymbol
import com.qkt.broker.bybit.BybitTransport
import com.qkt.broker.bybit.requireBybitOk
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** On-startup state reconciliation for [BybitSpotBroker] — replays open orders + balances. */
class BybitSpotStateRecovery(
    private val transport: BybitTransport,
    private val bus: EventBus,
    private val clock: Clock,
    private val getKnownOrders: () -> Map<String, ManagedOrderView>,
    private val lastFillTimeProvider: () -> Long,
    private val seenExecIds: MutableSet<String>,
) : BrokerStateRecovery {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    data class ManagedOrderView(
        val clientOrderId: String,
        val symbol: String,
        val side: Side,
        val strategyId: String = "",
    )

    override fun reconcile() {
        synchronized(lock) {
            val executedOrderIds = reconcileExecutions()
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

    private fun reconcileExecutions(): Set<String> {
        val startTime = (lastFillTimeProvider() - 60_000L).coerceAtLeast(0L)
        var cursor = ""
        var totalProcessed = 0
        val cap = MAX_EXECUTIONS_PER_RECONCILE
        val executedOrderIds = mutableSetOf<String>()
        while (totalProcessed < cap) {
            val params =
                buildMap {
                    put("category", "spot")
                    put("startTime", startTime.toString())
                    put("limit", "50")
                    if (cursor.isNotEmpty()) put("cursor", cursor)
                }
            val response = transport.getSigned("/v5/execution/list", params)
            val tree = requireBybitOk(response, "execution reconcile", json)
            val list =
                tree["result"]?.jsonObject?.get("list")?.jsonArray
                    ?: throw IllegalStateException("execution reconcile response omitted result.list")
            var newThisPage = 0
            for (entry in list) {
                val exec = BybitOrderTranslator.parseExecution(entry.jsonObject)
                executedOrderIds.add(exec.clientOrderId)
                if (!seenExecIds.add(exec.execId)) continue
                val qktSymbol = BybitSymbol.toQkt(category = "spot", bare = exec.bareSymbol)
                val strategyId = getKnownOrders()[exec.clientOrderId]?.strategyId ?: ""
                bus.publish(
                    BrokerEvent.OrderFilled(
                        clientOrderId = exec.clientOrderId,
                        brokerOrderId = exec.brokerOrderId,
                        symbol = qktSymbol,
                        side = exec.side,
                        price = exec.price,
                        quantity = exec.quantity,
                        strategyId = strategyId,
                        timestamp = clock.now(),
                    ),
                )
                newThisPage++
                totalProcessed++
                if (totalProcessed >= cap) return executedOrderIds
            }
            cursor = tree["result"]
                ?.jsonObject
                ?.get("nextPageCursor")
                ?.jsonPrimitive
                ?.content ?: ""
            // Stop if a non-empty page yielded no new executions: a perpetual cursor over
            // already-seen execs (e.g. a misconfigured page response) would otherwise spin.
            if (cursor.isEmpty() || list.isEmpty() || newThisPage == 0) break
        }
        return executedOrderIds
    }

    companion object {
        const val MAX_EXECUTIONS_PER_RECONCILE: Int = 200
    }
}
