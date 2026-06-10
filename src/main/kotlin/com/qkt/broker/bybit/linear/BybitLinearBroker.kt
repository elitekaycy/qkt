package com.qkt.broker.bybit.linear

import com.qkt.broker.Broker
import com.qkt.broker.OrderModification
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.broker.bybit.BybitOrderTranslator
import com.qkt.broker.bybit.BybitSymbol
import com.qkt.broker.bybit.BybitTransport
import com.qkt.broker.bybit.boundedExecIdSet
import com.qkt.broker.bybit.spot.BybitSpotStateRecovery
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.net.PeriodicReconciler
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Routes orders to Bybit USDT-denominated linear perpetuals.
 *
 * Linear futures expose a dedicated position endpoint (unlike Spot), so this broker
 * uses [PositionProvider] to mirror authoritative venue positions. Otherwise the
 * structure parallels [BybitSpotBroker].
 */
class BybitLinearBroker(
    private val transport: BybitTransport,
    private val bus: EventBus,
    private val clock: Clock,
    private val positionProvider: PositionProvider,
    private val recoveryWindowMs: Long = 5 * 60_000L,
    private val pollIntervalMs: Long = 30_000L,
    pollExecutor: ScheduledExecutorService? = null,
) : Broker {
    private val log = LoggerFactory.getLogger(BybitLinearBroker::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val symbolByClientOrderId: MutableMap<String, String> = ConcurrentHashMap()
    private val strategyByClientOrderId: MutableMap<String, String> = ConcurrentHashMap()
    private val knownOrders: MutableMap<String, BybitSpotStateRecovery.ManagedOrderView> = ConcurrentHashMap()
    private val seenExecIds: MutableSet<String> = boundedExecIdSet()
    private val lastFillTime: AtomicLong = AtomicLong(clock.now() - recoveryWindowMs)

    private val reconciler: PeriodicReconciler

    override val name: String = "BybitLinear"

    override val capabilities: Set<OrderTypeCapability> =
        setOf(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.LIMIT,
            OrderTypeCapability.STOP,
            OrderTypeCapability.STOP_LIMIT,
            OrderTypeCapability.IF_TOUCHED,
            OrderTypeCapability.MODIFY,
        )

    override fun supports(symbol: String): Boolean = symbol.startsWith("BYBIT_LINEAR:")

    init {
        transport.subscribe("order") { frame -> onOrderFrame(frame) }
        transport.subscribe("execution") { frame -> onExecutionFrame(frame) }

        val recovery =
            BybitLinearStateRecovery(
                transport = transport,
                bus = bus,
                clock = clock,
                positionProvider = positionProvider,
                getKnownOrders = { knownOrders.toMap() },
                lastFillTimeProvider = lastFillTime::get,
                seenExecIds = seenExecIds,
            )
        transport.onReconnect { recovery.reconcile() }
        recovery.reconcile()

        reconciler =
            if (pollExecutor != null) {
                PeriodicReconciler(
                    intervalMs = pollIntervalMs,
                    action = { recovery.reconcile() },
                    executor = pollExecutor,
                )
            } else {
                PeriodicReconciler(
                    intervalMs = pollIntervalMs,
                    action = { recovery.reconcile() },
                )
            }
        reconciler.start()

        bus.subscribe<BrokerEvent.OrderFilled> { e -> forgetTracking(e.clientOrderId) }
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> forgetTracking(e.clientOrderId) }
        bus.subscribe<BrokerEvent.OrderRejected> { e -> forgetTracking(e.clientOrderId) }
    }

    override fun submit(request: OrderRequest): SubmitAck {
        if (!supports(request.symbol)) {
            return SubmitAck(
                clientOrderId = request.id,
                brokerOrderId = null,
                accepted = false,
                rejectReason = "BybitLinearBroker does not support symbol ${request.symbol}",
            )
        }
        val body = BybitOrderTranslator.toCreateBody(request)
        // Register tracking BEFORE the send: Bybit's private WS order/execution frame can arrive
        // ahead of the REST reply, and onOrderFrame/onExecutionFrame read these maps to attribute
        // the strategy. A rejected placement forgets them in [handlePlacementResult].
        registerTracking(request)
        // Non-blocking placement: the HTTP send runs on the dispatcher and the venue result returns
        // as bus events via [handlePlacementResult]. submit returns an optimistic ack at once so the
        // engine thread never waits on the order round-trip — the real accept/reject/fill follows on
        // the bus, which is what the event-driven OCO/OTO sequencing consumes.
        transport.postSignedAsync("/v5/order/create", body) { result -> handlePlacementResult(request, result) }
        return SubmitAck(clientOrderId = request.id, brokerOrderId = null, accepted = true)
    }

    private fun registerTracking(request: OrderRequest) {
        strategyByClientOrderId[request.id] = request.strategyId
        symbolByClientOrderId[request.id] = request.symbol
        knownOrders[request.id] =
            BybitSpotStateRecovery.ManagedOrderView(
                clientOrderId = request.id,
                symbol = request.symbol,
                side = request.side,
                strategyId = request.strategyId,
            )
    }

    private fun forgetTracking(clientOrderId: String) {
        strategyByClientOrderId.remove(clientOrderId)
        symbolByClientOrderId.remove(clientOrderId)
        knownOrders.remove(clientOrderId)
    }

    /**
     * Turn the venue's placement reply into bus events. Runs off the engine thread (the HTTP
     * dispatcher); every bus.publish here is routed onto the engine loop and the tracking maps it
     * touches are concurrent. A transport failure or non-zero retCode becomes [BrokerEvent.OrderRejected]
     * and the order's tracking is forgotten; a clean reply publishes [BrokerEvent.OrderAccepted] and
     * leaves tracking in place for the WS execution stream to drive the eventual fill.
     */
    private fun handlePlacementResult(
        request: OrderRequest,
        result: Result<String>,
    ) {
        result.fold(
            onSuccess = { body ->
                val ack = parseSubmitResponse(request.id, body, request.strategyId)
                if (!ack.accepted) forgetTracking(request.id)
            },
            onFailure = { e ->
                log.warn("Bybit submit failed: {}", e.message)
                forgetTracking(request.id)
                bus.publish(
                    BrokerEvent.OrderRejected(
                        clientOrderId = request.id,
                        brokerOrderId = null,
                        reason = e.message ?: "transport failure",
                        strategyId = request.strategyId,
                        timestamp = clock.now(),
                    ),
                )
            },
        )
    }

    override fun getOpenPositions(): Map<String, List<com.qkt.positions.Position>> {
        val raw =
            try {
                transport.postSigned("/v5/position/list", """{"category":"linear","settleCoin":"USDT"}""")
            } catch (e: Exception) {
                log.warn("BybitLinear getOpenPositions failed: {}", e.message)
                return emptyMap()
            }
        val tree = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyMap()
        if (tree["retCode"]?.jsonPrimitive?.content?.toIntOrNull() != 0) return emptyMap()
        val list = tree["result"]?.jsonObject?.get("list")?.jsonArray ?: return emptyMap()
        val out: MutableMap<String, MutableList<com.qkt.positions.Position>> = mutableMapOf()
        for (entry in list) {
            val obj = entry.jsonObject
            val bareSym = obj["symbol"]?.jsonPrimitive?.content ?: continue
            val rawSize = obj["size"]?.jsonPrimitive?.content ?: continue
            if (rawSize.isBlank()) continue
            val size = java.math.BigDecimal(rawSize)
            if (size.signum() == 0) continue
            val side = obj["side"]?.jsonPrimitive?.content ?: continue
            val signed = if (side == "Sell") size.negate() else size
            val avg = java.math.BigDecimal(obj["avgPrice"]?.jsonPrimitive?.content ?: "0")
            val qktSymbol = "BYBIT_LINEAR:$bareSym"
            // Hedge-mode-aware: each long/short ticket on the same symbol is preserved
            // separately so the reconciler can match against persisted legs individually.
            out.getOrPut(qktSymbol) { mutableListOf() }.add(
                com.qkt.positions.Position(qktSymbol, signed, avg),
            )
        }
        return out
    }

    override fun cancel(orderId: String) {
        val symbol = symbolByClientOrderId[orderId] ?: return
        val body = BybitOrderTranslator.toCancelBody(symbol = symbol, orderLinkId = orderId)
        try {
            transport.postSigned("/v5/order/cancel", body)
        } catch (e: Exception) {
            log.warn("Bybit cancel failed for {}: {}", orderId, e.message)
        }
    }

    override fun modify(
        orderId: String,
        changes: OrderModification,
    ): SubmitAck {
        val symbol =
            symbolByClientOrderId[orderId]
                ?: return SubmitAck(orderId, null, accepted = false, rejectReason = "unknown orderId $orderId")
        val strategyId = strategyByClientOrderId[orderId] ?: ""
        val parsed = BybitSymbol.parse(symbol)
        val sb = StringBuilder("{")
        sb.append("\"category\":\"linear\",")
        sb.append("\"symbol\":\"${parsed.bare}\",")
        sb.append("\"orderLinkId\":\"$orderId\"")
        if (changes.newQuantity != null) sb.append(",\"qty\":\"${changes.newQuantity.toPlainString()}\"")
        if (changes.newLimitPrice != null) sb.append(",\"price\":\"${changes.newLimitPrice.toPlainString()}\"")
        if (changes.newStopPrice != null) sb.append(",\"triggerPrice\":\"${changes.newStopPrice.toPlainString()}\"")
        sb.append("}")
        val response =
            try {
                transport.postSigned("/v5/order/amend", sb.toString())
            } catch (e: Exception) {
                return SubmitAck(orderId, null, accepted = false, rejectReason = e.message ?: "transport failure")
            }
        return parseSubmitResponse(orderId, response, strategyId)
    }

    private fun parseSubmitResponse(
        clientOrderId: String,
        responseBody: String,
        strategyId: String,
    ): SubmitAck {
        val tree = json.parseToJsonElement(responseBody).jsonObject
        val retCode = tree["retCode"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        val retMsg = tree["retMsg"]?.jsonPrimitive?.content ?: ""
        if (retCode != 0) {
            bus.publish(
                BrokerEvent.OrderRejected(
                    clientOrderId = clientOrderId,
                    brokerOrderId = null,
                    reason = "$retCode: $retMsg",
                    strategyId = strategyId,
                    timestamp = clock.now(),
                ),
            )
            return SubmitAck(clientOrderId, null, accepted = false, rejectReason = "$retCode: $retMsg")
        }
        val brokerOrderId =
            tree["result"]
                ?.jsonObject
                ?.get("orderId")
                ?.jsonPrimitive
                ?.content
        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = clientOrderId,
                brokerOrderId = brokerOrderId,
                strategyId = strategyId,
                timestamp = clock.now(),
            ),
        )
        return SubmitAck(clientOrderId, brokerOrderId, accepted = true)
    }

    private fun onOrderFrame(frame: JsonObject) {
        val list = frame["data"]?.jsonArray ?: return
        for (entry in list) {
            val parsed = BybitOrderTranslator.parseOpenOrder(entry.jsonObject)
            val qktSymbol = "BYBIT_LINEAR:${parsed.bareSymbol}"
            val strategyId = strategyByClientOrderId[parsed.clientOrderId] ?: ""
            when (parsed.status) {
                "New" ->
                    bus.publish(
                        BrokerEvent.OrderAccepted(
                            clientOrderId = parsed.clientOrderId,
                            brokerOrderId = parsed.brokerOrderId,
                            strategyId = strategyId,
                            timestamp = clock.now(),
                        ),
                    )
                "Cancelled" ->
                    bus.publish(
                        BrokerEvent.OrderCancelled(
                            clientOrderId = parsed.clientOrderId,
                            brokerOrderId = parsed.brokerOrderId,
                            reason = "WS-reported cancel",
                            strategyId = strategyId,
                            timestamp = clock.now(),
                        ),
                    )
                "Rejected" ->
                    bus.publish(
                        BrokerEvent.OrderRejected(
                            clientOrderId = parsed.clientOrderId,
                            brokerOrderId = parsed.brokerOrderId,
                            reason = "WS-reported reject",
                            strategyId = strategyId,
                            timestamp = clock.now(),
                        ),
                    )
            }
            symbolByClientOrderId[parsed.clientOrderId] = qktSymbol
        }
    }

    private fun onExecutionFrame(frame: JsonObject) {
        val list = frame["data"]?.jsonArray ?: return
        for (entry in list) {
            val exec = BybitOrderTranslator.parseExecution(entry.jsonObject)
            if (!seenExecIds.add(exec.execId)) continue
            val qktSymbol = "BYBIT_LINEAR:${exec.bareSymbol}"
            val strategyId = strategyByClientOrderId[exec.clientOrderId] ?: ""
            bus.publish(
                BrokerEvent.OrderFilled(
                    clientOrderId = exec.clientOrderId,
                    brokerOrderId = exec.brokerOrderId,
                    symbol = qktSymbol,
                    strategyId = strategyId,
                    side = exec.side,
                    price = exec.price,
                    quantity = exec.quantity,
                    timestamp = clock.now(),
                    venueCosts = exec.fee,
                ),
            )
            lastFillTime.set(clock.now())
        }
    }

    override fun shutdown() {
        reconciler.stop()
    }
}
