package com.qkt.app

/**
 * Whether a session stopped because its live feed was lost rather than because it was asked to.
 *
 * A live feed that stays disconnected past its reconnect budget ends the session instead of letting
 * it trade on stale prices. The daemon reads [unexpectedFeedEnd] after the session stopped to
 * redeploy it once the venue is back ([com.qkt.cli.daemon.FeedLossRedeployer]).
 */
interface FeedLossReads {
    /**
     * The reason the live feed ended without a stop being requested, e.g. `market-data source
     * exceeded its 120000ms reconnect budget`, or null while running, after an operator stop, or for a
     * finite feed that simply ran out.
     */
    fun unexpectedFeedEnd(): String? = null
}
