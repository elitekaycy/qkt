package com.qkt.connectivity

import com.qkt.broker.BrokerFactory
import com.qkt.broker.LogBroker
import com.qkt.common.SymbolCalendars
import com.qkt.common.SystemClock
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.openinterest.OpenInterest
import com.qkt.marketdata.openinterest.OpenInterestSource
import com.qkt.marketdata.openinterest.OpenInterestSymbol
import com.qkt.marketdata.source.MarketSource
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The open-interest route reads from the account a contract names, and says which account cannot. */
class AccountDirectoryOpenInterestTest {
    private val reads = OpenInterestSource { _, _, _ -> emptyList<OpenInterest>() }

    private val connector =
        object : Connector {
            override val spec = ConnectorSpec("oi", "OI", setOf(ProductType.CFD), settings = setOf("gateway_url"))

            override fun open(
                accounts: List<AccountConfig>,
                context: ConnectorContext,
            ): List<TradingAccount> =
                accounts.map { cfg ->
                    object : TradingAccount {
                        override val config = cfg
                        override val openInterest: OpenInterestSource? = reads.takeIf { cfg.name == "reader" }

                        override fun verify() =
                            AccountProfile(cfg.name, "id", "srv", AccountType.DEMO, "USD", 100, cfg.name)

                        override val orderEntry: BrokerFactory = { bus, clock, _, _, _ -> LogBroker(bus, clock) }
                        override val marketData: MarketSource? = null
                        override val tradingHours = SymbolCalendars(emptyList(), TradingCalendar.fxDefault())

                        override fun close() {}
                    }
                }
        }

    private fun account(name: String) = AccountConfig(name, "oi", mapOf("type" to "oi"))

    private val directory =
        AccountDirectory.open(
            listOf(account("reader"), account("mute")),
            ConnectorRegistry(listOf(connector)),
            ConnectorContext(stateRoot = null, env = emptyMap(), clock = SystemClock()),
        )
    private val range = TimeRange(Instant.ofEpochMilli(0), Instant.ofEpochMilli(1_000))

    @Test
    fun `an account that does not read open interest is named when its contract is asked for`() {
        val route = directory.marketDataRoutes().single().second

        assertThat(route.ticks(OpenInterestSymbol.of("reader:BTC"), range).toList()).isEmpty()
        assertThatThrownBy { route.ticks(OpenInterestSymbol.of("mute:BTC"), range).toList() }
            .hasMessage("mute (type oi) does not read open interest")
    }
}
