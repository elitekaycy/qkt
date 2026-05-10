package com.qkt.broker.bybit

import com.qkt.broker.BrokerStateRecovery
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/** On-startup state reconciliation for [BybitSpotBroker] — replays open orders + balances. */
class BybitSpotStateRecovery(
    private val transport: BybitTransport,
    private val bus: EventBus,
    private val clock: Clock,
    private val getKnownOrders: () -> Map<String, ManagedOrderView>,
    private val lastFillTimeProvider: () -> Long,
    private val seenExecIds: MutableSet<String>,
) : BrokerStateRecovery {
    private val log = LoggerFactory.getLogger(BybitSpotStateRecovery::class.java)
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
            runCatching { reconcileOpenOrders() }
                .onFailure { log.warn("Open-orders reconcile failed: {}", it.message) }
            runCatching { reconcileExecutions() }
                .onFailure { log.warn("Executions reconcile failed: {}", it.message) }
            runCatching { reconcileBalances() }
                .onFailure { log.warn("Balances reconcile failed: {}", it.message) }
        }
    }

    private fun reconcileBalances() {
        val response =
            transport.postSigned(
                "/v5/account/wallet-balance",
                """{"accountType":"${transport.accountType}"}""",
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

    private fun reconcileOpenOrders() {
        val response = transport.postSigned("/v5/order/realtime", """{"category":"spot","openOnly":0,"limit":50}""")
        val tree = json.parseToJsonElement(response).jsonObject
        if (tree["retCode"]?.jsonPrimitive?.content?.toIntOrNull() != 0) return
        val list = tree["result"]?.jsonObject?.get("list")?.jsonArray ?: return
        val openOrderIds = list.mapNotNull { it.jsonObject["orderLinkId"]?.jsonPrimitive?.content }.toSet()
        val known = getKnownOrders()
        for ((id, view) in known) {
            if (id !in openOrderIds) {
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

    private fun reconcileExecutions() {
        val startTime = (lastFillTimeProvider() - 60_000L).coerceAtLeast(0L)
        var cursor = ""
        var totalProcessed = 0
        val cap = MAX_EXECUTIONS_PER_RECONCILE
        while (totalProcessed < cap) {
            val body =
                if (cursor.isEmpty()) {
                    """{"category":"spot","startTime":$startTime,"limit":50}"""
                } else {
                    """{"category":"spot","startTime":$startTime,"limit":50,"cursor":"$cursor"}"""
                }
            val response = transport.postSigned("/v5/execution/list", body)
            val tree = json.parseToJsonElement(response).jsonObject
            if (tree["retCode"]?.jsonPrimitive?.content?.toIntOrNull() != 0) return
            val list = tree["result"]?.jsonObject?.get("list")?.jsonArray ?: return
            for (entry in list) {
                val exec = BybitOrderTranslator.parseExecution(entry.jsonObject)
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
                totalProcessed++
                if (totalProcessed >= cap) return
            }
            cursor = tree["result"]
                ?.jsonObject
                ?.get("nextPageCursor")
                ?.jsonPrimitive
                ?.content ?: ""
            if (cursor.isEmpty() || list.isEmpty()) break
        }
    }

    companion object {
        const val MAX_EXECUTIONS_PER_RECONCILE: Int = 200
    }
}
