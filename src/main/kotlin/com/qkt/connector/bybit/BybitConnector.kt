package com.qkt.connector.bybit

import com.qkt.broker.BrokerFactory
import com.qkt.common.SymbolCalendars
import com.qkt.common.TradingCalendar
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.AccountProfile
import com.qkt.connectivity.AccountType
import com.qkt.connectivity.Connector
import com.qkt.connectivity.ConnectorContext
import com.qkt.connectivity.ConnectorSpec
import com.qkt.connectivity.ProductType
import com.qkt.connectivity.Secret
import com.qkt.connectivity.TradingAccount
import com.qkt.connector.bybit.linear.BybitLinearBroker
import com.qkt.connector.bybit.marketdata.BybitLinearMarketSource
import com.qkt.connector.bybit.marketdata.BybitSpotMarketSource
import com.qkt.connector.bybit.spot.BybitSpotBroker
import com.qkt.marketdata.source.MarketSource

/** Resolved credentials and endpoint for one Bybit login. Accounts with equal credentials share a client. */
data class BybitCredentials(
    val apiKey: Secret,
    val apiSecret: Secret,
    val testnet: Boolean,
    val recvWindowMs: Long,
    val accountType: String,
)

/**
 * Bybit v5 — spot and USDT perpetual (linear) accounts.
 *
 * Each `type: bybit` entry is one product category on a Bybit login:
 *
 * ```yaml
 * bybit_linear:
 *   type: bybit
 *   category: linear            # spot | linear
 *   api_key: env:BYBIT_API_KEY
 *   api_secret: env:BYBIT_API_SECRET
 *   testnet: "true"             # default; "false" trades mainnet
 * ```
 *
 * The Bybit brokers serve the fixed `BYBIT_SPOT:` / `BYBIT_LINEAR:` prefixes, so an entry must be
 * named after its category (`bybit_spot`, `bybit_linear`). Entries with the same credentials and
 * endpoint share one [BybitClient], built the first time an account trades or verifies.
 */
class BybitConnector(
    private val clientFactory: (BybitCredentials) -> BybitClient = { c ->
        BybitClient(c.apiKey.reveal(), c.apiSecret.reveal(), c.testnet, c.recvWindowMs, c.accountType)
    },
) : Connector {
    override val spec: ConnectorSpec =
        ConnectorSpec(
            type = "bybit",
            displayName = "Bybit",
            productTypes = setOf(ProductType.SPOT, ProductType.PERPETUAL),
        )

    override fun open(
        accounts: List<AccountConfig>,
        context: ConnectorContext,
    ): List<TradingAccount> {
        val shared = mutableMapOf<BybitCredentials, SharedBybitClient>()
        return accounts.map { cfg ->
            val category = categoryOf(cfg)
            val credentials = credentialsOf(cfg, context)
            BybitTradingAccount(
                cfg,
                category,
                shared.getOrPut(credentials) { SharedBybitClient { clientFactory(credentials) } },
            )
        }
    }

    private fun categoryOf(cfg: AccountConfig): String {
        val category = cfg.setting("category") ?: error("brokers.${cfg.name}.category is required: spot or linear")
        require(category in CATEGORIES) { "brokers.${cfg.name}.category must be spot or linear, got '$category'" }
        val expectedName = "bybit_$category"
        require(cfg.name.equals(expectedName, ignoreCase = true)) {
            "Bybit $category account must be named '$expectedName' " +
                "(it serves the ${expectedName.uppercase()}: prefix); got '${cfg.name}'"
        }
        return category
    }

    private fun credentialsOf(
        cfg: AccountConfig,
        context: ConnectorContext,
    ): BybitCredentials =
        BybitCredentials(
            apiKey = requiredSecret(cfg, context, "api_key"),
            apiSecret = requiredSecret(cfg, context, "api_secret"),
            testnet = cfg.setting("testnet")?.equals("false", ignoreCase = true) != true,
            recvWindowMs = cfg.setting("recv_window_ms")?.toLong() ?: DEFAULT_RECV_WINDOW_MS,
            accountType = cfg.setting("account_type") ?: DEFAULT_ACCOUNT_TYPE,
        )

    /** A credential that must be present and non-empty; an empty one would only fail later as a connect timeout. */
    private fun requiredSecret(
        cfg: AccountConfig,
        context: ConnectorContext,
        field: String,
    ): Secret {
        val secret = context.secrets.resolve(cfg, field) ?: error("brokers.${cfg.name}.$field is required")
        require(secret.reveal().isNotBlank()) { "brokers.${cfg.name}.$field resolved to an empty value" }
        return secret
    }

    private companion object {
        val CATEGORIES = setOf("spot", "linear")
        const val DEFAULT_RECV_WINDOW_MS: Long = 5_000L
        const val DEFAULT_ACCOUNT_TYPE: String = "UNIFIED"
    }
}

/** One Bybit client shared by the accounts that use it: built on first use, closed with the last account. */
internal class SharedBybitClient(
    private val build: () -> BybitClient,
) {
    private var client: BybitClient? = null
    private var users = 0

    @Synchronized
    fun acquire(): BybitClient = client ?: build().also { client = it }

    @Synchronized
    fun join() {
        users++
    }

    @Synchronized
    fun leave() {
        users--
        if (users <= 0) {
            client?.let { runCatching { it.close() } }
            client = null
        }
    }
}

/** One Bybit product category on one login, opened by [BybitConnector]. */
class BybitTradingAccount internal constructor(
    override val config: AccountConfig,
    private val category: String,
    private val shared: SharedBybitClient,
) : TradingAccount {
    private var closed = false

    init {
        shared.join()
    }

    /** The login's client; built on first call and shared with the account's sibling category. */
    internal fun client(): BybitClient = shared.acquire()

    override val tradingHours: SymbolCalendars = SymbolCalendars(emptyList(), TradingCalendar.crypto())

    override val marketData: MarketSource by lazy {
        if (category == "spot") BybitSpotMarketSource() else BybitLinearMarketSource()
    }

    override val orderEntry: BrokerFactory =
        if (category == "spot") {
            { bus, clock, _, _, _ -> BybitSpotBroker(client(), bus, clock) }
        } else {
            { bus, clock, _, positions, _ -> BybitLinearBroker(client(), bus, clock, positions) }
        }

    /** Opens the private connection; a failure stops startup rather than trading on a dead link. */
    override fun verify(): AccountProfile {
        val client = client()
        client.connect()
        val testnet = client.restBaseUrl.contains("testnet")
        return AccountProfile(
            accountName = config.name,
            accountId = config.name,
            server = client.restBaseUrl,
            type = if (testnet) AccountType.DEMO else AccountType.LIVE,
            currency = null,
            leverage = null,
            description =
                "${config.name}: bybit category=$category ${if (testnet) "testnet" else "mainnet"} " +
                    "account=${client.accountType}",
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        shared.leave()
    }
}
