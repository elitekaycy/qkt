package com.qkt.cli

import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.connectivity.AccountDirectory
import com.qkt.connectivity.ConnectorContext
import com.qkt.connectivity.ConnectorRegistry
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** One calendar rule for backtest and live: the backtest reads a venue's calendars as the live account trades on. */
class CalendarResolverTest {
    private val yaml =
        """
        brokers:
          prop:
            type: mt5
            extends: exness
            gateway_url: http://gw:5001
            magic: 7
            calendars:
              "XAU*": fx pause 16:45-18:06 America/New_York
              "BTC*": crypto pause 16:50-17:10 America/New_York
              "*": fx pause 16:50-17:10 America/New_York
          fivers:
            type: mt5
            extends: the5ers
            gateway_url: http://gw:5002
            magic: 8
          fwd:
            type: mt5
            extends: exness
            gateway_url: http://gw:5004
            magic: 10
          plain:
            type: mt5
            gateway_url: http://gw:5003
            magic: 9
            server_time_zone: UTC
          bybit_linear:
            type: gateway
            gateway_url: http://gw:8443
            api_key: env:BYBIT_TRADER_TOKEN
            expected_adapter: bybit
            expected_account_login: "1"
            expected_trade_mode: demo
            calendars:
              "SPX*": nyse
        """.trimIndent()

    private fun config(dir: Path): Config {
        val file = dir.resolve("qkt.config.yaml")
        Files.writeString(file, yaml)
        return Config.load(file)
    }

    private fun live(cfg: Config): AccountDirectory =
        AccountDirectory.open(
            cfg.accountConfigs(),
            ConnectorRegistry.discover(),
            ConnectorContext(stateRoot = null, env = mapOf("BYBIT_TRADER_TOKEN" to "k"), clock = SystemClock()),
        )

    private val tradedLive =
        listOf(
            "PROP:XAUUSD",
            "PROP:BTCUSD",
            "PROP:EURUSD",
            "PROP:SPX",
            "FIVERS:BTCUSD",
            "FIVERS:XAUUSD",
            "FIVERS:EURUSD",
            "FWD:XAUUSD",
            "FWD:BTCUSD",
            "FWD:XCUUSD",
            "FWD:USOIL",
            "FWD:AUDUSD",
            "PLAIN:ETHUSD",
            "PLAIN:EURUSD",
            "BYBIT_LINEAR:BTCUSDT",
            "BYBIT_LINEAR:SPX500",
        )

    @Test
    fun `a backtest of a configured broker's streams resolves every calendar the live account trades on`(
        @TempDir dir: Path,
    ) {
        val cfg = config(dir)
        val backtest = cfg.backtestCalendars(null, null)
        live(cfg).use { accounts ->
            val live = CalendarResolver.live(accounts, null)
            for (symbol in tradedLive) {
                assertThat(backtest.calendarFor(symbol)).describedAs(symbol).isEqualTo(live.calendarFor(symbol))
                assertThat(live.calendarFor(symbol))
                    .describedAs("$symbol keeps the calendar its account trades on")
                    .isEqualTo(accounts.tradingHoursFor(symbol))
            }
        }
    }

    @Test
    fun `the calendar is the venue's, not qkt's guess from the name`(
        @TempDir dir: Path,
    ) {
        val calendars = config(dir).backtestCalendars(null, null)
        assertThat(calendars.calendarFor("FIVERS:BTCUSD").name).startsWith("fx pause")
        assertThat(calendars.calendarFor("PROP:BTCUSD").name).startsWith("crypto pause")
        assertThat(calendars.calendarFor("PLAIN:ETHUSD").name).isEqualTo("fx")
        assertThat(calendars.calendarFor("BYBIT_LINEAR:SPX500").name).isEqualTo("nyse")
        assertThat(calendars.choose("PROP:BTCUSD").source).isEqualTo("prop calendars 'BTC*'")
        assertThat(calendars.choose("PLAIN:ETHUSD").source).isEqualTo("plain default")
    }

    @Test
    fun `a prefix that names no venue takes the built-in rules`(
        @TempDir dir: Path,
    ) {
        val calendars = config(dir).backtestCalendars(null, null)
        assertThat(calendars.calendarFor("BACKTEST:BTCUSD")).isEqualTo(TradingCalendar.crypto())
        assertThat(calendars.calendarFor("BACKTEST:SPX")).isEqualTo(TradingCalendar.nyse())
        assertThat(calendars.calendarFor("BACKTEST:EURUSD")).isEqualTo(TradingCalendar.fxDefault())
        assertThat(calendars.choose("BACKTEST:BTCUSD").source).isEqualTo("built-in 'BTC*'")
        assertThat(calendars.choose("BACKTEST:EURUSD").source).isEqualTo("built-in default")
    }

    @Test
    fun `a built-in broker profile names a venue without any config`(
        @TempDir dir: Path,
    ) {
        val calendars = Config.load(dir.resolve("absent.yaml")).backtestCalendars(null, null)
        assertThat(calendars.calendarFor("THE5ERS:BTCUSD").name).startsWith("fx pause")
        assertThat(calendars.calendarFor("EXNESS:BTCUSD")).isEqualTo(TradingCalendar.crypto())
    }

    @Test
    fun `venue takes one broker's calendars for every stream and none drops them all`(
        @TempDir dir: Path,
    ) {
        val cfg = config(dir)
        assertThat(cfg.backtestCalendars("fivers", null).calendarFor("BACKTEST:BTCUSD").name).startsWith("fx pause")
        assertThat(cfg.backtestCalendars("THE5ERS", null).calendarFor("BACKTEST:BTCUSD").name).startsWith("fx pause")
        assertThat(cfg.backtestCalendars(CalendarResolver.NO_VENUE, null).calendarFor("FIVERS:BTCUSD"))
            .isEqualTo(TradingCalendar.crypto())
    }

    @Test
    fun `an unknown venue is refused naming the ones qkt knows`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy { config(dir).backtestCalendars("ftmoo", null) }
            .isInstanceOf(BacktestContext.Companion.SetupError::class.java)
            .hasMessageContaining("unknown --venue 'ftmoo'")
            .hasMessageContaining("fivers")
            .hasMessageContaining("none")
    }

    @Test
    fun `an unreadable broker calendar fails only a run that names that broker`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("qkt.config.yaml")
        Files.writeString(file, yaml.replace("\"SPX*\": nyse", "\"SPX*\": nasdaq"))
        val calendars = Config.load(file).backtestCalendars(null, null)
        assertThat(calendars.calendarFor("BACKTEST:EURUSD")).isEqualTo(TradingCalendar.fxDefault())
        assertThatThrownBy { calendars.calendarFor("BYBIT_LINEAR:SPX500") }
            .isInstanceOf(BacktestContext.Companion.SetupError::class.java)
            .hasMessageContaining("nasdaq")
    }
}
