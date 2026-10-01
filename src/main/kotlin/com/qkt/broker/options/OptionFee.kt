package com.qkt.broker.options

import com.qkt.common.Money
import com.qkt.instrument.OptionRoot
import java.math.BigDecimal

/**
 * Option venue fees in the root's currency, per contract of `contractSize` underlying units, as
 * Deribit charges linear options: a trade pays `takerFeeRate` of the underlying index (plus any flat
 * `exchangeFeePerContract`), a contract delivered in the money pays `deliveryFeeRate` of the delivery
 * price, and each percentage fee is capped at `feeCapRate` of the option's value when the root
 * declares a cap (Deribit: 12.5%).
 */
object OptionFee {
    /** The fee on trading [quantity] contracts of [root] at [premium] with the underlying at [underlying]. */
    fun trade(
        root: OptionRoot,
        quantity: BigDecimal,
        premium: BigDecimal,
        underlying: BigDecimal,
    ): BigDecimal {
        val percentage = capped(root, root.takerFeeRate.multiply(underlying), premium)
        val perContract = percentage.multiply(root.contractSize).add(root.exchangeFeePerContract)
        return perContract.multiply(quantity.abs()).setScale(Money.SCALE, Money.ROUNDING)
    }

    /** The fee on delivering [quantity] contracts of [root] worth [intrinsic] each at [deliveryPrice]. */
    fun delivery(
        root: OptionRoot,
        quantity: BigDecimal,
        intrinsic: BigDecimal,
        deliveryPrice: BigDecimal,
    ): BigDecimal {
        if (intrinsic.signum() <= 0) return BigDecimal.ZERO.setScale(Money.SCALE)
        val percentage = capped(root, root.deliveryFeeRate.multiply(deliveryPrice), intrinsic)
        return percentage.multiply(root.contractSize).multiply(quantity.abs()).setScale(Money.SCALE, Money.ROUNDING)
    }

    private fun capped(
        root: OptionRoot,
        fee: BigDecimal,
        value: BigDecimal,
    ): BigDecimal = root.feeCapRate?.let { fee.min(it.multiply(value)) } ?: fee
}
