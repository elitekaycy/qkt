package com.qkt.connector.mt5

import com.qkt.common.SymbolCalendars
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.AccountType
import com.qkt.connectivity.ConnectorContext
import com.qkt.marketdata.source.PrefixRemapMarketSource
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class Mt5ConnectorTest {
    private val context = ConnectorContext(stateRoot = null, env = emptyMap(), clock = SystemClock())

    private fun acct(
        name: String,
        magic: Int,
        gateway: String = "http://gw:5001",
    ) = AccountConfig(
        name = name,
        type = "mt5",
        settings = mapOf("type" to "mt5", "extends" to "exness", "gateway_url" to gateway, "magic" to "$magic"),
    )

    @Test
    fun `profiles match the legacy loader field for field`() {
        val accounts = listOf(acct("exness_s0", 1001), acct("exness_s1", 1002))

        val opened = Mt5Connector().open(accounts, context).map { (it as Mt5TradingAccount).profile }
        val legacy =
            MT5BrokerProfileLoader().load(
                raw = accounts.associate { it.name to it.settings },
                defaults = MT5DefaultProfiles.all,
                env = emptyMap(),
            )

        assertThat(opened).containsExactlyInAnyOrderElementsOf(legacy)
    }

    @Test
    fun `accounts on one feed share a market data source, others get their own`() {
        val opened =
            Mt5Connector().open(
                listOf(acct("a", 1), acct("b", 2), acct("c", 3, gateway = "http://other:5001")),
                context,
            )

        val (a, b, c) = opened.map { it.marketData }
        assertThat(b).isInstanceOf(PrefixRemapMarketSource::class.java)
        assertThat(a).isNotInstanceOf(PrefixRemapMarketSource::class.java)
        assertThat(c).isNotInstanceOf(PrefixRemapMarketSource::class.java).isNotSameAs(a)
        assertThat(b?.supports("B:XAUUSD")).isTrue()
    }

    @Test
    fun `verify maps the venue account and keeps the operator line byte-identical`() {
        val info =
            MT5AccountInfo(
                balance = BigDecimal("50000"),
                equity = BigDecimal("48497.80"),
                currency = "USD",
                leverage = 100,
                marginMode = MARGIN_MODE_HEDGING,
                login = 26645824L,
                server = "FivePercentOnline-Real",
                tradeMode = MT5TradeMode.REAL.wireValue,
            )
        val account = Mt5Connector(accountFetcher = { info }).open(listOf(acct("prop_s01", 7)), context).single()

        val profile = account.verify()

        assertThat(profile.accountId).isEqualTo("26645824")
        assertThat(profile.server).isEqualTo("FivePercentOnline-Real")
        assertThat(profile.type).isEqualTo(AccountType.LIVE)
        assertThat(profile.description)
            .isEqualTo(MT5AccountVerifier.describe((account as Mt5TradingAccount).profile, info))
    }

    @Test
    fun `gateway api keys resolve through the shared credential forms`(
        @org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path,
    ) {
        val keyFile =
            dir.resolve("gateway_key").also {
                java.nio.file.Files
                    .writeString(it, "from-file\n")
            }
        val ctx = ConnectorContext(stateRoot = null, env = mapOf("GW_KEY" to "from-env"), clock = SystemClock())

        fun keyOf(value: String): String? {
            val cfg = acct("a", 1).let { it.copy(settings = it.settings + ("api_key" to value)) }
            return (Mt5Connector().open(listOf(cfg), ctx).single() as Mt5TradingAccount).profile.apiKey
        }

        assertThat(keyOf("env:GW_KEY")).isEqualTo("from-env")
        assertThat(keyOf("file:$keyFile")).isEqualTo("from-file")
        assertThat(keyOf("literal-key")).isEqualTo("literal-key")
    }

    @Test
    fun `trading hours come from the account's calendars block`() {
        val cfg = acct("a", 1).copy(tradingHours = listOf("BTC*" to "crypto", "*" to "fx"))

        val account = Mt5Connector().open(listOf(cfg), context).single()

        assertThat(account.tradingHours.calendarFor("BTCUSD").name).isEqualTo("crypto")
        assertThat(account.tradingHours.calendarFor("EURUSD").name).isEqualTo("fx")
    }

    @Test
    fun `profiles identical except name and magic share one market-data group`() {
        val groups =
            groupByMarketDataIdentity(
                listOf(
                    profile("exness_s0", magic = 100),
                    profile("exness_s1", magic = 101),
                    profile("exness_s2", magic = 102),
                ),
            )

        assertThat(groups).hasSize(1)
        assertThat(groups.single().map { it.name })
            .containsExactly("exness_s0", "exness_s1", "exness_s2")
    }

    @Test
    fun `profiles differing in any market-data field keep their own groups`() {
        val base = profile("exness_s0", magic = 100)

        val byGateway =
            groupByMarketDataIdentity(
                listOf(base, profile("exness_s1", magic = 101, gatewayUrl = "http://other-gateway:8080")),
            )
        assertThat(byGateway).hasSize(2)

        val bySuffix = groupByMarketDataIdentity(listOf(base, profile("exness_s1", magic = 101, suffix = "")))
        assertThat(bySuffix).hasSize(2)

        val byPollInterval =
            groupByMarketDataIdentity(listOf(base, profile("exness_s1", magic = 101).copy(tickPollIntervalMs = 2000)))
        assertThat(byPollInterval).hasSize(2)

        val byCalendar =
            groupByMarketDataIdentity(
                listOf(
                    base,
                    profile("exness_s1", magic = 101).copy(
                        symbolCalendars =
                            SymbolCalendars(
                                listOf(SymbolCalendars.Rule("BTC*", TradingCalendar.crypto())),
                                TradingCalendar.fxDefault(),
                            ),
                    ),
                ),
            )
        assertThat(byCalendar).hasSize(2)
    }

    private fun profile(
        name: String,
        magic: Int,
        gatewayUrl: String = "http://gateway:8080",
        suffix: String = "m",
    ): MT5BrokerProfile =
        MT5BrokerProfile(
            name = name,
            gatewayUrl = gatewayUrl,
            symbolPolicy = SymbolPolicy(suffix = suffix),
            magic = magic,
        )
}
