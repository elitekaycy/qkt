package com.qkt.connector.gateway

import com.qkt.broker.BrokerFactory
import com.qkt.common.Clock
import com.qkt.common.SymbolCalendars
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.AccountProfile
import com.qkt.connectivity.AccountType
import com.qkt.connectivity.TradingAccount
import com.qkt.connectivity.transport.TransportJournal
import com.qkt.connectivity.transport.TransportJournalDirs
import com.qkt.instrument.ContractCatalogSource
import com.qkt.instrument.FundingRateSource
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.OptionCatalogSource
import com.qkt.instrument.OptionTerms
import com.qkt.instrument.withInstrumentCategories
import com.qkt.marketdata.depth.BookDepthSource
import com.qkt.marketdata.flow.PrintHistorySource
import com.qkt.marketdata.marks.MarkHistorySource
import com.qkt.marketdata.openinterest.OpenInterestSource
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.SymbolPattern
import java.nio.file.Path

/** One account on a VGP v1 gateway, opened by [GatewayConnector]. Nothing connects until it verifies or trades. */
class GatewayTradingAccount internal constructor(
    override val config: AccountConfig,
    private val settings: GatewaySettings,
    private val clock: Clock,
    private val strategies: () -> Set<String>,
    private val recording: GatewayChainRecording,
    private val instruments: InstrumentRegistry?,
    private val deploysUnderway: () -> Boolean,
    stateRoot: Path? = null,
) : TradingAccount {
    private val identity = GatewayIdentity(settings.adapter, settings.accountLogin, settings.tradeMode)

    /** The account's REST exchanges and stream events, for golden capture (`gateway-transport-journal/<LABEL>/`). */
    private val journal =
        stateRoot?.let { TransportJournal(it.resolve(TransportJournalDirs.GATEWAY), config.name.uppercase(), clock) }

    /** One rate-limit backoff for every client of this gateway, shared with other accounts on it (#1663). */
    private val throttle = GatewayThrottle.of(settings.url)
    private val client by lazy {
        GatewayClient(settings.url, settings.apiKey, settings.httpTimeoutMs, settings.retryAttempts, journal, throttle)
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
                    GatewayStream(settings.url, settings.apiKey, onEvent, onReset, onConnection, journal = journal)
                },
                label = config.name.uppercase(),
                sharedAccount = settings.sharedAccount,
                deploysUnderway = deploysUnderway,
            )
        }

    // `category:` rules read each instrument's venue category through this account's own instrument spec.
    override val tradingHours: SymbolCalendars =
        GatewaySettings.calendars(config).withInstrumentCategories(config.name) { instrumentSpec(it)?.meta }

    /**
     * A spot, equity or event instrument's spec from the venue's listing, found by its symbol (the code with `-`
     * written `_`); futures and options keep their catalog terms (null here).
     */
    override fun instrumentSpec(qktSymbol: String) =
        GatewaySymbols(config.symbolPrefix).let { symbols ->
            client.instruments().firstOrNull { symbols.qkt(it.code) == qktSymbol }?.instrumentSpec(qktSymbol)
        }

    private val quotes =
        GatewayMarketSource(
            config.symbolPrefix,
            settings.url,
            settings.apiKey,
            listing = { client.instruments() },
            recorderFor = recording::sinkFor,
            bars = client::bars,
            capabilities = { client.health().capabilities },
            prints = { code, kind, fromMs, toMs -> historyClient.prints(code, kind, fromMs, toMs) },
        )

    override val marketData: MarketSource = quotes

    override val contractCatalogs: ContractCatalogSource
        get() = GatewayContractCatalog(client, GatewaySymbols(config.symbolPrefix), clock)

    override val optionCatalogs: OptionCatalogSource
        get() = GatewayOptionCatalog(client, GatewaySymbols(config.symbolPrefix), clock)

    override val fundingRates: FundingRateSource = GatewayFundingRates(client, GatewaySymbols(config.symbolPrefix))

    /**
     * Histories read on a client of their own whose calls may take [MARKS_TIMEOUT_MS]: a page of marks can cost the
     * venue a call per window, and a page of liquidations a read of an hour of its tape.
     */
    private val historyClient by lazy {
        GatewayClient(
            settings.url,
            settings.apiKey,
            maxOf(settings.httpTimeoutMs, MARKS_TIMEOUT_MS),
            settings.retryAttempts,
            throttle = throttle,
        )
    }

    override val markHistory: MarkHistorySource
        get() = GatewayMarkHistory(historyClient, GatewaySymbols(config.symbolPrefix))

    override val printHistory: PrintHistorySource
        get() = GatewayPrintHistory(historyClient, GatewaySymbols(config.symbolPrefix))

    override val openInterest: OpenInterestSource =
        GatewayOpenInterest(client, GatewaySymbols(config.symbolPrefix), config.name)

    override val bookDepth: BookDepthSource = GatewayBookDepth(client, GatewaySymbols(config.symbolPrefix), config.name)

    override val marketDataPattern: SymbolPattern = SymbolPattern(quotes::supports)

    override val orderEntry: BrokerFactory = { bus, clock, prices, positions, strategyName ->
        GatewayBroker(opened.value, bus, clock, positions, strategyName, prices) { symbol ->
            (instruments?.lookup(symbol)?.derivative as? OptionTerms)?.tickSteps
        }
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

    /** The gateway spoken to directly, once it reports the identity the config expects. */
    override fun directVenue(): com.qkt.connectivity.DirectVenue {
        identity.mismatch(client.health())?.let { error("${config.name}: $it") }
        return GatewayDirectVenue(config.name, config.symbolPrefix, client, settings.url, settings.apiKey)
    }

    /** The holdings check that waited for the startup deploys judges now, if a strategy ever traded here. */
    override fun deploysFinished() {
        if (opened.isInitialized()) opened.value.deploysFinished()
    }

    /** Closes the account's gateway connection, if it was ever opened. */
    override fun close() {
        if (opened.isInitialized()) opened.value.close()
        recording.close()
        journal?.close()
    }
}

private const val MARKS_TIMEOUT_MS = 60_000L
