package com.qkt.connector.bybit.linear

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.connector.bybit.BybitExecutionKind
import com.qkt.connector.bybit.BybitOrderTranslator
import com.qkt.connector.bybit.BybitTransport
import com.qkt.connector.bybit.requireBybitOk
import com.qkt.connector.bybit.spot.BybitSpotStateRecovery
import com.qkt.events.BrokerEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Replays the linear account's executions since shortly before the last fill from `/v5/execution/list`:
 * each unseen `Trade` of an order [strategyOf] knows becomes an [BrokerEvent.OrderFilled] with its fee, then
 * goes to [afterFill]; one of an order qkt did not place is logged and never booked. Each `Funding` goes to
 * [funding], and any other [BybitExecutionKind] (a liquidation, an auto-deleverage, a delivery) is left to
 * the position reconcile, since no order of ours filled.
 */
internal class BybitLinearExecutionReconcile(
    private val transport: BybitTransport,
    private val bus: EventBus,
    private val clock: Clock,
    private val strategyOf: (String) -> String?,
    private val lastFillTimeProvider: () -> Long,
    private val seenExecIds: MutableSet<String>,
    private val funding: BybitLinearFunding,
    private val afterFill: (BybitOrderTranslator.ParsedExecution) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(BybitLinearExecutionReconcile::class.java)
    private val json = Json { ignoreUnknownKeys = true }

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
                    put("category", "linear")
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
        val params = mapOf("category" to "linear", "orderLinkId" to clientOrderId, "limit" to "100")
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
                funding.take(execution)
                return true
            }
            BybitExecutionKind.OTHER -> {
                if (!seenExecIds.add(execId)) return false
                log.info("Bybit linear execution is not a fill; the position reconcile applies it: {}", execution)
                return true
            }
            BybitExecutionKind.FILL -> Unit
        }
        val exec = BybitOrderTranslator.parseExecution(execution)
        executedOrderIds.add(exec.clientOrderId)
        if (!seenExecIds.add(exec.execId)) return false
        val strategyId = strategyOf(exec.clientOrderId)
        if (strategyId == null) {
            log.warn("Bybit linear execution of an order qkt did not place; not booked: {}", execution)
            return true
        }
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = exec.clientOrderId,
                brokerOrderId = exec.brokerOrderId,
                symbol = "BYBIT_LINEAR:${exec.bareSymbol}",
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
