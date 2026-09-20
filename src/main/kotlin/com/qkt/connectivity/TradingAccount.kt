package com.qkt.connectivity

import com.qkt.broker.BrokerFactory
import com.qkt.common.SymbolCalendars
import com.qkt.marketdata.source.MarketSource

/**
 * One login at one broker, exchange or prop firm, opened through a [Connector].
 *
 * Strategies reach an account through its [symbolPrefix]: a strategy trading `PROP_S01:XAUUSD`
 * trades on the account named `prop_s01`.
 */
interface TradingAccount : AutoCloseable {
    /** The account's `brokers:` entry. */
    val config: AccountConfig

    /** The strategy symbol prefix this account serves, e.g. `PROP_S01:`. */
    val symbolPrefix: String get() = config.symbolPrefix

    /**
     * Connects and checks the venue reports the account the config expects — login, server,
     * live or demo, currency, leverage, position mode, as far as the connector can see them.
     * Throws when the venue is unreachable or anything mismatches; trading must not start then.
     */
    fun verify(): AccountProfile

    /** Creates each strategy's order-entry session on this account. */
    val orderEntry: BrokerFactory

    /** Prices from this account's own feed, or null when the connector supplies none. */
    val marketData: MarketSource?

    /** When each of this account's symbols trades. */
    val tradingHours: SymbolCalendars

    /** Releases the connections and files this account holds. Idempotent. */
    override fun close()
}
