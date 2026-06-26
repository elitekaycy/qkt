package com.qkt.pnl

import com.qkt.accounting.AccountingEngine
import com.qkt.common.Money
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.positions.PositionProvider
import java.math.BigDecimal

interface PnLProvider {
    fun realizedTotal(): BigDecimal

    fun unrealizedFor(symbol: String): BigDecimal

    fun unrealizedTotal(): BigDecimal

    fun totalPnL(): BigDecimal
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
) : PnLProvider {
    private var realizedTotal: BigDecimal = Money.ZERO

    fun recordRealized(realized: BigDecimal) {
        realizedTotal = realizedTotal.add(realized).setScale(Money.SCALE, Money.ROUNDING)
    }

    override fun realizedTotal(): BigDecimal = realizedTotal

    override fun unrealizedFor(symbol: String): BigDecimal {
        val pos = positions.positionFor(symbol) ?: return Money.ZERO
        val price = prices.lastPrice(symbol) ?: return Money.ZERO
        val cs = instruments.lookup(symbol)?.contractSize ?: BigDecimal.ONE
        val native =
            price
                .subtract(pos.avgEntryPrice)
                .multiply(pos.quantity)
                .multiply(cs)
                .setScale(Money.SCALE, Money.ROUNDING)
        return accounting
            .convertPnl(
                symbol = symbol,
                nativeAmount = native,
                timestamp = markTimestamp(),
                referencePrice = price,
            ).account.amount
    }

    override fun unrealizedTotal(): BigDecimal =
        positions
            .allPositions()
            .keys
            .map { unrealizedFor(it) }
            .fold(Money.ZERO) { acc, v -> acc.add(v) }
            .setScale(Money.SCALE, Money.ROUNDING)

    override fun totalPnL(): BigDecimal = realizedTotal().add(unrealizedTotal()).setScale(Money.SCALE, Money.ROUNDING)
}
