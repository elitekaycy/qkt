package com.qkt.connector.bybit

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.connector.bybit.spot.BybitSpotStateRecovery
import com.qkt.events.BrokerEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Replays one [category]'s executions since shortly before the last fill from `/v5/execution/list`: each
 * unseen `Trade` of an order [strategyOf] knows becomes an [BrokerEvent.OrderFilled] with its fee, then goes
 * to [afterFill]; one of an order qkt did not place is logged once and never booked. Each `Funding` goes to
 * [onFunding], and any other [BybitExecutionKind] (a liquidation, an auto-deleverage, a delivery) is left to
 * the position reconcile, since no order of ours filled.
 */
class BybitExecutionReplay(
    private val category: String,
    private val transport: BybitTransport,
    private val bus: EventBus,
    private val clock: Clock,
    private val strategyOf: (String) -> String?,
    private val lastFillTimeProvider: () -> Long,
    private val seenExecIds: MutableSet<String>,
    private val onFunding: (JsonObject) -> Unit = {},
    private val afterFill: (BybitOrderTranslator.ParsedExecution) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(BybitExecutionReplay::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val unowned = boundedExecIdSet(1_000)

    /** Replays what was missed; returns the client order ids that filled in the window. */
    fun reconcile(): Set<String> {
        val startTime = (lastFillTimeProvider() - 60_000L).coerceAtLeast(0L)
        var cursor = ""
        var totalProcessed = 0
        val cap = BybitSpotStateRecovery.MAX_EXECUTIONS_PER_RECONCILE
        val executedOrderIds = mutableSetOf<String>()
        while (totalProcessed < cap) {
            val params =
                buildMap {
                    put("category", category)
                    put("startTime", startTime.toString())
                    put("limit", "50")
                    if (cursor.isNotEmpty()) put("cursor", cursor)
                }
            val tree = requireBybitOk(transport.getSigned("/v5/execution/list", params), "execution reconcile", json)
            val list =
                tree["result"]?.jsonObject?.get("list")?.jsonArray
                    ?: throw IllegalStateException("execution reconcile response omitted result.list")
            var newThisPage = 0
            for (entry in list) {
                val execution = entry.jsonObject
                if (!route(execution, executedOrderIds)) continue
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
            // already-seen execs would otherwise spin.
            if (cursor.isEmpty() || list.isEmpty() || newThisPage == 0) break
        }
        return executedOrderIds
    }

    /** Replays the executions of order [clientOrderId] alone (Bybit's default window: the last 7 days). */
    fun replayOrder(clientOrderId: String) {
        val params = mapOf("category" to category, "orderLinkId" to clientOrderId, "limit" to "100")
        val tree = requireBybitOk(transport.getSigned("/v5/execution/list", params), "order execution replay", json)
        val list =
            tree["result"]?.jsonObject?.get("list")?.jsonArray
                ?: throw IllegalStateException("order execution replay response omitted result.list")
        list.forEach { route(it.jsonObject, mutableSetOf()) }
    }

    /** Handles one [execution]; true when it had not been seen. */
    private fun route(
        execution: JsonObject,
        executedOrderIds: MutableSet<String>,
    ): Boolean {
        val execId = execution["execId"]?.jsonPrimitive?.content ?: error("missing execId: $execution")
        when (BybitExecutionKind.of(execution)) {
            BybitExecutionKind.FUNDING -> {
                if (execId in seenExecIds) return false
                onFunding(execution)
                return true
            }
            BybitExecutionKind.OTHER -> {
                if (!seenExecIds.add(execId)) return false
                log.info("Bybit {} execution is not a fill; the position reconcile applies it: {}", category, execution)
                return true
            }
            BybitExecutionKind.FILL -> Unit
        }
        val exec = BybitOrderTranslator.parseExecution(execution)
        executedOrderIds.add(exec.clientOrderId)
        val strategyId = strategyOf(exec.clientOrderId)
        if (strategyId == null) {
            // Not marked seen: a restart that restores its order still books it.
            if (unowned.add(execId)) {
                log.warn("Bybit {} execution of an order qkt did not place; not booked: {}", category, execution)
            }
            return false
        }
        if (!seenExecIds.add(exec.execId)) return false
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = exec.clientOrderId,
                brokerOrderId = exec.brokerOrderId,
                symbol = BybitSymbol.toQkt(category, exec.bareSymbol),
                side = exec.side,
                price = exec.price,
                quantity = exec.quantity,
                strategyId = strategyId,
                timestamp = clock.now(),
                venueCosts = exec.fee,
            ),
        )
        afterFill(exec)
        return true
    }
}
