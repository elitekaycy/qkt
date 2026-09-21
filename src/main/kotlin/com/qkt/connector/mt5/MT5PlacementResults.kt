package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest

/**
 * Reads the venue's reply to a one-ticket placement and tells the engine what happened.
 * E.g. a market buy answered DONE at 2400.35 becomes Accepted + Filled and its ticket is tracked
 * for the eventual close; a BUY_STOP answered DONE becomes Accepted only and its ticket waits in
 * the pending book; a timeout, a price of 0.0 or a partial fill is handed to the collaborators
 * that settle it from venue truth.
 */
internal class MT5PlacementResults(
    private val bus: EventBus,
    private val clock: Clock,
    private val books: MT5BrokerState,
    private val events: MT5BrokerEvents,
    private val unknownResolver: MT5UnknownResolveScheduler,
    private val pendingFills: MT5PendingFills,
    private val unknownPlacement: MT5UnknownPlacementResolution,
    private val partialPlacement: MT5PartialPlacement,
) {
    /**
     * Turn the venue's placement response into bus events. Runs on an OkHttp dispatcher thread
     * (off the engine thread); every `bus.publish` here is rerouted onto the engine thread by
     * the single-consumer loop, and the ticket maps it mutates are concurrent. A bad retcode
     * becomes [BrokerEvent.OrderRejected]; success becomes [BrokerEvent.OrderAccepted] plus, for
     * an instant-fill market, [BrokerEvent.OrderFilled].
     * A venue partial instead emits [BrokerEvent.OrderPartiallyFilled] and retains the residual
     * ticket for position-poller reconciliation.
     */
    fun handlePlacementResult(
        request: OrderRequest,
        placement: MT5OrderRequest,
        placementStartedAtMs: Long,
        protection: MT5PositionProtection?,
        resp: MT5OrderResponse,
    ) {
        if (!isOrderSuccessful(resp.result.retcode)) {
            val message = resp.errorMessage
            // An IO error or gateway 5xx AFTER the send leaves the outcome unknown — the
            // order may have reached MT5 and filled. Telling the strategy "rejected"
            // makes it re-fire and double the position; resolve against venue truth first.
            if (message != null && MT5SendOutcomes.isAmbiguousSendFailure(message)) {
                unknownResolver.executeUnknownResolution {
                    unknownPlacement.resolveUnknownOutcome(
                        request,
                        placement,
                        placementStartedAtMs,
                        protection,
                        message,
                    )
                }
                return
            }
            events.reject(request, message ?: "retcode=${resp.result.retcode}")
            return
        }
        val brokerOrderId =
            resp.result.order
                .takeIf { it != 0L }
                ?.toString() ?: resp.result.deal.toString()
        // A Bracket with a Market entry fills synchronously like a plain Market; a Bracket
        // whose entry is Stop/Limit places a pending order on the venue and waits for the
        // position poller to surface the eventual fill. Treating every Bracket as an
        // instant fill produces a phantom OrderFilled at placement time, which marks OCO
        // siblings FILLED before either has actually triggered on MT5 and turns the
        // strategy's OCO into a hedge.
        val isInstantFill =
            request is OrderRequest.Market ||
                (request is OrderRequest.Bracket && request.entry is OrderRequest.Market)
        val isPartialEntry = isInstantFill && resp.result.retcode == MT5_TRADE_RETCODE_DONE_PARTIAL
        val partialQuantity = resp.result.volume
        if (
            isPartialEntry &&
            (
                partialQuantity == null ||
                    partialQuantity.signum() != 1 ||
                    partialQuantity >= placement.volume ||
                    resp.result.order == 0L ||
                    resp.result.deal == 0L
            )
        ) {
            unknownResolver.executeUnknownResolution {
                unknownPlacement.resolveUnknownOutcome(
                    request,
                    placement,
                    placementStartedAtMs,
                    protection,
                    "partial fill response cannot identify a positive residual order",
                )
            }
            return
        }
        if (isPartialEntry) {
            unknownResolver.executeUnknownResolution {
                partialPlacement.resolvePartialPlacement(
                    request = request,
                    placement = placement,
                    placementStartedAtMs = placementStartedAtMs,
                    protection = protection,
                    response = resp,
                )
            }
            return
        }
        if (isInstantFill && resp.result.price.signum() <= 0) {
            // Async-fill venues (#1092) acknowledge a market order with DONE and price 0.0; the
            // real fill price lands on the position a moment later. Booking 0.0 faults the
            // engine loop, so resolve the fill from venue truth (bounded retry, exact
            // client_order_id match) instead — the same path an ambiguous send takes.
            unknownResolver.executeUnknownResolution {
                unknownPlacement.resolveUnknownOutcome(
                    request,
                    placement,
                    placementStartedAtMs,
                    protection,
                    "fill acknowledged with price 0.0 — anchoring from the venue position",
                )
            }
            return
        }
        // Register the venue ticket BEFORE announcing acceptance so any consumer reacting to
        // [BrokerEvent.OrderAccepted] (e.g. a follow-up modify keyed by clientOrderId) sees the
        // broker's bookkeeping already consistent.
        if (isInstantFill) {
            // Use whichever of `order` / `deal` is non-zero; instant-fill markets typically
            // return `order=0` and `deal=N`. Lets [MT5PositionPoller] attribute the close.
            val positionTicket =
                resp.result.order.takeIf { it != 0L }
                    ?: resp.result.deal.takeIf { it != 0L }
            if (positionTicket != null) {
                books.positionBook.track(
                    positionTicket,
                    MT5TicketMeta(request.id, request.strategyId, protection),
                    request.symbol,
                    clock.now(),
                )
            }
        } else {
            // Pending: track ticket so we can correlate fill events and cancel by orderId.
            resp.result.order
                .takeIf { it != 0L }
                ?.let { ticket ->
                    pendingFills.registerPendingTicket(
                        ticket,
                        MT5TicketMeta(request.id, request.strategyId, protection),
                    )
                }
        }
        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = request.id,
                brokerOrderId = brokerOrderId,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        if (isInstantFill) {
            val filledQuantity =
                resp.result.volume?.takeIf { it.signum() > 0 }
                    ?: placement.volume
            bus.publish(
                BrokerEvent.OrderFilled(
                    clientOrderId = request.id,
                    brokerOrderId = brokerOrderId,
                    symbol = request.symbol,
                    side = request.side,
                    price = resp.result.price,
                    quantity = filledQuantity,
                    strategyId = request.strategyId,
                    timestamp = clock.now(),
                ),
            )
        }
    }
}
