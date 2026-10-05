package com.qkt.connector.bybit.linear

import com.qkt.broker.BrokerStateRecovery
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.connector.bybit.BybitBalanceTranslator
import com.qkt.connector.bybit.BybitExecutionReplay
import com.qkt.connector.bybit.BybitHeldEnds
import com.qkt.connector.bybit.BybitTransport
import com.qkt.connector.bybit.requireBybitOk
import com.qkt.connector.bybit.spot.BybitSpotStateRecovery
import com.qkt.events.BrokerEvent
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * State reconciliation for [BybitLinearBroker], on startup and periodically: replays executions, open
 * orders, balances and positions, then the account's perpetual funding through [funding]. With [ends],
 * fills are attributed through its orders (ended ones included), and the order ends it holds for their
 * executions are resolved on each reconcile.
 */
class BybitLinearStateRecovery(
    private val transport: BybitTransport,
    private val bus: EventBus,
    private val clock: Clock,
    private val positionProvider: PositionProvider,
    private val getKnownOrders: () -> Map<String, BybitSpotStateRecovery.ManagedOrderView>,
    private val lastFillTimeProvider: () -> Long,
    private val seenExecIds: MutableSet<String>,
    private val positionTolerance: BigDecimal = BigDecimal("0.00000001"),
    private val funding: BybitLinearFunding = BybitLinearFunding(transport, bus, clock, seenExecIds),
    private val ends: BybitHeldEnds? = null,
) : BrokerStateRecovery {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val executions =
        BybitExecutionReplay(
            "linear",
            transport,
            bus,
            clock,
            ends?.orders?.let { it::strategyOf } ?: { getKnownOrders()[it]?.strategyId },
            lastFillTimeProvider,
            seenExecIds,
            funding::take,
            onFill = ends?.let { it::fill },
        )

    override fun reconcile() {
        synchronized(lock) {
            val executedOrderIds = executions.reconcile()
            ends?.resolve(executions)
            reconcileOpenOrders(executedOrderIds)
            reconcileBalances()
            reconcilePositions()
            funding.poll()
        }
    }

    private fun reconcileOpenOrders(executedOrderIds: Set<String>) {
        val response =
            transport.getSigned(
                "/v5/order/realtime",
                mapOf("category" to "linear", "openOnly" to "0", "limit" to "50"),
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
        val tree = requireBybitOk(response, "position reconcile", json)
        val list =
            tree["result"]?.jsonObject?.get("list")?.jsonArray
                ?: throw IllegalStateException("position reconcile response omitted result.list")

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
