package com.qkt.connector.bybit.spot

import com.qkt.broker.Broker
import com.qkt.broker.OrderModification
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PositionAccountingMode
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.net.PeriodicReconciler
import com.qkt.connector.bybit.BybitExecutionStream
import com.qkt.connector.bybit.BybitHeldEnds
import com.qkt.connector.bybit.BybitOrderTranslator
import com.qkt.connector.bybit.BybitOrderUpdates
import com.qkt.connector.bybit.BybitOrders
import com.qkt.connector.bybit.BybitRestartRecovery
import com.qkt.connector.bybit.BybitSymbol
import com.qkt.connector.bybit.BybitTransport
import com.qkt.connector.bybit.boundedExecIdSet
import com.qkt.connector.bybit.requireBybitOk
import com.qkt.connector.bybit.resolveBybitOrder
import com.qkt.events.BrokerEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
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

    private val orders = BybitOrders()
    private val ends = BybitHeldEnds(orders, bus, clock, "spot")
    private val restart: BybitRestartRecovery
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
        val updates = BybitOrderUpdates(ends, bus, clock)
        transport.subscribe("order", updates::onFrame)
        val executions =
            BybitExecutionStream(
                "spot",
                bus,
                clock,
                seenExecIds,
                lastFillTime,
                orders::strategyOf,
                onFill = ends::fill,
            )
        transport.subscribe("execution", executions::onFrame)

        val recovery =
            BybitSpotStateRecovery(
                transport = transport,
                bus = bus,
                clock = clock,
                getKnownOrders = orders::open,
                lastFillTimeProvider = lastFillTime::get,
                seenExecIds = seenExecIds,
                ends = ends,
            )
        restart = BybitRestartRecovery("spot", transport, ends, updates, recovery::replayOrder)
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

        // An end keeps the order's owner: an execution can still follow it (#1333).
        bus.subscribe<BrokerEvent.OrderFilled> { e -> orders.end(e.clientOrderId) }
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> orders.end(e.clientOrderId) }
        bus.subscribe<BrokerEvent.OrderRejected> { e -> orders.end(e.clientOrderId) }
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
        orders.register(
            BybitSpotStateRecovery.ManagedOrderView(request.id, request.symbol, request.side, request.strategyId),
        )
        // Non-blocking placement: the HTTP send runs on the dispatcher and the venue result returns
        // as bus events via [handlePlacementResult]. submit returns an optimistic ack at once so the
        // engine thread never waits on the order round-trip — the real accept/reject/fill follows on
        // the bus, which is what the event-driven OCO/OTO sequencing consumes.
        transport.postSignedAsync("/v5/order/create", body) { result -> handlePlacementResult(request, result) }
        return SubmitAck(clientOrderId = request.id, brokerOrderId = null, accepted = true)
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
                if (!ack.accepted) orders.forget(request.id)
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
            orders.forget(request.id)
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
        val symbol = orders.symbolOf(orderId) ?: return
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
                    strategyId = orders.strategyOf(orderId).orEmpty(),
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
            orders.symbolOf(orderId)
                ?: return SubmitAck(orderId, null, accepted = false, rejectReason = "unknown orderId $orderId")
        val strategyId = orders.strategyOf(orderId).orEmpty()
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

    override fun recoverPendingOrders(
        orders: List<ManagedOrder>,
        bookedTickets: Set<String>,
    ): Set<String> = restart.recover(orders)

    override fun shutdown() {
        reconciler.stop()
    }
}
