package com.qkt.connector.bybit

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * One Bybit broker's view of the private `execution` topic: each `Trade` execution of its [category]
 * becomes an [BrokerEvent.OrderFilled] once (by `execId`, in [seenExecIds]), attributed through
 * [strategyOf]. The all-in-one topic carries every category of the login, so an entry naming another
 * category is the sibling broker's and is skipped. A `Funding` execution goes to [onFunding]; any other
 * [BybitExecutionKind] is not a fill (see there) and is only logged.
 */
class BybitExecutionStream(
    private val category: String,
    private val bus: EventBus,
    private val clock: Clock,
    private val seenExecIds: MutableSet<String>,
    private val lastFillTime: AtomicLong,
    private val strategyOf: (String) -> String?,
    private val onFunding: (JsonObject) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(BybitExecutionStream::class.java)

    /** Handles one `execution` [frame]. */
    fun onFrame(frame: JsonObject) {
        val list = frame["data"]?.jsonArray ?: return
        for (entry in list) {
            val execution = entry.jsonObject
            val entryCategory = execution["category"]?.jsonPrimitive?.content
            if (entryCategory != null && entryCategory != category) continue
            when (BybitExecutionKind.of(execution)) {
                BybitExecutionKind.FILL -> fill(execution)
                BybitExecutionKind.FUNDING -> onFunding(execution)
                BybitExecutionKind.OTHER ->
                    log.info(
                        "Bybit {} execution is not a fill; the position reconcile applies it: {}",
                        category,
                        execution,
                    )
            }
        }
    }

    private fun fill(execution: JsonObject) {
        val exec = BybitOrderTranslator.parseExecution(execution)
        if (!seenExecIds.add(exec.execId)) return
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = exec.clientOrderId,
                brokerOrderId = exec.brokerOrderId,
                symbol = BybitSymbol.toQkt(category = category, bare = exec.bareSymbol),
                side = exec.side,
                price = exec.price,
                quantity = exec.quantity,
                strategyId = strategyOf(exec.clientOrderId) ?: "",
                timestamp = clock.now(),
                venueCosts = exec.fee,
            ),
        )
        lastFillTime.set(clock.now())
    }
}
