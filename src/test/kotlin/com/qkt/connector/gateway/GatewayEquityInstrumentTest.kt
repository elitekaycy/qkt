package com.qkt.connector.gateway

import com.qkt.common.FixedClock
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.ConnectorContext
import com.qkt.instrument.TradeMode
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * A share a VGP gateway lists reaches qkt as one [com.qkt.instrument.InstrumentMeta], through the account's
 * instrument contract like an MT5 symbol's, and its venue category picks its session calendar.
 */
class GatewayEquityInstrumentTest {
    private val fake = FakeGateway(listOf("AAPL", "SPCE", "BTC_USDC-25DEC26"))
    private val context = ConnectorContext(null, mapOf("GW_KEY" to "secret"), FixedClock(5L))

    @AfterEach
    fun stop() = fake.shutdown()

    private fun share(
        code: String,
        trading: String? = null,
    ) = WireInstrument(
        code,
        "equity",
        "USD",
        "1",
        "0.01",
        "0.000000001",
        "0.000000001",
        category = "us_equity",
        trading = trading,
    )

    private fun account(calendars: List<Pair<String, String>> = emptyList()) =
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
                        tradingHours = calendars,
                    ),
                ),
                context,
            ).single()

    @Test
    fun `a listed share is a fractional, cent-ticked instrument with its category and trading mode`() {
        fake.instruments["AAPL"] = share("AAPL")
        fake.instruments["SPCE"] = share("SPCE", trading = "close_only")
        fake.instruments["BTC_USDC-25DEC26"] =
            WireInstrument(
                "BTC_USDC-25DEC26",
                "future",
                "USDC",
                "1",
                "2.5",
                "0.0001",
                "0.0001",
                expiry = 1_798_185_600_000L,
            )
        val account = account()

        val aapl = requireNotNull(account.instrumentSpec("ALPACA:AAPL")).meta
        assertThat(aapl.contractSize).isEqualByComparingTo("1")
        assertThat(aapl.volumeStep).isEqualByComparingTo("0.000000001")
        assertThat(aapl.pointSize).isEqualByComparingTo("0.01")
        assertThat(aapl.digits).isEqualTo(2)
        assertThat(aapl.currency).isEqualTo("USD")
        assertThat(aapl.category).isEqualTo("us_equity")
        assertThat(aapl.tradeMode).isEqualTo(TradeMode.FULL)
        assertThat(requireNotNull(account.instrumentSpec("ALPACA:SPCE")).meta.tradeMode).isEqualTo(TradeMode.CLOSE_ONLY)
        // A dated future needs exchange terms its listing does not carry: left to its contract catalog.
        assertThat(account.instrumentSpec("ALPACA:BTC_USDC-25DEC26")).isNull()
        assertThat(account.instrumentSpec("ALPACA:MISSING")).isNull()
    }

    @Test
    fun `a category rule in config gives every listed share its session, and a ticker rule still wins`() {
        fake.instruments["AAPL"] = share("AAPL")
        fake.instruments["SPCE"] = share("SPCE")
        val hours = account(listOf("SPCE" to "nyse", "category:us_equity" to "nyse[04:00-20:00]")).tradingHours

        assertThat(hours.calendarFor("AAPL").name).isEqualTo("nyse[04:00-20:00]")
        assertThat(hours.calendarFor("AAPL").isInSession("AAPL", Instant.parse("2026-10-26T23:30:00Z"))).isTrue()
        assertThat(hours.calendarFor("SPCE").name).isEqualTo("nyse")
    }

    @Test
    fun `a trading mode VGP does not define is refused by name, never read as tradable`() {
        assertThatThrownBy { tradeModeOf("sometimes") }.hasMessageContaining("'sometimes'")
        assertThat(listOf("long_only", "short_only", "close_only", "disabled").map(::tradeModeOf))
            .containsExactly(TradeMode.LONG_ONLY, TradeMode.SHORT_ONLY, TradeMode.CLOSE_ONLY, TradeMode.DISABLED)
    }
}
