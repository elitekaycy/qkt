package com.qkt.connectivity

import com.qkt.broker.BrokerFactory
import com.qkt.broker.LogBroker
import com.qkt.common.SymbolCalendars
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.NullMarketSource

/** A connector for directory tests: records what it was asked to open and close. */
class FakeConnector(
    type: String,
    private val hours: TradingCalendar = TradingCalendar.fxDefault(),
    private val suppliesMarketData: Boolean = true,
) : Connector {
    override val spec = ConnectorSpec(type, "Fake $type", setOf(ProductType.CFD))
    val openCalls = mutableListOf<List<String>>()
    val closed = mutableListOf<String>()

    override fun open(
        accounts: List<AccountConfig>,
        context: ConnectorContext,
    ): List<TradingAccount> {
        openCalls += accounts.map { it.name }
        return accounts.map { cfg ->
            object : TradingAccount {
                override val config = cfg

                override fun verify() =
                    AccountProfile(cfg.name, "id-${cfg.name}", "srv", AccountType.DEMO, "USD", 100, "${cfg.name}: fake")

                override val orderEntry: BrokerFactory = { bus, clock, _, _, _ -> LogBroker(bus, clock) }
                override val marketData: MarketSource? = if (suppliesMarketData) NullMarketSource else null
                override val tradingHours = SymbolCalendars(emptyList(), hours)

                override fun close() {
                    closed += cfg.name
                }
            }
        }
    }
}
