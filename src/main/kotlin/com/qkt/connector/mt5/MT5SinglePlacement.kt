package com.qkt.connector.mt5

import com.qkt.broker.SubmitAck
import com.qkt.common.Clock
import com.qkt.common.IdGenerator
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.MarketPriceProvider
import org.slf4j.LoggerFactory

/**
 * Sends a one-ticket order (market, limit, stop, or a bracket) to the venue without making the
 * engine thread wait. E.g. a market buy is shaped to the symbol's volume step, given a fresh
 * placement id, handed to the HTTP dispatcher, and acknowledged optimistically at once; the real
 * accept / reject / fill arrives later on the bus through [MT5PlacementResults].
 */
internal class MT5SinglePlacement(
    private val client: MT5Client,
    private val clock: Clock,
    private val priceTracker: MarketPriceProvider?,
    private val placementPrep: MT5PlacementPreparation,
    private val placementIds: IdGenerator,
    private val events: MT5BrokerEvents,
    private val requestedProtection: MT5RequestedProtection,
    private val placementResults: MT5PlacementResults,
) {
    private val log = LoggerFactory.getLogger(MT5Broker::class.java)

    fun submitSingle(
        request: OrderRequest,
        wire: MT5OrderRequest,
    ): SubmitAck {
        val prepared =
            when (val result = placementPrep.prepareForPlacement(wire)) {
                is MT5PlacementPreparation.PrepareResult.Ok -> result.wire
                is MT5PlacementPreparation.PrepareResult.Reject -> return events.reject(request, result.reason)
            }
        // #185 diagnostic: the gateway rejects a STOP entry whose trigger sits the wrong side
        // of the live quote (BUY_STOP <= ask). Log the submitted trigger vs the last market
        // price we saw, so a rejection's stale-quote delta is visible — without a fresh
        // getTick, which would add the signal-to-submission latency that causes the staleness.
        if ("STOP" in prepared.type) {
            log.info(
                "STOP submit {} type={} price={} lastSeen={}",
                request.id,
                prepared.type,
                prepared.price?.toPlainString(),
                priceTracker?.lastPrice(request.symbol)?.toPlainString(),
            )
        }
        // Non-blocking placement: the HTTP send runs on OkHttp's dispatcher and the venue's
        // result returns as bus events via [handlePlacementResult] (rerouted onto the engine
        // thread by the single-consumer loop). submit returns an optimistic ack at once so the
        // engine thread never waits on the order round-trip — the real accept/reject/fill
        // follows on the bus, which is what the event-driven OCO/OTO sequencing consumes.
        val placement = prepared.withPlacementId()
        val placementStartedAtMs = clock.now()
        val protection = requestedProtection.protectionOf(placement)
        client.placeOrderAsync(placement) { resp ->
            placementResults.handlePlacementResult(request, placement, placementStartedAtMs, protection, resp)
        }
        return SubmitAck(
            clientOrderId = request.id,
            brokerOrderId = null,
            accepted = true,
        )
    }

    private fun MT5OrderRequest.withPlacementId(): MT5OrderRequest = copy(clientOrderId = placementIds.next())
}
