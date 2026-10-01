package com.qkt.connector.gateway

import com.qkt.broker.BrokerFactory
import com.qkt.common.Clock
import com.qkt.common.SymbolCalendars
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.AccountProfile
import com.qkt.connectivity.AccountType
import com.qkt.connectivity.Connector
import com.qkt.connectivity.ConnectorContext
import com.qkt.connectivity.ConnectorSpec
import com.qkt.connectivity.ProductType
import com.qkt.connectivity.TradingAccount
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.SymbolPattern

/**
 * Any venue served by a VGP v1 gateway (`docs/superpowers/specs/2026-10-01-vgp-v1-wire.md`): futures,
 * perpetuals, spot and options. Each `type: gateway` entry is one account on one gateway; several
 * strategies may share it through one [GatewaySession] (fills are attributed by client order id, and a
 * settlement closes each strategy's own holding). See [GatewaySettings] for the entry.
 */
class GatewayConnector : Connector {
    override val spec: ConnectorSpec =
        ConnectorSpec(
            type = "gateway",
            displayName = "VGP gateway",
            productTypes = setOf(ProductType.FUTURE, ProductType.PERPETUAL, ProductType.SPOT, ProductType.OPTION),
            settings = GatewaySettings.KEYS,
        )

    override fun open(
        accounts: List<AccountConfig>,
        context: ConnectorContext,
    ): List<TradingAccount> =
        accounts.map { account ->
            val settings = GatewaySettings.of(account, context)
            GatewayTradingAccount(
                account,
                settings,
                context.clock,
                { context.strategiesTrading(account.name).toSet() },
                GatewayChainRecording(context.instruments, settings.chainSnapshotMs),
            )
        }
}

/** One account on a VGP v1 gateway, opened by [GatewayConnector]. Nothing connects until it verifies or trades. */
class GatewayTradingAccount internal constructor(
    override val config: AccountConfig,
    private val settings: GatewaySettings,
    private val clock: Clock,
    private val strategies: () -> Set<String>,
    private val recording: GatewayChainRecording,
) : TradingAccount {
    private val identity = GatewayIdentity(settings.adapter, settings.accountLogin, settings.tradeMode)
    private val client by lazy {
        GatewayClient(settings.url, settings.apiKey, settings.httpTimeoutMs, settings.retryAttempts)
    }
    private val opened =
        lazy {
            GatewaySession(
                client,
                GatewaySymbols(config.symbolPrefix),
                clock,
                identity,
                strategies,
                streamFactory = { onEvent, onReset, onConnection ->
                    GatewayStream(settings.url, settings.apiKey, onEvent, onReset, onConnection)
                },
            )
        }

    override val tradingHours: SymbolCalendars = GatewaySettings.calendars(config)

    private val quotes =
        GatewayMarketSource(
            config.symbolPrefix,
            settings.url,
            settings.apiKey,
            listing = { client.instruments() },
            recorderFor = recording::sinkFor,
            bars = client::bars,
        )

    override val marketData: MarketSource = quotes

    override val marketDataPattern: SymbolPattern = SymbolPattern(quotes::supports)

    override val orderEntry: BrokerFactory = { bus, clock, _, positions, strategyName ->
        GatewayBroker(opened.value, bus, clock, positions, strategyName)
    }

    /** Checks the gateway before anything trades: it must speak `vgp1` and report the expected identity. */
    override fun verify(): AccountProfile {
        val health = client.health()
        identity.mismatch(health)?.let { error("${config.name}: $it") }
        val account = client.account()
        return AccountProfile(
            accountName = config.name,
            accountId = health.accountLogin,
            server = settings.url,
            type = if (health.tradeMode == "real") AccountType.LIVE else AccountType.DEMO,
            currency = account.currency,
            leverage = null,
            description =
                "${config.name}: gateway ${health.adapter} ${health.adapterVersion} account ${health.accountLogin} " +
                    "(${health.tradeMode})${if (health.venueConnected) "" else ", venue disconnected"}",
        )
    }

    /** Closes the account's gateway connection, if it was ever opened. */
    override fun close() {
        if (opened.isInitialized()) opened.value.close()
        recording.close()
    }
}
