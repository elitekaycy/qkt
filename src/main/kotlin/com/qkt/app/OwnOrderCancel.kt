package com.qkt.app

/**
 * Cancels [strategyId]'s working orders on [symbol], and no other strategy's: a structure that ends
 * cancels its own legs, never another strategy's order on the same contract.
 */
internal fun OrderManager.cancelOwnOrders(
    strategyId: String,
    symbol: String,
) {
    activeOrders()
        .filter { it.request.symbol == symbol && it.request.strategyId == strategyId }
        .forEach { cancel(it.id) }
}
