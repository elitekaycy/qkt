package com.qkt.pnl

import com.qkt.accounting.AccountingEngine
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.positions.LegExposureProvider
import com.qkt.positions.PositionProvider
import java.math.BigDecimal

interface PnLProvider {
    fun realizedTotal(): BigDecimal

    fun unrealizedFor(symbol: String): BigDecimal

    fun unrealizedTotal(): BigDecimal

    fun totalPnL(): BigDecimal
}

/** A PnL provider whose lifetime realized total can be restored before live processing resumes. */
interface RestorablePnLProvider : PnLProvider {
    /** Replace the realized total with persisted account-currency truth. */
    fun restoreRealizedTotal(realized: BigDecimal)
}

/**
 * Tracks realized + unrealized PnL across all positions.
 *
 * Phase 30: when an [InstrumentRegistry] is wired, the multiplier `contractSize` is
 * applied to every PnL calculation — so `(price - avgEntry) * quantity * contractSize`
 * matches what the broker venue reports for the same trade. The default
 * [NoopInstrumentRegistry] makes that multiplier degenerate to 1, preserving pre-Phase-30
 * test behavior for code paths that don't depend on contract-size-aware PnL.
 */
class PnLCalculator(
    private val positions: PositionProvider,
    private val prices: MarketPriceProvider,
    private val instruments: InstrumentRegistry = NoopInstrumentRegistry,
    private val accounting: AccountingEngine = AccountingEngine(),
    private val markTimestamp: () -> Long = { 0L },
) : RestorablePnLProvider {
    private var realizedTotal: BigDecimal = Money.ZERO

    fun recordRealized(realized: BigDecimal) {
        realizedTotal = realizedTotal.add(realized).setScale(Money.SCALE, Money.ROUNDING)
    }

    override fun restoreRealizedTotal(realized: BigDecimal) {
        realizedTotal = realized.setScale(Money.SCALE, Money.ROUNDING)
    }

    override fun realizedTotal(): BigDecimal = realizedTotal

    override fun unrealizedFor(symbol: String): BigDecimal {
        val price = prices.lastPrice(symbol) ?: return Money.ZERO
        val cs = instruments.lookup(symbol)?.contractSize ?: BigDecimal.ONE
        // Per leg when the provider exposes legs: a hedged pair nets to zero but both legs are
        // open at the venue, and their locked spread must reach equity and every halt rule.
        val native =
            if (positions is LegExposureProvider) {
                var sum = Money.ZERO
                positions.forEachLeg(symbol) { leg ->
                    val signedQty = if (leg.side == Side.BUY) leg.quantity else leg.quantity.negate()
                    sum = sum.add(price.subtract(leg.entryPrice).multiply(signedQty).multiply(cs))
                }
                sum.setScale(Money.SCALE, Money.ROUNDING)
            } else {
                val pos = positions.positionFor(symbol) ?: return Money.ZERO
                price
                    .subtract(pos.avgEntryPrice)
                    .multiply(pos.quantity)
                    .multiply(cs)
                    .setScale(Money.SCALE, Money.ROUNDING)
            }
        return accounting.convertPnlAmount(
            symbol = symbol,
            nativeAmount = native,
            timestamp = markTimestamp(),
            referencePrice = price,
        )
    }

    override fun unrealizedTotal(): BigDecimal {
        // Plain loop over the no-copy symbol view: this runs on every tick via the equity
        // tracker, and the map-copy + intermediate-list version dominated per-tick allocation.
        var acc = Money.ZERO
        for (symbol in positions.symbols()) acc = acc.add(unrealizedFor(symbol))
        return acc.setScale(Money.SCALE, Money.ROUNDING)
    }

    override fun totalPnL(): BigDecimal = realizedTotal().add(unrealizedTotal()).setScale(Money.SCALE, Money.ROUNDING)
}
