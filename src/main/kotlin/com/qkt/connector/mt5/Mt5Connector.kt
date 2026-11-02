package com.qkt.connector.mt5

import com.qkt.common.SymbolCalendars
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.Connector
import com.qkt.connectivity.ConnectorContext
import com.qkt.connectivity.ConnectorSpec
import com.qkt.connectivity.ProductType
import com.qkt.connectivity.TradingAccount
import com.qkt.connector.mt5.marketdata.Mt5MarketSource
import com.qkt.marketdata.source.CachedHistoricalMarketSource
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.PrefixRemapMarketSource
import com.qkt.marketdata.source.SharedLiveMarketSource

/**
 * MetaTrader 5 through mt5-gateway: CFD accounts at MT5 brokers and prop firms.
 *
 * Opens every `type: mt5` account together so connections are shared the way the daemon always
 * shared them: accounts on one gateway and API key share one read cache; each account has one
 * [MT5Client] used by all its strategies; accounts with the same [Mt5MarketDataIdentity] share one
 * live feed, the first owning the poller and the rest remapped onto it.
 *
 * [accountFetcher] reads and checks the venue account at [TradingAccount.verify]; tests replace it.
 */
class Mt5Connector(
    private val accountFetcher: (MT5BrokerProfile) -> MT5AccountInfo = { MT5AccountVerifier.fetchAndVerify(it) },
) : Connector {
    override val spec: ConnectorSpec =
        ConnectorSpec("mt5", "MetaTrader 5", productTypes = setOf(ProductType.CFD), settings = MT5ProfileSettings.KEYS)

    /** Each `type: mt5` entry's calendars from config alone, built as [open] builds each account's profile. */
    override fun tradingHours(accounts: List<AccountConfig>): Map<String, SymbolCalendars> =
        MT5ProfileCalendars.resolve(accounts, MT5DefaultProfiles.all)

    override fun builtInTradingHours(): Map<String, SymbolCalendars> =
        MT5DefaultProfiles.all.mapValues { it.value.symbolCalendars }

    override fun open(
        accounts: List<AccountConfig>,
        context: ConnectorContext,
    ): List<TradingAccount> {
        val chain = withParents(accounts, context.configured)
        val opening = accounts.mapTo(HashSet()) { it.name }
        val loaded =
            MT5BrokerProfileLoader()
                .load(
                    raw = chain.associate { it.name to withResolvedApiKey(it, context) },
                    defaults = MT5DefaultProfiles.all,
                    env = context.env,
                    calendars = chain.associate { it.name to it.tradingHours },
                    aliases = chain.associate { it.name to it.symbolAliases },
                    capabilityRestrictions = chain.associate { it.name to it.disabledOrderTypes },
                    instrumentOverrides = chain.associate { it.name to it.instrumentOverrides },
                ).filter { it.name in opening }
        val readCaches =
            loaded
                .map { it.gatewayUrl to it.apiKey }
                .distinct()
                .associateWith { MT5ReadCache(SHARED_READ_TTL_MS) }

        fun client(
            profile: MT5BrokerProfile,
            journal: MT5TransportJournal?,
        ) = MT5Client(
            gatewayUrl = profile.gatewayUrl,
            serverTimeZone = profile.serverTimeZone,
            httpTimeoutMs = profile.httpTimeoutMs,
            retryAttempts = profile.retryAttempts,
            apiKey = profile.apiKey,
            readCache = readCaches.getValue(profile.gatewayUrl to profile.apiKey),
            transportJournal = journal,
        )
        // `category:` calendar rules read the venue's folder for each symbol; every user of the profile sees them.
        val profiles = loaded.map { it.withInstrumentCategories(client(it, null)) }
        val feeds = marketDataFeeds(profiles)
        val byName = profiles.associateBy { it.name }
        return accounts.map { cfg ->
            val profile = byName[cfg.name] ?: error("MT5 profile for brokers.${cfg.name} did not load")
            val journal =
                context.stateRoot?.let {
                    MT5TransportJournal(it.resolve(TRANSPORT_JOURNAL_DIR), profile.name, context.clock)
                }
            Mt5TradingAccount(
                config = cfg,
                profile = profile,
                client = client(profile, journal),
                feed = feeds.getValue(profile.name),
                journal = journal,
                accountFetcher = accountFetcher,
                strategiesTrading = context.strategiesTrading,
            )
        }
    }

    /**
     * [accounts] and the entries of [configured] their `extends:` chains name: a command opening one account
     * (`qkt bot --broker child`) resolves it from the whole `brokers:` section, as the daemon does, and is not
     * stopped by a broken entry the account does not extend.
     */
    private fun withParents(
        accounts: List<AccountConfig>,
        configured: List<AccountConfig>,
    ): List<AccountConfig> {
        val byName = configured.associateBy { it.name }
        val chain = accounts.associateByTo(LinkedHashMap()) { it.name }
        val walking = ArrayDeque(accounts)
        while (walking.isNotEmpty()) {
            val parent = walking.removeFirst().setting("extends")?.let(byName::get) ?: continue
            if (chain.putIfAbsent(parent.name, parent) == null) walking += parent
        }
        return chain.values.toList()
    }

    /**
     * Resolves an `env:` or `file:` gateway key through the shared [ConnectorContext.secrets], the
     * same forms every connector accepts. `${VAR}` was already substituted when the config file
     * loaded, and a literal key passes through, so existing configs load exactly as before.
     */
    private fun withResolvedApiKey(
        account: AccountConfig,
        context: ConnectorContext,
    ): Map<String, String> {
        val raw = account.setting(API_KEY) ?: return account.settings
        if (!raw.startsWith("env:") && !raw.startsWith("file:")) return account.settings
        val resolved = context.secrets.resolve(account, API_KEY) ?: return account.settings
        return account.settings + (API_KEY to resolved.reveal())
    }

    /**
     * One lazily built feed per market-data identity. The first account of a group owns the
     * poller; the others remap their prefix onto it, so the gateway sees one tick poller per
     * identity. Nothing is constructed until an account's market data is first read.
     */
    private fun marketDataFeeds(profiles: List<MT5BrokerProfile>): Map<String, Lazy<MarketSource>> {
        val feeds = mutableMapOf<String, Lazy<MarketSource>>()
        for (group in groupByMarketDataIdentity(profiles)) {
            val canonical = group.first()
            val canonicalPrefix = "${canonical.name.uppercase()}:"
            val shared: Lazy<MarketSource> =
                lazy { CachedHistoricalMarketSource(SharedLiveMarketSource(Mt5MarketSource(canonical))) }
            feeds[canonical.name] = shared
            for (profile in group.drop(1)) {
                feeds[profile.name] =
                    lazy {
                        PrefixRemapMarketSource(
                            delegate = shared.value,
                            delegatePrefix = canonicalPrefix,
                            localPrefix = "${profile.name.uppercase()}:",
                        )
                    }
            }
        }
        return feeds
    }

    private companion object {
        /** Below the 1s venue poll cadence: collapses sibling reads without hiding a poll round. */
        const val SHARED_READ_TTL_MS: Long = 500L

        /** Under the state root; the daemon's journal retention sweeps the same directory. */
        const val TRANSPORT_JOURNAL_DIR: String = "mt5-transport-journal"

        const val API_KEY: String = "api_key"
    }
}
