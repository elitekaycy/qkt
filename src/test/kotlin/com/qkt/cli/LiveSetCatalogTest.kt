package com.qkt.cli

import com.qkt.broker.BrokerFactory
import com.qkt.broker.LogBroker
import com.qkt.common.SymbolCalendars
import com.qkt.common.TradingCalendar
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.AccountProfile
import com.qkt.connectivity.AccountType
import com.qkt.connectivity.TradingAccount
import com.qkt.dsl.sets.SymbolSetSpec
import com.qkt.instrument.EquityCosts
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.universe.VenueCatalog
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LiveSetCatalogTest {
    private fun meta(
        symbol: String,
        category: String,
        contractSize: String,
        equity: Boolean = false,
    ) = InstrumentMeta(
        qktSymbol = symbol,
        contractSize = BigDecimal(contractSize),
        volumeStep = BigDecimal("0.01"),
        volumeMin = BigDecimal("0.01"),
        volumeMax = null,
        pointSize = BigDecimal("0.01"),
        digits = 2,
        tradeStopsLevelPoints = 0,
        category = category,
        equityCosts = if (equity) EquityCosts() else null,
    )

    private val rows =
        listOf(
            meta("GW:XAUUSD", "Metals", "100"),
            meta("GW:XAGUSD", "Metals", "5000"),
            meta("GW:XPTUSD", "Metals", "50"),
            meta("GW:EURUSD", "Forex", "100000"),
        )

    /** An account whose venue lists [listing] (null: a connector that cannot list), recording each kind hint. */
    private class Account(
        name: String,
        private val listing: VenueCatalog?,
    ) : TradingAccount {
        val hints = mutableListOf<String?>()
        override val config = AccountConfig(name, "fake", emptyMap())

        override fun verify() = AccountProfile(config.name, "1", "srv", AccountType.DEMO, "USD", 100, config.name)

        override val orderEntry: BrokerFactory = { bus, clock, _, _, _ -> LogBroker(bus, clock) }
        override val marketData = null
        override val tradingHours = SymbolCalendars(emptyList(), TradingCalendar.fxDefault())

        override fun instrumentCatalog(kind: String?): VenueCatalog? {
            hints += kind
            return listing
        }

        override fun close() {}
    }

    private fun spec(text: String) = SymbolSetSpec.parse(text) as SymbolSetSpec.Filtered

    @Test
    fun `a live set reads the listing of the account its venue names, passing the kind the filter requires`() {
        val gw = Account("gw", VenueCatalog(rows))
        val catalog = LiveSetCatalog { name -> gw.takeIf { name.equals("gw", ignoreCase = true) } }
        assertThat(catalog.source).isEqualTo("the venue listing")
        assertThat(catalog.survivorsOnly).isFalse()
        assertThat(catalog.venue("m", spec("GW:* WHERE kind = spot TOP 2 BY symbol")).rows).isEqualTo(rows)
        catalog.venue("m", spec("gw:* WHERE category = Metals TOP 2 BY symbol"))
        assertThat(gw.hints).containsExactly("spot", null)
    }

    @Test
    fun `live and backtest pick the same members from the same instruments`() {
        val spec = spec("GW:* WHERE category = Metals TOP 2 BY contract_size DESC")
        val live =
            SymbolSetResolution.members("m", spec, LiveSetCatalog { Account("gw", VenueCatalog(rows)) }, 0L, false)
        val backtest = SymbolSetResolution.members("m", spec, SymbolSetResolution.FileCatalog(rows), 0L, true)
        assertThat(live).containsExactly("XAGUSD", "XAUUSD").isEqualTo(backtest)
    }

    @Test
    fun `a venue no account serves, or an account that cannot list, is refused by name`() {
        assertThatThrownBy { LiveSetCatalog { null }.venue("m", spec("NOPE:* WHERE kind = spot TOP 1 BY symbol")) }
            .isInstanceOf(BacktestContext.Companion.SetupError::class.java)
            .hasMessage("symbol set 'm' filters NOPE:*, and no configured account is named nope")
        assertThatThrownBy {
            LiveSetCatalog { Account("tv", null) }.venue("m", spec("TV:* WHERE kind = spot TOP 1 BY symbol"))
        }.isInstanceOf(BacktestContext.Companion.SetupError::class.java)
            .hasMessage(
                "symbol set 'm': brokers.tv (type fake) cannot list its instruments, so it cannot resolve a " +
                    "filtered set; list the members instead (TV:{A, B})",
            )
    }

    @Test
    fun `a listing needs no survivorship acceptance and says what it left out when nothing passes`() {
        val listing = rows + meta("GW:AAPL", "Stocks", "1", equity = true)
        val gw = { omitted: String? -> LiveSetCatalog { Account("gw", VenueCatalog(listing, omitted)) } }
        assertThat(
            SymbolSetResolution.members("m", spec("GW:* WHERE kind = equity TOP 5 BY symbol"), gw(null), 0L, false),
        ).containsExactly("AAPL")
        assertThatThrownBy {
            val future = spec("GW:* WHERE kind = future TOP 5 BY symbol")
            SymbolSetResolution.members("m", future, gw("left out: 2 future"), 0L, false)
        }.hasMessage("symbol set 'm': no GW instrument passes kind = \"future\" (left out: 2 future)")
        val empty = LiveSetCatalog { Account("gw", VenueCatalog(emptyList(), "left out: 3 option")) }
        assertThatThrownBy {
            SymbolSetResolution.members("m", spec("GW:* WHERE kind = option TOP 5 BY symbol"), empty, 0L, false)
        }.hasMessage("symbol set 'm': the venue listing lists no GW: symbol (left out: 3 option)")
    }

    @Test
    fun `a connector lists no catalog unless it says it can`() {
        val plain =
            object : TradingAccount by Account("tv", null) {
                override fun instrumentCatalog(kind: String?): VenueCatalog? = super.instrumentCatalog(kind)
            }
        assertThat(plain.instrumentCatalog("spot")).isNull()
    }
}
