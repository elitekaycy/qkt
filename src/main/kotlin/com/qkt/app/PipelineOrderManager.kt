package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.PositionAccountingMode
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.persistence.StatePersistor
import com.qkt.positions.PositionProvider
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskState
import com.qkt.risk.isRiskReducing
import com.qkt.strategy.Mode
import java.math.BigDecimal

/**
 * Builds the pipeline's [OrderManager] and wires its engine-side callbacks to the strategy
 * position ledger and risk state: venue tickets for engine-held closes, the halt gate on
 * engine-held submissions, per-strategy net quantity, and the venue tickets already booked.
 */
internal fun pipelineOrderManager(
    broker: Broker,
    bus: EventBus,
    priceTracker: MarketPriceTracker,
    clock: Clock,
    persistor: StatePersistor,
    strategyPositions: StrategyPositionTracker,
    positions: PositionProvider,
    riskState: RiskState,
    mode: Mode,
    instruments: InstrumentRegistry,
    onProtectionFailure: (strategyId: String, message: String) -> Unit,
    positionMode: (symbol: String) -> PositionAccountingMode,
): OrderManager =
    OrderManager(
        broker,
        bus,
        priceTracker,
        clock,
        persistor,
        // An engine-managed exit id is `${bracketId}-sl`; the independent leg's id IS the
        // bracket id, so strip the suffix and look up that leg's venue ticket.
        closeTicketFor = { strategyId, exitId ->
            strategyPositions.ticketForLeg(strategyId, exitId.removeSuffix("-sl"))
        },
        closePrimaryTicketFor = { strategyId, symbol ->
            strategyPositions.ticketForPrimary(strategyId, symbol)
        },
        requireArmedTrailTicket = mode == Mode.LIVE,
        instruments = instruments,
        // Risk-per-trade is a backtest-report feature; only record it there so the live
        // daemon's risk map doesn't grow unbounded.
        trackRisk = mode == Mode.BACKTEST,
        onProtectionFailure = onProtectionFailure,
        engineHeldSubmissionBlockReason = { request ->
            when {
                !riskState.isStrategyHalted(request.strategyId) -> null
                isRiskReducing(request, positions) -> null
                else -> "halted: ${riskState.haltReasonFor(request.strategyId) ?: "halted"}"
            }
        },
        isRiskReducingForHalt = { request ->
            isRiskReducing(request, positions)
        },
        strategyNetQty = { strategyId, symbol ->
            strategyPositions.positionFor(strategyId, symbol)?.quantity ?: BigDecimal.ZERO
        },
        positionMode = positionMode,
        bookedVenueTickets = { strategyId ->
            strategyPositions.allLegsFor(strategyId).mapNotNullTo(LinkedHashSet()) { it.brokerTicket }
        },
    )
