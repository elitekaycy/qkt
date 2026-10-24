package com.qkt.connector.mt5

import com.qkt.broker.BrokerFactory
import com.qkt.common.SymbolCalendars
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.AccountProfile
import com.qkt.connectivity.AccountType
import com.qkt.connectivity.TradingAccount
import com.qkt.marketdata.source.MarketSource

/**
 * One MT5 login, opened by [Mt5Connector].
 *
 * [profile] is exposed for the MT5-only operator tools; code outside the MT5 connector must use
 * the [TradingAccount] contract instead.
 */
class Mt5TradingAccount internal constructor(
    override val config: AccountConfig,
    val profile: MT5BrokerProfile,
    private val client: MT5Client,
    private val feed: Lazy<MarketSource>,
    private val journal: MT5TransportJournal?,
    private val accountFetcher: (MT5BrokerProfile) -> MT5AccountInfo,
    private val strategiesTrading: (String) -> List<String>,
) : TradingAccount {
    override val tradingHours: SymbolCalendars get() = profile.symbolCalendars

    override val marketData: MarketSource get() = feed.value

    override fun instrumentSpec(qktSymbol: String) = client.instrumentSpec(profile, qktSymbol)

    override fun directVenue(): com.qkt.connectivity.DirectVenue = Mt5DirectVenue.of(profile)

    override val orderEntry: BrokerFactory = { bus, clock, priceTracker, _, strategyName ->
        MT5Broker(
            profile = profile,
            bus = bus,
            clock = clock,
            priceTracker = priceTracker,
            client = client,
            strategyName = strategyName,
            siblingsLookup = {
                if (strategyName == null) {
                    emptyList()
                } else {
                    strategiesTrading(profile.name).filter { it != strategyName }
                }
            },
        )
    }

    override fun verify(): AccountProfile {
        val info = accountFetcher(profile)
        return AccountProfile(
            accountName = profile.name,
            accountId = info.login.toString(),
            server = info.server,
            type =
                when (MT5TradeMode.fromWire(info.tradeMode)) {
                    MT5TradeMode.REAL -> AccountType.LIVE
                    MT5TradeMode.DEMO -> AccountType.DEMO
                    MT5TradeMode.CONTEST -> AccountType.CONTEST
                    null -> AccountType.UNKNOWN
                },
            currency = info.currency,
            leverage = info.leverage,
            description = MT5AccountVerifier.describe(profile, info),
        )
    }

    override fun close() {
        journal?.let { runCatching { it.close() } }
    }
}
