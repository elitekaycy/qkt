package com.qkt.connectivity

import com.qkt.broker.BrokerFactory
import com.qkt.common.SymbolCalendars
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.SymbolPattern

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

    /** The symbols [marketData] is routed: the account's prefix, plus any feeds its connector derives from it. */
    val marketDataPattern: SymbolPattern get() = SymbolPattern.prefix(symbolPrefix)

    /** When each of this account's symbols trades. */
    val tradingHours: SymbolCalendars

    /**
     * The venue's own contract spec for [qktSymbol] (`PROP_S01:XAUUSD`), with the costs it cannot
     * report, or null when the connector cannot report one. Read-only and outside the trading path:
     * it lets an operator copy the specs live trades with into the file a backtest reads.
     */
    fun instrumentSpec(qktSymbol: String): com.qkt.instrument.VenueInstrumentSpec? = null

    /** Builds the catalogs of this account's futures roots from the venue's listing, or null when it lists none. */
    val contractCatalogs: com.qkt.instrument.ContractCatalogSource? get() = null

    /** The venue's published funding rates of its perpetuals, or null when the connector cannot read them. */
    val fundingRates: com.qkt.instrument.FundingRateSource? get() = null

    /** Releases the connections and files this account holds. Idempotent. */
    override fun close()
}
