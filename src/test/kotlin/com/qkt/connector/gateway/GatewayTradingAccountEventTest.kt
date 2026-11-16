package com.qkt.connector.gateway

import com.qkt.common.FixedClock
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.AccountDirectory
import com.qkt.connectivity.ConnectorContext
import com.qkt.connectivity.ConnectorRegistry
import com.qkt.connector.gateway.EventWire.ROOT
import com.qkt.connector.gateway.EventWire.symbol
import com.qkt.instrument.EventTerms
import com.qkt.instrument.StandardInstrumentRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** A gateway account lists its event families live, and a session's registry reads them behind its own files. */
class GatewayTradingAccountEventTest {
    private val fake = FakeGateway(emptyList())
    private val context = ConnectorContext(null, mapOf("GW_KEY" to "secret"), FixedClock(5L))
    private val config =
        AccountConfig(
            "kalshi",
            "gateway",
            mapOf(
                "type" to "gateway",
                "gateway_url" to fake.url,
                "api_key" to "env:GW_KEY",
                "expected_adapter" to "fake",
                "expected_account_login" to "7",
                "expected_trade_mode" to "demo",
            ),
        )

    init {
        EventWire.list(fake, EventWire.ladder())
    }

    @AfterEach
    fun stop() = fake.shutdown()

    @Test
    fun `the account's event families resolve contracts the files do not catalog`() {
        AccountDirectory.open(listOf(config), ConnectorRegistry.discover(), context).use { accounts ->
            val registry = accounts.withEventFamilies(StandardInstrumentRegistry)

            assertThat(registry.events()?.family(ROOT)).isNotNull
            assertThat((registry.lookup(symbol(1, 3))?.derivative as EventTerms).event).isEqualTo("KXS-H1")
            val pattern = accounts.accounts.single().marketDataPattern
            assertThat(pattern.matches("CHAIN:KALSHI.KXS.overround.front")).isTrue()
        }
    }
}
