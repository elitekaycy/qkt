package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.SignalSuppressedEvent
import com.qkt.strategy.targetSymbol
import org.slf4j.Logger

/**
 * Log a WARN line on [log] for every request risk rejects and every signal a gate suppresses, so a
 * decision that never reached the venue still leaves a trace in the session log.
 */
internal fun wireRejectionLogs(
    bus: EventBus,
    log: Logger,
) {
    // The pipeline logs a `submit …` context line before the risk decision; without a
    // paired rejection line the log reads as if the order reached the venue (#876).
    bus.subscribe<RiskRejectedEvent> { e ->
        log.warn(
            "risk rejected {} {} {} {} qty={}: {}",
            e.request.strategyId,
            e.request.id,
            e.request.symbol,
            e.request.side,
            e.request.quantity.toPlainString(),
            e.reason,
        )
    }
    // A gated drop never becomes an OrderRequest, so the rejection WARN above can't
    // cover it; without this line such signals vanish without trace (#889).
    bus.subscribe<SignalSuppressedEvent> { e ->
        log.warn(
            "signal suppressed {} {} {}: {}",
            e.strategyId,
            e.signal.targetSymbol() ?: "-",
            e.signal::class.simpleName,
            e.reason,
        )
    }
}
