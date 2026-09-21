package com.qkt.connector.mt5

import com.qkt.broker.Broker
import com.qkt.broker.BrokerAccountState
import com.qkt.broker.BrokerDeal
import com.qkt.broker.BrokerPositionTicket
import com.qkt.broker.MarginLevelProvider
import com.qkt.broker.OrderModification
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PositionAccountingMode
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.IdGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.MarketPriceProvider
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Routes orders to a MetaTrader 5 venue via an `mt5-gateway` HTTP service.
 *
 * Per-instance: each [com.qkt.app.LiveSession] instantiates its own broker so daemon
 * lifecycles are clean. Translates qkt [OrderRequest]s into MT5 wire shapes through
 * [MT5OrderTranslator], polls open positions through [MT5PositionPoller], and recovers
 * state on startup via [MT5StateRecovery].
 *
 * The profile determines venue identity, symbol policy (suffix translation), magic
 * number, and capability restrictions. See [MT5DefaultProfiles] for shipped templates
 * (Exness, ICMarkets, FTMO, Pepperstone) and [MT5BrokerProfileLoader] for YAML config.
 *
 * Market/bracket orders publish `OrderFilled` synchronously after successful placement
 * (the venue fills immediately). Pending Stop/Limit entries publish `OrderAccepted` and
 * rely on the position poller for eventual fill detection. MT5 has no venue-atomic OCO;
 * [com.qkt.app.OrderManager] places and links its pending legs separately.
 */
class MT5Broker(
    val profile: MT5BrokerProfile,
    private val bus: EventBus,
    private val clock: Clock,
    private val priceTracker: MarketPriceProvider? = null,
    private val client: MT5Client =
        MT5Client(
            gatewayUrl = profile.gatewayUrl,
            serverTimeZone = profile.serverTimeZone,
            httpTimeoutMs = profile.httpTimeoutMs,
            retryAttempts = profile.retryAttempts,
            apiKey = profile.apiKey,
        ),
    /**
     * Owning strategy name (single-strategy LiveSession). When set, startup state recovery
     * correlates venue-side orphan positions back to this strategy via comment-prefix match,
     * so a server-side TP/SL close fires [com.qkt.events.BrokerEvent.OrderFilled] with the
     * correct `strategyId`. Null in multi-strategy or test paths — recovery still publishes
     * [com.qkt.events.BrokerEvent.PositionReconciled] but does not seed orphan attribution.
     */
    private val strategyName: String? = null,
    /**
     * Names of other strategies sharing this MT5 magic — evaluated lazily at recovery time
     * so it picks up siblings deployed after this broker was built. Default empty (single-
     * strategy mode preserves the pre-#154 behaviour). See [MT5StateRecovery]'s docstring.
     */
    private val siblingsLookup: () -> List<String> = { emptyList() },
    /**
     * Base backoff between venue queries while resolving an UNKNOWN send outcome
     * (multiplied by attempt number). Tests shrink it; production keeps the default.
     */
    private val unknownResolveBackoffMs: Long = 500L,
    /** Delay before retrying an unresolved outcome; production follows the poll cadence. */
    private val unknownPeriodicResolveMs: Long =
        maxOf(profile.pollIntervalMs, UNKNOWN_PERIODIC_RESOLVE_MIN_MS),
    /** Clean restart-recovery venue reads required before startup may continue. */
    private val recoveryReadAttempts: Int = 5,
    /** Base linear backoff between restart-recovery venue reads. */
    private val recoveryReadBackoffMs: Long = 500L,
    /**
     * Generates gateway placement ids. The default combines venue, strategy, and the
     * injected session-start time with a sequence, so ids do not restart at zero across
     * live process restarts while fixed-clock tests remain deterministic.
     */
    private val placementIds: IdGenerator =
        SequentialIdGenerator(
            prefix = "mt5-${profile.magic}-${strategyName ?: "session"}-${clock.now()}",
        ),
) : Broker,
    com.qkt.broker.VenueOrderCancel by MT5VenueOrderCancel(client, profile.name),
    MarginLevelProvider,
    com.qkt.broker.InstrumentProvider,
    com.qkt.broker.ServerTimeZoneProvider,
    com.qkt.broker.TicketAttributionProvider {
    override val name: String = profile.name
    override val supportsPositionTickets: Boolean = true
    override val capabilities: Set<OrderTypeCapability> = profile.capabilities

    // Current mt5-gateway applies ORDER_TIME_SPECIFIED whenever expiration is present.
    override val supportsNativeGtd: Boolean = true

    private val log = LoggerFactory.getLogger(MT5Broker::class.java)
    private val accountReads = MT5BrokerAccountView(profile, client, clock)
    private val events = MT5BrokerEvents(profile, bus, clock)
    private val unknownResolver = MT5UnknownResolveScheduler(profile.name, unknownPeriodicResolveMs)
    private val mt5Symbol = MT5Symbol(profile.symbolPolicy)
    private val translator = MT5OrderTranslator(profile, mt5Symbol, priceTracker)
    private val requestedProtection = MT5RequestedProtection(translator)
    private val crossedStops = MT5CrossedStopConversion(profile, priceTracker)
    private val state = MT5BrokerState(profile)
    private val symbolMeta = state.symbolMeta
    private val pendingBook = state.pendingBook
    private val earlyPositionByTicket = state.earlyPositionByTicket
    private val pendingTransitionLock = state.pendingTransitionLock
    private val partialEntryByPositionTicket = state.partialEntryByPositionTicket
    private val partialPositionByResidualTicket = state.partialPositionByResidualTicket
    private val positionBook = state.positionBook
    private val venueCostLedger = state.venueCostLedger
    private val expectedProtectionByTicket = state.expectedProtectionByTicket
    private val recentlyFilledTickets = state.recentlyFilledTickets
    private val placementPrep = MT5PlacementPreparation(profile, client, priceTracker, mt5Symbol, symbolMeta)
    private val venueReads = MT5BrokerVenueReads(profile, client, mt5Symbol, positionBook, symbolMeta)
    private val engineCloses = MT5EngineCloseMarkers(profile, clock)
    private val positionModify = MT5PositionModify(profile, client, priceTracker, mt5Symbol, state)
    private val partialEntries = MT5PartialEntries(state, bus, clock)
    private val pendingOrderChanges = MT5PendingOrderChanges(profile, client, bus, clock, state, partialEntries)
    private val closeTruth = MT5CloseVenueTruth(client, clock, state)
    private val unknownClose =
        MT5UnknownCloseResolution(
            profile,
            client,
            bus,
            clock,
            state,
            events,
            engineCloses,
            unknownResolver,
            hasPublishedClose = { ticket -> poller.hasPublishedClose(ticket) },
            unknownResolveBackoffMs,
        )
    private val positionClose =
        MT5PositionClose(
            profile,
            client,
            bus,
            clock,
            mt5Symbol,
            placementPrep,
            state,
            events,
            engineCloses,
            unknownResolver,
            unknownClose,
            closeTruth,
        )
    private val pendingFills = MT5PendingFills(profile, bus, clock, mt5Symbol, state, partialEntries)
    private val pendingDisappearance =
        MT5PendingDisappearance(profile, client, bus, clock, state, partialEntries, pendingFills)
    private val unknownPlacement =
        MT5UnknownPlacementResolution(
            profile,
            client,
            bus,
            clock,
            mt5Symbol,
            state,
            events,
            unknownResolver,
            pendingFills,
            MT5UnknownVenueMatcher(state),
            MT5UnknownDealReplay(profile, client, bus, clock, state),
            unknownResolveBackoffMs,
        )

    /** Ledger legs on this broker's tickets; installed by the session, read by the poller thread. */
    @Volatile
    private var bookedLegs: () -> List<com.qkt.broker.BookedLeg> = { emptyList() }

    override fun watchBookedLegs(supplier: () -> List<com.qkt.broker.BookedLeg>) {
        bookedLegs = supplier
    }

    internal val poller =
        MT5PositionPoller(
            client,
            profile,
            mt5Symbol,
            bus,
            clock,
            bookedLegs = {
                val prefix = "${profile.name.uppercase()}:"
                bookedLegs().filter { it.symbol.startsWith(prefix) }
            },
            onPositionOpened = pendingFills::onPendingPositionOpened,
            onPositionIncreased = partialEntries::onPositionIncreased,
            closedTicketMeta = ::lookupClosedTicketMeta,
            onPositionClosed = ::removeClosedTicketMeta,
            isExpectedProtectionChange = positionModify::isExpectedProtectionChange,
            engineCloseState = engineCloses::engineCloseState,
            venueCostsForClose = closeTruth::bookVenueCloseCosts,
            priceProvider = priceTracker,
            sessionGate = profile.symbolCalendars::anyCalendarInSession,
            onGatewayUnreachable = events::publishGatewayUnreachable,
            onGatewayRecovered = events::publishGatewayRecovered,
            onPollRound = accountReads::refreshMarginLevelIfStale,
        )
    internal val pendingPoller =
        MT5PendingOrderPoller(
            client = client,
            profile = profile,
            clock = clock,
            sessionGate = profile.symbolCalendars::anyCalendarInSession,
            onPendingDisappeared = pendingDisappearance::onPendingDisappeared,
            onGatewayUnreachable = events::publishGatewayUnreachable,
            onGatewayRecovered = events::publishGatewayRecovered,
        )
    private val stateRecovery =
        MT5StateRecovery(
            client = client,
            profile = profile,
            symbol = mt5Symbol,
            bus = bus,
            strategyName = strategyName,
            seedOrphan = { ticket, orderId, strategyId ->
                positionBook.attribute(ticket, MT5TicketMeta(orderId, strategyId))
            },
            onPositionRecovered = { position ->
                positionBook.setOpenedAt(position.ticket, position.openTime)
            },
            siblingsLookup = siblingsLookup,
        )

    init {
        if (profile.hasExpectedAccount) {
            accountReads.recordAccountingMode(MT5AccountVerifier.fetchAndVerify(profile, client))
        }
        // Pollers start UNCONDITIONALLY: if recovery throws (one malformed gateway
        // response) but the pollers never start, the broker still accepts orders and
        // the session trades all day with no fill/close detection. Only recovery may
        // degrade, and loudly.
        try {
            stateRecovery.recover()
        } catch (e: Exception) {
            log.error(
                "MT5Broker ${profile.name} state recovery FAILED — orphan attribution degraded; " +
                    "positions opened before this start may close with blank strategyId",
                e,
            )
        }
        poller.start()
        pendingPoller.start()
    }

    override fun supports(symbol: String): Boolean = true

    override fun positionAccountingMode(symbol: String): PositionAccountingMode = accountReads.positionAccountingMode()

    override val supportsAccountEquity: Boolean = true

    override fun marketOpen(nowMs: Long): Boolean =
        profile.symbolCalendars.anyCalendarInSession(java.time.Instant.ofEpochMilli(nowMs))

    override fun scheduledBreak(
        symbol: String,
        nowMs: Long,
    ): Boolean {
        val bare = symbol.substringAfter(':')
        return profile.symbolCalendars.calendarFor(bare).isScheduledBreak(bare, java.time.Instant.ofEpochMilli(nowMs))
    }

    override val supportsMarginLevel: Boolean = true

    override fun accountEquity(): java.math.BigDecimal? = accountReads.accountEquity()

    override fun accountState(): BrokerAccountState? = accountReads.accountState()

    override fun deals(
        from: Long,
        to: Long,
    ): List<BrokerDeal> = venueReads.deals(from, to)

    override fun pendingOrders(): List<com.qkt.broker.BrokerPendingOrder> = venueReads.pendingOrders()

    override fun positionTickets(): List<BrokerPositionTicket> = venueReads.positionTickets()

    /**
     * Ticket → strategy-id pairs this broker currently attributes — recovery-seeded
     * orphans plus positions whose fills it has tracked. Read once at session start to
     * seed the insights ticket-attribution mirror, e.g. an orphan ticket 2832831596
     * recovered for hedge_straddle yields ("2832831596", "hedge_straddle").
     */
    override fun ticketAttributions(): Map<String, String> = positionBook.attributions()

    override fun instrumentRegistry(): com.qkt.instrument.InstrumentRegistry = MT5InstrumentRegistry(this)

    override fun serverTimeZone(): java.time.ZoneId = profile.serverTimeZone.asZoneId()

    override fun marginLevel(): java.math.BigDecimal? = accountReads.marginLevel()

    override fun submit(request: OrderRequest): SubmitAck {
        if (request is OrderRequest.Market && request.closesTicket != null) {
            return positionClose.submitCloseByTicket(request, request.closesTicket)
        }
        val dispatchRequest = crossedStops.convertAlreadyCrossedStopAtMarket(request)
        val translation =
            runCatching { translator.translate(dispatchRequest) }.getOrElse { ex ->
                return events.reject(dispatchRequest, ex.message ?: "translation failed")
            }

        return when (translation) {
            is MT5Translation.Single -> submitSingle(dispatchRequest, translation.request)
            is MT5Translation.Composite -> submitComposite(dispatchRequest, translation)
        }
    }

    /**
     * Venue-side rules for a symbol — used to quantize wire fields before placement.
     *
     * [digits] is the price scale (e.g. `3` for XAUUSD → prices in 0.001 increments).
     * MT5 rejects orders carrying more decimals than the symbol declares.
     */
    private fun submitSingle(
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
            handlePlacementResult(request, placement, placementStartedAtMs, protection, resp)
        }
        return SubmitAck(
            clientOrderId = request.id,
            brokerOrderId = null,
            accepted = true,
        )
    }

    /**
     * Turn the venue's placement response into bus events. Runs on an OkHttp dispatcher thread
     * (off the engine thread); every `bus.publish` here is rerouted onto the engine thread by
     * the single-consumer loop, and the ticket maps it mutates are concurrent. A bad retcode
     * becomes [BrokerEvent.OrderRejected]; success becomes [BrokerEvent.OrderAccepted] plus, for
     * an instant-fill market, [BrokerEvent.OrderFilled].
     * A venue partial instead emits [BrokerEvent.OrderPartiallyFilled] and retains the residual
     * ticket for position-poller reconciliation.
     */
    private fun handlePlacementResult(
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
                resolvePartialPlacement(
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
                positionBook.track(
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

    /**
     * MqlTradeResult separates order and deal tickets and exposes no position ticket. Resolve
     * the exact deal through venue history, whose `position_id` is the authoritative key used by
     * `/get_positions`; never infer that the residual order ticket owns the same numeric id.
     */
    private fun resolvePartialPlacement(
        request: OrderRequest,
        placement: MT5OrderRequest,
        placementStartedAtMs: Long,
        protection: MT5PositionProtection?,
        response: MT5OrderResponse,
    ) {
        for (attempt in 1..UNKNOWN_RESOLVE_ATTEMPTS) {
            val deals =
                client.getDeals(
                    fromUtcMs = placementStartedAtMs - MT5UnknownOutcomeMatching.CORRELATION_WINDOW_MS,
                    toUtcMs =
                        maxOf(
                            clock.now(),
                            placementStartedAtMs,
                        ) + MT5UnknownOutcomeMatching.CORRELATION_WINDOW_MS,
                )
            val openingDeal =
                deals
                    ?.singleOrNull {
                        it.ticket == response.result.deal &&
                            it.entry == 0 &&
                            it.positionTicket > 0L &&
                            (it.orderTicket == 0L || it.orderTicket == response.result.order) &&
                            it.magic == profile.magic &&
                            it.symbol == placement.symbol &&
                            it.type == (if (request.side == Side.BUY) 0 else 1)
                    }
            if (openingDeal != null) {
                publishInitialPartialEntry(
                    request,
                    placement,
                    placementStartedAtMs,
                    protection,
                    response,
                    openingDeal,
                )
                return
            }
            if (attempt < UNKNOWN_RESOLVE_ATTEMPTS) {
                try {
                    Thread.sleep(unknownResolveBackoffMs * attempt)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
        log.error(
            "MT5Broker {} partial entry {} cannot resolve deal {} to a position ticket; " +
                "retaining UNKNOWN outcome and retrying without assuming order-ticket identity",
            profile.name,
            request.id,
            response.result.deal,
        )
        events.publishGatewayUnreachable(UNKNOWN_RESOLVE_ATTEMPTS)
        unknownResolver.scheduleUnknownResolution {
            resolvePartialPlacement(request, placement, placementStartedAtMs, protection, response)
        }
    }

    private fun publishInitialPartialEntry(
        request: OrderRequest,
        placement: MT5OrderRequest,
        placementStartedAtMs: Long,
        protection: MT5PositionProtection?,
        response: MT5OrderResponse,
        openingDeal: MT5Deal,
    ) {
        val residualTicket = response.result.order
        val positionTicket = openingDeal.positionTicket
        val filledQuantity = requireNotNull(response.result.volume)
        // Async-fill venues report price 0.0 on the acknowledgement (#1092); the opening deal
        // carries the executed price.
        val fillPrice = response.result.price.takeIf { it.signum() > 0 } ?: openingDeal.price
        val meta = MT5TicketMeta(request.id, request.strategyId, protection)
        val earlyPosition =
            partialEntries.registerPartialEntry(
                PartialEntryState(
                    meta = meta,
                    residualTicket = residualTicket,
                    positionTicket = positionTicket,
                    symbol = request.symbol,
                    side = request.side,
                    requestedQuantity = placement.volume,
                    cumulativeFilled = filledQuantity,
                    averageFillPrice = fillPrice,
                ),
                openedAtMs = openingDeal.timeMs.takeIf { it > 0L } ?: placementStartedAtMs,
            )
        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = request.id,
                brokerOrderId = residualTicket.toString(),
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = request.id,
                brokerOrderId = positionTicket.toString(),
                symbol = request.symbol,
                side = request.side,
                price = fillPrice,
                quantity = filledQuantity,
                cumulativeFilled = filledQuantity,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        earlyPosition?.let(partialEntries::reconcilePartialEntry)
        if (partialPositionByResidualTicket.containsKey(residualTicket)) {
            pendingPoller.seedTrackedTickets(setOf(residualTicket))
        }
    }

    private fun submitComposite(
        request: OrderRequest,
        composite: MT5Translation.Composite,
    ): SubmitAck {
        // Prepare every leg first. Prepare is pre-placement validation only; rejecting
        // here doesn't need rollback because no client.placeOrder has run yet.
        val prepared =
            composite.requests.map { wire ->
                when (val result = placementPrep.prepareForPlacement(wire)) {
                    is MT5PlacementPreparation.PrepareResult.Ok -> result.wire
                    is MT5PlacementPreparation.PrepareResult.Reject ->
                        return events.reject(request, "OCO leg ${wire.comment}: ${result.reason}")
                }
            }
        // Place legs sequentially. Each leg's ticket is registered in [pendingBook]
        // so [MT5PositionPoller] can correlate the eventual fill back to a strategy. If
        // any leg rejects, every previously-placed leg is cancelled on the venue and
        // the entire composite is rejected — never leave a one-legged OCO running as a
        // directional bet the caller never intended.
        val placed = mutableListOf<PlacedLeg>()
        for (preparedWire in prepared) {
            val wire = preparedWire.withPlacementId()
            val resp = client.placeOrder(wire)
            if (!isOrderSuccessful(resp.result.retcode)) {
                val reason =
                    "OCO leg ${wire.comment}: ${resp.errorMessage ?: "retcode=${resp.result.retcode}"}"
                log.warn("MT5Broker ${profile.name} $reason; rolling back ${placed.size} placed leg(s)")
                for (leg in placed) {
                    runCatching { client.cancelOrder(leg.ticket) }
                        .onFailure { e ->
                            log.warn(
                                "MT5Broker ${profile.name} OCO rollback cancel(${leg.ticket}) failed: ${e.message}",
                            )
                        }
                    pendingBook.forgetLeg(leg.legOrderId, leg.ticket)
                }
                return events.reject(request, reason)
            }
            val ticket = resp.result.order
            if (ticket != 0L) {
                val legOrderId = decodeOcoLegOrderId(wire.comment) ?: request.id
                pendingFills.registerPendingTicket(ticket, MT5TicketMeta(legOrderId, request.strategyId))
                placed.add(PlacedLeg(ticket, legOrderId))
            }
        }

        val firstBrokerId = placed.firstOrNull { it.ticket != 0L }?.ticket?.toString() ?: composite.groupId

        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = request.id,
                brokerOrderId = firstBrokerId,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        return SubmitAck(
            clientOrderId = request.id,
            brokerOrderId = firstBrokerId,
            accepted = true,
        )
    }

    private fun MT5OrderRequest.withPlacementId(): MT5OrderRequest = copy(clientOrderId = placementIds.next())

    private data class PlacedLeg(
        val ticket: Long,
        val legOrderId: String,
    )

    /**
     * Recover the per-leg client order id from an OCO wire comment.
     *
     * [MT5OrderTranslator.translateStandaloneOCO] prepends `"oco:<parent-id>/"` to each
     * leg's original comment (which is the leg's `OrderRequest.id`). The qkt-side comment
     * is full-length even though MT5 truncates to 16 chars on the venue side — we decode
     * before sending, so truncation is not an issue here.
     */
    private fun decodeOcoLegOrderId(comment: String): String? {
        if (!comment.startsWith("oco:")) return null
        val slash = comment.indexOf('/')
        if (slash < 0 || slash == comment.length - 1) return null
        return comment.substring(slash + 1)
    }

    override fun getOpenPositions(): Map<String, List<com.qkt.positions.Position>> = venueReads.getOpenPositions()

    override fun recoverPendingOrders(
        orders: List<com.qkt.execution.ManagedOrder>,
        bookedTickets: Set<String>,
    ): Set<String> {
        if (orders.isEmpty()) return emptySet()
        val snapshot =
            readMT5RecoverySnapshot(
                attempts = recoveryReadAttempts,
                backoffMs = recoveryReadBackoffMs,
                onFailedAttempt = { attempt, reason ->
                    log.warn(
                        "MT5Broker {} recovery read failed (attempt {}/{}): {}",
                        profile.name,
                        attempt,
                        recoveryReadAttempts,
                        reason,
                    )
                },
                readPendingOrders = { client.getPendingOrders(magic = profile.magic) },
                readPositions = { client.getPositions(magic = profile.magic) },
            )
        val pending = snapshot.pendingOrders
        val positions = snapshot.positions
        val recoveredPartialIds = recoverPartialEntries(orders, pending, positions, bookedTickets)
        val resolvedOrders =
            orders.filterNot { it.id in recoveredPartialIds }.map { order ->
                if (order.brokerOrderId != null) return@map order
                val pendingMatch =
                    pending.firstOrNull {
                        it.clientOrderId == order.id ||
                            matchesOrderComment(it.comment, order.id)
                    }
                val positionMatch =
                    positions.firstOrNull {
                        it.clientOrderId == order.id ||
                            matchesOrderComment(it.comment, order.id)
                    }
                val ticket = pendingMatch?.ticket ?: positionMatch?.ticket
                if (ticket == null) {
                    order
                } else {
                    order.copy(brokerOrderId = ticket.toString())
                }
            }
        val actions = classifyOcoRecovery(resolvedOrders, pending.map { it.ticket }.toSet(), positions)
        // Pass 1: re-seed every still-pending leg before any fill is emitted, so a
        // cancel triggered by pass 2 can resolve its sibling's ticket.
        for (a in actions) {
            if (a is OcoRecoveryAction.Reseed) {
                pendingFills.registerPendingTicket(
                    a.ticket,
                    MT5TicketMeta(
                        a.order.id,
                        a.order.request.strategyId,
                        requestedProtection.protectionFor(a.order.request),
                    ),
                )
                log.info(
                    "MT5Broker ${profile.name} recovery: re-seeded pending leg ${a.order.id} ticket=${a.ticket}",
                )
            }
            if (a is OcoRecoveryAction.TrackVanished) {
                pendingFills.registerPendingTicket(
                    a.ticket,
                    MT5TicketMeta(
                        a.order.id,
                        a.order.request.strategyId,
                        requestedProtection.protectionFor(a.order.request),
                    ),
                )
            }
        }
        val vanishedTickets =
            actions
                .filterIsInstance<OcoRecoveryAction.TrackVanished>()
                .mapTo(mutableSetOf()) { it.ticket }
        pendingPoller.seedTrackedTickets(vanishedTickets)
        // Pass 2: republish the fill for any leg that filled while the daemon was down;
        // OrderManager's cancel-on-fill then unwinds the still-pending sibling.
        for (a in actions) {
            if (a is OcoRecoveryAction.EmitFill) {
                pendingBook.attribute(
                    a.position.ticket,
                    MT5TicketMeta(
                        a.order.id,
                        a.order.request.strategyId,
                        requestedProtection.protectionFor(a.order.request),
                    ),
                )
                if (a.position.ticket.toString() in bookedTickets) {
                    // The ledger booked this execution before the restart; republishing it
                    // would book it again (#1096). The ticket stays tracked for its close.
                    positionBook.track(
                        a.position.ticket,
                        pendingBook.requireMeta(a.position.ticket),
                        a.order.request.symbol,
                        a.position.openTime,
                    )
                    log.info(
                        "MT5Broker ${profile.name} recovery: leg ${a.order.id} ticket=${a.position.ticket} already booked; not republishing",
                    )
                    // Hand the restored order its venue ticket without republishing the execution:
                    // OrderManager uses it to recognise the entry as position-backed (already
                    // filled and booked) instead of leaving it working for the rest of the session.
                    bus.publish(
                        BrokerEvent.OrderAccepted(
                            clientOrderId = a.order.id,
                            brokerOrderId = a.position.ticket.toString(),
                            strategyId = a.order.request.strategyId,
                            timestamp = clock.now(),
                        ),
                    )
                    continue
                }
                log.info(
                    "MT5Broker ${profile.name} recovery: leg ${a.order.id} filled during downtime " +
                        "ticket=${a.position.ticket}",
                )
                pendingFills.onPendingPositionOpened(a.position)
            }
        }
        // Accounted for: partial fills adopted above, plus every order joined to a venue ticket
        // (re-seeded, filled during downtime, or vanished-and-tracked). Anything else has no
        // venue counterpart and is the caller's to retire.
        return recoveredPartialIds +
            resolvedOrders.filter { it.brokerOrderId != null }.mapTo(LinkedHashSet()) { it.id }
    }

    private fun recoverPartialEntries(
        orders: List<com.qkt.execution.ManagedOrder>,
        pending: List<MT5PendingOrder>,
        positions: List<MT5Position>,
        bookedTickets: Set<String>,
    ): Set<String> {
        val recovered = mutableSetOf<String>()
        for (order in orders) {
            val pendingMatches =
                pending.filter {
                    it.clientOrderId == order.id || matchesOrderComment(it.comment, order.id)
                }
            val positionMatches =
                positions.filter {
                    it.clientOrderId == order.id || matchesOrderComment(it.comment, order.id)
                }
            if (pendingMatches.size > 1 || positionMatches.size != 1) continue
            val position = positionMatches.single()
            val requestedQuantity = order.request.quantity
            if (position.volume.signum() <= 0 || position.volume >= requestedQuantity) continue

            val meta =
                MT5TicketMeta(
                    order.id,
                    order.request.strategyId,
                    requestedProtection.protectionFor(order.request),
                )
            positionBook.track(position.ticket, meta, order.request.symbol, position.openTime)
            if (position.ticket.toString() in bookedTickets) {
                // Already in the ledger from before the restart: keep the ticket tracked and let
                // the residual resolve, but never republish the booked execution (#1096).
                log.info(
                    "MT5Broker ${profile.name} recovery: partial entry ${order.id} ticket=${position.ticket} already booked; not republishing",
                )
                recovered.add(order.id)
                continue
            }
            val partialEvent =
                BrokerEvent.OrderPartiallyFilled(
                    clientOrderId = order.id,
                    brokerOrderId = position.ticket.toString(),
                    symbol = order.request.symbol,
                    side = order.request.side,
                    price = position.priceOpen,
                    quantity = position.volume,
                    cumulativeFilled = position.volume,
                    strategyId = order.request.strategyId,
                    timestamp = clock.now(),
                )
            val residual = pendingMatches.singleOrNull()
            bus.publish(
                BrokerEvent.OrderAccepted(
                    clientOrderId = order.id,
                    brokerOrderId = (residual?.ticket ?: position.ticket).toString(),
                    strategyId = order.request.strategyId,
                    timestamp = clock.now(),
                ),
            )
            if (residual != null) {
                partialEntries.registerPartialEntry(
                    PartialEntryState(
                        meta = meta,
                        residualTicket = residual.ticket,
                        positionTicket = position.ticket,
                        symbol = order.request.symbol,
                        side = order.request.side,
                        requestedQuantity = requestedQuantity,
                        cumulativeFilled = position.volume,
                        averageFillPrice = position.priceOpen,
                    ),
                    openedAtMs = position.openTime,
                )
                bus.publish(partialEvent)
                pendingPoller.seedTrackedTickets(setOf(residual.ticket))
                log.info(
                    "MT5Broker {} recovery: restored partial entry {} residual={} position={} cumulative={}",
                    profile.name,
                    order.id,
                    residual.ticket,
                    position.ticket,
                    position.volume,
                )
            } else {
                bus.publish(partialEvent)
                bus.publish(
                    BrokerEvent.OrderCancelled(
                        clientOrderId = order.id,
                        brokerOrderId = position.ticket.toString(),
                        reason = "residual absent during partial-entry recovery",
                        strategyId = order.request.strategyId,
                        timestamp = clock.now(),
                    ),
                )
            }
            recovered += order.id
        }
        return recovered
    }

    override fun cancel(orderId: String) = pendingOrderChanges.cancel(orderId)

    override fun modify(
        orderId: String,
        changes: OrderModification,
    ): SubmitAck = pendingOrderChanges.modify(orderId, changes)

    override fun modifyPosition(
        ticket: String,
        sl: BigDecimal?,
        tp: BigDecimal?,
    ): SubmitAck = positionModify.modifyPosition(ticket, sl, tp)

    override fun modifyPositionAsync(
        ticket: String,
        sl: BigDecimal?,
        tp: BigDecimal?,
        onResult: (SubmitAck) -> Unit,
    ) = positionModify.modifyPositionAsync(ticket, sl, tp, onResult)

    /**
     * [MT5PositionPoller] calls this when a ticket disappears from the venue snapshot
     * to resolve which qkt strategy and clientOrderId originally opened it. Attribution
     * remains until full closure so multiple partial closes keep the same strategy id.
     */
    private fun lookupClosedTicketMeta(ticket: Long): ClosedPositionMeta? {
        val meta = positionBook.meta(ticket) ?: return null
        return ClosedPositionMeta(clientOrderId = meta.orderId, strategyId = meta.strategyId)
    }

    private fun removeClosedTicketMeta(ticket: Long) {
        partialEntryByPositionTicket[ticket]?.let { cancel(it.meta.orderId) }
        earlyPositionByTicket.remove(ticket)
        positionBook.forget(ticket)
        expectedProtectionByTicket.remove(ticket)
    }

    /**
     * Resolve [InstrumentMeta] for a qkt-side symbol (e.g. `EXNESS:XAUUSD`).
     *
     * Reads through the same `/symbol_info` cache that powers v0.26.3 volume quantization
     * and v0.26.4 price rounding — primes the cache on first call, hits memory after.
     * Used by [com.qkt.connector.mt5.MT5InstrumentRegistry] so the trading pipeline gets a
     * consistent meta picture regardless of mode.
     */
    fun instrumentMeta(qktSymbol: String): com.qkt.instrument.InstrumentMeta? = venueReads.instrumentMeta(qktSymbol)

    override fun shutdown() {
        poller.stop()
        pendingPoller.stop()
        unknownResolver.shutdownNow()
    }

    companion object {
        /**
         * Multiplier applied to [MT5BrokerProfile.pollIntervalMs] for the
         * fill-vs-cancel disambiguation TTL. 3 cycles is enough headroom for the
         * position poller to tick at least once after the pending poller does.
         */
        private const val DISAMBIGUATION_TTL_MULTIPLIER: Long = MT5BrokerLimits.DISAMBIGUATION_TTL_MULTIPLIER

        /** Venue queries before giving up on resolving an UNKNOWN send outcome. */
        private const val UNKNOWN_RESOLVE_ATTEMPTS: Int = MT5BrokerLimits.UNKNOWN_RESOLVE_ATTEMPTS
        private const val UNKNOWN_PERIODIC_RESOLVE_MIN_MS: Long = 5_000L
    }
}
