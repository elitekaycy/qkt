package com.qkt.broker.liquidation

/**
 * A simulated venue that liquidates: it closes an account's positions on a contract itself, as a live
 * venue's liquidation engine does once the account's equity falls below maintenance margin. Backtests
 * only; a live venue liquidates on its own and qkt books the fill the venue reports.
 */
fun interface LiquidatingVenue {
    /**
     * Cancel the working orders on [symbol] and close every strategy's position on it at the
     * contract's executable price now (a long sells at the bid, a short buys at the ask), each as a
     * venue close ([liquidationFill]) carrying the venue's fee for a taker fill. A contract the venue
     * cannot price now is left for a later call.
     */
    fun liquidate(symbol: String)
}
