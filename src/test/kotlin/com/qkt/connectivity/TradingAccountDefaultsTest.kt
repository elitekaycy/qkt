package com.qkt.connectivity

import com.qkt.common.SystemClock
import com.qkt.instrument.StandardInstrumentRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TradingAccountDefaultsTest {
    private val context = ConnectorContext(stateRoot = null, env = emptyMap(), clock = SystemClock())

    private fun acct(name: String) = AccountConfig(name, "mt5", mapOf("type" to "mt5"))

    @Test
    fun `an account reads no optional venue data unless its connector provides it`() {
        val account = FakeConnector("mt5").open(listOf(acct("prop")), context).single()

        assertThat(account.instrumentSpec("PROP:XAUUSD")).isNull()
        assertThat(account.contractCatalogs).isNull()
        assertThat(account.optionCatalogs).isNull()
        assertThat(account.fundingRates).isNull()
        assertThat(account.markHistory).isNull()
        assertThat(account.printHistory).isNull()
        assertThat(account.eventFamilies).isNull()
        assertThatThrownBy { account.directVenue() }
            .isInstanceOf(UnsupportedOperationException::class.java)
            .hasMessageContaining("brokers.prop (type mt5) cannot be driven one-shot")
    }

    @Test
    fun `a session's registry gains no event layer when no account lists event families`() {
        val accounts =
            AccountDirectory.open(listOf(acct("prop")), ConnectorRegistry(listOf(FakeConnector("mt5"))), context)

        assertThat(accounts.withEventFamilies(StandardInstrumentRegistry)).isSameAs(StandardInstrumentRegistry)
    }

    @Test
    fun `a connector knows no trading hours before opening by default`() {
        assertThat(FakeConnector("mt5").tradingHours(listOf(acct("prop")))).isEmpty()
    }

    @Test
    fun `a connector that opens accounts out of order is refused and its accounts are closed`() {
        val inner = FakeConnector("mt5")
        val reversing =
            object : Connector {
                override val spec = inner.spec

                override fun open(
                    accounts: List<AccountConfig>,
                    context: ConnectorContext,
                ): List<TradingAccount> = inner.open(accounts, context).reversed()
            }

        assertThatThrownBy {
            AccountDirectory.open(listOf(acct("a"), acct("b")), ConnectorRegistry(listOf(reversing)), context)
        }.hasMessageContaining("connector 'mt5' must open exactly the accounts it was given, in order")
        assertThat(inner.closed).containsExactlyInAnyOrder("a", "b")
    }
}
