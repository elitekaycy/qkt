package com.qkt.broker.bybit.spot

import com.qkt.broker.Broker
import com.qkt.broker.OrderModification
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PositionAccountingMode
import com.qkt.broker.SubmitAck
import com.qkt.broker.bybit.BybitOrderTranslator
import com.qkt.broker.bybit.BybitSymbol
import com.qkt.broker.bybit.BybitTransport
import com.qkt.broker.bybit.boundedExecIdSet
import com.qkt.broker.bybit.requireBybitOk
import com.qkt.broker.bybit.resolveBybitOrder
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.net.PeriodicReconciler
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
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
 * Routes orders to Bybit Spot via REST + WebSocket.
 *
 * Handles symbol translation through [BybitSymbol], order translation through
 * [BybitOrderTranslator], periodic balance reconciliation, and broker state recovery
 * on startup. Spot positions are inferred from balances — there's no separate
 * position endpoint.
 */
class BybitSpotBroker(
    private val transport: BybitTransport,
    private val bus: EventBus,
    private val clock: Clock,
    private val recoveryWindowMs: Long = 5 * 60_000L,
    private val pollIntervalMs: Long = 30_000L,
    pollExecutor: ScheduledExecutorService? = null,
) : Broker {
    private val log = LoggerFactory.getLogger(BybitSpotBroker::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val symbolByClientOrderId: MutableMap<String, String> = ConcurrentHashMap()
    private val strategyByClientOrderId: MutableMap<String, String> = ConcurrentHashMap()
    private val knownOrders: MutableMap<String, BybitSpotStateRecovery.ManagedOrderView> = ConcurrentHashMap()
    private val seenExecIds: MutableSet<String> = boundedExecIdSet()
    private val lastFillTime: AtomicLong = AtomicLong(clock.now() - recoveryWindowMs)

    private val reconciler: PeriodicReconciler

    override val name: String = "BybitSpot"

    override fun positionAccountingMode(symbol: String): PositionAccountingMode = PositionAccountingMode.NETTING

    override val capabilities: Set<OrderTypeCapability> =
        setOf(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.LIMIT,
            OrderTypeCapability.STOP,
            OrderTypeCapability.STOP_LIMIT,
            OrderTypeCapability.IF_TOUCHED,
            OrderTypeCapability.MODIFY,
        )

    override fun supports(symbol: String): Boolean = symbol.startsWith("BYBIT_SPOT:")

    init {
        transport.subscribe("order") { frame -> onOrderFrame(frame) }
        transport.subscribe("execution") { frame -> onExecutionFrame(frame) }

        val recovery =
            BybitSpotStateRecovery(
                transport = transport,
                bus = bus,
                clock = clock,
                getKnownOrders = { knownOrders.toMap() },
                lastFillTimeProvider = lastFillTime::get,
                seenExecIds = seenExecIds,
            )
        transport.onDisconnect { reason ->
            bus.publish(
                BrokerEvent.ConnectionChanged(
                    broker = name,
                    state = BrokerEvent.ConnectionState.DISCONNECTED,
                    reason = reason,
                    timestamp = clock.now(),
                ),
            )
        }
        transport.onReconnect {
            bus.publish(
                BrokerEvent.ConnectionChanged(
                    broker = name,
                    state = BrokerEvent.ConnectionState.RECONNECTED,
                    reason = "private-ws-reconnected",
                    timestamp = clock.now(),
                ),
            )
            recovery.reconcile()
        }

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
                rejectReason = "BybitSpotBroker does not support symbol ${request.symbol}",
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
     * and the order's tracking is forgotten; a clean reply leaves tracking in place for the WS
     * order/execution stream to drive OrderAccepted/OrderFilled.
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
                resolvePlacementFailure(request, e)
            },
        )
    }

    private fun resolvePlacementFailure(
        request: OrderRequest,
        failure: Throwable,
    ) {
        val attempt = runCatching { resolveBybitOrder(transport, "spot", request.id, json) }
        if (attempt.isFailure) {
            log.error(
                "Bybit submit outcome unknown for {}; tracking retained: send={} resolve={}",
                request.id,
                failure.message,
                attempt.exceptionOrNull()?.message,
            )
            return
        }
        val resolution = attempt.getOrNull()
        if (resolution == null) {
            forgetTracking(request.id)
            bus.publish(
                BrokerEvent.OrderRejected(
                    clientOrderId = request.id,
                    brokerOrderId = null,
                    reason = "placement not found after transport failure: ${failure.message}",
                    strategyId = request.strategyId,
                    timestamp = clock.now(),
                ),
            )
            return
        }
        when (resolution.status) {
            "Rejected" ->
                bus.publish(
                    BrokerEvent.OrderRejected(
                        request.id,
                        resolution.brokerOrderId,
                        "venue resolved placement as rejected",
                        request.strategyId,
                        clock.now(),
                    ),
                )
            "Cancelled" ->
                bus.publish(
                    BrokerEvent.OrderCancelled(
                        request.id,
                        resolution.brokerOrderId,
                        "venue resolved placement as cancelled",
                        request.strategyId,
                        clock.now(),
                    ),
                )
            else ->
                bus.publish(
                    BrokerEvent.OrderAccepted(
                        request.id,
                        resolution.brokerOrderId,
                        request.strategyId,
                        clock.now(),
                    ),
                )
        }
    }

    override fun cancel(orderId: String) {
        val symbol = symbolByClientOrderId[orderId] ?: return
        val body = BybitOrderTranslator.toCancelBody(symbol = symbol, orderLinkId = orderId)
        runCatching {
            requireBybitOk(transport.postSigned("/v5/order/cancel", body), "order cancel", json)
        }.onFailure { e ->
            log.warn("Bybit cancel failed for {}: {}", orderId, e.message)
            bus.publish(
                BrokerEvent.OrderCancelFailed(
                    clientOrderId = orderId,
                    brokerOrderId = null,
                    reason = e.message ?: "cancel failure",
                    strategyId = strategyByClientOrderId[orderId].orEmpty(),
                    timestamp = clock.now(),
                ),
            )
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
        sb.append("\"category\":\"${parsed.category}\",")
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
        return parseModifyResponse(orderId, response, strategyId, changes)
    }

    private fun parseModifyResponse(
        clientOrderId: String,
        responseBody: String,
        strategyId: String,
        changes: OrderModification,
    ): SubmitAck {
        val tree = json.parseToJsonElement(responseBody).jsonObject
        val retCode = tree["retCode"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        val retMsg = tree["retMsg"]?.jsonPrimitive?.content ?: ""
        val brokerOrderId =
            tree["result"]
                ?.jsonObject
                ?.get("orderId")
                ?.jsonPrimitive
                ?.content
        if (retCode != 0) {
            log.warn(
                "Bybit order modify rejected: clientOrderId={} retCode={} retMsg={}",
                clientOrderId,
                retCode,
                retMsg,
            )
            bus.publish(
                BrokerEvent.OrderRejected(
                    clientOrderId = clientOrderId,
                    brokerOrderId = brokerOrderId,
                    reason = "$retCode: $retMsg",
                    strategyId = strategyId,
                    timestamp = clock.now(),
                ),
            )
            return SubmitAck(clientOrderId, brokerOrderId, accepted = false, rejectReason = "$retCode: $retMsg")
        }
        bus.publish(
            BrokerEvent.OrderModified(
                clientOrderId = clientOrderId,
                brokerOrderId = brokerOrderId,
                changes = changes,
                strategyId = strategyId,
                timestamp = clock.now(),
            ),
        )
        return SubmitAck(clientOrderId, brokerOrderId, accepted = true)
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
            log.warn("Bybit order rejected: clientOrderId={} retCode={} retMsg={}", clientOrderId, retCode, retMsg)
            bus.publish(
                BrokerEvent.OrderRejected(
                    clientOrderId = clientOrderId,
                    brokerOrderId = null,
                    reason = "$retCode: $retMsg",
                    strategyId = strategyId,
                    timestamp = clock.now(),
                ),
            )
            return SubmitAck(
                clientOrderId = clientOrderId,
                brokerOrderId = null,
                accepted = false,
                rejectReason = "$retCode: $retMsg",
            )
        }
        val brokerOrderId =
            tree["result"]
                ?.jsonObject
                ?.get("orderId")
                ?.jsonPrimitive
                ?.content
        return SubmitAck(
            clientOrderId = clientOrderId,
            brokerOrderId = brokerOrderId,
            accepted = true,
        )
    }

    private fun onOrderFrame(frame: JsonObject) {
        val data = frame["data"]?.jsonArray ?: return
        for (entry in data) {
            val obj = entry.jsonObject
            val clientOrderId = obj["orderLinkId"]?.jsonPrimitive?.content ?: continue
            val brokerOrderId = obj["orderId"]?.jsonPrimitive?.content
            val status = obj["orderStatus"]?.jsonPrimitive?.content ?: continue
            val now = clock.now()
            val strategyId = strategyByClientOrderId[clientOrderId] ?: ""
            when (status) {
                "New" ->
                    bus.publish(
                        BrokerEvent.OrderAccepted(
                            clientOrderId = clientOrderId,
                            brokerOrderId = brokerOrderId,
                            strategyId = strategyId,
                            timestamp = now,
                        ),
                    )
                "Cancelled" ->
                    bus.publish(
                        BrokerEvent.OrderCancelled(
                            clientOrderId = clientOrderId,
                            brokerOrderId = brokerOrderId,
                            reason = "broker cancel",
                            strategyId = strategyId,
                            timestamp = now,
                        ),
                    )
                "Rejected" ->
                    bus.publish(
                        BrokerEvent.OrderRejected(
                            clientOrderId = clientOrderId,
                            brokerOrderId = brokerOrderId,
                            reason = obj["rejectReason"]?.jsonPrimitive?.content ?: "broker rejected",
                            strategyId = strategyId,
                            timestamp = now,
                        ),
                    )
                else -> log.debug("Bybit order frame status={} (no event)", status)
            }
        }
    }

    private fun onExecutionFrame(frame: JsonObject) {
        val data = frame["data"]?.jsonArray ?: return
        for (entry in data) {
            val exec = BybitOrderTranslator.parseExecution(entry.jsonObject)
            if (!seenExecIds.add(exec.execId)) continue
            val qktSymbol = BybitSymbol.toQkt(category = "spot", bare = exec.bareSymbol)
            val strategyId = strategyByClientOrderId[exec.clientOrderId] ?: ""
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
