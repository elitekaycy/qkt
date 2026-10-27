package com.qkt.risk.rules

import com.qkt.execution.OrderRequest
import com.qkt.instrument.CorporateAction
import com.qkt.instrument.CorporateActionSource
import com.qkt.instrument.InstrumentRegistry
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule
import com.qkt.risk.isRiskReducing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Refuses an opening order on a share from [LEAD_DAYS] calendar days before one of its splits' ex-date through that
 * date (UTC dates, so the last session before the split is inside however long the weekend or holiday before it), in
 * backtest and live alike. Live, qkt does not restate its ledger for a split the venue applies, so a position opened
 * there would be held into one; closing orders pass. A share with no stored split is not this rule's to judge.
 */
class SplitWindow(
    private val instruments: InstrumentRegistry,
) : RiskRule {
    private val cache = HashMap<String, Pair<LocalDate, List<CorporateAction.Split>>>()

    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision {
        val actions = instruments.corporateActions() ?: return Decision.Approve
        val day = LocalDate.ofInstant(Instant.ofEpochMilli(request.timestamp), ZoneOffset.UTC)
        val split =
            splits(actions, request.symbol, day)
                .firstOrNull { !day.isAfter(it.exDate) && !day.isBefore(it.exDate.minusDays(LEAD_DAYS)) }
                ?: return Decision.Approve
        if (isRiskReducing(request, positions)) return Decision.Approve
        return Decision.Reject(
            "${request.symbol} splits ${split.ratio} for 1 on ${split.exDate}: no new position is held into a split",
        )
    }

    /** [symbol]'s splits, read once a UTC day: a backtest reads no file per order, a live session sees a new one. */
    private fun splits(
        actions: CorporateActionSource,
        symbol: String,
        day: LocalDate,
    ): List<CorporateAction.Split> {
        val cached = cache[symbol]
        if (cached != null && cached.first == day) return cached.second
        val read = actions.actions(symbol).filterIsInstance<CorporateAction.Split>()
        cache[symbol] = day to read
        return read
    }

    companion object {
        const val LEAD_DAYS = 4L
    }
}
