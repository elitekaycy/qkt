package com.qkt.instrument

import com.qkt.common.Side
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * What one order in a share costs beyond its spread, in the instrument's currency: the broker's commission per share,
 * with a per-order minimum and an optional cap as a fraction of the order's value, and on a sale the US regulatory
 * fees a broker passes through, the SEC fee (a rate on the sale's value) and FINRA's trading activity fee (per share
 * sold, capped per order), each rounded up to the cent as brokers bill them. Every rate is the instrument file's: the
 * regulators change theirs each year, so none is assumed. Absent, an instrument costs what it did before.
 */
data class EquityCosts(
    val commissionPerShare: BigDecimal = BigDecimal.ZERO,
    val minCommission: BigDecimal = BigDecimal.ZERO,
    val maxCommissionRate: BigDecimal? = null,
    val secFeeRate: BigDecimal = BigDecimal.ZERO,
    val tafPerShare: BigDecimal = BigDecimal.ZERO,
    val tafMax: BigDecimal? = null,
) {
    init {
        listOfNotNull(commissionPerShare, minCommission, maxCommissionRate, secFeeRate, tafPerShare, tafMax)
            .forEach { require(it.signum() >= 0) { "equity costs must be >= 0: $this" } }
    }

    /** What an order on [side] filled [shares] at a total value of [notional] costs in all. */
    fun orderCost(
        side: Side,
        shares: BigDecimal,
        notional: BigDecimal,
    ): BigDecimal {
        if (shares.signum() == 0) return BigDecimal.ZERO
        val perShare = shares.multiply(commissionPerShare).max(minCommission)
        val commission = maxCommissionRate?.let { perShare.min(notional.multiply(it)) } ?: perShare
        if (side == Side.BUY) return commission
        val sec = cents(notional.multiply(secFeeRate))
        val taf = cents(shares.multiply(tafPerShare).let { t -> tafMax?.let(t::min) ?: t })
        return commission.add(sec).add(taf)
    }

    private fun cents(amount: BigDecimal): BigDecimal = amount.setScale(2, RoundingMode.CEILING)
}
