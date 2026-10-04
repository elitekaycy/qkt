package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.events.CostIncurred
import com.qkt.events.FUNDING_REPLAY_MS
import com.qkt.events.FillAccountedEvent
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
 * position) is left unbooked. A venue that realizes a day's funding once the position is gone charges on
 * a basis of zero; the holdings strategies closed since the symbol's last funding share it then (a close
 * is written to disk only for a symbol funding was heard for, so trading anything else costs no I/O). What was
 * booked persists under the session's state owner, so a record heard again, from the stream or a replay
 * after a restart, is booked once, and a session books nothing funded before its first start.
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
        val funded = HashSet(state.closed.keys)
        bus.subscribe<FillAccountedEvent> { a ->
            val before = a.strategyPositionBefore?.quantity ?: BigDecimal.ZERO
            if (a.kind != FillAccountingKind.EXECUTION || a.strategyId !in strategyIds || before.signum() == 0) return@subscribe
            if ((a.strategyPositionAfter?.quantity ?: BigDecimal.ZERO).signum() != 0) return@subscribe
            val held = state.closed[a.symbol].orEmpty() + (a.strategyId to before)
            state = state.copy(closed = state.closed + (a.symbol to held))
            // Only a symbol funding was heard for is a perpetual worth a write; others never pay funding.
            if (a.symbol in funded) persistence.saveFunding(owner, state.copy(closed = state.closed.filterKeys(funded::contains)))
        }
        bus.subscribe<FundingCharged> { e ->
            funded += e.symbol
            if (e.fundedAtMs < state.sinceMs || e.fundingId in state.booked) return@subscribe
            book(e, holdings(e.symbol, strategyIds, state.closed[e.symbol].orEmpty()))
            val keepFrom = clock.now() - RETAIN_MS
            state =
                state.copy(
                    booked = state.booked.filterValues { it >= keepFrom } + (e.fundingId to e.fundedAtMs),
                    closed = state.closed - e.symbol,
                )
            persistence.saveFunding(owner, state.copy(closed = state.closed.filterKeys(funded::contains)))
        }
    }

    /** What each strategy holds of [symbol] now, or, when none does, what each closed since its last funding. */
    private fun holdings(
        symbol: String,
        strategyIds: List<String>,
        closed: Map<String, BigDecimal>,
    ): Map<String, BigDecimal> =
        strategyIds
            .mapNotNull { id -> positions.positionFor(id, symbol)?.quantity?.takeIf { it.signum() != 0 }?.let { id to it } }
            .toMap()
            .ifEmpty { closed }

    private fun book(
        e: FundingCharged,
        held: Map<String, BigDecimal>,
    ) {
        val basis = e.basis.takeIf { it.signum() != 0 } ?: held.values.fold(BigDecimal.ZERO, BigDecimal::add)
        if (basis.signum() == 0) {
            log.warn("funding {} of {} reaches no holding to share it by; not booked: {}", e.fundingId, e.symbol, e)
            return
        }
        val price = prices.lastPrice(e.symbol)
        for ((id, quantity) in held) {
            val part = e.amount.multiply(quantity, Money.CONTEXT).divide(basis, Money.CONTEXT)
            bus.publish(CostIncurred(id, e.symbol, part, "funding ${e.fundingId}", price, FillAccountingKind.FINANCING))
        }
    }

    private companion object {
        /** How long a booked record's id is kept: a day longer than a replay reaches back. */
        const val RETAIN_MS = FUNDING_REPLAY_MS + 86_400_000L
    }
}
