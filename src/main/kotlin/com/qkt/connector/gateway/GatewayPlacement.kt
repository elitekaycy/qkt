package com.qkt.connector.gateway

import java.util.concurrent.Executors
import org.slf4j.LoggerFactory

/**
 * Sends one account's submits and cancels to its gateway off the engine thread, one at a time, in
 * order. What the gateway placed or ended reaches [onOrder]; a submit it never answered is reported to
 * [onUnconfirmed], for the next resynchronization to settle.
 */
internal class GatewayPlacement(
    private val client: GatewayClient,
    private val onOrder: (WireOrder) -> Unit,
    private val onUnconfirmed: (String) -> Unit,
) {
    private val log = LoggerFactory.getLogger(GatewayPlacement::class.java)
    private val executor =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "gateway-orders").apply { isDaemon = true }
        }

    /** Sends [body]; [reject] hears a refusal (`venue_rejected`, `kill_switch`) or an error. */
    fun submit(
        body: WireSubmit,
        reject: (String) -> Unit,
    ) = executor.execute {
        try {
            when (val result = client.submit(body)) {
                is GatewaySubmit.Placed -> onOrder(result.order)
                is GatewaySubmit.Refused -> reject("${result.code}: ${result.message}")
            }
        } catch (e: GatewayException) {
            reject(e.message ?: e.code)
        } catch (e: GatewayUnavailableException) {
            log.warn("submit {} unconfirmed: {}", body.clientOrderId, e.message)
            onUnconfirmed(body.clientOrderId)
        }
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

    /** Stops sending. */
    fun shutdown() {
        executor.shutdownNow()
    }
}
