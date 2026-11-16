package com.qkt.connector.mt5

import com.qkt.broker.CloseReason
import com.qkt.broker.VenueAccountId
import com.qkt.broker.VenueInstrument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** How an MT5 account names itself, its positions and its instruments. */
class MT5VenueDescriptionTest {
    private val gateway = Mt5RoutedGateway()
    private val client = MT5Client(gateway.url, MT5ServerTimeZone.UTC, httpTimeoutMs = 2_000L, retryAttempts = 0)

    @AfterEach
    fun stop() = gateway.close()

    private fun description(
        login: Long? = null,
        server: String? = null,
    ) = MT5VenueDescription(
        MT5BrokerProfile(
            name = "exness",
            gatewayUrl = gateway.url,
            symbolPolicy = SymbolPolicy(suffix = "m"),
            magic = 1,
            expectedAccountLogin = login,
            expectedAccountServer = server,
        ),
        client,
    )

    @Test
    fun `before any account read the identity comes from the profile's expected account`() {
        val unknown = description().venueIdentity()
        val expected = description(login = 55L, server = "ICMarkets-Live7").venueIdentity()

        assertThat(unknown.connector).isEqualTo("mt5")
        assertThat(unknown.adapter).isEqualTo("mt5")
        assertThat(unknown.label).isEqualTo("EXNESS")
        assertThat(unknown.accountId).isNull()
        assertThat(unknown.tradeMode).isNull()
        assertThat(expected.adapter).isEqualTo("icmarkets")
        assertThat(expected.accountId).isEqualTo(VenueAccountId.mt5("55", "ICMarkets-Live7"))
    }

    @Test
    fun `the account the client last read names the venue over the profile`() {
        gateway.on("/account", """{"login":77,"server":"Exness-MT5Trial9","trade_mode":0}""")
        client.getAccount()

        val identity = description(login = 55L, server = "ICMarkets-Live7").venueIdentity()

        assertThat(identity.adapter).isEqualTo("exness")
        assertThat(identity.accountId).isEqualTo(VenueAccountId.mt5("77", "Exness-MT5Trial9"))
        assertThat(identity.tradeMode).isEqualTo("demo")
    }

    @Test
    fun `an account read that names no login or server falls back to the profile`() {
        gateway.on("/account", "{}")
        client.getAccount()

        val identity = description(login = 55L, server = "ICMarkets-Live7").venueIdentity()

        assertThat(identity.accountId).isEqualTo(VenueAccountId.mt5("55", "ICMarkets-Live7"))
        assertThat(identity.tradeMode).`as`("-1 is no MT5 trade mode").isNull()
    }

    @Test
    fun `an identity needs both a login and a server for an account id`() {
        assertThat(MT5VenueDescription.identity("p", "1", null).accountId).isNull()
        assertThat(MT5VenueDescription.identity("p", "1", " ").accountId).isNull()
        assertThat(MT5VenueDescription.identity("p", null, "Exness-Real").accountId).isNull()
        assertThat(MT5VenueDescription.identity("p", "1", "-Real").adapter).isEqualTo("mt5")
        assertThat(MT5VenueDescription.identity("p", "1", "Exness-Real", 2).tradeMode).isEqualTo("real")
        assertThat(MT5VenueDescription.identity("p", "1", "Exness-Real", 9).tradeMode).isNull()
    }

    @Test
    fun `a fill's ticket is its position id unless it is missing or blank`() {
        val description = description()

        assertThat(description.fillPositionId("9001")).isEqualTo("9001")
        assertThat(description.fillPositionId(" ")).isNull()
        assertThat(description.fillPositionId(null)).isNull()
    }

    @Test
    fun `only the profile's own symbols are its instruments`() {
        val description = description()

        assertThat(description.instrument("EXNESS:XAUUSD")).isEqualTo(VenueInstrument("XAUUSDm", kind = "cfd"))
        assertThat(description.instrument("OANDA:XAUUSD")).isNull()
    }

    @Test
    fun `a closing deal's reason names why the position closed`() {
        assertThat(MT5VenueDescription.closeReason(6, null)).isEqualTo(CloseReason.STOP_OUT)
        assertThat(listOf(0, 1, 2).map { MT5VenueDescription.closeReason(it, "x-sl") }).containsOnly(CloseReason.MANUAL)
        assertThat(MT5VenueDescription.closeReason(3, "s-tp")).isEqualTo(CloseReason.TAKE_PROFIT)
        assertThat(MT5VenueDescription.closeReason(3, "s-entry")).isEqualTo(CloseReason.SIGNAL)
        assertThat(MT5VenueDescription.closeReason(null, null)).isEqualTo(CloseReason.SIGNAL)
        assertThat(MT5VenueDescription.closeReason(null, "s-close-r2")).isEqualTo(CloseReason.TIMED)
        assertThat(MT5VenueDescription.closeReason(42, "s-sl")).isNull()
    }
}
