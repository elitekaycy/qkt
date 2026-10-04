package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.events.CostIncurred
import com.qkt.events.FUNDING_REPLAY_MS
import com.qkt.events.FillAccountingKind
import com.qkt.events.FundingCharged
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.persistence.FundingPersistence
import com.qkt.persistence.PersistedFunding
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Books a venue's perpetual funding ([FundingCharged]) for the strategies that share one session: each
 * strategy holding the perpetual is charged `amount × its signed holding / basis` as a [CostIncurred] of
 * kind [FillAccountingKind.FINANCING], the kind a backtest books funding as. A long and a short on one
 * account each get their own sign, and a part of the basis no strategy here holds (another tool's
 * position) is left unbooked. What was booked persists under the session's state owner, so a record
 * heard again, from the stream or a replay after a restart, is booked once, and a session books nothing
 * funded before its first start.
 */
internal class FundingBooking(
    private val bus: EventBus,
    private val positions: StrategyPositionTracker,
    private val persistence: FundingPersistence,
    private val prices: MarketPriceProvider,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(FundingBooking::class.java)

    /** Book for [strategyIds], the strategies this pipeline runs; the first owns the persisted state. */
    fun bind(strategyIds: List<String>) {
        val owner = strategyIds.firstOrNull() ?: return
        var state =
            persistence.loadFunding(owner)
                ?: PersistedFunding(clock.now(), emptyMap()).also { persistence.saveFunding(owner, it) }
        bus.subscribe<FundingCharged> { e ->
            if (e.fundedAtMs < state.sinceMs || e.fundingId in state.booked) return@subscribe
            book(e, strategyIds)
            val keepFrom = clock.now() - RETAIN_MS
            state = state.copy(booked = state.booked.filterValues { it >= keepFrom } + (e.fundingId to e.fundedAtMs))
            persistence.saveFunding(owner, state)
        }
    }

    private fun book(
        e: FundingCharged,
        strategyIds: List<String>,
    ) {
        if (e.basis.signum() == 0) {
            log.warn("funding {} of {} came with no position to share it by; not booked: {}", e.fundingId, e.symbol, e)
            return
        }
        val price = prices.lastPrice(e.symbol)
        for (id in strategyIds) {
            val held = positions.positionFor(id, e.symbol)?.quantity?.takeIf { it.signum() != 0 } ?: continue
            val part = e.amount.multiply(held, Money.CONTEXT).divide(e.basis, Money.CONTEXT)
            bus.publish(CostIncurred(id, e.symbol, part, "funding ${e.fundingId}", price, FillAccountingKind.FINANCING))
        }
    }

    private companion object {
        /** How long a booked record's id is kept: a day longer than a replay reaches back. */
        const val RETAIN_MS = FUNDING_REPLAY_MS + 86_400_000L
    }
}
