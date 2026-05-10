package com.qkt.broker

/**
 * Optional capability for brokers that can re-sync state with the venue after restart.
 *
 * Live brokers implement this to recover open orders + positions from the venue on
 * startup (so a daemon restart doesn't lose track of work in flight). Backtest and
 * paper brokers don't need it.
 */
interface BrokerStateRecovery {
    /** Pull authoritative state from the venue and publish reconciliation events. */
    fun reconcile()
}
