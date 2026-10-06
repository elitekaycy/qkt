package com.qkt.connector.gateway

import com.qkt.common.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Sends one account's submits and cancels to its gateway off the engine thread, one at a time, in
 * order. What the gateway placed or ended reaches [onOrder], its fills [onFill]. A submit the gateway
 * does not answer, or answers with a server error (`5xx`, so it may have placed it), is sent again, same
 * body (idempotent on its client order id), every [retryMs] until [submitDeadlineMs] after it was first
 * sent; then it is resolved by id: its fills, then the order as the gateway reports it, or, when the
 * gateway answers that it never placed it (an id it then refuses for ever), the order is [reject]ed. A
 * cancel that finds no order while its submit is still being sent is held and sent once the order is
 * placed; one the gateway cannot take is sent again until it can. No failure is dropped unlogged.
 */
internal class GatewayPlacement(
    private val client: GatewayClient,
    private val clock: Clock,
    private val onOrder: (WireOrder) -> Unit,
    private val onFill: (WireFill) -> Unit,
    private val submitDeadlineMs: Long = 30_000,
    private val retryMs: Long = 1_000,
) {
    private val log = LoggerFactory.getLogger(GatewayPlacement::class.java)
    private val executor =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "gateway-orders").apply { isDaemon = true } }
    private val sending: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val cancelOnPlace: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Sends [body]; [reject] hears a refusal (`venue_rejected`, `kill_switch`), an error, or that it was never placed. */
    fun submit(
        body: WireSubmit,
        reject: (String) -> Unit,
    ) {
        val deadline = clock.now() + submitDeadlineMs
        sending += body.clientOrderId
        run { attempt(body, reject, deadline) }
    }

    /** Cancels [clientOrderId]; an order already ended at the gateway (`not_found` once placed) needs nothing. */
    fun cancel(clientOrderId: String) = run { cancelNow(clientOrderId) }

    /** Runs [action] on the placement thread, after what is already queued; a failure is logged. */
    fun background(action: () -> Unit) = run(action)

    /** Stops sending; called when the account closes. */
    fun shutdown() {
        executor.shutdownNow()
    }

    private fun cancelNow(clientOrderId: String) {
        try {
            onOrder(client.cancel(clientOrderId))
        } catch (e: GatewayException) {
            when {
                e.code == "not_found" && clientOrderId in sending -> cancelOnPlace += clientOrderId
                e.code == "not_found" -> Unit
                e.status >= SERVER_ERROR -> retryCancel(clientOrderId, e.message)
                else -> log.warn("cancel {} refused: {}", clientOrderId, e.message)
            }
        } catch (e: GatewayUnavailableException) {
            retryCancel(clientOrderId, e.message)
        }
    }

    private fun retryCancel(
        clientOrderId: String,
        why: String?,
    ) {
        log.warn("cancel {} not delivered ({}); sending it again", clientOrderId, why)
        later { cancelNow(clientOrderId) }
    }

    private fun attempt(
        body: WireSubmit,
        reject: (String) -> Unit,
        deadline: Long,
    ) {
        try {
            when (val result = client.submit(body)) {
                is GatewaySubmit.Placed -> placed(result.order)
                is GatewaySubmit.Refused -> refused(body.clientOrderId, reject, "${result.code}: ${result.message}")
            }
        } catch (e: GatewayException) {
            if (e.status < SERVER_ERROR) return refused(body.clientOrderId, reject, e.message ?: e.code)
            unanswered(body, reject, deadline, e.message)
        } catch (e: GatewayUnavailableException) {
            unanswered(body, reject, deadline, e.message)
        }
    }

    private fun unanswered(
        body: WireSubmit,
        reject: (String) -> Unit,
        deadline: Long,
        why: String?,
    ) {
        log.warn("submit {} unanswered: {}", body.clientOrderId, why)
        if (clock.now() < deadline) later { attempt(body, reject, deadline) } else resolve(body.clientOrderId, reject)
    }

    private fun placed(order: WireOrder) {
        sending -= order.clientOrderId
        onOrder(order)
        if (cancelOnPlace.remove(order.clientOrderId)) cancelNow(order.clientOrderId)
    }

    private fun refused(
        clientOrderId: String,
        reject: (String) -> Unit,
        reason: String,
    ) {
        sending -= clientOrderId
        cancelOnPlace -= clientOrderId
        reject(reason)
    }

    /** Asks the gateway what became of [clientOrderId], until it can answer: its fills first, then the order. */
    private fun resolve(
        clientOrderId: String,
        reject: (String) -> Unit,
    ) {
        try {
            val order =
                client.order(clientOrderId)
                    ?: return refused(clientOrderId, reject, "not placed: the gateway never received it")
            client.dealsOf(clientOrderId).forEach(onFill)
            placed(order)
        } catch (e: GatewayUnavailableException) {
            log.warn("submit {} still unresolved: {}", clientOrderId, e.message)
            later { resolve(clientOrderId, reject) }
        } catch (e: GatewayException) {
            log.warn("submit {} still unresolved: {}", clientOrderId, e.message)
            later { resolve(clientOrderId, reject) }
        }
    }

    private fun run(action: () -> Unit) {
        if (!executor.isShutdown) executor.execute(logged(action))
    }

    private fun later(action: () -> Unit) {
        if (!executor.isShutdown) executor.schedule(logged(action), retryMs, TimeUnit.MILLISECONDS)
    }

    private fun logged(action: () -> Unit) =
        Runnable { runCatching(action).onFailure { log.error("gateway order task failed: {}", it.toString(), it) } }

    private companion object {
        const val SERVER_ERROR = 500
    }
}
