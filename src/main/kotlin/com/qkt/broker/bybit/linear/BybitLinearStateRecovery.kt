package com.qkt.broker.bybit.linear

import com.qkt.broker.BrokerStateRecovery
import com.qkt.broker.bybit.BybitBalanceTranslator
import com.qkt.broker.bybit.BybitOrderTranslator
import com.qkt.broker.bybit.BybitTransport
import com.qkt.broker.bybit.spot.BybitSpotStateRecovery
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/** On-startup state reconciliation for [BybitLinearBroker] — replays open orders + positions. */
class BybitLinearStateRecovery(
    private val transport: BybitTransport,
    private val bus: EventBus,
    private val clock: Clock,
    private val positionProvider: PositionProvider,
    private val getKnownOrders: () -> Map<String, BybitSpotStateRecovery.ManagedOrderView>,
    private val lastFillTimeProvider: () -> Long,
    private val seenExecIds: MutableSet<String>,
    private val positionTolerance: BigDecimal = BigDecimal("0.00000001"),
) : BrokerStateRecovery {
    private val log = LoggerFactory.getLogger(BybitLinearStateRecovery::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    override fun reconcile() {
        synchronized(lock) {
            runCatching { reconcileOpenOrders() }
                .onFailure { log.warn("Open-orders reconcile failed: {}", it.message) }
            runCatching { reconcileExecutions() }
                .onFailure { log.warn("Executions reconcile failed: {}", it.message) }
            runCatching { reconcileBalances() }
                .onFailure { log.warn("Balances reconcile failed: {}", it.message) }
            runCatching { reconcilePositions() }
                .onFailure { log.warn("Positions reconcile failed: {}", it.message) }
        }
    }

    private fun reconcileOpenOrders() {
        val response =
            transport.getSigned(
                "/v5/order/realtime",
                mapOf("category" to "linear", "openOnly" to "0", "limit" to "50"),
            )
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
        val cap = BybitSpotStateRecovery.MAX_EXECUTIONS_PER_RECONCILE
        while (totalProcessed < cap) {
            val params =
                buildMap {
                    put("category", "linear")
                    put("startTime", startTime.toString())
                    put("limit", "50")
                    if (cursor.isNotEmpty()) put("cursor", cursor)
                }
            val response = transport.getSigned("/v5/execution/list", params)
            val tree = json.parseToJsonElement(response).jsonObject
            if (tree["retCode"]?.jsonPrimitive?.content?.toIntOrNull() != 0) return
            val list = tree["result"]?.jsonObject?.get("list")?.jsonArray ?: return
            var newThisPage = 0
            for (entry in list) {
                val exec = BybitOrderTranslator.parseExecution(entry.jsonObject)
                if (!seenExecIds.add(exec.execId)) continue
                val qktSymbol = "BYBIT_LINEAR:${exec.bareSymbol}"
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
                if (totalProcessed >= cap) return
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
                source = "BYBIT_LINEAR",
                timestamp = clock.now(),
            ),
        )
    }

    private fun reconcilePositions() {
        val response =
            transport.getSigned(
                "/v5/position/list",
                mapOf("category" to "linear", "settleCoin" to "USDT"),
            )
        val tree = json.parseToJsonElement(response).jsonObject
        if (tree["retCode"]?.jsonPrimitive?.content?.toIntOrNull() != 0) return
        val list = tree["result"]?.jsonObject?.get("list")?.jsonArray ?: return

        val brokerPositions: Map<String, Pair<BigDecimal, BigDecimal>> =
            list
                .mapNotNull { entry ->
                    val obj = entry.jsonObject
                    val bareSym = obj["symbol"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val side = obj["side"]?.jsonPrimitive?.content ?: ""
                    val rawSize = obj["size"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    if (rawSize.isBlank() || side.isBlank()) return@mapNotNull null
                    val size = BigDecimal(rawSize)
                    if (size.signum() == 0) return@mapNotNull null
                    val signed = if (side == "Sell") size.negate() else size
                    val avgPrice = BigDecimal(obj["avgPrice"]?.jsonPrimitive?.content ?: "0")
                    bareSym to (signed to avgPrice)
                }.toMap()

        for ((bareSymbol, qa) in brokerPositions) {
            val (signedQty, avgPrice) = qa
            val qktSymbol = "BYBIT_LINEAR:$bareSymbol"
            val enginePos = positionProvider.positionFor(qktSymbol)

            val qtyDiffers =
                enginePos == null ||
                    enginePos.quantity.subtract(signedQty).abs() > positionTolerance
            val avgDiffers =
                enginePos == null ||
                    enginePos.avgEntryPrice.subtract(avgPrice).abs() > positionTolerance

            if (qtyDiffers || avgDiffers) {
                bus.publish(
                    BrokerEvent.PositionReconciled(
                        symbol = qktSymbol,
                        oldQty = enginePos?.quantity,
                        newQty = signedQty,
                        oldAvgPx = enginePos?.avgEntryPrice,
                        newAvgPx = avgPrice,
                        source = "BYBIT_LINEAR",
                        reason = "periodic reconcile",
                        timestamp = clock.now(),
                    ),
                )
            }
        }

        val brokerSymbols = brokerPositions.keys.map { "BYBIT_LINEAR:$it" }.toSet()
        for ((sym, pos) in positionProvider.allPositions()) {
            if (sym.startsWith("BYBIT_LINEAR:") && sym !in brokerSymbols && pos.quantity.signum() != 0) {
                bus.publish(
                    BrokerEvent.PositionReconciled(
                        symbol = sym,
                        oldQty = pos.quantity,
                        newQty = BigDecimal.ZERO,
                        oldAvgPx = pos.avgEntryPrice,
                        newAvgPx = BigDecimal.ZERO,
                        source = "BYBIT_LINEAR",
                        reason = "broker reports flat (externally closed)",
                        timestamp = clock.now(),
                    ),
                )
            }
        }
    }
}
