package com.qkt.cli

import com.qkt.common.SystemClock
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.AccountDirectory
import com.qkt.connectivity.ConnectorContext
import com.qkt.connectivity.ConnectorRegistry
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.SymbolPattern
import java.nio.file.Path

/**
 * Every `brokers:` entry as a connector-neutral [AccountConfig], in file order, with its nested
 * blocks attached. Nothing here knows which connector an entry uses.
 */
fun Config.accountConfigs(): List<AccountConfig> =
    brokers.map { (name, fields) ->
        AccountConfig(
            name = name,
            type = fields["type"].orEmpty(),
            settings = fields,
            tradingHours = brokerCalendars[name].orEmpty(),
            symbolAliases = brokerAliases[name].orEmpty(),
            disabledOrderTypes = brokerCapabilityRestrictions[name].orEmpty(),
            instrumentOverrides = brokerInstrumentOverrides[name].orEmpty(),
        )
    }

/**
 * Opens every configured account through the installed connectors. [stateRoot] is where
 * connectors keep files (null for commands that keep none); [strategiesTrading] answers which
 * deployed strategies trade an account (empty outside the daemon). Throws on a missing or
 * unknown `type`, or on an entry its connector rejects.
 */
internal fun Config.openAccounts(
    stateRoot: Path? = null,
    strategiesTrading: (accountName: String) -> List<String> = { emptyList() },
): AccountDirectory =
    AccountDirectory.open(
        accountConfigs(),
        ConnectorRegistry.discover(),
        ConnectorContext(
            stateRoot = stateRoot,
            env = System.getenv(),
            clock = SystemClock(),
            strategiesTrading = strategiesTrading,
        ),
    )

/**
 * Price routes from every configured account that supplies its own feed, for commands that read
 * prices but place no orders (paper trading): accounts are opened, never verified. A config the
 * connectors reject yields no routes and a warning, as a missing MT5 profile always did.
 */
internal fun Config.accountMarketDataRoutes(): List<Pair<SymbolPattern, MarketSource>> =
    try {
        openAccounts().marketDataRoutes()
    } catch (e: Exception) {
        println("[WARN] broker account load failed: ${e.message}")
        emptyList()
    }
