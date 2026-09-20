package com.qkt.connectivity

import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AccountDirectoryTest {
    private val context = ConnectorContext(stateRoot = null, env = emptyMap(), clock = SystemClock())

    private fun acct(
        name: String,
        type: String,
    ) = AccountConfig(name, type, if (type.isBlank()) emptyMap() else mapOf("type" to type))

    @Test
    fun `each connector opens all of its accounts in one call, in config order`() {
        val mt5 = FakeConnector("mt5")
        val bybit = FakeConnector("bybit", TradingCalendar.crypto())

        val dir =
            AccountDirectory.open(
                listOf(acct("a", "mt5"), acct("bybit_spot", "bybit"), acct("b", "mt5")),
                ConnectorRegistry(listOf(mt5, bybit)),
                context,
            )

        assertThat(mt5.openCalls).containsExactly(listOf("a", "b"))
        assertThat(bybit.openCalls).containsExactly(listOf("bybit_spot"))
        assertThat(dir.accounts.map { it.config.name }).containsExactly("a", "bybit_spot", "b")
    }

    @Test
    fun `an unknown or missing type is refused, naming the installed types`() {
        val registry = ConnectorRegistry(listOf(FakeConnector("mt5")))

        assertThatThrownBy { AccountDirectory.open(listOf(acct("x", "rithmic")), registry, context) }
            .hasMessageContaining("brokers.x")
            .hasMessageContaining("rithmic")
            .hasMessageContaining("mt5")
        assertThatThrownBy { AccountDirectory.open(listOf(acct("y", "")), registry, context) }
            .hasMessageContaining("brokers.y")
            .hasMessageContaining("no type")
    }

    @Test
    fun `lookups go by account name and by strategy symbol prefix`() {
        val dir =
            AccountDirectory.open(
                listOf(acct("prop_s01", "mt5"), acct("bybit_spot", "bybit")),
                ConnectorRegistry(listOf(FakeConnector("mt5"), FakeConnector("bybit", TradingCalendar.crypto()))),
                context,
            )

        assertThat(dir.byName("PROP_S01")?.config?.name).isEqualTo("prop_s01")
        assertThat(dir.forSymbol("PROP_S01:XAUUSD")?.config?.name).isEqualTo("prop_s01")
        assertThat(dir.forSymbol("OTHER:XAUUSD")).isNull()
        assertThat(dir.forSymbol("XAUUSD")).isNull()
        assertThat(dir.orderEntry().keys).containsExactly("prop_s01", "bybit_spot")
        assertThat(dir.tradingHoursFor("BYBIT_SPOT:BTCUSDT")?.name).isEqualTo("crypto")
        assertThat(dir.tradingHoursFor("NOPE:BTCUSDT")).isNull()
        assertThat(dir.marketDataRoutes().map { it.first.matches("PROP_S01:XAUUSD") }).containsExactly(true, false)
    }

    @Test
    fun `accounts without their own feed contribute no market data route`() {
        val dir =
            AccountDirectory.open(
                listOf(acct("a", "mt5")),
                ConnectorRegistry(listOf(FakeConnector("mt5", suppliesMarketData = false))),
                context,
            )

        assertThat(dir.marketDataRoutes()).isEmpty()
    }

    @Test
    fun `verifyAll returns every profile and close closes every account`() {
        val mt5 = FakeConnector("mt5")
        val dir =
            AccountDirectory.open(
                listOf(acct("a", "mt5"), acct("b", "mt5")),
                ConnectorRegistry(listOf(mt5)),
                context,
            )

        assertThat(dir.verifyAll().map { it.second.description }).containsExactly("a: fake", "b: fake")
        dir.close()
        assertThat(mt5.closed).containsExactly("a", "b")
    }

    @Test
    fun `two connectors claiming one type is refused`() {
        assertThatThrownBy { ConnectorRegistry(listOf(FakeConnector("mt5"), FakeConnector("mt5"))) }
            .hasMessageContaining("mt5")
    }
}
