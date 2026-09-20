package com.qkt.connectivity

import com.qkt.broker.BrokerFactory
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.SymbolPattern

/**
 * Every trading account configured on this process, opened through its connector.
 *
 * The one lookup the rest of qkt uses: by account name, or by the prefix a strategy symbol carries
 * (`PROP_S01:XAUUSD` is served by the account named `prop_s01`). Accounts keep config order.
 */
class AccountDirectory private constructor(
    /** Every account, in config order. */
    val accounts: List<TradingAccount>,
) : AutoCloseable {
    private val byLowerName: Map<String, TradingAccount> = accounts.associateBy { it.config.name.lowercase() }

    /** The account named [name], ignoring case. */
    fun byName(name: String): TradingAccount? = byLowerName[name.lowercase()]

    /** The account serving [qktSymbol]'s prefix, or null for an unprefixed or unknown symbol. */
    fun forSymbol(qktSymbol: String): TradingAccount? {
        val prefix = qktSymbol.substringBefore(':', missingDelimiterValue = "")
        return if (prefix.isEmpty()) null else byName(prefix)
    }

    /** Each account's order-entry factory, keyed by lower-case account name. */
    fun orderEntry(): Map<String, BrokerFactory> = accounts.associate { it.config.name.lowercase() to it.orderEntry }

    /** One prefix route per account that supplies market data, in config order. */
    fun marketDataRoutes(): List<Pair<SymbolPattern, MarketSource>> =
        accounts.mapNotNull { account ->
            account.marketData?.let { SymbolPattern.prefix(account.symbolPrefix) to it }
        }

    /** The trading hours governing [qktSymbol] on its account, or null when no account serves it. */
    fun tradingHoursFor(qktSymbol: String): TradingCalendar? =
        forSymbol(qktSymbol)?.tradingHours?.calendarFor(qktSymbol.substringAfter(':'))

    /** Verifies every account in config order; the first failure throws, so nothing trades. */
    fun verifyAll(): List<Pair<TradingAccount, AccountProfile>> = accounts.map { it to it.verify() }

    /** Closes every account, continuing past failures. */
    override fun close() {
        accounts.forEach { runCatching { it.close() } }
    }

    companion object {
        /**
         * Opens [configs] through [registry]. A missing or unknown `type`, or a setting the entry's
         * connector does not declare, is refused before any connector runs. If a connector fails, accounts already
         * opened are closed before the failure propagates.
         */
        fun open(
            configs: List<AccountConfig>,
            registry: ConnectorRegistry,
            context: ConnectorContext,
        ): AccountDirectory {
            for (cfg in configs) {
                require(cfg.type.isNotBlank()) {
                    "brokers.${cfg.name} has no type; set type to one of: ${registry.types.joinToString()}"
                }
                val connector =
                    requireNotNull(registry.find(cfg.type)) {
                        "brokers.${cfg.name} has type '${cfg.type}', which no installed connector provides " +
                            "(installed: ${registry.types.joinToString()})"
                    }
                AccountSettings.requireKnown(cfg.name, cfg.settings.keys, connector.spec.settings)
            }
            val opened = linkedMapOf<String, TradingAccount>()
            try {
                for ((type, group) in configs.groupBy { it.type }) {
                    val connector = registry.find(type) ?: error("connector for '$type' disappeared")
                    val accounts = connector.open(group, context)
                    accounts.forEach { opened[it.config.name] = it }
                    check(accounts.map { it.config.name } == group.map { it.name }) {
                        "connector '$type' must open exactly the accounts it was given, in order"
                    }
                }
            } catch (e: Exception) {
                opened.values.forEach { runCatching { it.close() } }
                throw e
            }
            return AccountDirectory(configs.map { opened.getValue(it.name) })
        }
    }
}
