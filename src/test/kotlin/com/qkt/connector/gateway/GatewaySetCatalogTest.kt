package com.qkt.connector.gateway

import com.qkt.common.FixedClock
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.ConnectorContext
import com.qkt.instrument.universe.CatalogField
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** A gateway account's listing as the catalog a live filtered symbol set resolves its members from. */
class GatewaySetCatalogTest {
    private val fake = FakeGateway(listOf("AAPL", "EURUSD", "BTC_USDC-25DEC26", "BTC-27DEC26-100000-C"))
    private val context = ConnectorContext(null, mapOf("GW_KEY" to "secret"), FixedClock(5L))

    @AfterEach
    fun stop() = fake.shutdown()

    private fun listed(
        code: String,
        kind: String,
        category: String? = null,
    ) = WireInstrument(code, kind, "USD", "1", "0.01", "0.01", "0.01", category = category)

    private fun account() =
        GatewayConnector()
            .open(
                listOf(
                    AccountConfig(
                        "alpaca",
                        "gateway",
                        mapOf(
                            "type" to "gateway",
                            "gateway_url" to fake.url,
                            "api_key" to "env:GW_KEY",
                            "expected_adapter" to "fake",
                            "expected_account_login" to "7",
                            "expected_trade_mode" to "demo",
                        ),
                    ),
                ),
                context,
            ).single()

    private fun seed() {
        fake.instruments["AAPL"] = listed("AAPL", "equity", "us_equity")
        fake.instruments["EURUSD"] = listed("EURUSD", "spot", "forex")
        fake.instruments["BTC_USDC-25DEC26"] =
            listed("BTC_USDC-25DEC26", "future").copy(expiry = 1_798_185_600_000L)
    }

    @Test
    fun `spot, equity and event instruments are rows, an equity reads as one, and the rest is named as left out`() {
        seed()
        val catalog = requireNotNull(account().instrumentCatalog(null))
        assertThat(catalog.rows.map { it.qktSymbol }).containsExactly("ALPACA:AAPL", "ALPACA:EURUSD")
        assertThat(catalog.rows.map { CatalogField.KIND.read(it, 0L)?.canonical })
            .containsExactly("\"equity\"", "\"spot\"")
        assertThat(catalog.rows.map { it.category }).containsExactly("us_equity", "forex")
        assertThat(catalog.omitted)
            .isEqualTo("left out: 1 future, 1 option (a listing carries no exchange terms for them)")
    }

    @Test
    fun `a kind the gateway can filter by narrows the read, and any other kind reads the whole listing`() {
        seed()
        val account = account()
        val equities = requireNotNull(account.instrumentCatalog("equity"))
        assertThat(equities.rows.map { it.qktSymbol }).containsExactly("ALPACA:AAPL")
        assertThat(equities.omitted).isNull()
        val whole = requireNotNull(account.instrumentCatalog("future"))
        assertThat(whole.rows.map { it.qktSymbol }).containsExactly("ALPACA:AAPL", "ALPACA:EURUSD")
    }

    @Test
    fun `a listing of only rows leaves nothing out`() {
        val catalog = gatewayCatalog(GatewaySymbols("ALPACA:"), listOf(listed("EURUSD", "spot")))
        assertThat(catalog.rows.single().qktSymbol).isEqualTo("ALPACA:EURUSD")
        assertThat(catalog.omitted).isNull()
    }
}
