package com.qkt.pnl

import com.qkt.instrument.InstrumentRegistry
import java.math.BigDecimal

/**
 * Trading cost charged on a single fill, in account currency.
 *
 * A backtest applies one of these so its PnL reflects the same commission the live venue
 * bills; live runs use [NoCommission] because the real broker already deducts it from the
 * account. The fill's [quantity] is the lot size (e.g. 0.25), the unit the per-lot rate is
 * quoted against.
 */
interface CommissionModel {
    /** Commission for filling [quantity] lots of [symbol], in account currency (>= 0). */
    fun cost(
        symbol: String,
        quantity: BigDecimal,
    ): BigDecimal
}

/** No trading cost — every fill is free. The live default, since the venue bills for real. */
object NoCommission : CommissionModel {
    override fun cost(
        symbol: String,
        quantity: BigDecimal,
    ): BigDecimal = BigDecimal.ZERO
}

/**
 * Per-lot commission read from the instrument metadata: `quantity * commissionPerLot`.
 *
 * e.g. a 0.25-lot fill of an instrument with `commissionPerLot = 3.50` costs $0.875. Charged
 * on every fill (entry and exit), so a round turn pays it twice. Symbols with no registry
 * entry, or a zero rate, cost nothing.
 */
class PerLotCommission(
    private val instruments: InstrumentRegistry,
) : CommissionModel {
    override fun cost(
        symbol: String,
        quantity: BigDecimal,
    ): BigDecimal {
        val rate = instruments.lookup(symbol)?.commissionPerLot ?: BigDecimal.ZERO
        if (rate.signum() == 0) return BigDecimal.ZERO
        return quantity.abs().multiply(rate)
    }
}
