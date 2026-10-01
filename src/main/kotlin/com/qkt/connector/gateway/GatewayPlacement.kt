package com.qkt.connector.gateway

import com.qkt.common.Clock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Sends one account's submits and cancels to its gateway off the engine thread, one at a time, in
 * order. What the gateway placed or ended reaches [onOrder], its fills [onFill]. A submit the gateway
 * does not answer is sent again, same body (idempotent on its client order id), every [retryMs] until
 * [submitDeadlineMs] after it was first sent; then it is resolved by id: the gateway reports the order,
 * or answers that it never placed it (an id it then refuses for ever), and the order is [reject]ed.
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

    /** Sends [body]; [reject] hears a refusal (`venue_rejected`, `kill_switch`), an error, or that it was never placed. */
    fun submit(
        body: WireSubmit,
        reject: (String) -> Unit,
    ) {
        val deadline = clock.now() + submitDeadlineMs
        executor.execute { attempt(body, reject, deadline) }
    }

    /** Cancels [clientOrderId]; an order already ended at the gateway (`not_found`) needs nothing. */
    fun cancel(clientOrderId: String) =
        executor.execute {
            try {
                onOrder(client.cancel(clientOrderId))
            } catch (e: GatewayException) {
                if (e.code != "not_found") log.warn("cancel {} refused: {}", clientOrderId, e.message)
            } catch (e: GatewayUnavailableException) {
                log.warn("cancel {} not delivered: {}", clientOrderId, e.message)
            }
        }

    /** Runs [action] on the placement thread, after what is already queued; a failure is logged. */
    fun background(action: () -> Unit) =
        executor.execute {
            runCatching(
                action,
            ).onFailure { log.warn("gateway background task failed: {}", it.message) }
        }

    /** Stops sending; called when the account closes. */
    fun shutdown() {
        executor.shutdownNow()
    }

    private fun attempt(
        body: WireSubmit,
        reject: (String) -> Unit,
        deadline: Long,
    ) {
        try {
            when (val result = client.submit(body)) {
                is GatewaySubmit.Placed -> onOrder(result.order)
                is GatewaySubmit.Refused -> reject("${result.code}: ${result.message}")
            }
        } catch (e: GatewayException) {
            reject(e.message ?: e.code)
        } catch (e: GatewayUnavailableException) {
            log.warn("submit {} unanswered: {}", body.clientOrderId, e.message)
            if (clock.now() < deadline) {
                later { attempt(body, reject, deadline) }
            } else {
                resolve(body.clientOrderId, reject)
            }
        }
    }

    /** Asks the gateway what became of [clientOrderId], until it can answer. */
    private fun resolve(
        clientOrderId: String,
        reject: (String) -> Unit,
    ) {
        try {
            val order = client.order(clientOrderId)
            if (order == null) {
                reject("not placed: the gateway never received it")
                return
            }
            onOrder(order)
            client.dealsOf(clientOrderId).forEach(onFill)
        } catch (e: GatewayUnavailableException) {
            log.warn("submit {} still unresolved: {}", clientOrderId, e.message)
            later { resolve(clientOrderId, reject) }
        }
    }

    private fun later(action: () -> Unit) {
        if (!executor.isShutdown) executor.schedule(action, retryMs, TimeUnit.MILLISECONDS)
    }
}
